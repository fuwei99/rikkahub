package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.db.dao.MemoryAutoSaveCandidateDAO
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.common.android.MemoryGraphDebugLog
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/**
 * 记忆自动提炼调度器（记忆图 P3，对齐 Operit `MemoryAutoSaveScheduler.kt`）：
 *
 * - 每 60s tick 轮询；按 assistant 独立计时（间隔 DEFAULT_POLL_INTERVAL_MS，默认 10 分钟）；
 * - 首次启动立即检查一轮（不再空等 10 分钟），攒够 MIN_TOTAL_CANDIDATES（5 条）才批量抽取；
 * - 每个 chat 每轮最多处理 MAX_CANDIDATES_PER_CHAT 条候选，消息窗口从最早候选时间起取最近 N 条；
 * - processing 超时（进程崩溃遗留）自动恢复为 pending 重新排队，杜绝永久卡死；
 * - 失败候选标记 failed 保留重试，重试次数超过 MAX_RETRIES 直接丢弃，防止死循环烧 token；
 * - 抽取只写独立图谱表（MemoryGraphRepository），与传统记忆完全隔离（方案 2026-08-05）。
 */
class MemoryAutoSaveScheduler(
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val candidateDAO: MemoryAutoSaveCandidateDAO,
    private val extractor: MemoryGraphExtractor,
    private val bindingResolver: MemoryGraphBindingResolver,
) {
    companion object {
        private const val TAG = "MemoryAutoSaveScheduler"
        private const val LOOP_TICK_MS = 60 * 1000L
        private const val DEFAULT_POLL_INTERVAL_MS = 10 * 60 * 1000L
        private const val MIN_TOTAL_CANDIDATES = 5
        private const val MAX_CANDIDATES_PER_CHAT = 20
        private const val MAX_MESSAGES_PER_BATCH = 48
        private const val MAX_RETRIES = 3
        /** processing 卡死判定：超过 30 分钟视为进程崩溃遗留，恢复为 pending */
        private const val PROCESSING_STALE_MS = 30 * 60 * 1000L
    }

    private val isRunning = AtomicBoolean(false)
    private val nextRunAtMsByAssistant = ConcurrentHashMap<String, Long>()
    private var loopJob: Job? = null

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "记忆自动提炼轮询器已启动")
            while (isActive) {
                delay(LOOP_TICK_MS)
                runOnce()
            }
        }
    }

    /** 立即跑一轮（也可由外部手动触发，如设置页开关打开时） */
    suspend fun runOnce() {
        if (!isRunning.compareAndSet(false, true)) return
        try {
            // 崩溃遗留的 processing 候选先恢复排队
            runCatching {
                candidateDAO.recoverStaleProcessing(System.currentTimeMillis() - PROCESSING_STALE_MS)
            }.onFailure { Log.w(TAG, "recoverStaleProcessing failed: ${it.message}") }
            // 重试超限的 failed 候选丢弃
            runCatching { candidateDAO.dropExhausted(MAX_RETRIES) }
                .onFailure { Log.w(TAG, "dropExhausted failed: ${it.message}") }
            scanAndProcess()
        } finally {
            isRunning.set(false)
        }
    }

    private suspend fun scanAndProcess() {
        val settings = settingsStore.settingsFlow.value
        val nowMs = System.currentTimeMillis()

        for (assistant in settings.assistants) {
            // 仅对开启「记忆图（任意一张启用）」且开启「自动提炼」的助手生效。
            // 门槛统一走 Resolver（唯一真源）：多图体系下老三个布尔字段已不是唯一依据，
            // 但对未设置 binding 的老配置 Resolver 会从这三个字段推导，行为等价。
            if (!assistant.enableMemoryAutoExtract) continue
            val graphEnabled = runCatching {
                bindingResolver.resolve(assistant, conversation = null).any { it.enabled }
            }.getOrDefault(false)
            if (!graphEnabled) continue
            val assistantId = assistant.id.toString()

            // 首次启动立即检查（nextRunAt 默认 now），攒批不足再顺延 DEFAULT_POLL_INTERVAL_MS
            val nextRunAtMs = nextRunAtMsByAssistant[assistantId] ?: nowMs
            if (nowMs < nextRunAtMs) continue

            val candidates = candidateDAO.getPendingAndFailedByAssistant(assistantId)
            MemoryGraphDebugLog.i(TAG, "scan: assistant=$assistantId pendingAndFailed=${candidates.size} " +
                "minRequired=$MIN_TOTAL_CANDIDATES")
            if (candidates.size < MIN_TOTAL_CANDIDATES) {
                nextRunAtMsByAssistant[assistantId] = nowMs + DEFAULT_POLL_INTERVAL_MS
                continue
            }

            val groupedByChat = candidates.groupBy { it.chatId }.filterKeys { it.isNotBlank() }
            for ((chatId, chatCandidates) in groupedByChat) {
                val batch = chatCandidates
                    .sortedBy { it.triggerTimestamp }
                    .take(MAX_CANDIDATES_PER_CHAT)
                val batchIds = batch.map { it.id }
                candidateDAO.markProcessing(batchIds)
                try {
                    val windowStart = batch.minOfOrNull { it.triggerTimestamp }
                    val history = loadHistory(chatId, windowStart)
                    if (history.isEmpty()) {
                        MemoryGraphDebugLog.w(TAG, "history empty, dropping candidates: assistant=$assistantId chat=$chatId windowStart=$windowStart")
                        Log.w(TAG, "候选消息缺失，清理候选: assistant=$assistantId chat=$chatId")
                        candidateDAO.deleteByIds(batchIds)
                        continue
                    }
                    val wrote = extractor.extract(settings, assistant, history)
                    MemoryGraphDebugLog.i(TAG, "candidate processed: assistant=$assistantId chat=$chatId " +
                        "batch=${batchIds.size} history=${history.size} wrote=$wrote")
                    candidateDAO.deleteByIds(batchIds)
                    Log.i(TAG, "候选处理完成: assistant=$assistantId chat=$chatId candidates=${batchIds.size} wrote=$wrote")
                } catch (e: Exception) {
                    MemoryGraphDebugLog.e(TAG, "candidate processing FAILED: assistant=$assistantId chat=$chatId", e)
                    Log.e(TAG, "候选处理失败: assistant=$assistantId chat=$chatId", e)
                    batchIds.forEach { candidateDAO.markFailed(it, e.message ?: e.javaClass.simpleName) }
                }
            }
            nextRunAtMsByAssistant[assistantId] = nowMs + DEFAULT_POLL_INTERVAL_MS
        }
    }

    /** 从最早候选时间起取最近 N 条 user/assistant 消息（避免把整段旧对话反复喂给抽取器） */
    private suspend fun loadHistory(chatId: String, windowStart: Long?): List<Pair<String, String>> {
        val conversation = runCatching { conversationRepo.getConversationById(Uuid.parse(chatId)) }
            .getOrNull() ?: return emptyList()
        return conversation.currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter {
                windowStart == null || it.createdAt.toJavaLocalDateTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli() >= windowStart
            }
            .takeLast(MAX_MESSAGES_PER_BATCH)
            .map { message ->
                val role = if (message.role == MessageRole.USER) "user" else "assistant"
                role to message.toText()
            }
            .filter { (_, content) -> content.isNotBlank() }
    }
}
