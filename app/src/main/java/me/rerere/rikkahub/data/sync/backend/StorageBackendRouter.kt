package me.rerere.rikkahub.data.sync.backend

import kotlinx.serialization.Serializable

/**
 * 时间分片路由（多后端 · Step I）。
 *
 * ## 一句话
 *
 * 每个后端认领一段时间，会话按**建立时间**落到对应后端。
 * 老数据原地不动、新数据进新库，满了再加一个 —— 换后端从「数据搬迁」变成「加一行配置」。
 *
 * ## 为什么判据是 `createAt` 而不是写入时间
 *
 * 判据必须是数据的固有属性。按写入时间分片，一条老会话今天被改就得跨库搬家，
 * 于是要迁移管道 + 复活路径 + 路由表；按 `createAt` 分片，归属是纯函数，
 * **随时可重算、零状态、零迁移**。
 *
 * ## 已知缺口（必须靠别的机制补）
 *
 * 老而活跃的会话（Schedule Agent 产线，每 10 分钟写一轮）会被钉在老库上，
 * 照样烧老库的写额度。分片规则解决不了这个，只能给它一条显式「移籍」通道。
 *
 * ## 区间约定
 *
 * 一律**左闭右开** `[rangeStart, rangeEnd)`，两端可 `null` = 不限。
 * **范围只允许向后追加，禁止修改已有范围** —— 改了就会出现「没有后端认领」的孤儿行。
 */
object StorageBackendRouter {

    /** `createdAt` 是否落在 [config] 负责的时间段内（左闭右开）。 */
    fun covers(config: StorageBackendConfig, createdAt: Long): Boolean {
        val start = config.rangeStart
        val end = config.rangeEnd
        return (start == null || createdAt >= start) && (end == null || createdAt < end)
    }

    /**
     * 读取目标：**不看 [StorageBackendConfig.enabled]**。
     *
     * 关闭只表示「不再接收新推送」，历史数据还得读得回来 —— 这是可回退切换的前提。
     * 多个命中时取 `rangeStart` 最大的那个（范围最专一的优先，且结果确定）。
     */
    fun readTarget(
        configs: List<StorageBackendConfig>,
        createdAt: Long,
    ): StorageBackendConfig? = configs
        .filter { it.isConfigured && covers(it, createdAt) }
        .maxByOrNull { it.rangeStart ?: Long.MIN_VALUE }

    /** 写入目标：在 [readTarget] 基础上额外要求 `enabled`。 */
    fun writeTarget(
        configs: List<StorageBackendConfig>,
        createdAt: Long,
    ): StorageBackendConfig? = configs
        .filter { it.enabled && it.isConfigured && covers(it, createdAt) }
        .maxByOrNull { it.rangeStart ?: Long.MIN_VALUE }

    /**
     * 非会话数据（settings 分片、bundles）的归属：**永远走最新的那个后端**。
     *
     * settings 是「当前状态」而不是「历史」，按时间段切没有意义；它们量小、改动频繁，
     * 放在最新后端才不会一改就往老库写。
     */
    fun latestWriteTarget(configs: List<StorageBackendConfig>): StorageBackendConfig? = configs
        .filter { it.enabled && it.isConfigured }
        .maxByOrNull { it.rangeStart ?: Long.MIN_VALUE }

    /**
     * 配置体检：重复 id / 时间重叠 / 时间空档。
     *
     * 返回人话条目，空列表 = 健康。设置页拿它做红字提示 —— 空档意味着
     * 那段时间的会话**无处可去**，会静默不同步，必须显式警告。
     */
    fun validate(
        configs: List<StorageBackendConfig>,
        hasLegacyFallback: Boolean = false,
    ): List<String> {
        val out = mutableListOf<String>()
        if (configs.isEmpty()) return out

        configs.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { out += "后端 id 重复：$it（路由结果不确定）" }

        val sorted = configs.sortedBy { it.rangeStart ?: Long.MIN_VALUE }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val nameA = a.alias.ifBlank { a.typeName }
            val nameB = b.alias.ifBlank { b.typeName }
            val aEnd = a.rangeEnd
            if (aEnd == null) continue              // a 覆盖到 +∞，后面的后端永远拿不到数据
            val bStart = b.rangeStart ?: continue   // b 覆盖 -∞，排序上不该出现在 a 之后
            when {
                bStart < aEnd -> out += "「$nameA」与「$nameB」时间段重叠（$bStart < $aEnd），后加的优先"
                bStart > aEnd -> out += "时间空档：$aEnd ~ $bStart 之间没有后端负责，这段的会话不会同步"
            }
        }

        // ★ 两端边界外无人认领 —— 最容易踩、也最难发现的那一类。
        //
        // 原实现只比「相邻对」，于是「只配一个 [2026-09-16, null) 的 Supabase」时
        // 09-16 之前的会话**没有任何后端认领**，却一条警告都不报。
        // 症状是那批会话静默不同步：不报错、不重试、界面上也看不出少了东西。
        //
        // @param hasLegacyFallback 旧 d1Config 是否已配齐。它覆盖 `(-∞, +∞)`，
        //   所以只要它还配着，左侧边界就不是空档 —— 否则会天天误报一条
        //   「09-16 之前没人管」，而其实老会话正躺在老库里。
        if (!hasLegacyFallback) {
            val earliest = sorted.first()
            if (earliest.rangeStart != null) {
                out += "时间空档：${earliest.rangeStart} 之前没有后端负责，" +
                    "这段的会话不会同步（把最早那个的起点留空可覆盖全部历史）"
            }
        }
        val lastEnd = sorted.last().rangeEnd
        if (lastEnd != null) {
            out += "时间空档：$lastEnd 之后没有后端负责，" +
                "这段的会话不会同步（把最晚那个的终点留空可覆盖未来）"
        }
        return out
    }

    /**
     * 可跨设备同步的**路由投影** —— 只有 id / 别名 / 类型 / 时间段 / 开关，**不含任何凭据**。
     *
     * ## 为什么必须有这个
     *
     * [StorageBackendConfig] 含 `apiToken` / `serviceKey`，上云会自指且密钥跨设备，
     * 所以整个 `backends` 目前被打成 LOCAL 剔除。但**路由信息必须跨设备** ——
     * B 设备不知道「9 月 16 号之后的数据在哪个库」，就永远拉不到那批会话。
     *
     * 于是拆成两块：`backends`（含密钥，设备本地）+ `backendRoutings`（纯路由，随设置同步）。
     * 本对象就是后者。缺凭据的设备会知道「该去哪个库」但连不上 ——
     * 这是显式失败，比静默漏拉好一万倍。
     */
    @Serializable
    data class Routing(
        val id: String,
        val alias: String,
        val typeName: String,
        val enabled: Boolean,
        val rangeStart: Long? = null,
        val rangeEnd: Long? = null,
    )

    fun routingOf(config: StorageBackendConfig): Routing = Routing(
        id = config.id,
        alias = config.alias,
        typeName = config.typeName,
        enabled = config.enabled,
        rangeStart = config.rangeStart,
        rangeEnd = config.rangeEnd,
    )

    fun routingsOf(configs: List<StorageBackendConfig>): List<Routing> = configs.map(::routingOf)
}
