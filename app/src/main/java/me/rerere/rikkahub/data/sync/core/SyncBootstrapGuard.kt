package me.rerere.rikkahub.data.sync.core

import android.util.Log
import me.rerere.rikkahub.data.db.dao.SyncStateDao
import me.rerere.rikkahub.data.db.entity.SyncStateEntity

/**
 * 首次升级 / 全新设备的 bootstrap 安全阀（大统一重构 v2 §2.5）。
 *
 * ## 这是整个方案里最高危的一处
 *
 * 字段版本表升级后是**空表** → 全字段 `hlc == 0`（unknown）。
 * 此时若允许 push settings，会发生：
 *
 * ```
 * 09:00  设备 A 升级 → 空版本表 → 用户改了几个设置 → 打戳上云（正常）
 * 09:05  设备 B 升级 → 空版本表 → B 上的 Settings 是【本地 DataStore 里的旧值】
 *        若给 B 全字段打 now()：09:05 > 09:00
 *        → B 的全部字段（含 Uuid.random() 生成的 modelId、空 prompt）
 *          带着更大的 HLC 上云 → 把 A 的真实配置全刷掉 💀
 * ```
 *
 * 这就是「切换那天两台设备设置全变默认」的剧本。防线是两条**同时**生效：
 *
 * 1. 默认值永不打戳（`SyncFieldStamper` 没有全量打戳入口，做不到）
 * 2. **未 bootstrap 前只 pull 不 push**（本类）
 *
 * 只有第 1 条不够：用户在升级后、首次 pull 前手动改了一个设置，那个字段会被
 * 正常打戳；但此时本地其余 83 个字段仍是 unknown，若这一轮就 push 整片 envelope，
 * 那 83 个 `hlc=0` 的 cell 会覆盖云端 —— 因为 pull 端看到 `local=0, remote=0`
 * 时按 §2.5 表格「保本地」，而云端那份此刻已经被本机的空片写掉了。
 *
 * 所以必须等**首次完整 pull 落地之后**才开 push 闸门。
 */
class SyncBootstrapGuard(
    private val dao: SyncStateDao,
) {

    /**
     * settings shard 是否允许上推。
     *
     * @return false 表示本轮只 pull，不 push settings（会话/媒体等其他数据不受影响）
     */
    suspend fun canPushSettings(): Boolean = readMark() != null

    /**
     * 首次完整 pull 成功落地后调用，开启 push 闸门。
     *
     * ⚠️ 只能在**确实拉到并应用了云端 settings** 之后调用。
     * 特别是：网络失败、云端无 settings 行、pull 被安全阀 Abort 这三种情况
     * **都不算 bootstrap 完成** —— 否则等于用「没拉到」当作「拉到了空的」，
     * 下一轮就把本地默认值当真相推上去。
     */
    suspend fun markBootstrapped(reason: String) {
        if (readMark() != null) return
        dao.put(
            SyncStateEntity(
                key = KEY,
                value = reason,
                updatedAt = System.currentTimeMillis(),
            )
        )
        Log.i(TAG, "settings sync bootstrapped: $reason")
    }

    /**
     * 云端**确认为空**（首次启用同步、新账号）时也要放行，否则 push 永远开不了闸，
     * 这台设备的配置一辈子上不了云。
     *
     * 与 [markBootstrapped] 分开命名是为了让审计日志能区分这两种来源 ——
     * 「云端本来是空的」和「拉到了云端数据」在排查时是完全不同的两种情况。
     */
    suspend fun markEmptyCloud() = markBootstrapped(REASON_EMPTY_CLOUD)

    /**
     * 本地被整体恢复（导入备份 / 快照回滚）后退回未 bootstrap 状态。
     *
     * 配合 `SyncFieldStamper.resetAfterRestore()`：版本表清空后，
     * 恢复出来的旧值全部变成 unknown，此时必须先 pull 一轮认清云端现状，
     * 否则会把备份里的旧配置当新事实推上云。
     */
    suspend fun resetAfterRestore() {
        dao.delete(KEY)
        Log.w(TAG, "bootstrap mark cleared after local restore; settings push disabled until next pull")
    }

    private suspend fun readMark(): SyncStateEntity? = runCatching { dao.get(KEY) }.getOrNull()

    companion object {
        private const val TAG = "SyncBootstrapGuard"

        /** 与 `sync_state` 里既有的 `conv:` / `bundle:` 前缀风格一致 */
        private const val KEY = "sync:settings_bootstrapped"

        const val REASON_EMPTY_CLOUD = "cloud-empty"
        const val REASON_PULLED = "pulled-remote-settings"
    }
}
