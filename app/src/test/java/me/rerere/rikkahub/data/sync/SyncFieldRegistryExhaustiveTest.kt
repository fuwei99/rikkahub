package me.rerere.rikkahub.data.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.core.SyncFieldKind
import me.rerere.rikkahub.data.sync.core.SyncFieldRegistry
import me.rerere.rikkahub.data.sync.core.SyncShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **这个测试就是「一劳永逸」的载体。**
 *
 * 大统一方案 v2 §2.2：真正的保障不是「加字段不用写同步代码」，而是
 * 「**加字段忘写同步代码，CI 当场红脸**」。
 *
 * 历史教训：`SyncSettingsFilter.mergeRemote()` 最后一句是 `return remote.copy(...)`，
 * 骨架取自云端，只有显式列进白名单的字段才保本地。于是每次给 `Settings` 加字段而
 * 忘了同步登记，那个字段就会在两台设备之间互相抹平——**而且没有任何报错**。
 * 靠人记住去改两处手写清单，从来没有靠住过。
 *
 * 实现方式：用 `kotlinx.serialization` 的 descriptor 取字段名，零反射、零 KSP、纯 JVM。
 */
@OptIn(ExperimentalSerializationApi::class)
class SyncFieldRegistryExhaustiveTest {

    private val declaredFields: Set<String> =
        Settings.serializer().descriptor.elementNames.toSet()

    private val registeredFields: Set<String> =
        SyncFieldRegistry.fields.map { it.name }.toSet()

    @Test
    fun `every Settings field is registered`() {
        val missing = declaredFields - registeredFields
        assertEquals(
            """
            |
            |🔴 有 ${missing.size} 个 Settings 字段没在 SyncFieldRegistry 登记：
            |    $missing
            |
            |不登记的后果：该字段在跨设备合并时行为未定义，大概率被云端整包抹平（历史 bug 复现）。
            |修法：打开 SyncFieldRegistry.kt，按 Settings 声明顺序在对应分片处补一行。
            |  - 普通标量/整体替换  → lww(name, SyncShard.XXX)
            |  - 按 id 管理的集合    → orSet(name, SyncShard.XXX)
            |  - 密钥/设备身份/本机服务 → local(name, "为什么不上云")
            |  - 值依赖其他字段的合并结果 → derived(name, shard, "依赖谁")
            |  - 启动计数之类的噪音  → noise(name, "理由")
            |""".trimMargin(),
            emptySet<String>(),
            missing,
        )
    }

    @Test
    fun `registry has no stale entries`() {
        val stale = registeredFields - declaredFields
        assertEquals(
            """
            |
            |🔴 SyncFieldRegistry 里有 ${stale.size} 个字段在 Settings 中已不存在：
            |    $stale
            |
            |通常是字段改名或删除后忘了同步改注册表。留着会让合并循环去找一个不存在的属性。
            |""".trimMargin(),
            emptySet<String>(),
            stale,
        )
    }

    @Test
    fun `no duplicate registrations`() {
        val dupes = SyncFieldRegistry.fields
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
        assertTrue("字段重复登记会导致合并循环跑两次、后者覆盖前者：$dupes", dupes.isEmpty())
    }

    // ---------------- 语义一致性 ----------------

    @Test
    fun `local and noise fields must live in LOCAL shard`() {
        val misplaced = SyncFieldRegistry.fields.filter {
            (it.kind == SyncFieldKind.LOCAL || it.kind == SyncFieldKind.NOISE) &&
                it.shard != SyncShard.LOCAL
        }
        assertTrue(
            "不上云的字段必须归入 LOCAL 分片，否则会被打包进某个云端 bundle 而泄露：$misplaced",
            misplaced.isEmpty()
        )
    }

    @Test
    fun `LOCAL shard contains only non-uploadable kinds`() {
        val leaking = SyncFieldRegistry.ofShard(SyncShard.LOCAL).filter {
            it.kind != SyncFieldKind.LOCAL && it.kind != SyncFieldKind.NOISE
        }
        assertTrue("LOCAL 分片里出现了会上云的字段，等于绕过了不上云约定：$leaking", leaking.isEmpty())
    }

    @Test
    fun `local fields must document why`() {
        // 「为什么不上云」是安全决策，必须留下理由，否则后人无法判断能不能改回来
        val undocumented = SyncFieldRegistry.fields.filter {
            it.kind == SyncFieldKind.LOCAL && it.note.isBlank()
        }
        assertTrue("设备本地字段必须写明理由：$undocumented", undocumented.isEmpty())
    }

