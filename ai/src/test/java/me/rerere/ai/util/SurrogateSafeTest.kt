package me.rerere.ai.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-31 `loadMessageNodes` 反序列化崩溃的回归测试。
 *
 * 核心断言：`take()` 劈开 emoji 代理对产生的孤立代理，
 * 经 UTF-8 往返后会吞掉相邻的 JSON 转义反斜杠 —— 修复后不允许再发生。
 */
class SurrogateSafeTest {

    private val json = Json { encodeDefaults = true }

    // 事故里那个 emoji：👜 U+1F45C = D83D DC5C，低位代理低字节恰好是 0x5C('\')
    private val bag = "\uD83D\uDC5C"

    @Test
    fun `takeSafe 不劈开代理对`() {
        val text = "abc$bag"
        // 长度 5：a b c D83D DC5C；take(4) 会切出孤立高位代理
        assertTrue("前提：take 会产生孤立代理", text.take(4).hasLoneSurrogate())
        assertEquals("abc", text.takeSafe(4))
        assertFalse(text.takeSafe(4).hasLoneSurrogate())
        // 边界完整时原样保留 emoji
        assertEquals("abc$bag", text.takeSafe(5))
        assertEquals("abc$bag", text.takeSafe(999))
        assertEquals("", text.takeSafe(0))
        assertEquals("", text.takeSafe(-3))
    }

    @Test
    fun `takeLastSafe 不劈开代理对`() {
        val text = "${bag}xyz"
        assertTrue(text.takeLast(4).hasLoneSurrogate())
        assertEquals("xyz", text.takeLastSafe(4))
        assertEquals("${bag}xyz", text.takeLastSafe(5))
    }

    @Test
    fun `truncateSafe 加省略号且不残`() {
        val text = "abc$bag"
        assertEquals("abc…", text.truncateSafe(4))
        assertEquals("abc$bag", text.truncateSafe(5))
        assertFalse(text.truncateSafe(4).hasLoneSurrogate())
    }

    @Test
    fun `stripLoneSurrogates 保留合法代理对且零拷贝`() {
        val clean = "中文 emoji $bag ok"
        assertFalse(clean.hasLoneSurrogate())
        // 干净串必须返回同一实例，才敢挂热路径
        assertSame(clean, clean.stripLoneSurrogates())
    }

    @Test
    fun `stripLoneSurrogates 替换孤立高低位代理`() {
        val loneHigh = "abc\uD83D"
        val loneLow = "\uDC5Cabc"
        assertEquals("abc\uFFFD", loneHigh.stripLoneSurrogates())
        assertEquals("\uFFFDabc", loneLow.stripLoneSurrogates())
        assertEquals("a\uFFFDb", "a\uD83Db".stripLoneSurrogates())
        assertFalse("abc\uD83D".stripLoneSurrogates().hasLoneSurrogate())
    }

    /**
     * 事故复现：孤立高位代理 + 紧随其后的转义反斜杠，
     * 经 UTF-8 编解码后反斜杠被吞进 emoji，JSON 字符串提前闭合。
     */
    @Test
    fun `复现 - 孤立代理经 UTF8 往返吞掉转义反斜杠`() {
        // 模拟 preview = "...了？！" + 被劈开的 👜 高位代理
        val broken = mapOf("text" to "了？！\uD83D", "next" to "line")
        val encoded = json.encodeToString(broken)
        val roundTripped = String(encoded.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

        // 往返后不再是原串（孤立代理被 UTF-8 编码破坏）
        val reparsed = runCatching { json.decodeFromString<Map<String, String>>(roundTripped) }
        // 断言：存在孤立代理时 JSON 往返不可靠 —— 这就是必须消毒的理由
        assertTrue(
            "含孤立代理的 JSON 往返后必然失真或解析失败",
            reparsed.isFailure || reparsed.getOrThrow()["text"] != "了？！\uD83D"
        )
    }

    @Test
    fun `修复后 - 消毒过的 JSON 可安全 UTF8 往返`() {
        val sanitizedMap = mapOf("text" to "了？！\uD83D".stripLoneSurrogates(), "next" to "line")
        val encoded = json.encodeToString(sanitizedMap)
        val roundTripped = String(encoded.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        val reparsed = json.decodeFromString<Map<String, String>>(roundTripped)
        assertNotNull(reparsed)
        assertEquals("line", reparsed["next"])
        assertEquals("了？！\uFFFD", reparsed["text"])
    }

    @Test
    fun `修复后 - takeSafe 截断的完整 JSON 往返无损`() {
        val preview = "凌晨两点整，导管把自己导清醒了？！$bag".takeSafe(20)
        val encoded = json.encodeToString(mapOf("preview" to preview, "next" to "line"))
        val roundTripped = String(encoded.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        val reparsed = json.decodeFromString<Map<String, String>>(roundTripped)
        assertEquals(preview, reparsed["preview"])
        assertEquals("line", reparsed["next"])
    }
}
