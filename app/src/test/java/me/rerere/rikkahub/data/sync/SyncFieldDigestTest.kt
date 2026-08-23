package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.sync.core.SyncFieldDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段指纹与变更检测（大统一重构 v2 §2.3）。
 *
 * 这些性质是**平票裁决**的地基：§1.4 规定 hlc 相等时按内容 sha 定序，
 * 若两台设备对同一个值算出不同 sha，两端会各自认为自己该赢 → 反复互推。
 */
class SyncFieldDigestTest {

    // ---------------- sha 必须与字段声明顺序无关 ----------------

    @Test
    fun `sha ignores object key order`() {
        // 新版本 app 在中间插了个字段，导致序列化输出的 key 顺序变了。
        // 若 sha 跟顺序有关，同一份配置在两个版本间会算出不同 sha → 永远判不平。
        val a = buildJsonObject {
            put("alpha", JsonPrimitive(1))
            put("beta", JsonPrimitive(2))
        }
        val b = buildJsonObject {
            put("beta", JsonPrimitive(2))
            put("alpha", JsonPrimitive(1))
        }
        assertEquals("对象 key 顺序不得影响 sha", SyncFieldDigest.shaOf(a), SyncFieldDigest.shaOf(b))
    }

    @Test
    fun `sha ignores key order in nested objects`() {
        val a = buildJsonObject {
            put("outer", buildJsonObject {
                put("x", JsonPrimitive("1"))
                put("y", JsonPrimitive("2"))
            })
        }
        val b = buildJsonObject {
            put("outer", buildJsonObject {
                put("y", JsonPrimitive("2"))
                put("x", JsonPrimitive("1"))
            })
        }
        assertEquals("嵌套对象也必须递归规范化", SyncFieldDigest.shaOf(a), SyncFieldDigest.shaOf(b))
    }

