package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ScreenTimeDayEntity

@Dao
interface ScreenTimeDayDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScreenTimeDayEntity)

    @Query("SELECT * FROM screen_time_day WHERE device_id = :deviceId AND date = :date LIMIT 1")
    suspend fun get(deviceId: String, date: String): ScreenTimeDayEntity?

    /** 本机采集：导出该设备全部日聚合（云端 bundle 用，取最近 [CLOUD_RETENTION_DAYS] 天） */
    @Query("SELECT * FROM screen_time_day WHERE device_id = :deviceId ORDER BY date DESC")
    suspend fun getByDevice(deviceId: String): List<ScreenTimeDayEntity>

    /** 跨设备查询：请求区间内的所有设备日聚合行（get_screen_time 合并输出用） */
    @Query("SELECT * FROM screen_time_day WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    suspend fun getBetween(startDate: String, endDate: String): List<ScreenTimeDayEntity>

    /** 云端整组替换对端设备的行（本机行不经过此路径） */
    @Query("DELETE FROM screen_time_day WHERE device_id = :deviceId")
    suspend fun deleteByDevice(deviceId: String)

    /** 本地保留期清理（默认 90 天） */
    @Query("DELETE FROM screen_time_day WHERE date < :beforeDate")
    suspend fun pruneBefore(beforeDate: String)
}
