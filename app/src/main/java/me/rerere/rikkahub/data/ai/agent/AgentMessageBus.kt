package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "AgentMessageBus"

/**
 * 跨对话唤醒调度器（方案 2026-08-07「多 Agent 通信内核」Step 3 重构）。
 *
 * **与旧版的根本区别**：旧版把「消息全文」排队、等目标空闲后作为 user 消息投递进去；
 * 新版里消息已经在 [AgentInboxStore] 无条件落库（I2，发送方立即返回，I3），
 * 本类只负责**唤醒**这一个调度动作：等目标空闲 → 以 system 署名开一轮「你有 N 封未读」。
 *
 * 保留 per-conversation 串行队列的理由：唤醒走 `ChatService.sendMessage`，
 * 开头就是 `previousJob?.cancel()`——两路唤醒同时打到同一对话仍会互掐，必须串行。
 *
 * 重复唤醒由 bridge 侧的唤醒水位（wokenWatermark）去重：同一批未读只唤醒一次（§6.2），
 * 因此不再需要攒批窗口——多条回报在窗口内到达时，第一条唤醒之后的请求都会被水位挡掉。
 *
 * **期二复用说明（代码保留不删）**：CALL 抢占 = 在本类同一串行点上把
 * 「awaitGenerationIdle 等空闲」换成「Deps.stopGeneration 立即打断 + 立即 dispatch」，
 * 冷却/并线合并/人类在场判定都挂在这条路径上，不需要第二套队列。
 *
 * **所有 launch 必须显式带 [Dispatchers.Default]**：`AppScope` 绑的是 `Dispatchers.Main`，
 * 等待循环不指定调度器会压主线程（2026-08-07 ANR 病根，见 [awaitGenerationIdle] 注释）。
 */
class AgentMessageBus(
    private val appScope: AppScope,
    /** 目标对话是否正在生成 */
    private val isGenerating: (Uuid) -> Boolean,
    /** 订阅生成完成事件（先订阅再查状态的前提） */
    private val awaitGenerationDone: suspend (Uuid) -> Unit,
    /** 真正的唤醒动作：system 署名开一轮提示读信（内部走 ChatService.sendMessage） */
    private val dispatchWake: suspend (target: Uuid) -> Unit,
) {
    private class Queue(
        val channel: Channel<CompletableDeferred<Unit>> = Channel(Channel.UNLIMITED),
        var worker: Job? = null,
    )

    private val queues = ConcurrentHashMap<Uuid, Queue>()

    /**
     * 请求唤醒目标对话（挂起直到唤醒被分发或被水位/无未读跳过）。
     *
     * 不抛异常：唤醒失败只记日志，信已在箱里，下一轮自然会被看到（I8 的弱化形态）。
     */
    suspend fun requestWake(target: Uuid) {
        val ack = CompletableDeferred<Unit>()
        val queue = queues.computeIfAbsent(target) { Queue() }
        queue.channel.send(ack)
        ensureWorker(target, queue)
        ack.await()
    }

    /** 只请求不等待（入箱后的常规路径：发送方不应为唤醒阻塞） */
    fun requestWakeAsync(target: Uuid) {
        appScope.launch(Dispatchers.Default) { runCatching { requestWake(target) } }
    }

    private fun ensureWorker(target: Uuid, queue: Queue) {
        // 必须加锁：worker 正在 break 退出时若外部只看 isActive == true 就跳过启动，
        // 刚入队的请求永远不会被消费，requestWake 会挂死。
        synchronized(queue) {
            val current = queue.worker
            if (current != null && current.isActive) return
            queue.worker = startWorker(target, queue)
        }
    }

    private fun startWorker(target: Uuid, queue: Queue): Job =
        appScope.launch(Dispatchers.Default) {
            while (true) {
                val ack = queue.channel.tryReceive().getOrNull() ?: run {
                    synchronized(queue) {
                        // 再确认一次：加锁期间没有新请求才真正退出，
                        // 否则由本 worker 继续处理（返回 null 表示要 continue）
                        queue.channel.tryReceive().getOrNull().also { pending ->
                            if (pending == null) queue.worker = null
                        }
                    }
                } ?: break

                // 等目标空闲再唤醒：正在生成时掐进去会掐掉半成品（抢占才是掐，唤醒要等）。
                runCatching {
                    val idle = awaitGenerationIdle(
                        timeoutMs = AgentLimits.WAIT_GENERATION_TIMEOUT_MS,
                        isGenerating = { isGenerating(target) },
                        awaitGenerationDone = { awaitGenerationDone(target) },
                    )
                    if (!idle) Log.w(TAG, "wait idle timed out for $target, waking anyway")
                }.onFailure { Log.w(TAG, "wait idle failed for $target", it) }

                // 水位去重在 dispatchWake 内做：无新邮件时静默跳过。
                runCatching { dispatchWake(target) }
                    .onFailure { Log.e(TAG, "dispatchWake failed for $target", it) }

                ack.complete(Unit)
            }
        }
}
