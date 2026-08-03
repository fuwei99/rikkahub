package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 批量 OCR 的限流闸门：并发上限 + 每分钟请求数上限。
 *
 * 为什么两个都要：
 * - 并发上限挡住「同时打出去 50 条请求」，视觉模型基本都会直接 429；
 * - 令牌桶挡住「串行但太快」，很多厂商是按分钟计费窗口限流的，
 *   单并发照样能在几秒内把一分钟的配额打光。
 *
 * 用 [Semaphore] 而不是自己数计数器：它的 withPermit 在协程被取消时
 * 保证归还许可，用户中途退出批量 OCR 不会永久漏掉一个名额。
 */
class OcrRateLimiter(
    maxConcurrency: Int,
    private val ratePerMinute: Int,
) {
    private val semaphore = Semaphore(maxConcurrency.coerceIn(1, 8))
    private val mutex = Mutex()

    /** 最近一分钟内的发起时刻，用于滑动窗口判断 */
    private val recentStarts = ArrayDeque<Long>()

    private val windowMillis = 60_000L

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit {
        awaitRateToken()
        block()
    }

    /**
     * 滑动窗口而非固定窗口：固定窗口在边界上会放过两倍流量
     * （窗口末尾打满 + 新窗口开头再打满），而厂商的计数往往就是滑动的。
     */
    private suspend fun awaitRateToken() {
        val limit = ratePerMinute.coerceIn(1, 600)
        while (true) {
            val waitFor = mutex.withLock {
                val now = System.currentTimeMillis()
                while (recentStarts.isNotEmpty() && now - recentStarts.first() >= windowMillis) {
                    recentStarts.removeFirst()
                }
                if (recentStarts.size < limit) {
                    recentStarts.addLast(now)
                    0L
                } else {
                    // 等到最老的那次请求滑出窗口
                    windowMillis - (now - recentStarts.first())
                }
            }
            if (waitFor <= 0L) return
            delay(waitFor.coerceAtLeast(50L))
        }
    }
}
