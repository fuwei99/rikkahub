package me.rerere.rikkahub.data.sync.core

/**
 * `Settings` 全字段同步注册表（大统一方案 v2 §2.2）。
 *
 * ## 为什么不用 `@SyncField` 注解 + KSP
 *
 * v1 方案想靠注解让「加字段零同步代码」。否决，三条理由：
 *
 * 1. `Settings` 是 `@Serializable data class`，注解方案要么拖 `kotlin-reflect`（数 MB），
 *    要么为 84 个字段写一个 KSP processor，维护成本远超收益。
 * 2. **注解表达不了跨字段依赖**：`searchServiceSelected` 是个 Int 下标，
 *    必须等 `searchServices` 合并完才能 coerce 到合法范围；
 *    `selectedTTSProviderId` 同理。这类 [SyncFieldKind.DERIVED] 语义注解写不出来。
 * 3. **注解一样会忘写。** 忘写注解 == 忘写白名单，根本问题一点没解决。
 *
 * ## 真正的「一劳永逸」是编译期强制
 *
 * 本注册表 + `SyncFieldRegistryExhaustiveTest` 的组合才是机制核心：
 *
 * > 不是「加字段不用写代码」，而是「**加字段忘写代码，CI 当场红脸**」。
 *
 * 那个测试用 `Settings.serializer().descriptor.elementNames()` 取全部字段名，
 * 与本表逐一对账，缺一个或多一个都直接 fail。零反射、零 KSP、纯 JVM 单测。
 *
 * ## 现状对比：这张表取代了什么
 *
 * `SyncSettingsFilter.forUpload()` 与 `mergeRemote()` 里那两坨手写清单。
 * 那套机制的致命缺陷是 `mergeRemote` 最后 `return remote.copy(...)` —— 
 * **骨架是云端的**，只有显式列出的字段才保本地。没登记 = 被云端抹平。
 * 换成注册表驱动后，骨架改为「逐字段挑胜者」，未登记的字段根本进不了合并循环
 * （因为它压根编译不过测试）。
 *
 * ## 维护约定
 *
 * - 新增 `Settings` 字段 → 在下方对应分片处加一行，**顺序按 Settings 声明顺序**，便于对账
 * - 拿不准归哪片：看「谁会跟它一起改」。同一次用户操作会一起变的字段放同一片
 * - 涉密/设备身份 → [SyncShard.LOCAL] + [SyncFieldKind.LOCAL]，别偷懒放别处
 */
object SyncFieldRegistry {

    /**
     * @param name  必须与 `Settings` 属性名**完全一致**（穷尽性测试按名字对账）
     * @param shard 所属分片
     * @param kind  合并语义
     * @param note  为什么这么归类；只在「看名字推不出来」时才写
     */
    data class Entry(
        val name: String,
        val shard: SyncShard,
        val kind: SyncFieldKind,
        val note: String = "",
    )

    private fun lww(name: String, shard: SyncShard, note: String = "") =
        Entry(name, shard, SyncFieldKind.LWW, note)

    private fun orSet(name: String, shard: SyncShard, note: String = "") =
        Entry(name, shard, SyncFieldKind.OR_SET, note)

    private fun local(name: String, note: String) =
        Entry(name, SyncShard.LOCAL, SyncFieldKind.LOCAL, note)

    private fun noise(name: String, note: String) =
        Entry(name, SyncShard.LOCAL, SyncFieldKind.NOISE, note)

    private fun derived(name: String, shard: SyncShard, note: String) =
        Entry(name, shard, SyncFieldKind.DERIVED, note)

    private fun custom(name: String, shard: SyncShard, note: String) =
        Entry(name, shard, SyncFieldKind.CUSTOM, note)

