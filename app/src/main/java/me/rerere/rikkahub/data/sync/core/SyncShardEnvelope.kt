package me.rerere.rikkahub.data.sync.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 一个 shard 的上云载荷（大统一重构 v2 §2.3 ②）。
 *
 * ## 为什么字段值要和它的版本贴在一起走线
 *
 * 现状 `bundles` 一行是整包 settings 的裸 JSON，只有一个整包 `updated_at`。
 * pull 端拿到之后**无从判断云端某个字段有多新** —— 只能整包比较，
 * 于是只剩「谁的整包更新谁全赢」这一种选择，这正是设置互相覆盖的根源。
 *
 * envelope 把 `{值, 版本}` 成对传输后，pull 端可以逐字段裁决，不需要猜。
 *
 * 代价：payload 比裸 JSON 大 ~30%。分片之后每片十几个字段，可接受。
 *
 * ## 兼容性：老版本读到 envelope 会怎样
 *
 * shard 行的 key 是 `settings.<shard>`（如 `settings.models`），
 * 与 legacy 整包行的 key（`settings`）**不同**。老版本只认 `settings`，
 * 根本不会去读 shard 行 → 天然互不干扰。这是阶段 A「双写期老版本无感」的实现方式：
 * 新版本双写两种行，读侧仍只读 legacy；等两端都升级完（阶段 B）才切读侧。
 */
@Serializable
data class SyncShardEnvelope(
    /** 分片 key，如 `settings.models`。与 [SyncShard.key] 一致 */
    @SerialName("shard")
    val shard: String,

    /**
     * 本片内所有字段 hlc 的最大值。
     *
     * 用途是**快速跳过**：pull 时若云端片的 maxHlc <= 本地片的 maxHlc 且 sha 相同，
     * 整片无需逐字段比对。注意它**不能**用来裁决整片胜负 —— 一片里 A 设备改了
     * 字段 x、B 设备改了字段 y 是常态，按 maxHlc 判整片会丢掉输家那个字段的改动。
     */
    @SerialName("hlc")
    val hlc: Long,

    /** 字段名 → {值, 版本} */
    @SerialName("fields")
    val fields: Map<String, Cell> = emptyMap(),

    /**
     * 写这一片的设备 id。
     *
     * 仅用于审计与「回推判定」（自己刚推的片不必再拉一遍），
     * **不参与冲突裁决** —— §1.4 明确平票比内容 sha 而不比设备名：
     * 比设备名会让某台设备永久优先，它的默认值能压掉另一台的真实配置。
     */
    @SerialName("device")
    val deviceId: String = "",

    /**
     * envelope 格式版本。加字段时先看这个，避免用老版本的解析器读新格式。
     * 当前为 1。
     */
    @SerialName("v")
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
) {

    /** 单个字段的值 + 版本 */
    @Serializable
    data class Cell(
        /** 版本：packed HLC。0 = unknown（既不赢也不输，§2.5） */
        @SerialName("v")
        val hlc: Long,

        /** 字段值本身（原样 JSON，不做二次编码） */
        @SerialName("d")
        val data: JsonElement,

        /**
         * 内容指纹。冗余存储（可由 [data] 重算），但存着有两个好处：
         * ① 平票裁决不用在 pull 热路径上重算 84 次 sha
         * ② 传输损坏时能发现（sha 与 data 对不上 → 丢弃该 cell 而不是写入脏值）
         */
        @SerialName("s")
        val sha: String,
    ) {
        /** 校验 [sha] 与 [data] 是否自洽。不自洽说明传输/存储损坏，调用方应丢弃本 cell */
        fun isConsistent(): Boolean = SyncFieldDigest.shaOf(data) == sha
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        /**
         * 从当前 `Settings` 的 JSON 表示 + 本地版本表组装一片 envelope。
         *
         * @param settingsJson `Settings` 序列化后的 JsonObject
         * @param hlcOf        取某字段的本地 hlc（表里没有 → [SyncClock.UNKNOWN]）
         */
        fun build(
            shard: SyncShard,
            settingsJson: JsonElement,
            deviceId: String,
            hlcOf: (String) -> Long,
        ): SyncShardEnvelope {
            val all = SyncFieldDigest.fieldsOf(settingsJson)
            val cells = LinkedHashMap<String, Cell>()

            SyncFieldRegistry.ofShard(shard).forEach { entry ->
                // LOCAL / NOISE 永不上云。这是「加字段忘登记就泄露密钥」的堵漏点：
                // 声明式之后「不上云」是一个显式标记，而不是散落在 forUpload/mergeRemote
                // 两处手写清单里的遗漏。
                if (entry.kind == SyncFieldKind.LOCAL || entry.kind == SyncFieldKind.NOISE) return@forEach

                val value = all[entry.name] ?: return@forEach
                cells[entry.name] = Cell(
                    hlc = hlcOf(entry.name),
                    data = value,
                    sha = SyncFieldDigest.shaOf(value),
                )
            }

            return SyncShardEnvelope(
                shard = shard.key,
                hlc = cells.values.maxOfOrNull { it.hlc } ?: SyncClock.UNKNOWN,
                fields = cells,
                deviceId = deviceId,
            )
        }

        /**
         * 宽容解析：整体解不开就返回 null（调用方回退到 legacy 整包路径），
         * 单个 cell 坏了只丢那一个 cell。
         *
         * 「解不开就当没有」而不是抛异常，是因为同步链路上任何异常都会中断整轮同步；
         * 一片坏数据不该让另外 11 片也同步不了。
         */
        fun parse(text: String): SyncShardEnvelope? {
            val env = runCatching {
                val obj = SyncFieldDigest.json().parseToJsonElement(text).jsonObject
                SyncFieldDigest.json().decodeFromJsonElement(serializer(), obj)
            }.getOrNull() ?: return null

            // 未来格式：宁可不同步也不能拿旧解析器猜新字段的语义
            if (env.formatVersion > CURRENT_FORMAT_VERSION) return null

            val clean = env.fields.filterValues { it.isConsistent() }
            return if (clean.size == env.fields.size) env else env.copy(fields = clean)
        }
    }
}