    @Test
    fun `derived and custom fields must document dependency`() {
        val undocumented = SyncFieldRegistry.fields.filter {
            (it.kind == SyncFieldKind.DERIVED || it.kind == SyncFieldKind.CUSTOM) &&
                it.note.isBlank()
        }
        assertTrue("派生/自定义合并字段必须说明依赖或语义：$undocumented", undocumented.isEmpty())
    }

    // ---------------- 已知的关键归类，防止被无意改动 ----------------

    @Test
    fun `known device local fields stay device local`() {
        // 这些都是踩过坑或涉密的字段，任何人把它们改成上云都必须先让这个测试红
        val mustBeLocal = listOf(
            "d1Config",                  // 含 API token，且上云会自指
            "s3Config",
            "webServerAccessPassword",   // 密码
            "externalDeliveryToken",     // 令牌
            "webServerEnabled",
            "webServerPort",
            "webServerJwtEnabled",
            "webServerLocalhostOnly",
            "assistantId",               // 曾导致跨设备助手被静默切换
            "toolLog",
            "displaySetting",
        )
        mustBeLocal.forEach { name ->
            val entry = SyncFieldRegistry.of(name)
            assertTrue("$name 未登记", entry != null)
            assertEquals(
                "$name 必须保持设备本地（涉密或设备语义）",
                SyncShard.LOCAL,
                entry!!.shard
            )
        }
    }

    @Test
    fun `r2Accounts must be uploaded with secrets`() {
        val entry = SyncFieldRegistry.of("r2Accounts")!!
        assertEquals(
            "r2Accounts 必须上云：其他设备需要 secretAccessKey 才能为 r2:// 对象签名读取",
            SyncShard.R2,
            entry.shard
        )
        assertTrue(entry.kind != SyncFieldKind.LOCAL)
    }

    @Test
    fun `supervision uses custom merger not plain lww`() {
        val entry = SyncFieldRegistry.of("supervision")!!
        assertEquals(
            "supervision 走事件日志专属合并；退化成 LWW 会重现「解锁被对端旧锁态覆盖」",
            SyncFieldKind.CUSTOM,
            entry.kind
        )
        assertEquals(SyncShard.SUPERVISION, entry.shard)
    }

    @Test
    fun `supervision shard is exclusive`() {
        assertEquals(
            "SUPERVISION 分片只放 supervision 自己，其合并语义与其他字段不同",
            listOf("supervision"),
            SyncFieldRegistry.ofShard(SyncShard.SUPERVISION).map { it.name }
        )
    }

    @Test
    fun `every uploadable shard has at least one field`() {
        val empty = SyncShard.uploadable.filter { SyncFieldRegistry.ofShard(it).isEmpty() }
        assertTrue("空分片会产生无意义的云端 bundle 行：$empty", empty.isEmpty())
    }

    @Test
    fun `sync meta fields sit in the same shard as their host list`() {
        // 外挂版本表必须和宿主列表同片，否则一个推上去另一个没推 = 版本与内容脱节
        val pairs = mapOf(
            "imageProvidersSyncMeta" to "imageProviders",
            "ttsProvidersSyncMeta" to "ttsProviders",
            "asrProvidersSyncMeta" to "asrProviders",
            "searchServicesSyncMeta" to "searchServices",
            "vectorProvidersSyncMeta" to "vectorProviders",
        )
        pairs.forEach { (meta, host) ->
            assertEquals(
                "$meta 必须与 $host 同分片，否则版本表与内容会分两次上行而脱节",
                SyncFieldRegistry.of(host)!!.shard,
                SyncFieldRegistry.of(meta)!!.shard,
            )
        }
    }

    @Test
    fun `providerTombstones stays with providers`() {
        assertEquals(
            SyncFieldRegistry.of("providers")!!.shard,
            SyncFieldRegistry.of("providerTombstones")!!.shard,
        )
    }

    /** 留个人工核对锚点：字段总数变化时提醒确认过一遍归类 */
    @Test
    fun `field count snapshot`() {
        assertEquals(
            "Settings 字段数变了。请确认新字段已正确归类（这不是坏事，改掉这个数字即可）",
            84,
            declaredFields.size,
        )
    }
}
