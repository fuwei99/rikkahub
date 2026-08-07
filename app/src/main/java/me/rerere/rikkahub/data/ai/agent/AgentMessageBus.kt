package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "AgentMessageBus"

/**
 * 跨对话投递总线（方案 2026-08-07 §3.1，本方案最致命的坑）。
 *
 * `ChatService.sendMessage` 开头就是 `previousJob?.cancel()`，直接后果：
 * - 三个子 agent 同时回报 → 后两条把前一条的主对话生成掐掉；
 * - 用户正在和主 agent 聊天时子 agent 回报 → 用户的生成被打断。
 *
 * 对策：
 * 1. **per-conversation 单并发队列**，串行消费；
 * 2. 投递前若目标正在生成 → 等它结束（**先订阅再查状态**，否则会 miss 事件；带超时兜底）；
 * 3. **攒批合并**：窗口内到达的多条 REPORT 合并成一条 user 消息，减少唤醒次数与 token；
 * 4. **用户优先**：用户发消息时 [dropDeferrable] 清空该对话待投递的"可延后"项（回报可延后，ask 不可）。
 *
 * 明确不做持久化 outbox：v1 内存队列足够，重启丢队列可接受
 * （对话本体已落库、状态可查、可人工重发）。
 */
class AgentMessageBus(
    private val appScope: AppScope,
    /** 目标对话是否正在生成 */
    private val isGenerating: (Uuid) -> Boolean,
    /** 订阅生成完成事件（先订阅再查状态的前提） */
    private val awaitGenerationDone: suspend (Uuid) -> Unit,
    /** 真正落地的投递动作（内部走 ChatService.sendMessage） */
    private val dispatch: suspend (target: Uuid, messages: List<AgentMessage>) -> Unit,
) {
    private class Queue(
        val channel: Channel<Pair<AgentMessage, CompletableDeferred<Unit>>> = Channel(Channel.UNLIMITED),
        var worker: Job? = null,
    )

    private val queues = ConcurrentHashMap<Uuid, Queue>()

    /**
     * 投递一条消息（挂起直到该条真正被送进目标对话）。
     *
     * 不抛异常：投递失败只记日志，避免把调用方（工具执行）连带炸掉。
     */
    suspend fun deliver(message: AgentMessage) {
        val ack = CompletableDeferred<Unit>()
        val queue = queues.computeIfAbsent(message.target) { Queue() }
        queue.channel.send(message to ack)
        ensureWorker(message.target, queue)
        ack.await()
    }

    /** 只入队不等待（用于系统通告等无需回执的场景） */
    fun deliverAsync(message: AgentMessage) {
        appScope.launch { runCatching { deliver(message) } }
    }

    /**
     * 用户消息优先：清空该对话待投递队列里的"可延后"项（REPORT）。
     * ask / instruction / task 不可延后，保留。
     */
    fun dropDeferrable(target: Uuid) {
        val queue = queues[target] ?: return
        val kept = mutableListOf<Pair<AgentMessage, CompletableDeferred<Unit>>>()
        while (true) {
            val item = queue.channel.tryReceive().getOrNull() ?: break
            if (item.first.kind.deferrable) {
                item.second.complete(Unit)
                Log.d(TAG, "dropDeferrable: dropped ${item.first.kind} for $target")
            } else {
                kept += item
            }
        }
        kept.forEach { queue.channel.trySend(it) }
    }

    private fun ensureWorker(target: Uuid, queue: Queue) {
        // 必须加锁：worker 正在 break 退出时若外部只看 isActive == true 就跳过启动，
        // 那条刚入队的消息永远不会被消费，deliver 会挂死。
        synchronized(queue) {
            val current = queue.worker
            if (current != null && current.isActive) return
            queue.worker = startWorker(target, queue)
        }
    }

    private fun startWorker(target: Uuid, queue: Queue): Job =
        appScope.launch {
            while (true) {
                val first = queue.channel.tryReceive().getOrNull() ?: run {
                    synchronized(queue) {
                        // 再确认一次：加锁期间没有新消息才真正退出，
                        // 否则由本 worker 继续处理（返回 null 表示要 continue）
                        queue.channel.tryReceive().getOrNull().also { pending ->
                            if (pending == null) queue.worker = null
                        }
                    }
                } ?: break
                val batch = mutableListOf(first)

                // 攒批：可合并的类型才等窗口，ask/instruction 立即走
                if (first.first.kind.batchable) {
                    val deadline = System.currentTimeMillis() + AgentLimits.BATCH_WINDOW_MS
                    while (System.currentTimeMillis() < deadline) {
                        val next = queue.channel.tryReceive().getOrNull()
                        if (next == null) {
                            delay(120)
                            continue
                        }
                        if (next.first.kind.batchable) {
                            batch += next
                        } else {
                            // 不可合并的插队项：先送它，剩下的回队列下轮处理
                            batch.forEach { queue.channel.trySend(it) }
                            batch.clear()
                            batch += next
                            break
                        }
                    }
                }

                runCatching { waitUntilIdle(target) }
                    .onFailure { Log.w(TAG, "waitUntilIdle failed for $target", it) }

                runCatching { dispatch(target, batch.map { it.first }) }
                    .onFailure { Log.e(TAG, "dispatch failed for $target", it) }

                batch.forEach { it.second.complete(Unit) }
            }
        }

    /**
     * 等目标对话空闲。
     *
     * **不能"先查后等"**：先查 isGenerating 再订阅 SharedFlow 会 miss 事件
     * （检查后、订阅前事件已 emit）。这里是"订阅 + 轮询状态"双轨，并带超时兜底：
     * generationDoneFlow 三处无条件 emit、不带原因、无 replay，不能当唯一真源。
     */
    private suspend fun waitUntilIdle(target: Uuid) {
        if (!isGenerating(target)) return
        withTimeoutOrNull(AgentLimits.WAIT_GENERATION_TIMEOUT_MS) {
            while (isGenerating(target)) {
                // 双轨：事件先到就走事件，否则 500ms 轮询兜底
                withTimeoutOrNull(500) { awaitGenerationDone(target) }
            }
        } ?: Log.w(TAG, "waitUntilIdle timed out for $target, delivering anyway")
    }
}
