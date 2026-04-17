package com.CMPS490.weathertracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HourlyPredictionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prediction: HourlyPredictionEntity)

    @Query("SELECT * FROM hourly_prediction WHERE timestamp >= :fromTime ORDER BY timestamp ASC")
    suspend fun getPredictionsFrom(fromTime: Long): List<HourlyPredictionEntity>

    @Query("DELETE FROM hourly_prediction WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
