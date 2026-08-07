package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * 设备本机同步参数（永不上云，不参与 settings 同步）：
 * - device_id：设备唯一身份，生成一次后固定；分叉裁决的确定性依据
 * - device_label：用户可读设备名（如 k70 / matepad），用于分叉会话标题后缀
 * - display_sync_enabled：displaySetting（界面观感）是否参与同步，默认关
 */
object SyncLocalPrefs {
    private const val PREF_NAME = "rikkahub.sync_local"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_LABEL = "device_label"
    private const val KEY_DISPLAY_SYNC = "display_sync_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }

    /**
     * 用户自定义设备标签：分叉会话标题后缀用它，所以必须短、稳定、人类可读；
     * 未设置时回落机型名。
     */
    fun deviceLabel(context: Context): String {
        val stored = prefs(context).getString(KEY_DEVICE_LABEL, null)?.trim()
        if (!stored.isNullOrBlank()) return stored
        return Build.MODEL?.trim()?.takeIf { it.isNotBlank() } ?: deviceName()
    }

    fun setDeviceLabel(context: Context, label: String) {
        val cleaned = label.trim().take(24)
        prefs(context).edit().apply {
            if (cleaned.isBlank()) remove(KEY_DEVICE_LABEL) else putString(KEY_DEVICE_LABEL, cleaned)
        }.apply()
    }

    fun isDisplaySyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISPLAY_SYNC, false)

    fun setDisplaySyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISPLAY_SYNC, enabled).apply()
    }
}
