package me.rerere.rikkahub.data.sync.core

/**
 * Fork 自激熔断器。
 *
 * ## 为什么需要它
 *
 * `Resolution.Fork` 的动作是「把一方另存为新会话」。这个动作本身会**产生新数据**：
 * 新会话 → 推上云 → 对端拉到 → 若判据仍不稳定 → 又 Fork → 又一个新会话……
 *
 * 也就是说 Fork 是一条**带正反馈的路径**。只要分叉判据存在任何抖动来源
 * （空占位节点、时区漂移、元数据竞争、乱序到达），环路就会自我维持，
 * 而且**用户完全没有操作**也会持续增殖 —— 2026-09-11 现场就是同一个 createAt
 * 在两小时内派生出 6 个副本，全部只含一条空 assistant 消息。
 *
 * T7 信令上线后轮询间隔从 30s 降到 ~1s，等于把增殖速率放大了三十倍，
 * 于是这个一直潜伏的缺陷才暴露成「肉眼可见的疯狂刷屏」。
 *
 * ## 策略
 *
 * 每个会话在 [WINDOW_MS] 滑动窗口内最多允许 [MAX_FORKS] 次分叉。
 * 超限后 `allow()` 返回 false，调用方应退化为 TakeRemote（认云端、放弃本地差异）。
 *
 * 这是**故意选择的取舍**：宁可丢掉少量本地未合并的编辑，也不能让会话列表被
 * 无限副本刷爆 —— 后者不仅噪音大，还会持续消耗 D1 写配额和用户流量。
 *
 * 窗口过后自动恢复，因此真实的「两端长期各自编辑」场景仍能正常分叉，
 * 只是不会在几秒内连爆多个。
 *
 * 进程内存态即可：自激环路是秒级现象，重启后重新计数不影响防护效果。
 */
object ForkCircuitBreaker {

    /** 滑动窗口长度 */
    private const val WINDOW_MS = 10 * 60 * 1000L

    /** 窗口内允许的最大分叉次数 */
    private const val MAX_FORKS = 2

    private val history = mutableMapOf<String, MutableList<Long>>()

    /**
     * 询问某会话现在是否还允许分叉。返回 true 表示放行**并已记账**。
     */
    @Synchronized
    fun allow(convId: String): Boolean {
        val now = System.currentTimeMillis()
        val list = history.getOrPut(convId) { mutableListOf() }
        list.removeAll { now - it > WINDOW_MS }
        if (list.size >= MAX_FORKS) return false
        list += now
        return true
    }

    /** 会话被用户手动干预（删除/清理）后重置计数 */
    @Synchronized
    fun reset(convId: String) {
        history.remove(convId)
    }

    @Synchronized
    fun clear() {
        history.clear()
    }
}
