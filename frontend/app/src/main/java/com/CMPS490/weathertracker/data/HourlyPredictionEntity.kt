package com.CMPS490.weathertracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hourly_prediction")
data class HourlyPredictionEntity(
    @PrimaryKey
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "storm_probability") val stormProbability: Float,
    @ColumnInfo(name = "alert_state") val alertState: Int,
    @ColumnInfo(name = "model_version") val modelVersion: String,
)
