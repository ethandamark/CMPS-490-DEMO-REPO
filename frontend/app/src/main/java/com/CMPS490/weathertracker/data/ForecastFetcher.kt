package com.CMPS490.weathertracker.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.CMPS490.weathertracker.AuthenticationService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that fetches 7-day hourly forecasts from Open-Meteo
 * and persists them into the local Room weather_cache table.
 */
class ForecastFetcher(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ForecastFetcher"
        const val WORK_NAME = "forecast_fetcher_periodic"
        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val FORECAST_RETENTION_MS = 7L * 24 * MILLIS_PER_HOUR
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val authService = AuthenticationService(applicationContext)
        val deviceId = authService.getStoredDeviceId() ?: run {
            Log.w(TAG, "No device ID, skipping forecast fetch")
            return Result.success()
        }

        val db = WeatherDatabase.getInstance(applicationContext)

        // Get last known location from the most recent snapshot
        val snapshots = db.offlineWeatherSnapshotDao().getSnapshotsForDevice(deviceId, 1)
        val lastCache = snapshots.firstOrNull()?.cache ?: run {
            Log.w(TAG, "No location cached yet, skipping forecast fetch")
            return Result.success()
        }

        val latitude = lastCache.latitude
        val longitude = lastCache.longitude

        return try {
            val forecastRows = fetchForecastFromOpenMeteo(latitude, longitude)
            if (forecastRows.isEmpty()) {
                Log.w(TAG, "Open-Meteo returned no forecast rows")
                return Result.success()
            }

            db.weatherCacheDao().upsertAll(forecastRows)

            // Link forecast rows to offline_weather_snapshot for this device
            val snapshotEntities = forecastRows.map { row ->
                OfflineWeatherSnapshotEntity(
                    offlineWeatherId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    cacheId = row.cacheId,
                    syncedAt = null,
                    isCurrent = false,
                )
            }
            db.offlineWeatherSnapshotDao().upsertSnapshots(snapshotEntities)

            // Prune past forecast rows (recorded_at < now)
            val now = System.currentTimeMillis()
            // pruneOlderThan only prunes entries not referenced by any snapshot,
            // so forecasts linked to snapshots are retained until they become observations.
            db.weatherCacheDao().pruneOlderThan(now - FORECAST_RETENTION_MS)

            Log.i(TAG, "✓ Stored ${forecastRows.size} forecast rows for ($latitude, $longitude)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Forecast fetch failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun fetchForecastFromOpenMeteo(
        latitude: Double,
        longitude: Double,
    ): List<WeatherCacheEntity> {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&timezone=UTC&wind_speed_unit=kmh&forecast_days=7" +
            "&hourly=temperature_2m,relative_humidity_2m,dew_point_2m," +
            "precipitation,pressure_msl,wind_speed_10m"

        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Open-Meteo HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw RuntimeException("Empty Open-Meteo response")
        val json = JSONObject(body)
        val hourly = json.getJSONObject("hourly")
        val elevation = json.optDouble("elevation")

        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val humidities = hourly.getJSONArray("relative_humidity_2m")
        val dewPoints = hourly.getJSONArray("dew_point_2m")
        val precips = hourly.getJSONArray("precipitation")
        val pressures = hourly.getJSONArray("pressure_msl")
        val winds = hourly.getJSONArray("wind_speed_10m")

        val nowMs = System.currentTimeMillis()
        val result = mutableListOf<WeatherCacheEntity>()

        for (i in 0 until times.length()) {
            val isoTime = times.getString(i)
            val epochMs = parseIsoToEpochMs(isoTime) ?: continue

            // Skip past hours
            if (epochMs < nowMs - MILLIS_PER_HOUR) continue

            result.add(
                WeatherCacheEntity(
                    cacheId = UUID.randomUUID().toString(),
                    temp = temps.optDouble(i).takeUnless { it.isNaN() },
                    humidity = humidities.optDouble(i).takeUnless { it.isNaN() },
                    windSpeed = winds.optDouble(i).takeUnless { it.isNaN() },
                    windDirection = null,
                    precipitationAmount = precips.optDouble(i).takeUnless { it.isNaN() },
                    pressure = pressures.optDouble(i).takeUnless { it.isNaN() },
                    weatherCondition = null,
                    recordedAt = epochMs,
                    latitude = latitude,
                    longitude = longitude,
                    resultLevel = null,
                    resultType = null,
                    isForecast = true,
                    dewPointC = dewPoints.optDouble(i).takeUnless { it.isNaN() },
                    elevation = elevation.takeUnless { it.isNaN() },
                    distToCoastKm = null,
                    nwpCapeF36Max = null,
                    nwpCinF36Max = null,
                    nwpPwatF36Max = null,
                    nwpSrh03F36Max = null,
                    nwpLiF36Min = null,
                    nwpLclF36Min = null,
                    nwpAvailableLeads = null,
                    mrmsMaxDbz75km = null,
                )
            )
        }
        return result
    }

    private fun parseIsoToEpochMs(iso: String): Long? {
        return try {
            // Format: "2024-01-15T12:00"
            val parts = iso.split("T")
            val dateParts = parts[0].split("-")
            val timeParts = parts[1].split(":")
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.set(
                dateParts[0].toInt(),
                dateParts[1].toInt() - 1,
                dateParts[2].toInt(),
                timeParts[0].toInt(),
                timeParts[1].toInt(),
                0,
            )
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
