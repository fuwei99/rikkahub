package me.rerere.rikkahub.data.ai.agent

import android.os.SystemClock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkahub.data.db.entity.AgentInboxEntity
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/** await 的匹配模式：ANY = 任一匹配即返回；ALL = 指定的发送方全部到齐才返回 */
enum class AwaitMode { ANY, ALL }

/** await/join 的批返回结果 */
data class AwaitBatchResult(
    /** 消费到的信（读取即已读，I4） */
    val mails: List<AgentInboxEntity>,
    /** true = 到超时仍未满足条件（已到的部分照常返回，不丢，I8） */
    val timedOut: Boolean,
) {
    val anyArrived: Boolean get() = mails.isNotEmpty()
}

/**
 * await/join 阻塞等待原语（收敛设计 §3.3/§4，期三接线，2026-08-08）。
 *
 * 语义：挂起在收件箱事件流上等「匹配的信」到达；条件满足后再等一个攒批窗口
 * （通信设置 mailBatchWindowSeconds），窗口内到的其他结果合并一起批返回——
 * 「第一个邮箱到了之后等几秒，如果有其他邮箱随后到，就一起批返回」（用户拍板，
 * OpenClaw `batched` 思路：一次拿全部，不被逐封唤醒，token 不阶梯暴涨）。
 *
 * 硬不变式在本函数物化：
 * - **I6**：进入时先查一次已有未读——信在 await 之前就到了，不用白等一个完整超时；
 * - **I7**：不轮询——只在 Room 的 unreadFlow 上等「未读数变化」，绝无 sleep/while+delay；
 * - **I5**：单层 withTimeout + 单调钟 deadline + catch 后 ensureActive + 循环体 yield，
 *   与 awaitGenerationIdle 同款纪律（2026-08-07 ANR 教训：嵌套超时会被内层吞掉）；
 * - **I8**：消费的信在**返回前一刻**才标记已读；中途被取消/超时，信保持未读，
 *   下一轮 inbox/唤醒自然看到——「被打断 ≠ 白干」。
 *
 * 调用方注意：本函数只负责等，**不负责换线程**；从 AppScope（Dispatchers.Main）起的
 * 调用必须显式 withContext(Dispatchers.Default)，否则等待循环压主线程（ANR 同款病根）。
 */
internal suspend fun awaitMailBatch(
    inboxStore: AgentInboxStore,
    target: Uuid,
    /** 等待的发送方（对话 id）集合；null = 任意发送方 */
    from: Set<Uuid>?,
    mode: AwaitMode,
    timeoutMs: Long,
    batchWindowMs: Long,
): AwaitBatchResult {
    val consumed = mutableListOf<AgentInboxEntity>()
    val consumedIds = mutableSetOf<Long>()

    fun senderIdOf(row: AgentInboxEntity): Uuid? =
        row.senderId?.let { runCatching { Uuid.parse(it) }.getOrNull() }

    fun matches(row: AgentInboxEntity): Boolean = from == null || senderIdOf(row) in from

    /** 把当前未读里匹配的新信收进 consumed（不标记）。返回条件是否已满足。 */
    suspend fun drain(): Boolean {
        inboxStore.peekUnread(target)
            .filter { it.id !in consumedIds && matches(it) }
            .forEach { consumed += it; consumedIds += it.id }
        return when (mode) {
            AwaitMode.ANY -> consumed.isNotEmpty()
            AwaitMode.ALL -> from == null || from.all { f -> consumed.any { senderIdOf(it) == f } }
        }
    }

    /**
     * 等一次「未读数变化」或 deadline 到期（I7：挂事件流，不轮询）。
     * @return true = 观察到变化；false = 到期（或协程被取消——ensureActive 会先抛出去）
     */
    suspend fun waitForChange(deadline: Long): Boolean {
        val remain = deadline - SystemClock.elapsedRealtime()
        if (remain <= 0) return false
        val lastCount = inboxStore.countUnread(target)
        try {
            withTimeout(remain) { inboxStore.unreadFlow(target).first { it != lastCount } }
        } catch (_: TimeoutCancellationException) {
            coroutineContext.ensureActive()
            return false
        }
        yield()
        return true
    }

    // I6：进入先查已有未读（信在 await 之前就到了，直接收）
    var satisfied = drain()
    if (!satisfied) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!satisfied && waitForChange(deadline)) {
            satisfied = drain()
        }
    }

    // 攒批窗口：条件满足后再等 batchWindowMs，窗口内到的更多信合并进同一批（用户拍板）
    if (batchWindowMs > 0) {
        val windowDeadline = SystemClock.elapsedRealtime() + batchWindowMs
        while (waitForChange(windowDeadline)) {
            drain()
        }
    }

    // I8：此刻才标记已读；此前任何取消/超时都保持未读
    val rows = inboxStore.takeByIds(consumedIds.toList())
    return AwaitBatchResult(mails = rows, timedOut = !satisfied)
}
