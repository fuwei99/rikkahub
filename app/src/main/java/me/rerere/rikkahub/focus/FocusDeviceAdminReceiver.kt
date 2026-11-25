package me.rerere.rikkahub.focus

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 锁机冷静期的设备管理员组件（2026-11-25）。
 *
 * 只声明 `force-lock` 一条策略 —— 冷静期内调用 `DevicePolicyManager.lockNow()` 锁屏。
 *
 * **不申请** `resetPassword` / `wipeData` 这类能真·变砖的能力：
 * 锁机是自律工具，不是把机主锁在门外的工具。改密码那一套一旦 App 崩了
 * 就可能永远改不回来，谁提这个方案谁负责。
 */
class FocusDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
        // 用户主动取消激活 = 放弃冷静期惩罚能力，别留着状态装蒜
        runCatching { CooldownScreenLock.cancel(context) }
            .onFailure { Log.w(TAG, "cancel cooldown on admin disable failed", it) }
    }

    private companion object {
        const val TAG = "FocusDeviceAdmin"
    }
}
