package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监督路径锁的 shell 命令级判定（方案 A：字符串黑名单，不防绕过）。
 *
 * 关键回归点：锁存在时**普通命令必须照跑**，别再一刀切禁 shell。
 */
class SupervisionShellLockTest {

    private val locked = setOf("/workspace/hf-space")

    @Test
    fun `无关命令放行`() {
        assertNull(lockedPathHitInCommand(locked, "echo hello"))
        assertNull(lockedPathHitInCommand(locked, "ls /tmp && git status"))
    }

    @Test
    fun `显式绝对路径被拦`() {
        val hit = lockedPathHitInCommand(locked, "rm -rf /workspace/hf-space/app")
        assertEquals("/workspace/hf-space" to "/workspace/hf-space", hit)
    }

    @Test
    fun `workspace 根下的相对路径被拦`() {
        val hit = lockedPathHitInCommand(locked, "cat hf-space/secret.txt")
        assertEquals("/workspace/hf-space" to "hf-space", hit)
    }

    @Test
    fun `前缀相近的兄弟路径不误伤`() {
        assertNull(lockedPathHitInCommand(locked, "ls /workspace/hf-spaces-backup"))
        assertNull(lockedPathHitInCommand(locked, "ls /workspace/hf-space2"))
    }

    @Test
    fun `cwd 下的相对路径按 cwd 解析`() {
        val hit = lockedPathHitInCommand(
            locked = setOf("/workspace/projects/rikkahub/app"),
            command = "wc -l app/build.gradle.kts",
            base = "/workspace/projects/rikkahub",
        )
        assertEquals("/workspace/projects/rikkahub/app" to "app", hit)
    }

    @Test
    fun `右边界为分隔符时命中`() {
        assertTrue("cp x /workspace/hf-space".containsPathToken("/workspace/hf-space"))
        assertTrue("cp x /workspace/hf-space/y".containsPathToken("/workspace/hf-space"))
        assertTrue("tar -C '/workspace/hf-space' -c .".containsPathToken("/workspace/hf-space"))
        assertFalse("ls /workspace/hf-spaceX".containsPathToken("/workspace/hf-space"))
    }
}
