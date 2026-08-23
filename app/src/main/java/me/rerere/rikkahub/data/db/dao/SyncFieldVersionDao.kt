package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SyncFieldVersionEntity

/**
 * `Settings` 字段级版本表访问（大统一重构 v2 §2.3）。
 *
 * 注意 [getAll] 是主力读法：一次 pull 要裁决整片 shard 的所有字段，
 * 逐字段 `get()` 会打出几十次查询。表本身只有 84 行，一把捞进内存最省。
 */
@Dao
interface SyncFieldVersionDao {

    @Query("SELECT * FROM sync_field_version")
    suspend fun getAll(): List<SyncFieldVersionEntity>

    @Query("SELECT * FROM sync_field_version WHERE field = :field")
    suspend fun get(field: String): SyncFieldVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: SyncFieldVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(items: List<SyncFieldVersionEntity>)

    @Query("DELETE FROM sync_field_version WHERE field = :field")
    suspend fun delete(field: String)

    /**
     * 清空版本表 = 回到「全字段 unknown」状态。
     *
     * 用途：本地数据被整体恢复（导入备份、`DatabaseSnapshotHelper` 回滚）之后，
     * 旧的字段版本已经不描述当前值了，留着会让恢复出来的旧值带着大 HLC 上云。
     * 清空后下一轮同步按 bootstrap 走（只 pull 不 push），由云端重新填。
     */
    @Query("DELETE FROM sync_field_version")
    suspend fun clear()
}
