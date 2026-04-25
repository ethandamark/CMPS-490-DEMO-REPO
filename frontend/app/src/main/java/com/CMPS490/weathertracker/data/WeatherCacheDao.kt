package com.CMPS490.weathertracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WeatherCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: WeatherCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(caches: List<WeatherCacheEntity>)

    @Query("SELECT * FROM weather_cache WHERE cache_id IN (:cacheIds)")
    suspend fun getByIds(cacheIds: List<String>): List<WeatherCacheEntity>

    @Query(
        "DELETE FROM weather_cache WHERE recorded_at < :cutoff " +
        "AND cache_id NOT IN (SELECT cache_id FROM offline_weather_snapshot)"
    )
    suspend fun pruneOlderThan(cutoff: Long)

    @Query(
        "SELECT * FROM weather_cache " +
        "WHERE is_forecast = 0 " +
        "AND latitude BETWEEN :latMin AND :latMax " +
        "AND longitude BETWEEN :lonMin AND :lonMax " +
        "AND recorded_at >= :minTime " +
        "AND recorded_at <= :maxTime " +
        "ORDER BY recorded_at DESC LIMIT :limit"
    )
    suspend fun getObservationsNear(
        latMin: Double,
        latMax: Double,
        lonMin: Double,
        lonMax: Double,
        minTime: Long = 0L,
        maxTime: Long = Long.MAX_VALUE,
        limit: Int = 48,
    ): List<WeatherCacheEntity>

    /** All real observations strictly after [fromTime] — no row limit, sorted ascending. */
    @Query(
        "SELECT * FROM weather_cache " +
        "WHERE is_forecast = 0 " +
        "AND latitude BETWEEN :latMin AND :latMax " +
        "AND longitude BETWEEN :lonMin AND :lonMax " +
        "AND recorded_at > :fromTime " +
        "ORDER BY recorded_at ASC"
    )
    suspend fun getObservationsAfter(
        latMin: Double,
        latMax: Double,
        lonMin: Double,
        lonMax: Double,
        fromTime: Long,
    ): List<WeatherCacheEntity>

    @Query(
        "SELECT * FROM weather_cache " +
        "WHERE is_forecast = 1 " +
        "AND latitude BETWEEN :latMin AND :latMax " +
        "AND longitude BETWEEN :lonMin AND :lonMax " +
        "AND recorded_at >= :fromTime " +
        "ORDER BY recorded_at ASC"
    )
    suspend fun getForecastsFrom(
        latMin: Double,
        latMax: Double,
        lonMin: Double,
        lonMax: Double,
        fromTime: Long,
    ): List<WeatherCacheEntity>

    @Query(
        "DELETE FROM weather_cache " +
        "WHERE latitude BETWEEN :latMin AND :latMax " +
        "AND longitude BETWEEN :lonMin AND :lonMax"
    )
    suspend fun deleteNear(
        latMin: Double,
        latMax: Double,
        lonMin: Double,
        lonMax: Double,
    )

    @Query(
        "SELECT * FROM weather_cache " +
        "WHERE is_forecast = 0 " +
        "AND latitude = :lat AND longitude = :lon " +
        "ORDER BY recorded_at DESC LIMIT 1"
    )
    suspend fun getLatestObservation(lat: Double, lon: Double): WeatherCacheEntity?
}
