package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每个 `Settings` 字段的因果版本（大统一重构 v2 §2.3）。
 *
 * ## 为什么必须单独一张表，而不是塞进 `Settings`
 *
 * `Settings` 每变一次就**全量**序列化写 DataStore。把 84 条字段版本塞进这个
 * data class 会导致：
 * 1. UI 上点一下任何开关 → 整张版本表被重写
 * 2. `settingsFlow.distinctUntilChanged()` 被版本变动无脑击穿 →
 *    下游（Pebble 模板缓存失效、Compose 重组）全部跟着抖
 *
 * ## 语义
 *
 * - [hlc]：该字段最后一次**本机改动**时的 packed HLC（见 `SyncClock`）。
 *   **表里没有这一行 = `hlc == 0` = unknown**，既不赢也不输（§2.5）。
 *   这是刻意的：新装 / 刚升级的设备是空表，首次 pull 全采纳云端 —— 云上才是
 *   真实配置。反过来若在升级时给全字段打 `now()`，晚升级那台的**默认值**
 *   （`Uuid.random()` 的各种 modelId、空 prompt）会带着更大的 HLC 上云，
 *   把先升级那台的真实配置全刷成默认值。
 *
 * - [sha]：该字段值的内容指纹（规范化 JSON 的 sha256 前 16 位）。
 *   两个用途：① 平票时按 sha 字典序定序，保证两端选中同一个值（§1.4）；
 *   ② 判断字段是否真的变了，避免"写入同值也打新戳"这种自造冲突。
 *
 * 本表是**设备私有账簿**，与 `sync_state` 同性质，永不上云。
 * 上云的版本走 shard envelope 的 `fields` 字典（§2.3 ②）。
 */
@Entity(tableName = "sync_field_version")
data class SyncFieldVersionEntity(
    /** `Settings` 的属性名，与 `SyncFieldRegistry` 登记的 name 一致 */
    @PrimaryKey
    val field: String,

    /** packed HLC；0 = unknown（本行通常直接不存在） */
    @ColumnInfo("hlc")
    val hlc: Long,

    /** 内容指纹，平票裁决与变更检测用 */
    @ColumnInfo("sha")
    val sha: String,
)
