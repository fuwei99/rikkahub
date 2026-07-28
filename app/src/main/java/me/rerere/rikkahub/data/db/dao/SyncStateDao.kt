package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM sync_state")
    suspend fun getAll(): List<SyncStateEntity>
}
