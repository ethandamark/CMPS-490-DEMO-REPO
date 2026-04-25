package com.CMPS490.weathertracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ModelInstanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(instance: ModelInstanceEntity)

    @Query(
        "SELECT mi.* FROM model_instance mi " +
        "WHERE mi.synced_at IS NULL " +
        "AND (" +
        "  mi.weather_id IS NULL " +
        "  OR EXISTS (" +
        "    SELECT 1 FROM offline_weather_snapshot s " +
        "    WHERE s.weather_id = mi.weather_id AND s.synced_at IS NOT NULL" +
        "  )" +
        ") " +
        "ORDER BY mi.created_at ASC"
    )
    suspend fun getUnsynced(): List<ModelInstanceEntity>

    @Query("UPDATE model_instance SET synced_at = :syncedAt WHERE instance_id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncedAt: Long)

    @Query("DELETE FROM model_instance WHERE synced_at IS NOT NULL AND synced_at < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}
