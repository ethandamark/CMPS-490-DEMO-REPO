package com.CMPS490.weathertracker.data

import androidx.room.Embedded
import androidx.room.Relation

data class SnapshotWithCache(
    @Embedded val snapshot: OfflineWeatherSnapshotEntity,
    @Relation(
        parentColumn = "cache_id",
        entityColumn = "cache_id",
    )
    val cache: WeatherCacheEntity,
)
