package me.rerere.rikkahub.data.sync.core

/**
 * Hybrid Logical Clock（大统一方案 v2 §1）。
 *
 * ## 为什么需要它：墙钟跨设备不可比
 *
 * 现状 `SyncEngine` 只有一个时间维度 `updated_at`（墙钟毫秒），既当传输水位又当
 * 冲突裁决依据。代码里那句注释已经承认过这件事的不可行：
 *
 * > 「不能拿两台设备的墙钟比大小：对端时钟快几秒就会把本机刚改的整包设置判输。」
 *
 * 于是它绕过去了——改用「云端有没有走在本机基线之前」的乐观锁。但乐观锁只能判
 * *有没有冲突*，判不出 *谁的改动更晚*，一旦基线失配就退回
 * `maxOf(now, remoteUpdatedAt + 1)` 强推，等价于「谁最后写谁赢」。
 *
 * HLC 补上的正是这个缺口：**一个跨设备可比、且尊重因果的全序时间戳。**
 *
 * ## ⚠️ 两个时钟必须分开，别合成一个
 *
 * 这是本方案最容易踩错的地方（v1 方案就写错了）。pull 是**水位增量**查询：
 *
 * ```sql
 * SELECT ... FROM conversations WHERE updated_at > ? ORDER BY updated_at ASC
 * ```
 *
 * 这一列承担的是「**给我比上次更新的行**」，它必须**全局单调递增**。
 * 如果把它换成 HLC（物理分量取自本机墙钟），那么一台时钟慢的设备写入的行
 * 会带上比对端水位更小的值：
 *
 * ```
 * 平板水位 = 1000
 * 手机写入 updated_at = 940      ← HLC 完全合法，因果上确实更早
 * 平板 pull: WHERE updated_at > 1000  → 940 这行永世不可见
 * ```
 *
 * 数据静静躺在云端，两端永不相遇，**且没有任何报错**。这比覆盖更难查。
 *
 * 所以职责必须切开：
 *
 * | 列 | 语义 | 用途 |
 * |---|---|---|
 * | `updated_at` | **传输序号**，`max(now, 云端旧值+1)` | pull 水位、manifest 增量；只增不减，允许「不公平」 |
 * | `hlc` | **因果时钟**，本类生成 | 冲突裁决、LWW 胜负、CRDT 比较 |
 *
 * 一句话：**谁赢由 hlc 决定，能不能被看见由 updated_at 决定。**
 *
 * ## 打包成 Long 而非结构体
 *
 * ```
 * packed = (wallMs shl 16) or counter     // 48 bit wall + 16 bit counter
 * ```
 *
 * - 48 位毫秒 ≈ 公元 10889 年；16 位 counter = 单毫秒 65535 次写入，都够用
 * - **关键好处**：`SyncVersionMap.versions/tombstones` 是 `Map<String, Long>`，
 *   换成 packed HLC 后**线格式一个字节都不用改**，老客户端读到的仍是「一个大整数」，
 *   `>` 比较语义天然兼容。若改成结构体则所有旧客户端立刻不认识。
 *
 * `node`（设备 id）**不进 packed**：平票不靠设备名裁决，见 [decide]。
 *
 * ## 线程安全
 *
 * [now] 在实例内 `synchronized`，保证同进程内单调；跨进程由持久化兜底
 * （每次 [now] 都写盘，崩溃重启不倒退）。
 */
