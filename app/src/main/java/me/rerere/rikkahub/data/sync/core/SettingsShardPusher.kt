package me.rerere.rikkahub.data.sync.core

import android.util.Log
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.datastore.Settings

/**
 * settings 分片双写（大统一重构 v2 §2.6，阶段 A 第 6 项）。
 *
 * ## 双写期的定位：只写，不读
 *
 * 本类把 settings 按 13 个分片额外写一份到 `bundles` 表（key = `settings.<shard>`），
 * 而**读侧仍然只读 legacy 整包**（`key = "settings"`）。
 *
 * 为什么要这么别扭地过一个阶段：
 *
 * - 分片行写上去之后，云端才有「字段级 hlc」这种数据。没有历史数据就切读侧，
 *   等于所有字段都是 unknown，第一轮 pull 谁也不赢谁，白折腾
 * - 老版本设备完全不认识 `settings.<shard>` 这些 key，写了它也不读，
 *   因此**双写期对老版本零影响**（这是阶段 A 的验收标准）
 * - 出问题只需停止调用本类，云端多几行没人读的数据，零副作用可回滚
 *
 * ## 与 legacy 行的关系
 *
 * legacy 行 key 是 `settings`，分片行是 `settings.models` 等。
 * ⚠️ 注意 `settings.display`（[BUNDLE_SETTINGS_DISPLAY]）是**早已存在的 legacy key**，
 * 它不属于本次分片体系（13 个分片里没有 `display`）。所以 pull 侧**绝不可**用
 * `WHERE k LIKE 'settings.%'` 盲扫，那会把 display 整包当成分片 envelope 解析。
 * 本类只按 [SyncShard.uploadable] 里的确切 key 逐个写，不做前缀操作。
 */
class SettingsShardPusher(
    private val clock: SyncClock,
    private val bootstrapGuard: SyncBootstrapGuard,
    private val stamper: SyncFieldStamper,
    private val deviceId: String,
) {

    /**
     * 一次推送的结果，供调用方写审计日志。
     */
    data class Outcome(
        val pushed: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val blockedReason: String? = null,
    ) {
        val isBlocked: Boolean get() = blockedReason != null
    }

    /**
     * 把 [settings] 拆成分片 envelope，交给 [write] 逐片落地。
     *
     * @param write 实际写云端的动作：(key, payload, hlc) → 是否写成功。
     *   做成回调是为了让本类**不依赖 D1Client**，可以在纯 JVM 单测里跑。
     * @param shaOfPushed 取某片上次推送成功时的 payload sha（`sync_state` 里的账簿）；
     *   内容没变就跳过，空闲设备零流量。
     */
    suspend fun push(
        settings: Settings,
        shaOfPushed: suspend (String) -> String?,
        write: suspend (key: String, payload: String, hlc: Long) -> Boolean,
    ): Outcome {
        // dummy settings 绝不上云（现有 PreferencesStore 也有同款判断）
        if (settings.init) {
            return Outcome(blockedReason = "settings-dummy")
        }

        // ★ bootstrap 安全阀（§2.5，最高危）。未完成首次 pull 前只 pull 不 push：
        // 此时本地字段版本表大概率是空的，全字段 unknown，推上去会把云端真实配置
        // 写成本机默认值（「切换那天两台设备设置全变默认」的剧本）。
        if (!bootstrapGuard.canPushSettings()) {
            Log.i(TAG, "shard push blocked: not bootstrapped yet (pull-only)")
            return Outcome(blockedReason = "not-bootstrapped")
        }

        val versions = stamper.loadVersions()
        val hlcOf: (String) -> Long = { name -> versions[name]?.hlc ?: SyncClock.UNKNOWN }

        val settingsJson: JsonElement =
            SyncFieldDigest.json().encodeToJsonElement(Settings.serializer(), settings)

        val pushed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        SyncShard.uploadable.forEach { shard ->
            val envelope = SyncShardEnvelope.build(
                shard = shard,
                settingsJson = settingsJson,
                deviceId = deviceId,
                hlcOf = hlcOf,
            )

            // 空片不写：某些分片可能全是 LOCAL 字段，或该版本还没有这些字段。
            // 写个空 envelope 上去毫无意义，还会让对端以为「这片被清空了」。
            if (envelope.fields.isEmpty()) {
                skipped += shard.key
                return@forEach
            }

            // ★ 全片字段都是 unknown 时不推（§2.5 + 模拟器抓出的真实 bug）。
            // 这一片没有任何本机改动记录，推上去只会用「我不知道」覆盖云端的
            // 「我知道」。push 侧显式拒绝 UNKNOWN 是防新设备默认值污染云端的最后一道闸。
            if (envelope.hlc == SyncClock.UNKNOWN) {
                skipped += shard.key
                return@forEach
            }

            val payload = SyncFieldDigest.json()
                .encodeToString(SyncShardEnvelope.serializer(), envelope)
            val sha = SyncFieldDigest.shaOf(
                SyncFieldDigest.json().parseToJsonElement(payload)
            )

            // 内容没变就跳过（沿用 pushBundle 的 sha 短路，空闲设备零流量）
            if (shaOfPushed(shard.key) == sha) {
                skipped += shard.key
                return@forEach
            }

            if (write(shard.key, payload, envelope.hlc)) {
                pushed += shard.key
            } else {
                skipped += shard.key
            }
        }

        if (pushed.isNotEmpty()) {
            Log.d(TAG, "pushed ${pushed.size} shard(s): ${pushed.joinToString()}")
        }
        return Outcome(pushed = pushed, skipped = skipped)
    }

    /**
     * 观测云端分片的 hlc，推进本机时钟。
     *
     * 即使读侧还没切到分片，也应该在 pull 到分片行时调用它：
     * 本机时钟必须知道「云端已经走到哪了」，否则等阶段 B 真的切读侧时，
     * 本机新产生的戳可能小于云端已有的戳，破坏 happens-before。
     */
    fun observeRemote(hlc: Long) {
        if (hlc != SyncClock.UNKNOWN) clock.observe(hlc)
    }

    companion object {
        private const val TAG = "SettingsShardPusher"
    }
}
