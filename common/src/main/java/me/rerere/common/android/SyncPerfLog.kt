package me.rerere.common.android

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * D1 同步性能剖析日志（纯旁路，不改变任何同步行为）。
 *
 * 输出到 `<filesDir>/logs/sync_perf.log`。
 *
 * ## 为什么单独做一个，而不是塞进 SyncAuditLog
 *
 * [me.rerere.rikkahub.data.sync.core.SyncAuditLog] 记的是**裁决**（谁覆盖了谁、
 * 哪个安全阀拦了刀），是低频的、出事才看的。而性能剖析要记的是**每一次网络往返**，
 * 高频、量大、且只在排查时才开。两者混在一个文件里，审计记录会被流水账淹没。
 *
 * ## 解决什么问题
 *
 * 「上了 sync-proxy，单次往返 27s→0.9s，但用户体感 pull 还是要等五六分钟。」
 *
 * 这种情况下光看单次耗时永远找不到原因 —— 瓶颈不在**每次多慢**，而在**发了多少次**。
 * 一轮 pull 里若有 89 个会话各自触发 2 次串行请求，178 × 0.9s ≈ 2.7 分钟，
 * 每一次都"很快"，加起来就是灾难。这类 N+1 问题必须靠聚合统计才能暴露。
 *
 * 因此本日志的核心不是流水账，而是**每轮同步结束时的耗时分解表**：
 * 哪个阶段、发了几次请求、总共耗时多少、单次均值多少。一眼就能看出该优化谁。
 *
 * ## 三个通道
 *
 * - [CHANNEL_ROUND]：每轮同步的汇总表（**最有用，排查先看这个**）
 * - [CHANNEL_PHASE]：阶段级耗时（pullConversations / pushOutbox / 各 bundle）
 * - [CHANNEL_REQUEST]：单条 SQL 往返明细（最吵，定位到具体语句时才开）
 *
 * 轮转/清理策略与 [ToolCallDebugLog] 一致，由设置页注入。
 */
object SyncPerfLog {

    /** 每轮同步汇总（阶段耗时分解 + 请求数统计） */
    const val CHANNEL_ROUND = "round"

    /** 阶段级耗时（pull/push 各子阶段） */
    const val CHANNEL_PHASE = "phase"

    /** 单条 SQL 往返明细（含是否走代理、语句数、字节数） */
    const val CHANNEL_REQUEST = "request"

    private val lock = ReentrantLock()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var channels: Set<String> = emptySet()

    @Volatile
    private var maxAgeHours: Int = 24

    @Volatile
    private var maxLines: Int = 5000

    @Volatile
    private var keepBackups: Int = 3

