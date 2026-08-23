package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.sync.core.SyncClock
import me.rerere.rikkahub.data.sync.core.SyncFieldDigest
import me.rerere.rikkahub.data.sync.core.SyncFieldKind
import me.rerere.rikkahub.data.sync.core.SyncFieldRegistry
import me.rerere.rikkahub.data.sync.core.SyncShard
import me.rerere.rikkahub.data.sync.core.SyncShardEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shard envelope 组装与解析（大统一重构 v2 §2.3 ②、§2.6）。
 */
class SyncShardEnvelopeTest {

    private val deviceId = "test-device"

    /** 造一份含目标分片全部字段的假 settings JSON */
    private fun fakeSettingsJson(shard: SyncShard) = buildJsonObject {
        SyncFieldRegistry.ofShard(shard).forEach { entry ->
            put(entry.name, JsonPrimitive("value-of-${entry.name}"))
        }
    }

    // ---------------- LOCAL / NOISE 永不上云 ----------------

    @Test
    fun `local and noise fields never enter the envelope`() {
        // 这条是「加字段忘登记就泄露密钥」的堵漏点。
        // LOCAL 分片装着 d1Config / webServerAccessPassword / externalDeliveryToken。
        val localShard = SyncShard.LOCAL
        val json = fakeSettingsJson(localShard)
        val env = SyncShardEnvelope.build(localShard, json, deviceId) { SyncClock.pack(1000, 0) }

        assertTrue(
            "LOCAL 分片不得产生任何上云 cell，实得 ${env.fields.keys}",
            env.fields.isEmpty()
        )
    }

    @Test
    fun `no uploadable shard leaks a LOCAL or NOISE field`() {
        // 全分片扫一遍，任何一片里混进 LOCAL/NOISE 都算漏
        SyncShard.uploadable.forEach { shard ->
            val env = SyncShardEnvelope.build(shard, fakeSettingsJson(shard), deviceId) { 0L }
            env.fields.keys.forEach { name ->
                val kind = SyncFieldRegistry.of(name)?.kind
                assertTrue(
                    "分片 ${shard.key} 的 envelope 里混进了不该上云的字段 $name（kind=$kind）",
                    kind != SyncFieldKind.LOCAL && kind != SyncFieldKind.NOISE
                )
            }
        }
    }

    // ---------------- 组装 ----------------

    @Test
    fun `envelope carries value and version together`() {
        val shard = SyncShard.PROMPTS
        val json = fakeSettingsJson(shard)
        val stamp = SyncClock.pack(1_700_000_000_000L, 3)
        val env = SyncShardEnvelope.build(shard, json, deviceId) { stamp }

        assertEquals(shard.key, env.shard)
        assertEquals(deviceId, env.deviceId)
        assertTrue("PROMPTS 分片应有可上云字段", env.fields.isNotEmpty())
        env.fields.forEach { (name, cell) ->
            assertEquals("字段 $name 的版本必须贴着值一起走线", stamp, cell.hlc)
            assertEquals(SyncFieldDigest.shaOf(cell.data), cell.sha)
        }
    }

    @Test
    fun `envelope hlc is the max of its fields`() {
        val shard = SyncShard.PROMPTS
        val json = fakeSettingsJson(shard)
        val names = SyncFieldRegistry.ofShard(shard)
            .filter { it.kind != SyncFieldKind.LOCAL && it.kind != SyncFieldKind.NOISE }
            .map { it.name }
        val target = names.first()

        val env = SyncShardEnvelope.build(shard, json, deviceId) { name ->
            if (name == target) 9999L else 100L
        }
        assertEquals("片级 hlc 取字段最大值（仅用于快速跳过，不用于整片裁决）", 9999L, env.hlc)
    }

    @Test
    fun `envelope hlc is unknown when no field is stamped`() {
        val shard = SyncShard.PROMPTS
        val env = SyncShardEnvelope.build(shard, fakeSettingsJson(shard), deviceId) { SyncClock.UNKNOWN }
        assertEquals(
            "全字段未打戳时片级 hlc 必须是 unknown，不能伪造成某个具体时刻",
            SyncClock.UNKNOWN,
            env.hlc,
        )
    }

