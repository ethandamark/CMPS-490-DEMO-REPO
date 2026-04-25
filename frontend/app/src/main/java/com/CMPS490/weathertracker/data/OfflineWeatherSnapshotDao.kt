package com.CMPS490.weathertracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface OfflineWeatherSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: OfflineWeatherSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshots(snapshots: List<OfflineWeatherSnapshotEntity>)

    @Transaction
    @Query(
        "SELECT * FROM offline_weather_snapshot " +
        "WHERE device_id = :deviceId " +
        "ORDER BY (SELECT recorded_at FROM weather_cache WHERE weather_cache.cache_id = offline_weather_snapshot.cache_id) DESC " +
        "LIMIT :limit"
    )
    suspend fun getSnapshotsForDevice(deviceId: String, limit: Int = 24): List<SnapshotWithCache>

    @Transaction
    @Query(
        "SELECT * FROM offline_weather_snapshot " +
        "WHERE device_id = :deviceId AND synced_at IS NULL " +
        "AND weather_id IN (SELECT weather_id FROM model_instance WHERE weather_id IS NOT NULL)"
    )
    suspend fun getUnsyncedSnapshots(deviceId: String): List<SnapshotWithCache>

    @Query(
        "UPDATE offline_weather_snapshot SET synced_at = :syncedAt " +
        "WHERE weather_id IN (:ids)"
    )
    suspend fun markSynced(ids: List<String>, syncedAt: Long)

    @Query(
        "UPDATE offline_weather_snapshot SET is_current = 0 WHERE device_id = :deviceId"
    )
    suspend fun clearCurrentFlag(deviceId: String)

    @Query(
        "DELETE FROM offline_weather_snapshot " +
        "WHERE device_id = :deviceId AND synced_at IS NOT NULL AND synced_at < :cutoff"
    )
    suspend fun pruneOld(deviceId: String, cutoff: Long)
}
