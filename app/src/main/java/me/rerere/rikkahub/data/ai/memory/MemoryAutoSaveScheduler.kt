package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_PROMPT
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.db.dao.MemoryAutoSaveCandidateDAO
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/**
 * 记忆自动提炼调度器（记忆图 P3，对齐 Operit `MemoryAutoSaveScheduler.kt`）：
 *
 * - 每 60s tick 轮询；按 assistant 独立计时（间隔 DEFAULT_POLL_INTERVAL_MS，默认 10 分钟）；
 * - 候选攒够 MIN_TOTAL_CANDIDATES（5 条）才批量抽取，避免每轮对话都调 LLM 烧 token；
 * - 每个 chat 每轮最多处理 MAX_CANDIDATES_PER_CHAT 条候选，消息取最近 MAX_MESSAGES_PER_BATCH 条；
 * - 处理失败候选标记 failed，下一轮重试（Operit 失败候选保留重试语义）。
 */
class MemoryAutoSaveScheduler(
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val candidateDAO: MemoryAutoSaveCandidateDAO,
    private val extractor: MemoryGraphExtractor,
) {
    companion object {
        private const val TAG = "MemoryAutoSaveScheduler"
        private const val LOOP_TICK_MS = 60 * 1000L
        private const val DEFAULT_POLL_INTERVAL_MS = 10 * 60 * 1000L
        private const val MIN_TOTAL_CANDIDATES = 5
        private const val MAX_CANDIDATES_PER_CHAT = 20
        private const val MAX_MESSAGES_PER_BATCH = 48
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
            scanAndProcess()
        } finally {
            isRunning.set(false)
        }
    }

    private suspend fun scanAndProcess() {
        val settings = settingsStore.settingsFlow.value
        val nowMs = System.currentTimeMillis()

        for (assistant in settings.assistants) {
            // 仅对开启「记忆」且开启「自动提炼」的助手生效（Plan §4.3 门槛）
            if (!assistant.enableMemory || !assistant.enableMemoryAutoExtract) continue
            val assistantId = assistant.id.toString()

            val nextRunAtMs = nextRunAtMsByAssistant[assistantId]
                ?: (nowMs + DEFAULT_POLL_INTERVAL_MS)
            if (nowMs < nextRunAtMs) continue

            val candidates = candidateDAO.getPendingAndFailedByAssistant(assistantId)
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
                    val history = loadHistory(chatId)
                    if (history.isEmpty()) {
                        Log.w(TAG, "候选消息缺失，清理候选: assistant=$assistantId chat=$chatId")
                        candidateDAO.deleteByIds(batchIds)
                        continue
                    }
                    val wrote = extractor.extract(settings, assistant, history)
                    candidateDAO.deleteByIds(batchIds)
                    Log.i(TAG, "候选处理完成: assistant=$assistantId chat=$chatId candidates=${batchIds.size} wrote=$wrote")
                } catch (e: Exception) {
                    Log.e(TAG, "候选处理失败: assistant=$assistantId chat=$chatId", e)
                    batchIds.forEach { candidateDAO.markFailed(it, e.message ?: e.javaClass.simpleName) }
                }
            }
            nextRunAtMsByAssistant[assistantId] = nowMs + DEFAULT_POLL_INTERVAL_MS
        }
    }

    private suspend fun loadHistory(chatId: String): List<Pair<String, String>> {
        val conversation = runCatching { conversationRepo.getConversationById(Uuid.parse(chatId)) }
            .getOrNull() ?: return emptyList()
        return conversation.currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(MAX_MESSAGES_PER_BATCH)
            .map { message ->
                val role = if (message.role == MessageRole.USER) "user" else "assistant"
                role to message.toText()
            }
            .filter { (_, content) -> content.isNotBlank() }
    }
}
