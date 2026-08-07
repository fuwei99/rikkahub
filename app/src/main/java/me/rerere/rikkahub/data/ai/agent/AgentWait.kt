package me.rerere.rikkahub.data.ai.agent

import android.os.SystemClock
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.math.min

/**
 * 等目标对话结束生成。返回 true = 等到空闲，false = 到 deadline 仍在生成（调用方自行决定继续还是放弃）。
 *
 * **为什么不能"先查后等"**：先查 isGenerating 再订阅 SharedFlow 会 miss 事件
 * （检查后、订阅前事件已 emit）。所以这里是"订阅 + 轮询"双轨，并带硬超时兜底：
 * generationDoneFlow 三处无条件 emit、不带原因、无 replay，不能当唯一真源。
 *
 * **为什么是单层 withTimeout + 手算 deadline，而不是"外层 withTimeoutOrNull 包 while、
 * 内层再 withTimeoutOrNull"**（原实现，2026-08-07 三 agent 并发 ANR 现场的写法）：
 * 嵌套超时能跑对（`withTimeoutOrNull` 会比对 `e.coroutine === coroutine`，不是自己的超时
 * 会原样上抛，不存在内层吞外层），但两层超时叠在一起语义极难推理 —— 出事时无法一眼断定
 * 循环有没有挂起点、取消能不能落地。改成单层后：catch 到的超时**必然**是本层轮询到期，
 * 总时长由单调钟 deadline 保证，读代码不需要再推演协程取消传播。
 *
 * 三道防线，任一条成立就不会变成紧循环烧 CPU：
 * 1. 每轮开头重算 remaining，≤0 立刻返回；
 * 2. catch 后 `ensureActive()`：若是外部取消导致的，原样抛出，绝不当黑洞；
 * 3. 每轮 `yield()`：即使 `awaitGenerationDone` 意外同步返回，也有确定的挂起点让出线程。
 *
 * 调用方注意：本函数只负责等，**不负责换线程**。`AppScope` 绑的是 `Dispatchers.Main`，
 * 从 appScope 起的调用必须显式带 `Dispatchers.Default`，否则轮询压在主线程上，
 * 三路 agent 并发就能把 main 烧到输入分发超时（那次 ANR 的实锤：main = Runnable、
 * utm=20346 ≈ 217s CPU、全 trace 零 `waiting to lock`）。
 */
internal suspend fun awaitGenerationIdle(
    timeoutMs: Long,
    pollIntervalMs: Long = 500L,
    isGenerating: () -> Boolean,
    awaitGenerationDone: suspend () -> Unit,
): Boolean {
    if (!isGenerating()) return true
    // elapsedRealtime 是单调钟：用户改系统时间 / NTP 校正都不会让 deadline 漂走
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (isGenerating()) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) return false
        try {
            withTimeout(min(remaining, pollIntervalMs)) { awaitGenerationDone() }
        } catch (_: TimeoutCancellationException) {
            // 本层轮询到期属正常路径。但若当前协程已被外部取消，
            // ensureActive 会把取消原样抛出去，不允许在这里吞掉。
            coroutineContext.ensureActive()
        }
        yield()
    }
    return true
}
