package com.CMPS490.weathertracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ModelInstanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(instance: ModelInstanceEntity)

    @Query("SELECT * FROM model_instance WHERE synced_at IS NULL ORDER BY created_at ASC")
    suspend fun getUnsynced(): List<ModelInstanceEntity>

    @Query("UPDATE model_instance SET synced_at = :syncedAt WHERE instance_id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncedAt: Long)

    @Query("DELETE FROM model_instance WHERE synced_at IS NOT NULL AND synced_at < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}