    private var lineCount = 0
    private var lastCleanupAt = 0L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun configure(
        enabled: Boolean,
        channels: Set<String>,
        maxAgeHours: Int,
        maxLines: Int,
        keepBackups: Int,
    ) {
        lock.withLock {
            this.enabled = enabled
            this.channels = channels
            this.maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30)
            this.maxLines = maxLines.coerceIn(100, 100_000)
            this.keepBackups = keepBackups.coerceIn(1, 10)
        }
    }

    fun init(dir: File) {
        logFile = File(dir, "logs/sync_perf.log")
        logFile?.parentFile?.mkdirs()
        lineCount = runCatching { logFile?.readLines()?.size ?: 0 }.getOrDefault(0)
    }

    fun isChannelEnabled(channel: String): Boolean = enabled && channel in channels

    fun log(channel: String, stage: String, message: String) {
        if (!isChannelEnabled(channel)) return
        writeLine(channel, stage, message)
    }

    // MARK: - 请求级打点

    /**
     * 记录一次 D1 往返。由 D1Client 在每次 HTTP 完成后调用。
     *
     * @param via "proxy" 或 "rest" —— 用来验证代理是否真的生效了
     * @param statements 本次携带的语句数（>1 说明批量成功合并了）
     * @param ms 往返耗时
     * @param bytes 响应体字节数，用来分辨"慢是因为网络往返"还是"数据量大"
     */
    fun request(via: String, statements: Int, ms: Long, bytes: Int, sqlHint: String) {
        RoundStats.current?.record(via, ms)
        if (!isChannelEnabled(CHANNEL_REQUEST)) return
        writeLine(
            CHANNEL_REQUEST, via,
            "${ms}ms stmts=$statements bytes=$bytes | $sqlHint"
        )
    }

    // MARK: - 轮次统计

    /**
     * 一轮同步的耗时账本。
     *
     * 用 ThreadLocal 而非全局单例：同步可能在多个协程上下文里跑，
     * 全局累加会把并发的两轮混在一起，算出来的数没有意义。
     */
    class RoundStats(val trigger: String) {
        val startedAt = System.currentTimeMillis()
        private val phases = LinkedHashMap<String, Long>()
        private val phaseCounts = LinkedHashMap<String, Int>()

        var requestCount = 0
            private set
        var proxyCount = 0
            private set
        var restCount = 0
            private set
        var networkMs = 0L
            private set

        @Synchronized
        fun record(via: String, ms: Long) {
            requestCount++
            networkMs += ms
            if (via == "proxy") proxyCount++ else restCount++
        }

        @Synchronized
        fun addPhase(name: String, ms: Long) {
            phases[name] = (phases[name] ?: 0L) + ms
            phaseCounts[name] = (phaseCounts[name] ?: 0) + 1
        }

        /**
         * 生成汇总表。
         *
         * 特意把「请求数」和「单次均值」并排放：如果均值很低但总时长很高，
         * 那就是 N+1 —— 该合并请求，而不是继续优化单次延迟。
         */
        fun render(): String {
            val total = System.currentTimeMillis() - startedAt
            val avg = if (requestCount > 0) networkMs / requestCount else 0L
            val sb = StringBuilder()
            sb.append("total=${total}ms net=${networkMs}ms reqs=$requestCount ")
            sb.append("(proxy=$proxyCount rest=$restCount) avg=${avg}ms/req trigger=$trigger")
            if (phases.isNotEmpty()) {
                sb.append("\n  ── 阶段分解 ──")
                phases.entries.sortedByDescending { it.value }.forEach { (name, ms) ->
                    val n = phaseCounts[name] ?: 1
                    val pct = if (total > 0) ms * 100 / total else 0
                    sb.append("\n  %-28s %6dms  %3d%%  x%d".format(name, ms, pct, n))
                }
            }
            // 本地耗时 = 总时长 - 网络时长，明显偏高说明卡在数据库/序列化而非网络
            val localMs = total - networkMs
            if (localMs > 0) {
                sb.append("\n  %-28s %6dms  %3d%%".format(
                    "(本地处理/DB/序列化)", localMs, if (total > 0) localMs * 100 / total else 0
                ))
            }
            return sb.toString()
        }

        companion object {
            /**
             * 当前轮次统计。**故意用全局 @Volatile，而不是 ThreadLocal**。
             *
             * 教训（2026-09-11 实测）：请求打点在 D1Client，跑在 Ktor 的 IO 线程池上；
             * 而 round()/phase() 跑在同步协程里。ThreadLocal 跨不过这个线程边界 ——
             * 于是明细里全是请求，汇总表却永远 `reqs=0 net=0ms`，性能日志当场变成
             * 误导性证据。
             *
             * 同步本身由 pushMutex / pullMutex 串行化，同一时刻只有一个轮次在跑，
             * 全局引用足够安全；真要并发，最多是两轮数据混在一起，也不会错到"归零"。
             */
            @Volatile
            var current: RoundStats? = null
        }
    }

    /**
     * 包裹一轮完整同步，结束时自动输出汇总表。
     *
     * 即使中途抛异常也会输出（finally），因为"同步失败前卡在哪"同样关键。
     */
    inline fun <T> round(trigger: String, block: () -> T): T {
        if (!isChannelEnabled(CHANNEL_ROUND) && !isChannelEnabled(CHANNEL_PHASE) &&
            !isChannelEnabled(CHANNEL_REQUEST)
        ) return block()

        val stats = RoundStats(trigger)
        val prev = RoundStats.current
        RoundStats.current = stats
        try {
            return block()
        } finally {
            RoundStats.current = prev
            if (isChannelEnabled(CHANNEL_ROUND)) {
                log(CHANNEL_ROUND, "summary", stats.render())
            }
        }
    }

    /** 包裹一个阶段，自动计时并累加进当前轮次。 */
    inline fun <T> phase(name: String, block: () -> T): T {
        val stats = RoundStats.current ?: return block()
        val t0 = System.currentTimeMillis()
        try {
            return block()
        } finally {
            val ms = System.currentTimeMillis() - t0
            stats.addPhase(name, ms)
            if (isChannelEnabled(CHANNEL_PHASE) && ms >= 50) {
                log(CHANNEL_PHASE, name, "${ms}ms")
            }
        }
    }

    // MARK: - 写盘（与 ToolCallDebugLog 同策略）

    private fun writeLine(channel: String, stage: String, message: String) {
        val file = logFile ?: return
        val line = "[${timeFormat.format(Date())}] [$channel] [$stage] $message\n"
        lock.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                cleanupExpired(file)
                if (lineCount >= maxLines) rotate(file)
                file.appendText(line)
                lineCount += line.count { it == '\n' }
            }.onFailure { e ->
                Log.w("SyncPerf", "write log failed: ${e.message}")
            }
        }
    }

    private fun cleanupExpired(file: File) {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt < 60_000L) return
        lastCleanupAt = now
        val cutoff = now - maxAgeHours * 3_600_000L
        val parent = file.parentFile ?: return
        parent.listFiles { f -> f.isFile && f.name.startsWith(file.name) }?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private fun rotate(file: File) {
        val parent = file.parentFile ?: return
        File(parent, file.name + "." + (keepBackups + 1)).delete()
        for (i in keepBackups downTo 1) {
            val from = if (i == 1) file else File(parent, file.name + "." + (i - 1))
            val to = File(parent, file.name + "." + i)
            if (from.exists()) from.renameTo(to)
        }
        lineCount = 0
    }

    /** 读取最近 N 行，供日志页展示。 */
    fun tail(lines: Int = 300): List<String> = runCatching {
        val file = logFile ?: return emptyList()
        if (!file.isFile) return emptyList()
        file.readLines().takeLast(lines)
    }.getOrDefault(emptyList())

    fun clear() {
        val file = logFile ?: return
        lock.withLock {
            runCatching {
                file.parentFile
                    ?.listFiles { f -> f.isFile && f.name.startsWith(file.name) }
                    ?.forEach { it.delete() }
                lineCount = 0
            }
        }
    }
}