class SyncClock(
    private val store: Store,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    /** HLC 的持久化后端。生产实现落在 `sync_state` 的 `sync:hlc` 键；测试可用内存实现。 */
    interface Store {
        fun load(): Long
        fun save(packed: Long)
    }

    private val lock = Any()

    @Volatile
    private var last: Long = store.load()

    /**
     * 产生一个新的本地事件时间戳。
     *
     * `wall = max(上次wall, 系统墙钟)`；若 wall 与上次相同则 counter++，否则 counter 归零。
     * 即使系统时间被回调（用户改时间 / NTP 校正），HLC 也**只增不减**。
     */
    fun now(): Long = synchronized(lock) {
        val sysWall = wallClock()
        val lastWall = wallOf(last)
        val next = if (sysWall > lastWall) {
            pack(sysWall, 0)
        } else {
            // 墙钟没前进（或倒退）：靠 counter 维持单调
            val c = counterOf(last)
            if (c >= MAX_COUNTER) pack(lastWall + 1, 0) else pack(lastWall, c + 1)
        }
        last = next
        store.save(next)
        next
    }

    /**
     * 观测到一个远端时间戳时调用，建立**因果顺序**：
     * 此后本机产生的时间戳必然大于这个远端值。
     *
     * ⚠️ pull 侧读到任何带 hlc 的行 / envelope 都必须先调用本方法再处理，
     * 漏一处就退化成纯物理时钟，HLC 的因果保证随之失效。
     */
    fun observe(remotePacked: Long) {
        if (remotePacked <= 0L) return // 0 = unknown（旧客户端写的），不参与推进
        synchronized(lock) {
            if (remotePacked <= last) return
            val remoteWall = wallOf(remotePacked)
            val localWall = wallOf(last)
            val next = when {
                remoteWall > localWall -> pack(remoteWall, counterOf(remotePacked))
                else -> pack(localWall, maxOf(counterOf(last), counterOf(remotePacked)))
            }
            last = next
            store.save(next)
        }
    }

    /** 当前已知的最大时间戳（不推进），用于诊断展示与 ack 上报 */
    fun peek(): Long = last

    companion object {
        const val UNKNOWN: Long = 0L
        private const val COUNTER_BITS = 16
        private const val MAX_COUNTER = (1 shl COUNTER_BITS) - 1

        fun pack(wallMs: Long, counter: Int): Long =
            (wallMs shl COUNTER_BITS) or (counter.toLong() and MAX_COUNTER.toLong())

        fun wallOf(packed: Long): Long = packed ushr COUNTER_BITS
        fun counterOf(packed: Long): Int = (packed and MAX_COUNTER.toLong()).toInt()

        /**
         * 全序裁决（方案 v2 §1.4）。
         *
         * ## 为什么平票不能「保本地」
         *
         * 现有代码平票一律 `else -> winner = l`（见 `SyncVersionMap.kt` 与
         * `SyncSettingsFilter`）。单机看着无害，**跨设备是发散的**：A 保 A 的、
         * B 保 B 的，两端永远不一致，且每轮都互相回推，形成同步永动机。
         *
         * ## 规则
         *
         * 1. packed hlc 大者赢
         * 2. 相等 → 内容 sha 字典序大者赢
         *
         * sha 裁决**无需额外存储、两端必然算出同一结果**，比引入 node 字段更省。
         * 两者都相等 = 内容本来就一样，取谁都行（返回 [Winner.LOCAL] 避免 UI 闪动）。
         *
         * @param localSha  本地值的内容 sha（规范化 JSON 的 sha256）
         * @param remoteSha 云端值的内容 sha
         */
        fun decide(
            localHlc: Long,
            remoteHlc: Long,
            localSha: String,
            remoteSha: String,
        ): Winner {
            // unknown（0）语义：既不赢也不输，见方案 §2.5。
            // 一方未知另一方已知 → 采纳已知方；双方未知 → 保本地且不制造假事实。
            if (localHlc == UNKNOWN && remoteHlc == UNKNOWN) return Winner.LOCAL_UNKNOWN
            if (localHlc == UNKNOWN) return Winner.REMOTE
            if (remoteHlc == UNKNOWN) return Winner.LOCAL

            return when {
                remoteHlc > localHlc -> Winner.REMOTE
                localHlc > remoteHlc -> Winner.LOCAL
                remoteSha > localSha -> Winner.REMOTE
                else -> Winner.LOCAL
            }
        }
    }

    enum class Winner {
        LOCAL,
        REMOTE,

        /**
         * 双方 hlc 都是 unknown：保本地，但**不得**把本地值当作新事实上推
         * （否则默认值会带着假戳污染对端，见方案 §2.5 的反面教材）。
         */
        LOCAL_UNKNOWN,
        ;

        val takesRemote: Boolean get() = this == REMOTE
    }
}
