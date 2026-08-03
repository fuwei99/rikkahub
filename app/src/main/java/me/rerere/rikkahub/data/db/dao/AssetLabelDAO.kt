package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AssetLabelEntity

@Dao
interface AssetLabelDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(labels: List<AssetLabelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(label: AssetLabelEntity)

    /**
     * 全表订阅。
     *
     * 标签是「每张图挂几个」的稀疏数据，整库通常只有几百到几千行，
     * 一次性拉进内存按 assetId 建 Map 比给每个网格项单独查一次要省得多
     * （相册一屏就有几十个 item，逐项查会打出几十条 SQL）。
     */
    @Query("SELECT * FROM asset_label_ref")
    fun observeAll(): Flow<List<AssetLabelEntity>>

    @Query("SELECT * FROM asset_label_ref")
    suspend fun getAll(): List<AssetLabelEntity>

    @Query("SELECT * FROM asset_label_ref WHERE asset_id = :assetId")
    suspend fun getByAsset(assetId: String): List<AssetLabelEntity>

    @Query("SELECT asset_id FROM asset_label_ref WHERE kind = :kind AND value = :value")
    suspend fun getAssetIdsByLabel(kind: String, value: String): List<String>

    @Query("DELETE FROM asset_label_ref WHERE asset_id = :assetId")
    suspend fun deleteByAsset(assetId: String)

    @Query("DELETE FROM asset_label_ref WHERE asset_id = :assetId AND kind = :kind")
    suspend fun deleteByAssetAndKind(assetId: String, kind: String)

    @Query("DELETE FROM asset_label_ref WHERE asset_id = :assetId AND kind = :kind AND value = :value")
    suspend fun delete(assetId: String, kind: String, value: String)

    /** 用户在设置里删掉某个标签后，清掉所有图上的残留引用 */
    @Query("DELETE FROM asset_label_ref WHERE kind = :kind AND value = :value")
    suspend fun deleteByLabel(kind: String, value: String)

    @Query("DELETE FROM asset_label_ref")
    suspend fun deleteAll()
}
