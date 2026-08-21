package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.common.android.ToolCallDebugLog
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 总结任务状态：非 null 表示正在调用压缩模型；成功或失败后自动清空。
    val summaryStatus = MutableStateFlow<String?>(null)

    /**
     * 压缩任务 Job（2026-08-21 下沉）。
     *
     * 以前压缩挂在 `ChatVM.viewModelScope`：用户切走对话/退出聊天页 → ViewModel 销毁 →
     * 协程被 cancel，压缩当场暴毙，UI 上转圈的图标也跟着消失（用户反馈「必须留在压缩页面」）。
     * 现在 Job 起在 Service 单例 scope 上并存于 session，UI 只做观察，切对话不影响后台压缩。
     *
     * 与 [_generationJob] 分开管理：压缩和生成是两条独立任务，
     * 复用同一个 job 槽会让 setJob(cancelPrevious=true) 互相掐死。
     */
    private val _summaryJob = MutableStateFlow<Job?>(null)
    val summaryJob: StateFlow<Job?> = _summaryJob.asStateFlow()
    val isCompressing: Boolean get() = _summaryJob.value?.isActive == true

    /**
     * 优雅停轮标记（2026-08-13）：子 agent 回报/反问后由 ChatService.finishPendingTools 置位，
     * GenerationHandler 在本轮工具执行完（结果已合并/落库）后检查并 break，正常走 onSuccess 收尾。
     *
     * 替代「从生成协程内部 job.cancel()」：从内部 cancel 会把正在执行的 agent_report 的
     * 结果合并（GenerationHandler 的 merge+emit）一起掐掉，工具永远停在「未执行」，
     * 下一轮 sendMessage 的兜底 finishInterruptedPendingTools 会用默认的
     * "Generation cancelled by user" 把它误标成用户取消（用户反馈「我没点取消却显示 cancelled」）。
     */
    val stopAfterCurrentStep = MutableStateFlow(false)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true

    /**
     * 压缩中也算「在用」：否则 UI 一切走，refCount 归零 + 没在生成 →
     * 5 秒后 removeSession → cleanup() 取消一切，后台压缩仍然会被回收掉。
     */
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || isCompressing

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        ToolCallDebugLog.askUserLazy("ConversationSession.setJob") {
            "conv=$id previous=${previous != null}/active=${previous?.isActive} replacement=${job != null}"
        }
        if (cancelPrevious) {
            previous?.cancel()
        }
        _generationJob.value = job
        job?.invokeOnCompletion { cause ->
            // A just-finished previous generation must not clear the replacement job
            // installed by an ask_user answer (or any other resume path).
            val completedJobMatches = _generationJob.value === job
            if (completedJobMatches) {
                _generationJob.value = null
            }
            ToolCallDebugLog.askUserLazy("ConversationSession.jobComplete") {
                "conv=$id completedJobMatches=$completedJobMatches " +
                    "replacementActive=${_generationJob.value?.isActive} cause=" +
                    (cause?.javaClass?.simpleName ?: "normal")
            }
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    /**
     * 安装压缩 Job。同一会话同时只允许一条压缩在跑（重复点「压缩历史」不并发烧钱），
     * 已有活跃压缩时直接返回 false，由调用方复用/忽略。
     */
    fun trySetSummaryJob(job: Job): Boolean {
        if (_summaryJob.value?.isActive == true) return false
        _summaryJob.value = job
        job.invokeOnCompletion {
            if (_summaryJob.value === job) _summaryJob.value = null
            if (refCount.get() <= 0) scheduleIdleCheck()
        }
        return true
    }

    fun cancelSummaryJob() {
        _summaryJob.value?.cancel()
        _summaryJob.value = null
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        _summaryJob.value?.cancel()
        _summaryJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
