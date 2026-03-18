package com.CMPS490.weathertracker

import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class CachedWeatherSnapshot(
    val currentWeather: CurrentWeatherUiModel,
    val alertWeather: WeatherAlertUiModel?,
    val forecastWeather: List<DailyForecastUiModel>
)

object WeatherApiCache {
    private const val TAG = "WeatherCache"
    private const val TTL_MILLIS = 10 * 60 * 1000L

    private data class CacheEntry(
        val createdAtMillis: Long,
        val snapshot: CachedWeatherSnapshot
    )

    private val entries = ConcurrentHashMap<String, CacheEntry>()

    fun get(lat: Double, lon: Double): CachedWeatherSnapshot? {
        val key = keyFor(lat, lon)
        val entry = entries[key] ?: run {
            Log.d(TAG, "MISS key=$key (not found)")
            return null
        }

        val age = System.currentTimeMillis() - entry.createdAtMillis
        if (age > TTL_MILLIS) {
            entries.remove(key)
            Log.d(TAG, "MISS key=$key (expired ageMs=$age)")
            return null
        }

        Log.d(TAG, "HIT key=$key ageMs=$age")
        return entry.snapshot
    }

    fun put(
        lat: Double,
        lon: Double,
        currentWeather: CurrentWeatherUiModel,
        alertWeather: WeatherAlertUiModel?,
        forecastWeather: List<DailyForecastUiModel>
    ) {
        if (forecastWeather.isEmpty()) {
            return
        }

        val key = keyFor(lat, lon)
        entries[key] = CacheEntry(
            createdAtMillis = System.currentTimeMillis(),
            snapshot = CachedWeatherSnapshot(
                currentWeather = currentWeather,
                alertWeather = alertWeather,
                forecastWeather = forecastWeather
            )
        )
        Log.d(TAG, "STORE key=$key ttlMs=$TTL_MILLIS")
    }

    private fun keyFor(lat: Double, lon: Double): String {
        return String.format(Locale.US, "%.4f,%.4f", lat, lon)
    }
}
