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

        /**
         * Build a deterministic cache_id from location + time + forecast flag.
         * Uses UUID v5 (SHA-1) with URL namespace to match the backend's uuid.uuid5().
         * This ensures re-fetches upsert the same row instead of creating duplicates.
         */
        fun deterministicCacheId(lat: Double, lon: Double, recordedAtMs: Long, isForecast: Boolean): String {
            val tag = if (isForecast) "f" else "o"
            val raw = "${"%.6f".format(lat)}_${"%.6f".format(lon)}_${recordedAtMs}_$tag"
            // UUID v5: SHA-1 with NAMESPACE_URL (same as Python uuid.uuid5(uuid.NAMESPACE_URL, ...))
            val nsBytes = uuidToBytes(UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"))
            val data = nsBytes + raw.toByteArray(Charsets.UTF_8)
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(data)
            sha1[6] = ((sha1[6].toInt() and 0x0f) or 0x50).toByte()  // version 5
            sha1[8] = ((sha1[8].toInt() and 0x3f) or 0x80).toByte()  // variant RFC 4122
            val hex = sha1.take(16).joinToString("") { "%02x".format(it) }
            return "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20,32)}"
        }

        /**
         * Derive a deterministic snapshot UUID from a cache_id.
         * Uses UUID v5(NAMESPACE_URL, cacheId + "_snap") so it's always a valid UUID.
         */
        fun deterministicSnapshotId(cacheId: String): String {
            val raw = cacheId + "_snap"
            val nsBytes = uuidToBytes(UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"))
            val data = nsBytes + raw.toByteArray(Charsets.UTF_8)
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(data)
            sha1[6] = ((sha1[6].toInt() and 0x0f) or 0x50).toByte()
            sha1[8] = ((sha1[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = sha1.take(16).joinToString("") { "%02x".format(it) }
            return "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20,32)}"
        }

        private fun uuidToBytes(uuid: UUID): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(16)
            buf.putLong(uuid.mostSignificantBits)
            buf.putLong(uuid.leastSignificantBits)
            return buf.array()
        }
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
                    weatherId = deterministicSnapshotId(row.cacheId),
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
            "precipitation,pressure_msl,wind_speed_10m,wind_direction_10m"

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
        val windDirs = hourly.getJSONArray("wind_direction_10m")

        val nowMs = System.currentTimeMillis()
        val result = mutableListOf<WeatherCacheEntity>()

        for (i in 0 until times.length()) {
            val isoTime = times.getString(i)
            val epochMs = parseIsoToEpochMs(isoTime) ?: continue

            // Skip past hours
            if (epochMs < nowMs - MILLIS_PER_HOUR) continue

            result.add(
                WeatherCacheEntity(
                    cacheId = deterministicCacheId(latitude, longitude, epochMs, true),
                    temp = temps.optDouble(i).takeUnless { it.isNaN() },
                    humidity = humidities.optDouble(i).takeUnless { it.isNaN() },
                    windSpeed = winds.optDouble(i).takeUnless { it.isNaN() },
                    windDirection = windDirs.optDouble(i).takeUnless { it.isNaN() },
                    precipitationAmount = precips.optDouble(i).takeUnless { it.isNaN() },
                    pressure = pressures.optDouble(i).takeUnless { it.isNaN() },
                    recordedAt = epochMs,
                    latitude = latitude,
                    longitude = longitude,
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
