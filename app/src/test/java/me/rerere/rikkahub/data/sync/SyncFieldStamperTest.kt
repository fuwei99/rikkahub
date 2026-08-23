package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.db.dao.SyncFieldVersionDao
import me.rerere.rikkahub.data.db.entity.SyncFieldVersionEntity
import me.rerere.rikkahub.data.sync.core.SyncClock
import me.rerere.rikkahub.data.sync.core.SyncFieldDigest
import me.rerere.rikkahub.data.sync.core.SyncFieldKind
import me.rerere.rikkahub.data.sync.core.SyncFieldRegistry
import me.rerere.rikkahub.data.sync.core.SyncFieldStamper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段打戳（大统一重构 v2 §2.3、§2.5）。
 *
 * 三条铁律：只给变化字段打戳、远端应用不打本机戳、默认值永不打戳。
 */
class SyncFieldStamperTest {

    /** 内存版 Dao */
    private class FakeDao : SyncFieldVersionDao {
        val rows = mutableMapOf<String, SyncFieldVersionEntity>()
        override suspend fun getAll() = rows.values.toList()
        override suspend fun get(field: String) = rows[field]
        override suspend fun put(item: SyncFieldVersionEntity) { rows[item.field] = item }
        override suspend fun putAll(items: List<SyncFieldVersionEntity>) {
            items.forEach { rows[it.field] = it }
        }
        override suspend fun delete(field: String) { rows.remove(field) }
        override suspend fun clear() { rows.clear() }
    }

    private class FakeStore(var packed: Long = 0L) : SyncClock.Store {
        override fun load() = packed
        override fun save(packed: Long) { this.packed = packed }
    }

    private fun stamper(dao: FakeDao, wall: Long = 1_700_000_000_000L): SyncFieldStamper {
        val clock = SyncClock(FakeStore()) { wall }
        return SyncFieldStamper(dao, clock)
    }

    /** 取一个真实的、会上云的 LWW 字段名，避免用假名字被 registry 过滤掉 */
    private val uploadableName: String =
        SyncFieldRegistry.fields.first { it.kind == SyncFieldKind.LWW }.name

    private val anotherUploadableName: String =
        SyncFieldRegistry.fields.filter { it.kind == SyncFieldKind.LWW }[1].name

    /** 取一个 LOCAL 字段名（d1Config 之类） */
    private val localName: String =
        SyncFieldRegistry.fields.first { it.kind == SyncFieldKind.LOCAL }.name

    // ---------------- 只给变化的字段打戳 ----------------