    /**
     * 全字段清单。顺序与 `Settings` 声明顺序一致。
     *
     * 注：`init` 是 `@Transient`（dummy 哨兵），不进 serializer descriptor，
     * 因此不在本表中；穷尽性测试对账的是 descriptor，不会因此失败。
     */
    val fields: List<Entry> = listOf(
        // ---------------- 主题观感 ----------------
        lww("dynamicColor", SyncShard.THEME),
        lww("themeId", SyncShard.THEME),
        orSet("customThemes", SyncShard.THEME),
        lww("developerMode", SyncShard.THEME),
        local(
            "displaySetting",
            "走独立 bundle settings.display，受设备级 display_sync_enabled 开关控制；" +
                "合并逻辑另见 SyncSettingsFilter.mergeRemoteDisplay（保留通知开关等设备本地项）"
        ),
        lww("fileCompressSetting", SyncShard.THEME),

        // ---------------- 模型指针 ----------------
        orSet("favoriteModels", SyncShard.MODELS),
        lww("chatModelId", SyncShard.MODELS),
        lww("fastModelId", SyncShard.MODELS),
        lww("titleModelId", SyncShard.MODELS),
        lww("imageGenerationModelId", SyncShard.MODELS),
        orSet("imageGenerationModelIds", SyncShard.MODELS),

        // ---------------- prompt 与各功能参数 ----------------
        lww("titlePrompt", SyncShard.PROMPTS),
        lww("translateModeId", SyncShard.MODELS),
        lww("translatePrompt", SyncShard.PROMPTS),
        lww("translateThinkingBudget", SyncShard.MODELS),
        lww("enableSuggestion", SyncShard.BEHAVIOR),
        lww("suggestionModelId", SyncShard.MODELS),
        lww("suggestionPrompt", SyncShard.PROMPTS),
        lww("ocrModelId", SyncShard.MODELS),
        lww("ocrPrompt", SyncShard.PROMPTS),
        lww("ocrThinkingBudget", SyncShard.MODELS),
        lww("memoryModelId", SyncShard.MODELS),
        lww("memoryPrompt", SyncShard.PROMPTS),
        lww("memoryThinkingBudget", SyncShard.MODELS),
        lww("memoryInjectModelId", SyncShard.MODELS),
        lww("memoryInjectFallbackModelId", SyncShard.MODELS),
        lww("memoryInjectPrompt", SyncShard.PROMPTS),
        lww("memoryInjectThinkingBudget", SyncShard.MODELS),
        lww("compressModelId", SyncShard.MODELS),
        lww("compressPrompt", SyncShard.PROMPTS),
        orSet("compressTemplates", SyncShard.CONTENT),
        lww("defaultCompressTemplateId", SyncShard.CONTENT),

        // ---------------- 助手 ----------------
        local(
            "assistantId",
            "当前选中的助手是设备 UI 状态。曾因整包同步导致「一台切助手把另一台正在用的" +
                "助手静默换掉」（用户报告的核心 bug）；对话内容本身照常同步"
        ),

        // ---------------- 渠道 ----------------
        orSet("providers", SyncShard.PROVIDERS, "条目自带 updatedAt，墓碑走 providerTombstones"),
        lww(
            "providerTombstones",
            SyncShard.PROVIDERS,
            "墓碑表本身是 Map<id, deletedAt> 的并集（同 key 取较晚），由 mergeLongMaps 处理"
        ),
        lww("imageProvidersSyncMeta", SyncShard.MEDIA, "OR_SET 的外挂版本表，随宿主列表一起合并"),
        lww("ttsProvidersSyncMeta", SyncShard.MEDIA, "同上"),
        lww("asrProvidersSyncMeta", SyncShard.MEDIA, "同上"),
        lww("searchServicesSyncMeta", SyncShard.SEARCH, "同上"),
        lww("vectorProvidersSyncMeta", SyncShard.MEDIA, "同上"),
        orSet("imageProviders", SyncShard.MEDIA, "@Transient builtIn/description 需从本地同 id 项回填"),
        orSet("vectorProviders", SyncShard.MEDIA, "同上"),

        orSet("assistants", SyncShard.ASSISTANTS, "条目自带 updatedAt"),
        orSet("assistantTags", SyncShard.ASSISTANTS),
        orSet("imageTags", SyncShard.ASSISTANTS),
        orSet("galleryFolders", SyncShard.ASSISTANTS, "字符串列表，id 即自身"),

        // ---------------- OCR 限流 ----------------
        lww("ocrMaxConcurrency", SyncShard.BEHAVIOR),
        lww("ocrRatePerMinute", SyncShard.BEHAVIOR),

        // ---------------- 搜索 ----------------
        orSet("searchServices", SyncShard.SEARCH),
        lww("searchCommonOptions", SyncShard.SEARCH),
        derived(
            "searchServiceSelected",
            SyncShard.SEARCH,
            "Int 下标，必须在 searchServices 合并后 coerce 到合法范围，否则 UI 指向不存在的项"
        ),

        // ---------------- 记忆与日志 ----------------
        lww("memorySearch", SyncShard.BEHAVIOR),
        lww("memoryLog", SyncShard.BEHAVIOR),
        lww("requestLog", SyncShard.BEHAVIOR),
        local(
            "toolLog",
            "「此刻在这台设备上排查什么」的属性。一台开了排查开关不该让另一台也一直写盘"
        ),
        lww("memoryInject", SyncShard.BEHAVIOR),

        // ---------------- MCP 与文件处理 ----------------
        orSet("mcpServers", SyncShard.MCP, "版本取 commonOptions.updatedAt"),
        orSet("fileProcessingServices", SyncShard.SEARCH),

        // ---------------- 云端与备份配置 ----------------
        lww("webDavConfig", SyncShard.BEHAVIOR),
        local("s3Config", "旧备份密钥，设备本地"),
        local("d1Config", "同步锚点本身。上云会造成自指，且含 API token"),
        orSet(
            "r2Accounts",
            SyncShard.R2,
            "必须完整同步（含 secretAccessKey），否则其他设备无法为 r2:// 对象签名读取；" +
                "云端 secret 为空时保留本机 secret 兼容旧数据"
        ),
        lww("r2PresignTtlSeconds", SyncShard.R2),

        // ---------------- TTS / ASR ----------------
        orSet("ttsProviders", SyncShard.MEDIA),
        derived(
            "selectedTTSProviderId",
            SyncShard.MEDIA,
            "选中项被合并删掉时要回落到合法 id，依赖 ttsProviders 的合并结果"
        ),
        orSet("asrProviders", SyncShard.MEDIA),
        derived("selectedASRProviderId", SyncShard.MEDIA, "同 selectedTTSProviderId"),

        // ---------------- 内容注入 ----------------
        orSet("modeInjections", SyncShard.CONTENT),
        orSet("lorebooks", SyncShard.CONTENT),
        orSet("quickMessages", SyncShard.CONTENT),

        // ---------------- 本机 Web 服务（全部设备本地） ----------------
        local("webServerEnabled", "本机服务开关"),
        local("webServerPort", "本机端口"),
        local("webServerJwtEnabled", "本机鉴权模式"),
        local("webServerAccessPassword", "本机访问密码，禁止上云"),
        local("externalDeliveryToken", "外部投递令牌，禁止上云"),
        local("webServerLocalhostOnly", "本机监听范围"),

        // ---------------- 其他 ----------------
        lww("backupReminderConfig", SyncShard.BEHAVIOR),
        lww("subagentMasterGate", SyncShard.BEHAVIOR),
        lww("communication", SyncShard.BEHAVIOR),
        noise("launchCount", "本机启动计数，volatile 噪音"),
        noise("sponsorAlertDismissedAt", "本机弹窗已读标记，volatile 噪音"),

        custom(
            "supervision",
            SyncShard.SUPERVISION,
            "事件日志 fold。旧 strengthenWith 是单调只增并集，并集无减法 → " +
                "「解锁」在数学上无法表达，导致本人在 A 设备解锁后被 B 设备的旧锁态覆盖回去。" +
                "改为「状态 = 事件序列 fold」，解锁 = 一个 hlc 更大的事件。见 SupervisionEventLog"
        ),
        lww("focusLock", SyncShard.BEHAVIOR),
    )

    private val byName: Map<String, Entry> = fields.associateBy { it.name }

    fun of(name: String): Entry? = byName[name]

    fun ofShard(shard: SyncShard): List<Entry> = fields.filter { it.shard == shard }

    /** 参与上云的字段（排除设备本地与噪音） */
    val uploadable: List<Entry>
        get() = fields.filter { it.kind != SyncFieldKind.LOCAL && it.kind != SyncFieldKind.NOISE }

    /** 合并主循环要跳过、收尾统一重算的派生字段 */
    val derivedFields: List<Entry>
        get() = fields.filter { it.kind == SyncFieldKind.DERIVED }
}
