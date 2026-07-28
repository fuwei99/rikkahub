package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * 设备本机同步参数（永不上云，不参与 settings 同步）：
 * - device_id：会话互斥锁语义的唯一身份来源，生成一次后固定
 * - display_sync_enabled：displaySetting（界面观感）是否参与同步，默认关
 */
object SyncLocalPrefs {
    private const val PREF_NAME = "rikkahub.sync_local"
    private const val KEY_DEVICE_ID = "device_id"
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

    fun isDisplaySyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISPLAY_SYNC, false)

    fun setDisplaySyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISPLAY_SYNC, enabled).apply()
    }
}
