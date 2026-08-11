package me.rerere.rikkahub.data.screentime

import kotlinx.serialization.Serializable

/** 跨设备屏幕时间 bundle key 前缀：完整 key = 前缀 + 设备 UUID（SyncLocalPrefs.deviceId），按设备隔离 */
const val SCREEN_TIME_BUNDLE_PREFIX = "screen_time:"

/** 云端 bundle 保留最近 N 天（本地 Room 保留 [LOCAL_RETENTION_DAYS] 天，更久历史只在本机） */
const val CLOUD_RETENTION_DAYS = 14

/** 本地 Room 保留天数 */
const val LOCAL_RETENTION_DAYS = 90

/** 每日进入 bundle 的 App 数上限（控制体积与隐私） */
const val SCREEN_TIME_TOP_APPS = 20

/** 每次采集回溯重算的天数（跨零点补尾巴 + Doze 掐掉后回补历史日） */
const val SCREEN_TIME_COLLECT_LOOKBACK_DAYS = 3

/** 单 App 屏幕时长条目（日聚合） */
@Serializable
data class SyncScreenTimeAppItem(
    val packageName: String,
    val appName: String,
    val ms: Long,
)

/** 单设备单日屏幕时长（screen_time:<deviceId> bundle payload 元素） */
@Serializable
data class SyncScreenTimeDayItem(
    val deviceId: String,
    val deviceLabel: String,
    val timezone: String,
    val date: String,
    val totalMs: Long,
    val apps: List<SyncScreenTimeAppItem> = emptyList(),
    /** 24 小时桶（下标 0-23 对应本地时区的 0:00-23:59），可选字段，兼容旧 payload */
    val hourlyMs: List<Long> = emptyList(),
)
