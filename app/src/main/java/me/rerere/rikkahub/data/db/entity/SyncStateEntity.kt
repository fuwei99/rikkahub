package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 云端同步的本地游标/状态（云锚点同步 P0）。
 *
 * 记录"本地已知每个云端对象的版本"（key -> JSON：{updated_at, sha, ...}），
 * diff 时与 manifest 逐条比对，只拉有变化的对象。
 * 本表是设备私有账簿，永不上云。
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    /** 如 "conv:<uuid>" / "bundle:<k>" / "cursor：<场景>" */
    @PrimaryKey
    val key: String,
    /** JSON 内容 {updated_at, sha, ...} */
    @ColumnInfo("value")
    val value: String,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
