package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 跨设备屏幕时间：按天 × 按 App 的日聚合（方案 2026-08-09）。
 *
 * 本机行由 ScreenTimeCollectWorker 每 10 分钟重算「今天 0 点 → now」后 upsert；
 * 对端行由 SyncEngine pull `screen_time:*` bundle 后写入。device_id 区分来源，
 * 本机行永远以本地采集为准（pull 跳过本机 device_id 的覆盖，防回环）。
 */
@Entity(
    tableName = "screen_time_day",
    indices = [Index(value = ["device_id", "date"], unique = true)]
)
data class ScreenTimeDayEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 来源设备 UUID（SyncLocalPrefs.deviceId）；对端行 = 对端的 id */
    @ColumnInfo("device_id")
    val deviceId: String,
    /** 采集/拉取时快照的设备名（CloudSyncTab 里用户设置的 label），展示用 */
    @ColumnInfo("device_label")
    val deviceLabel: String,
    /** yyyy-MM-dd（设备本地时区） */
    @ColumnInfo("date")
    val date: String,
    /** 当日前台总时长（ms） */
    @ColumnInfo("total_ms")
    val totalMs: Long,
    /** [{"package","app_name","ms"}] 的 JSON，top [SCREEN_TIME_TOP_APPS] */
    @ColumnInfo("apps_json")
    val appsJson: String,
    /** 24 个数字的 JSON 数组（本地时区 hour-of-day 分布，ms）；空串 = 老数据无小时粒度 */
    @ColumnInfo("hourly_json", defaultValue = "")
    val hourlyJson: String = "",
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
