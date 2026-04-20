package com.CMPS490.weathertracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_weather_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = WeatherCacheEntity::class,
            parentColumns = ["cache_id"],
            childColumns = ["cache_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("cache_id"), Index("device_id")],
)
data class OfflineWeatherSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "weather_id") val weatherId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "cache_id") val cacheId: String,
    @ColumnInfo(name = "synced_at") val syncedAt: Long?,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean = false,
)