    @Test
    fun `missing field in settings json is skipped not zero filled`() {
        // 字段在 JSON 里不存在（老版本产生的数据）时，不得凭空补一个空 cell 上云
        val shard = SyncShard.PROMPTS
        val env = SyncShardEnvelope.build(shard, buildJsonObject { }, deviceId) { 100L }
        assertTrue("settings 里没有的字段不得凭空造 cell", env.fields.isEmpty())
    }

    // ---------------- 解析 ----------------

    @Test
    fun `round trip preserves fields`() {
        val shard = SyncShard.PROMPTS
        val env = SyncShardEnvelope.build(shard, fakeSettingsJson(shard), deviceId) { 555L }
        val text = SyncFieldDigest.json().encodeToString(SyncShardEnvelope.serializer(), env)

        val back = SyncShardEnvelope.parse(text)
        assertNotNull("往返解析必须成功", back)
        assertEquals(env.shard, back!!.shard)
        assertEquals(env.fields.keys, back.fields.keys)
        assertEquals(env.hlc, back.hlc)
    }

    @Test
    fun `parse returns null on garbage instead of throwing`() {
        // 同步链路上任何异常都会中断整轮同步；一片坏数据不该让另外 11 片也同步不了
        assertNull(SyncShardEnvelope.parse("not json at all"))
        assertNull(SyncShardEnvelope.parse(""))
        assertNull(SyncShardEnvelope.parse("[1,2,3]"))
    }

    @Test
    fun `parse rejects future format version`() {
        val text = """{"shard":"settings.models","hlc":1,"fields":{},"device":"d","v":99}"""
        assertNull(
            "宁可不同步，也不能拿旧解析器猜新格式字段的语义",
            SyncShardEnvelope.parse(text)
        )
    }

    @Test
    fun `parse drops only the corrupted cell`() {
        // sha 与 data 对不上 = 传输/存储损坏。丢那一个 cell，别整片放弃，
        // 也别把脏值写进本地。
        val text = """
            {"shard":"settings.prompts","hlc":100,"device":"d","v":1,
             "fields":{
               "good":{"v":100,"d":"real","s":"${SyncFieldDigest.shaOf(JsonPrimitive("real"))}"},
               "bad":{"v":100,"d":"tampered","s":"deadbeefdeadbeef"}
             }}
        """.trimIndent()

        val env = SyncShardEnvelope.parse(text)
        assertNotNull(env)
        assertTrue("自洽的 cell 必须保留", env!!.fields.containsKey("good"))
        assertFalse("sha 对不上的 cell 必须丢弃，不能写入脏值", env.fields.containsKey("bad"))
    }

    @Test
    fun `cell consistency check works both ways`() {
        val value = JsonPrimitive("hello")
        val ok = SyncShardEnvelope.Cell(hlc = 1, data = value, sha = SyncFieldDigest.shaOf(value))
        val bad = SyncShardEnvelope.Cell(hlc = 1, data = value, sha = "0123456789abcdef")
        assertTrue(ok.isConsistent())
        assertFalse(bad.isConsistent())
    }

    // ---------------- 分片 key 与 legacy 不冲突 ----------------

    @Test
    fun `shard keys never collide with the legacy settings bundle key`() {
        // 阶段 A「老版本无感」全靠这个：老版本只认 key == "settings"，
        // 不会去读 settings.<shard> 行
        SyncShard.entries.forEach { shard ->
            assertTrue(
                "分片 key 必须带 settings. 前缀且不等于 legacy 的 settings：${shard.key}",
                shard.key.startsWith("settings.") && shard.key != "settings"
            )
        }
    }

    @Test
    fun `shard keys are unique`() {
        val keys = SyncShard.entries.map { it.key }
        assertEquals("分片 key 重复会让两片互相覆盖云端同一行", keys.size, keys.toSet().size)
    }

    @Test
    fun `local shard is excluded from uploadable`() {
        assertFalse(
            "LOCAL 分片绝不能出现在上云列表里",
            SyncShard.uploadable.contains(SyncShard.LOCAL)
        )
    }
}
