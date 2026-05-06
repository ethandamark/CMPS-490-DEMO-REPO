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
        "  OR NOT EXISTS (" +
        "    SELECT 1 FROM offline_weather_snapshot s " +
        "    WHERE s.weather_id = mi.weather_id" +
        "  ) " +
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

    /**
     * Returns the most recently created model instance for [deviceId] that is NOT a
     * simulation sentinel (sentinel weather_ids are the all-zeros UUIDs used by the
     * *SimulationActivity classes). Used by DebugPredictActivity to replay the last
     * real / seed prediction rather than rerunning the ONNX model on potentially
     * stale or incomplete cache rows.
     */
    @Query(
        "SELECT * FROM model_instance " +
        "WHERE device_id = :deviceId " +
        "AND weather_id NOT IN (" +
        "  '00000000-0000-0000-0000-000000000000'," +
        "  '10000000-0000-0000-0000-000000000000'," +
        "  '20000000-0000-0000-0000-000000000000'," +
        "  '30000000-0000-0000-0000-000000000000'" +
        ") " +
        "ORDER BY created_at DESC " +
        "LIMIT 1"
    )
    suspend fun getMostRecentRealForDevice(deviceId: String): ModelInstanceEntity?
}
