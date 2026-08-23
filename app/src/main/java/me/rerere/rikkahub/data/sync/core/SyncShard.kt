package me.rerere.rikkahub.data.sync.core

/**
 * settings 分片（大统一方案 v2 §2.1）。
 *
 * ## 分片解决什么、不解决什么
 *
 * ⚠️ **拆分片不修正确性。** 正确性 100% 来自字段级 CRDT（见 [SyncFieldKind]）：
 * 就算只有一个分片，只要逐字段裁决是对的就不会互踩；反过来拆成十几片但仍然
 * `remote.copy()` 整片落地，那只是把互踩范围缩小了一点。
 *
 * 分片带来的是**工程收益**：
 * - 传输量：改一个开关只推一片，而不是整包 ~124 字段
 * - 爆炸半径：某片 payload 解码失败不再拖垮全部设置
 * - 冲突概率：两端改不同类设置时连乐观锁都不会撞
 *
 * ## [LOCAL] 分片为什么必须存在
 *
 * 现状「哪些字段不上云」散落在 `SyncSettingsFilter.forUpload` 与 `mergeRemote`
 * **两处手写**，加字段忘登记的后果不只是被覆盖，而是**泄露密钥或污染对端**
 * （`d1Config` / `webServerAccessPassword` / `externalDeliveryToken` 都在里头）。
 * 声明式之后「不上云」变成注册表里一个显式标记，配合穷尽性单测，漏不掉。
 */
enum class SyncShard(val key: String) {
    /** 各类 modelId 指针 + thinkingBudget */
    MODELS("settings.models"),

    /** 全部 prompt 文本 */
    PROMPTS("settings.prompts"),

    /** providers + providerTombstones（现有 LWW + 墓碑逻辑平移，不动语义） */
    PROVIDERS("settings.providers"),

    /** 助手、标签、图片标签、相册目录 */
    ASSISTANTS("settings.assistants"),

    /** MCP 服务器 */
    MCP("settings.mcp"),

    /** image/vector/tts/asr 渠道 + 选中指针 + 各 SyncVersionMap */
    MEDIA("settings.media"),

    /** 搜索服务与文件处理服务 */
    SEARCH("settings.search"),

    /**
     * 监督锁。**必须单独一片**，不是可选：
     * 它的合并语义（事件日志 fold）和其他分片根本不同，见 `SupervisionEventLog`。
     */
    SUPERVISION("settings.supervision"),

    /** 行为开关、限流、通信参数、日志开关 */
    BEHAVIOR("settings.behavior"),

    /** 主题观感（非 displaySetting，那个走独立 bundle） */
    THEME("settings.theme"),

    /** 注入、世界书、快捷消息、压缩模板 */
    CONTENT("settings.content"),

    /** R2 账户（含 secret，必须完整同步，否则其他设备无法为 r2:// 签名） */
    R2("settings.r2"),

    /**
     * 设备本地，**永不上云**。
     * 对应 key 只用于本地记账，不会出现在 `bundles` 表。
     */
    LOCAL("settings.local"),
    ;

    companion object {
        /** 会产生云端 bundle 行的分片 */
        val uploadable: List<SyncShard> get() = entries.filter { it != LOCAL }

        fun ofKey(key: String): SyncShard? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 字段的合并语义（大统一方案 v2 §2.4）。
 */
enum class SyncFieldKind {
    /** 标量/整体替换，按 [SyncClock.decide] 全序裁决 */
    LWW,

    /**
     * 集合类，按 id 逐项 LWW + 墓碑。
     * 直接复用现有 [mergeListByVersion] / [stampListChanges]，不重新发明。
     */
    OR_SET,

    /** 设备本地，永不上云、永不被云端改写 */
    LOCAL,

    /**
     * 派生字段：其值由其他字段的合并结果推导，合并主循环跳过它，
     * 收尾阶段统一重算。
     *
     * 例：`searchServiceSelected` 是个 Int 下标，必须在 `searchServices` 合并完之后
     * 才能 coerce 到合法范围（现状 `SyncSettingsFilter` 已经在这么做了）。
     * **这类跨字段依赖是注解方案表达不了的**，也是本方案不用注解处理器的理由之一。
     */
    DERIVED,

    /** 专属合并器（目前仅 supervision 事件日志） */
    CUSTOM,

    /**
     * volatile 噪音字段：既不上云也无需本地保护，合并时原样保留本地值。
     * 与 [LOCAL] 的区别只在语义标注（LOCAL 是「设备身份/密钥」，NOISE 是「无意义计数」）。
     */
    NOISE,
}