    @Test
    fun `sha respects array order`() {
        // 数组顺序**是数据**（助手排序、模型列表顺序），不能排序掉
        val a = buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) }
        val b = buildJsonArray { add(JsonPrimitive("y")); add(JsonPrimitive("x")) }
        assertNotEquals("列表顺序是数据，换序必须算不同 sha", SyncFieldDigest.shaOf(a), SyncFieldDigest.shaOf(b))
    }

    // ---------------- sha 格式 ----------------

    @Test
    fun `sha is 16 lowercase hex chars`() {
        val sha = SyncFieldDigest.shaOf(JsonPrimitive("hello"))
        assertEquals(16, sha.length)
        assertTrue("必须是小写 hex，实得 $sha", sha.all { it in "0123456789abcdef" })
    }

    @Test
    fun `sha does not produce sign extended garbage`() {
        // 回归：Kotlin 的 Byte 是 signed，"%02x".format(-118) 输出 "ffffff8a"。
        // 用 toInt() and 0xFF 掩码之前，任何高位字节 >= 0x80 都会把 sha 拼成 f 海。
        // 遍历一批输入，任何一个出现 4 个以上连续 f 都极可能是符号扩展 bug 复发。
        repeat(200) { i ->
            val sha = SyncFieldDigest.shaOf(JsonPrimitive("probe-$i"))
            assertEquals("sha 长度异常（符号扩展会拉长）：$sha", 16, sha.length)
            assertTrue("疑似符号扩展 bug 复发：$sha", !sha.contains("ffffff"))
        }
    }

    @Test
    fun `null element maps to empty sha`() {
        assertEquals(SyncFieldDigest.EMPTY_SHA, SyncFieldDigest.shaOf(null))
    }

    @Test
    fun `empty sha differs from sha of any real value`() {
        // 否则「字段缺失」会被判成等于某个真实值
        assertNotEquals(SyncFieldDigest.EMPTY_SHA, SyncFieldDigest.shaOf(JsonPrimitive("")))
        assertNotEquals(SyncFieldDigest.EMPTY_SHA, SyncFieldDigest.shaOf(JsonPrimitive(0)))
    }

    @Test
    fun `different values yield different sha`() {
        assertNotEquals(
            SyncFieldDigest.shaOf(JsonPrimitive("model-a")),
            SyncFieldDigest.shaOf(JsonPrimitive("model-b")),
        )
    }

    @Test
    fun `sha is stable across calls`() {
        val v = buildJsonObject { put("k", JsonPrimitive("v")) }
        assertEquals(SyncFieldDigest.shaOf(v), SyncFieldDigest.shaOf(v))
    }

    // ---------------- changedFields ----------------

    @Test
    fun `changedFields detects only the changed one`() {
        val current = buildJsonObject {
            put("chatModelId", JsonPrimitive("uuid-1"))
            put("titlePrompt", JsonPrimitive("prompt"))
            put("enableSuggestion", JsonPrimitive(false))
        }
        val next = buildJsonObject {
            put("chatModelId", JsonPrimitive("uuid-1"))
            put("titlePrompt", JsonPrimitive("prompt"))
            put("enableSuggestion", JsonPrimitive(true))
        }
        assertEquals(
            "一次 update 只改一个开关，就只能打一个戳；全量打戳等于宣称改了全部字段",
            setOf("enableSuggestion"),
            SyncFieldDigest.changedFields(current, next),
        )
    }

    @Test
    fun `changedFields is empty for identical settings`() {
        val v = buildJsonObject { put("a", JsonPrimitive(1)) }
        assertTrue(SyncFieldDigest.changedFields(v, v).isEmpty())
    }

    @Test
    fun `changedFields ignores key reordering`() {
        val a = buildJsonObject {
            put("x", JsonPrimitive(1))
            put("y", JsonPrimitive(2))
        }
        val b = buildJsonObject {
            put("y", JsonPrimitive(2))
            put("x", JsonPrimitive(1))
        }
        assertTrue(
            "仅字段顺序不同不算变化，否则每次序列化都自造一批假冲突",
            SyncFieldDigest.changedFields(a, b).isEmpty()
        )
    }

    @Test
    fun `changedFields catches field removal and addition`() {
        val current = buildJsonObject { put("a", JsonPrimitive(1)) }
        val next = buildJsonObject { put("b", JsonPrimitive(1)) }
        assertEquals(setOf("a", "b"), SyncFieldDigest.changedFields(current, next))
    }

    @Test
    fun `changedFields detects value reset to default`() {
        // 关键场景：字段从「非默认」改回「默认」。
        // 若 Json 配置漏了 encodeDefaults=true，next 里会直接少这个 key，
        // 这里就会 catch 到（视为变化），但落地时值会丢 —— 所以这条同时守着
        // SyncFieldDigest.json() 的 encodeDefaults 配置不被人改掉。
        val current = buildJsonObject { put("cooldownMinutes", JsonPrimitive(30)) }
        val next = buildJsonObject { put("cooldownMinutes", JsonPrimitive(5)) }
        assertEquals(setOf("cooldownMinutes"), SyncFieldDigest.changedFields(current, next))
    }

    @Test
    fun `changedFields ignores non object input`() {
        assertTrue(
            SyncFieldDigest.changedFields(JsonPrimitive("x"), JsonPrimitive("y")).isEmpty()
        )
    }

    // ---------------- fieldsOf ----------------

    @Test
    fun `fieldsOf flattens top level only`() {
        val v = buildJsonObject {
            put("scalar", JsonPrimitive(1))
            put("nested", buildJsonObject { put("inner", JsonPrimitive(2)) })
        }
        val flat = SyncFieldDigest.fieldsOf(v)
        assertEquals(
            "只摊顶层：嵌套 data class 整体作为一个字段参与 LWW",
            setOf("scalar", "nested"),
            flat.keys,
        )
    }

    @Test
    fun `fieldsOf returns empty for non object`() {
        assertTrue(SyncFieldDigest.fieldsOf(JsonPrimitive("x")).isEmpty())
    }
}
