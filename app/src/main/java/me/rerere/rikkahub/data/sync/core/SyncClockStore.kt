package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.content.SharedPreferences

/**
 * [SyncClock] 的持久化后端（阶段 A）。
 *
 * ## 为什么用 SharedPreferences 而不是 `sync_state` 表
 *
 * 方案 v2 §1.5 原写「落 `sync_state` 的 `sync:hlc` 键」，实施时改成 SharedPreferences，
 * 理由是**接口形状对不上**：
 *
 * - `SyncClock.now()` 是**同步**函数，且每次调用都必须落盘（崩溃不倒退）
 * - `SyncStateDao.put()` 是 `suspend`，只能在协程里调
 *
 * 若为了写盘把 `now()` 改成 suspend，会传染到所有打戳点（`PreferencesStore.update`
 * 里逐字段比对、`stampListChanges`、outbox 入队…），把一个纯计算函数变成异步的，
 * 得不偿失。而 HLC 本身是**设备私有、永不上云**的账簿，与 `sync_state` 同性质，
 * 放哪张表都不影响同步语义。
 *
 * 复用 [SyncLocalPrefs] 的同一个 pref 文件（`rikkahub.sync_local`），
 * 那里存的 device_id / device_label 也是同类「设备私有且永不上云」的数据。
 *
 * 用 `commit()` 而非 `apply()`：`apply()` 是异步落盘，进程被杀时可能丢最后几次写入，
 * 而 HLC 一旦倒退就会让本机产生「比已上云事件更小」的时间戳，破坏因果。
 * 每次 `now()` 一次 commit 的开销（微秒级，写入同一个 key）远低于同步本身的网络成本。
 */
class SyncClockStore(context: Context) : SyncClock.Store {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun load(): Long = prefs.getLong(KEY_HLC, SyncClock.UNKNOWN)

    override fun save(packed: Long) {
        prefs.edit().putLong(KEY_HLC, packed).commit()
    }

    companion object {
        /** 与 SyncLocalPrefs 同一个文件：都是设备私有、永不上云的账簿 */
        private const val PREF_NAME = "rikkahub.sync_local"
        private const val KEY_HLC = "hlc_packed"
    }
}