    @Test
    fun `only changed fields get stamped`() = runBlocking {
        val dao = FakeDao()
        val s = stamper(dao)

        val current = buildJsonObject {
            put(uploadableName, JsonPrimitive("old"))
            put(anotherUploadableName, JsonPrimitive("same"))
        }
        val next = buildJsonObject {
            put(uploadableName, JsonPrimitive("new"))
            put(anotherUploadableName, JsonPrimitive("same"))
        }

        val stamped = s.stampLocalChanges(current, next)

        assertEquals(
            "一次 update 只改一个字段就只能打一个戳；全量打戳会让对端所有并发改动全判输",
            setOf(uploadableName),
            stamped.keys,
        )
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `no change means no stamp at all`() = runBlocking {
        val dao = FakeDao()
        val v = buildJsonObject { put(uploadableName, JsonPrimitive("x")) }
        val stamped = stamper(dao).stampLocalChanges(v, v)
        assertTrue("写入同值不得打戳，否则每次保存都自造一轮冲突", stamped.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `local fields are never stamped`() = runBlocking {
        val dao = FakeDao()
        val current = buildJsonObject { put(localName, JsonPrimitive("secret-old")) }
        val next = buildJsonObject { put(localName, JsonPrimitive("secret-new")) }

        val stamped = stamper(dao).stampLocalChanges(current, next)
        assertTrue(
            "LOCAL 字段（d1Config / token 之类）不参与裁决，不该进版本账簿",
            stamped.isEmpty()
        )
        assertNull(dao.rows[localName])
    }

    @Test
    fun `unknown field is not stamped`() = runBlocking {
        val dao = FakeDao()
        val current = buildJsonObject { put("someFieldNotInRegistry", JsonPrimitive(1)) }
        val next = buildJsonObject { put("someFieldNotInRegistry", JsonPrimitive(2)) }
        assertTrue(
            "注册表里查不到的字段宁可不打戳（退化成保本地不上推），也不能当事实推上云",
            stamper(dao).stampLocalChanges(current, next).isEmpty()
        )
    }

    @Test
    fun `each changed field gets a distinct hlc`() = runBlocking {
        val dao = FakeDao()
        val current = buildJsonObject {
            put(uploadableName, JsonPrimitive("a"))
            put(anotherUploadableName, JsonPrimitive("b"))
        }
        val next = buildJsonObject {
            put(uploadableName, JsonPrimitive("a2"))
            put(anotherUploadableName, JsonPrimitive("b2"))
        }
        val stamped = stamper(dao).stampLocalChanges(current, next)
        assertEquals(2, stamped.size)
        assertEquals(
            "同一次 update 里多个字段的 hlc 必须互不相同，否则两端在同一毫秒各改一个字段时会退化成按内容字典序裁决",
            stamped.values.toSet().size,
            stamped.size,
        )
    }

    @Test
    fun `stamped sha matches the new value`() = runBlocking {
        val dao = FakeDao()
        val current = buildJsonObject { put(uploadableName, JsonPrimitive("old")) }
        val next = buildJsonObject { put(uploadableName, JsonPrimitive("new")) }
        stamper(dao).stampLocalChanges(current, next)
        assertEquals(
            "记的必须是新值的 sha，记旧值会让下次比对认为「又变了」",
            SyncFieldDigest.shaOf(JsonPrimitive("new")),
            dao.rows[uploadableName]!!.sha,
        )
    }

    // ---------------- 远端应用不打本机戳 ----------------

    @Test
    fun `applying remote winners records the winner hlc not local now`() = runBlocking {
        val dao = FakeDao()
        val s = stamper(dao, wall = 9_000_000_000_000L) // 本机时钟远大于远端
        val remoteHlc = SyncClock.pack(1_700_000_000_000L, 0)

        s.applyRemoteWinners(mapOf(uploadableName to (remoteHlc to "abcdef0123456789")))

        assertEquals(
            "必须记胜出方的 hlc。记本机 now() 会让下一轮 push 时本机「赢」，" +
                "把刚采纳的云端值当本机新事实推回去 → 两端互推，永不静默",
            remoteHlc,
            dao.rows[uploadableName]!!.hlc,
        )
    }

    @Test
    fun `applying remote winners advances the local clock`() = runBlocking {
        val dao = FakeDao()
        val store = FakeStore()
        val clock = SyncClock(store) { 1_000L }
        val s = SyncFieldStamper(dao, clock)

        val bigRemote = SyncClock.pack(5_000_000_000_000L, 0)
        s.applyRemoteWinners(mapOf(uploadableName to (bigRemote to "sha")))

        assertTrue(
            "采纳外来 hlc 后必须推进本机时钟，否则下一次 now() 会小于刚采纳的值，破坏 happens-before",
            clock.peek() >= bigRemote
        )
    }

    @Test
    fun `unknown hlc winners are not written as rows`() = runBlocking {
        val dao = FakeDao()
        stamper(dao).applyRemoteWinners(
            mapOf(uploadableName to (SyncClock.UNKNOWN to "sha"))
        )
        assertTrue(
            "把 unknown 写成一行等于记录成「知道且很旧」，此后该字段会永远输给任何带戳的值，包括对端默认值",
            dao.rows.isEmpty()
        )
    }

    @Test
    fun `empty winners is a no-op`() = runBlocking {
        val dao = FakeDao()
        stamper(dao).applyRemoteWinners(emptyMap())
        assertTrue(dao.rows.isEmpty())
    }

    // ---------------- 恢复后清空 ----------------

    @Test
    fun `reset after restore clears the table`() = runBlocking {
        val dao = FakeDao()
        dao.put(SyncFieldVersionEntity("a", 100, "sha"))
        stamper(dao).resetAfterRestore()
        assertTrue(
            "恢复备份后旧版本号已不描述当前值，留着会让旧值带大 HLC 上云刷掉云端较新配置",
            dao.rows.isEmpty()
        )
    }

    @Test
    fun `load versions returns a map keyed by field`() = runBlocking {
        val dao = FakeDao()
        dao.putAll(
            listOf(
                SyncFieldVersionEntity("f1", 10, "s1"),
                SyncFieldVersionEntity("f2", 20, "s2"),
            )
        )
        val map = stamper(dao).loadVersions()
        assertEquals(setOf("f1", "f2"), map.keys)
        assertEquals(20L, map["f2"]!!.hlc)
    }

    // ---------------- 空表 = unknown（§2.5 的地基） ----------------

    @Test
    fun `fresh install has an empty version table`() = runBlocking {
        val dao = FakeDao()
        assertTrue(
            "空表 = 全字段 unknown = 首次 pull 全采纳云端。" +
                "本类刻意没有「初始化时全量打戳」入口，就是为了让那件事做不到",
            stamper(dao).loadVersions().isEmpty()
        )
    }
}
