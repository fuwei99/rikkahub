package me.rerere.ai.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「最后一个可用 Token 永不熄火」这条规矩。
 *
 * 背景：自动关闭 Token 的本意是「多 Token 时剔掉死号」。旧实现不看名册，
 * 单 Token 渠道被 401 一次就把自己禁掉，之后所有请求都是
 * "All API tokens are disabled"，真实错误原因被吞，用户还得手动去设置里翻开关。
 */
class KeyRouletteLastKeyTest {

    private fun isLast(
        key: String,
        all: List<String>,
        disabled: List<String> = emptyList(),
        alive: Set<String> = all.toSet(),
    ) = KeyRoulette.isLastLivingKey(key, all, disabled) { it in alive }

    @Test
    fun `单 Token 渠道：唯一的 key 必须被保护`() {
        assertTrue(isLast("k1", listOf("k1")))
    }

    @Test
    fun `多 Token 且其它 key 健在：正常处罚，不保护`() {
        assertFalse(isLast("k1", listOf("k1", "k2", "k3")))
    }

    @Test
    fun `多 Token 但其它 key 全挂：最后这个必须被保护`() {
        assertTrue(isLast("k3", listOf("k1", "k2", "k3"), alive = setOf("k3")))
    }

    @Test
    fun `其它 key 被用户手动禁用：等同不可用，本 key 受保护`() {
        assertTrue(isLast("k1", listOf("k1", "k2"), disabled = listOf("k2")))
    }

    @Test
    fun `手动禁用是用户明确意图：即便本 key 也在禁用列表也不越权复活`() {
        // k1、k2 都被手动禁用 -> 名册里没有活口，本 key 仍判定为"最后一个"，
        // 上层据此放弃处罚；真正的放行由用户自己在设置里控制。
        assertTrue(isLast("k1", listOf("k1", "k2"), disabled = listOf("k1", "k2")))
    }

    @Test
    fun `还有一个健康 key 时不受保护`() {
        assertFalse(isLast("k1", listOf("k1", "k2", "k3"), alive = setOf("k1", "k2")))
    }

    @Test
    fun `名册为空则退化为旧行为，不做保护`() {
        // 调用方没传 allKeys，无从判断，保持原样处罚
        assertFalse(isLast("k1", emptyList()))
    }

    @Test
    fun `名册去重与空串过滤`() {
        // "k1" 重复出现不该被当成两个活口
        assertTrue(isLast("k1", listOf("k1", "k1", "", "  ".trim())))
    }
}
