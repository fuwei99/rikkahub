package me.rerere.rikkahub.focus

import me.rerere.rikkahub.data.model.FocusLockSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 2026-08-20 两个 bug 的回归点：
 * 1. 白名单判定本身要成立（含 bootstrap 系统包）；
 * 2. settingsFlow 反复发射不得把 agent 设的锁清掉。
 */
class FocusPolicyEngineTest {

    @After
    fun tearDown() {
        FocusPolicyEngine.lockStatePersister = null
        FocusPolicyEngine.setLockActive(false)
        FocusPolicyEngine.updateSettings(FocusLockSettings())
    }

    @Test
    fun `base whitelist packages are allowed`() {
        FocusPolicyEngine.updateSettings(FocusLockSettings(allowLauncherAndSystemUi = true))
        assertTrue(FocusPolicyEngine.isPackageAllowed("com.tencent.mm"))
        assertTrue(FocusPolicyEngine.isPackageAllowed("com.eusoft.eudic"))
        assertTrue(FocusPolicyEngine.isPackageAllowed("me.rerere.rikkahub"))
    }

    @Test
    fun `bootstrap packages stay allowed even when launcher flag is off`() {
        FocusPolicyEngine.updateSettings(FocusLockSettings(allowLauncherAndSystemUi = false))
        // 关掉「允许桌面和系统界面」也不能把用户锁死在无法进设置的状态
        assertTrue(FocusPolicyEngine.isPackageAllowed("com.android.settings"))
        assertTrue(FocusPolicyEngine.isPackageAllowed("android"))
        assertTrue(FocusPolicyEngine.isPackageAllowed("me.rerere.rikkahub"))
    }

    @Test
    fun `unrelated package is not allowed`() {
        FocusPolicyEngine.updateSettings(FocusLockSettings())
        assertFalse(FocusPolicyEngine.isPackageAllowed("com.android.chrome"))
        assertFalse(FocusPolicyEngine.isPackageAllowed("tv.danmaku.bili"))
    }

    @Test
    fun `temporary grant expires and is rejected when non positive`() {
        FocusPolicyEngine.updateSettings(FocusLockSettings())
        assertFalse(FocusPolicyEngine.grantTemporary("com.android.chrome", 0))
        assertTrue(FocusPolicyEngine.grantTemporary("com.android.chrome", 5))
        assertTrue(FocusPolicyEngine.isPackageAllowed("com.android.chrome"))
        FocusPolicyEngine.revokeTemporary("com.android.chrome")
        assertFalse(FocusPolicyEngine.isPackageAllowed("com.android.chrome"))
    }

    @Test
    fun `agent lock survives unrelated settings emissions`() {
        val persisted = mutableListOf<Boolean>()
        FocusPolicyEngine.lockStatePersister = { persisted.add(it) }

        FocusPolicyEngine.setLockActive(true)
        assertTrue(FocusPolicyEngine.isLockActive)
        assertEquals(listOf(true), persisted)

        // 模拟落盘后 settingsFlow 回灌（enabled 仍是 false，但 agentLockActive 已置位）
        FocusPolicyEngine.updateSettings(FocusLockSettings(agentLockActive = true))
        assertTrue("settings 发射不得清掉 agent 锁", FocusPolicyEngine.isLockActive)

        // 再来几次无关设置变动
        repeat(3) {
            FocusPolicyEngine.updateSettings(FocusLockSettings(agentLockActive = true))
        }
        assertTrue(FocusPolicyEngine.isLockActive)
    }

    @Test
    fun `clearing agent lock flag releases the lock`() {
        FocusPolicyEngine.updateSettings(FocusLockSettings(agentLockActive = true))
        assertTrue(FocusPolicyEngine.isLockActive)
        // 用户在 UI 里关掉总开关 → agentLockActive=false
        FocusPolicyEngine.updateSettings(FocusLockSettings(enabled = false, agentLockActive = false))
        assertFalse(FocusPolicyEngine.isLockActive)
    }

    @Test
    fun `diagnostics snapshot exposes real state`() {
        FocusPolicyEngine.updateSettings(FocusLockSettings())
        FocusPolicyEngine.setLockActive(true)
        val snapshot = FocusPolicyEngine.diagnosticsSnapshot()
        assertEquals(true, snapshot["is_lock_active"])
        assertEquals(true, snapshot["agent_lock_state"])
        assertTrue(snapshot.containsKey("last_resolved_foreground"))
        assertTrue(snapshot.containsKey("recent_decisions"))
    }
}
