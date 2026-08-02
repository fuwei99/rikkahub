package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.core.SyncVersionMap
import me.rerere.rikkahub.data.sync.core.mergeListByVersion
import me.rerere.rikkahub.data.sync.core.stampListChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外挂版本表的通用 LWW 引擎回归锁。
 * imageProviders / ttsProviders / asrProviders / searchServices 四者共用这套逻辑，
 * 它们合计 40 个 sealed 子类，不适合逐个内嵌 updatedAt。
 */
class SyncVersionMapMergeTest {

    private data class Item(val id: String, val payload: String)

    private fun meta(versions: Map<String, Long> = emptyMap(), tombstones: Map<String, Long> = emptyMap()) =
        SyncVersionMap(versions, tombstones)

    @Test
    fun `本地较新的条目不会被云端覆盖`() {
        val local = listOf(Item("a", "local"))
        val remote = listOf(Item("a", "remote"))

        val (items, _) = mergeListByVersion(
            local, remote,
            meta(versions = mapOf("a" to 2000L)),
            meta(versions = mapOf("a" to 1000L)),
        ) { it.id }

        assertEquals("local", items.single().payload)
    }

    @Test
    fun `云端较新的条目胜出`() {
        val (items, _) = mergeListByVersion(
            listOf(Item("a", "local")), listOf(Item("a", "remote")),
            meta(versions = mapOf("a" to 1000L)),
            meta(versions = mapOf("a" to 5000L)),
        ) { it.id }

        assertEquals("remote", items.single().payload)
    }

    @Test
    fun `版本相同保本地`() {
        val (items, _) = mergeListByVersion(
            listOf(Item("a", "local")), listOf(Item("a", "remote")),
            meta(versions = mapOf("a" to 1000L)),
            meta(versions = mapOf("a" to 1000L)),
        ) { it.id }

        assertEquals("local", items.single().payload)
    }

    @Test
    fun `本地新增的条目不被云端抹掉`() {
        val (items, _) = mergeListByVersion(
            listOf(Item("a", "x"), Item("b", "new")), listOf(Item("a", "x")),
            meta(versions = mapOf("a" to 1000L, "b" to 3000L)),
            meta(versions = mapOf("a" to 1000L)),
        ) { it.id }

        assertTrue(items.any { it.id == "b" })
    }

    @Test
    fun `墓碑较新时条目被真删且不复活`() {
        val (items, mergedMeta) = mergeListByVersion(
            emptyList(), listOf(Item("a", "cloud")),
            meta(tombstones = mapOf("a" to 9000L)),
            meta(versions = mapOf("a" to 1000L)),
        ) { it.id }

        assertTrue(items.isEmpty())
        assertEquals(9000L, mergedMeta.tombstones["a"])
    }

    @Test
    fun `删除后被另一端编辑则编辑胜出且墓碑清除`() {
        val (items, mergedMeta) = mergeListByVersion(
            emptyList(), listOf(Item("a", "edited")),
            meta(tombstones = mapOf("a" to 1000L)),
            meta(versions = mapOf("a" to 5000L)),
        ) { it.id }

        assertEquals("edited", items.single().payload)
        // 存活条目不该同时挂墓碑，否则下轮读流又被滤掉
        assertNull(mergedMeta.tombstones["a"])
    }

    @Test
    fun `打戳只针对真实内容变化`() {
        val old = listOf(Item("a", "same"))
        val next = listOf(Item("a", "same"))

        val result = stampListChanges(old, next, meta(versions = mapOf("a" to 100L)), now = 9999L) { it.id }

        assertEquals(100L, result.versions["a"])
    }

    @Test
    fun `内容变化会推进版本号`() {
        val result = stampListChanges(
            listOf(Item("a", "old")), listOf(Item("a", "new")),
            meta(versions = mapOf("a" to 100L)), now = 9999L,
        ) { it.id }

        assertEquals(9999L, result.versions["a"])
    }

    @Test
    fun `normalize 抹平非同步字段后不再误判变更`() {
        // 模拟读流的加工：payload 被回填了不参与同步的运行时内容
        val result = stampListChanges(
            old = listOf(Item("a", "core|runtime-A")),
            next = listOf(Item("a", "core|runtime-B")),
            meta = meta(versions = mapOf("a" to 100L)),
            now = 9999L,
            normalize = { it.copy(payload = it.payload.substringBefore('|')) },
        ) { it.id }

        assertEquals(100L, result.versions["a"])
    }

    @Test
    fun `删除条目登记墓碑并清掉版本号`() {
        val result = stampListChanges(
            listOf(Item("a", "x")), emptyList(),
            meta(versions = mapOf("a" to 100L)), now = 9999L,
        ) { it.id }

        assertEquals(9999L, result.tombstones["a"])
        assertNull(result.versions["a"])
    }

    @Test
    fun `重建同 id 条目会消除墓碑`() {
        val result = stampListChanges(
            emptyList(), listOf(Item("a", "rebuilt")),
            meta(tombstones = mapOf("a" to 100L)), now = 9999L,
        ) { it.id }

        assertFalse(result.tombstones.containsKey("a"))
        assertEquals(9999L, result.versions["a"])
    }

    @Test
    fun `合并结果顺序在两端一致`() {
        val local = listOf(Item("b", "b"), Item("a", "a"), Item("c", "c"))
        val remote = listOf(Item("a", "a"), Item("b", "b"))
        val lm = meta(versions = mapOf("a" to 1L, "b" to 1L, "c" to 5L))
        val rm = meta(versions = mapOf("a" to 1L, "b" to 1L))

        val (fromLocal, _) = mergeListByVersion(local, remote, lm, rm) { it.id }
        val (fromRemote, _) = mergeListByVersion(local, remote, lm, rm) { it.id }

        assertEquals(fromLocal.map { it.id }, fromRemote.map { it.id })
        assertEquals(listOf("a", "b", "c"), fromLocal.map { it.id })
    }
}
