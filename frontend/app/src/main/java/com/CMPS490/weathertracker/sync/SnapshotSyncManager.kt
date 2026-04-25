package com.CMPS490.weathertracker.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.CMPS490.weathertracker.AuthenticationService
import com.CMPS490.weathertracker.BackendRepository
import com.CMPS490.weathertracker.data.ForecastFetcher.Companion.deterministicCacheId
import com.CMPS490.weathertracker.data.WeatherCacheEntity
import com.CMPS490.weathertracker.data.WeatherDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages syncing of offline weather snapshots to the backend.
 * - Schedules periodic WorkManager sync (every 1h, requires network).
 * - Immediate sync is triggered externally (e.g. on backend reconnect from MainActivity).
 * - Only syncs snapshots that are referenced by a model_instance prediction.
 * - On success, chains ModelInstanceSyncManager to ensure FK targets exist first.
 */
object SnapshotSyncManager {

    private const val TAG = "SnapshotSyncManager"
    const val SYNC_WORK_NAME = "snapshot_sync_periodic"
    const val SYNC_IMMEDIATE_WORK_NAME = "snapshot_sync_immediate"

    fun init(context: Context) {
        schedulePeriodicSync(context)
    }

    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SnapshotSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Periodic sync scheduled (every 1h, requires network)")
    }

    fun triggerImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SnapshotSyncWorker>()
            .setConstraints(constraints)
            .build()

        // KEEP ensures that if a sync is already queued or running (e.g. from a
        // rapid series of WiFi callbacks), we do not enqueue a second concurrent worker.
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "One-shot sync enqueued (deduplicated)")
    }

}

/**
 * WorkManager worker that syncs unsynced local snapshots to the backend.
 */
class SnapshotSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SnapshotSyncWorker"
        private const val SYNC_TIMEOUT_SECONDS = 30L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch and store the current hour's real observation from Open-Meteo if it isn't
     * already in Room. This ensures [postPredObs] can override forecast rows for hours
     * that have passed since the prediction was made (e.g., prediction at 3 PM, sync
     * triggered at 4 PM before the 4 PM storeWeatherSnapshot has run).
     */
    private suspend fun ensureCurrentObsInRoom(
        lat: Double,
        lon: Double,
        db: WeatherDatabase,
    ) = withContext(Dispatchers.IO) {
        try {
            val nowMs = System.currentTimeMillis()
            val currentHourMs = (nowMs / 3_600_000L) * 3_600_000L
            val cacheId = deterministicCacheId(lat, lon, currentHourMs, false)

            // Skip if we already have this obs in Room
            if (db.weatherCacheDao().getByIds(listOf(cacheId)).isNotEmpty()) return@withContext

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&timezone=UTC&wind_speed_unit=kmh" +
                "&current=temperature_2m,relative_humidity_2m,dew_point_2m," +
                "precipitation,pressure_msl,wind_speed_10m,wind_direction_10m"

            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return@withContext

            val body = response.body?.string() ?: return@withContext
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return@withContext
            val elevation = json.optDouble("elevation")

            db.weatherCacheDao().upsert(
                WeatherCacheEntity(
                    cacheId = cacheId,
                    temp = current.optDouble("temperature_2m").takeUnless { it.isNaN() },
                    humidity = current.optDouble("relative_humidity_2m").takeUnless { it.isNaN() },
                    windSpeed = current.optDouble("wind_speed_10m").takeUnless { it.isNaN() },
                    windDirection = current.optDouble("wind_direction_10m").takeUnless { it.isNaN() },
                    precipitationAmount = current.optDouble("precipitation").takeUnless { it.isNaN() },
                    pressure = current.optDouble("pressure_msl").takeUnless { it.isNaN() },
                    recordedAt = currentHourMs,
                    latitude = lat,
                    longitude = lon,
                    isForecast = false,
                    dewPointC = current.optDouble("dew_point_2m").takeUnless { it.isNaN() },
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
            Log.d(TAG, "✓ Pre-fetched current obs for sync (hourMs=$currentHourMs)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not pre-fetch current obs: ${e.message}")
        }
    }

    override suspend fun doWork(): Result {
        val authService = AuthenticationService(applicationContext)
        val deviceId = authService.getStoredDeviceId() ?: run {
            Log.w(TAG, "No device ID, skipping sync")
            return Result.success()
        }

        val db = WeatherDatabase.getInstance(applicationContext)

        // Only fetch UNSYNCED snapshots to avoid re-sending already-synced data
        val unsyncedSnapshots = db.offlineWeatherSnapshotDao().getUnsyncedSnapshots(deviceId)

        if (unsyncedSnapshots.isEmpty()) {
            Log.d(TAG, "No unsynced snapshots to sync")
            ModelInstanceSyncManager.triggerImmediateSync(applicationContext)
            return Result.success()
        }

        val unsyncedIds = unsyncedSnapshots.map { it.snapshot.weatherId }

        // Ensure the current hour's real obs is in Room before building weather_data.
        // If the sync runs before storeWeatherSnapshot has fired for the current hour
        // (e.g., prediction at 3 PM, sync triggered at 3:55 PM, 4 PM not yet observed),
        // postPredObs would miss the current hour. This pre-fetch covers that gap.
        val firstLat = unsyncedSnapshots.first().cache.latitude
        val firstLon = unsyncedSnapshots.first().cache.longitude
        ensureCurrentObsInRoom(firstLat, firstLon, db)

        // Build one snapshot object per unsynced item.
        // Each snapshot's weather_data contains the full window of cache rows that
        // were in Room at prediction time: up to 24 h of observations + 7-day forecast.
        // This mirrors what the seed endpoint stores so the backend gets a complete picture.
        val snapshotsArray = JsonArray()
        val DELTA = 0.045  // ~5 km bounding box (matches seedWeatherHistory)
        for (item in unsyncedSnapshots) {
            val lat = item.cache.latitude
            val lon = item.cache.longitude
            val snapshotTimeMs = item.cache.recordedAt

            val MILLIS_PER_HOUR = 3_600_000L

            // 24-hour observation window ending at the prediction hour.
            // Using a time-bounded window (not just LIMIT 24) means the start
            // of the obs section advances by 1 h with each hourly snapshot.
            val obsRows = db.weatherCacheDao().getObservationsNear(
                latMin = lat - DELTA,
                latMax = lat + DELTA,
                lonMin = lon - DELTA,
                lonMax = lon + DELTA,
                minTime = snapshotTimeMs - 24 * MILLIS_PER_HOUR,
                maxTime = snapshotTimeMs,
                limit = 24,
            ).sortedBy { it.recordedAt }

            // Real observations that arrived AFTER the prediction hour (i.e., the
            // hourly sync ran and fetched actual data for hours that were forecast
            // at prediction time). Prefer these over forecast rows for the same hour.
            val postPredObs = db.weatherCacheDao().getObservationsAfter(
                latMin = lat - DELTA,
                latMax = lat + DELTA,
                lonMin = lon - DELTA,
                lonMax = lon + DELTA,
                fromTime = snapshotTimeMs,
            )
            val postPredObsTimes = postPredObs.map { it.recordedAt }.toSet()

            // Forecasts only for hours that have no real observation yet.
            val forecastRows = db.weatherCacheDao().getForecastsFrom(
                latMin = lat - DELTA,
                latMax = lat + DELTA,
                lonMin = lon - DELTA,
                lonMax = lon + DELTA,
                fromTime = snapshotTimeMs + MILLIS_PER_HOUR,
            ).filter { it.recordedAt !in postPredObsTimes }

            val weatherDataArray = JsonArray()
            for (row in (obsRows + postPredObs + forecastRows)) {
                weatherDataArray.add(JsonObject().apply {
                    addProperty("cache_id", row.cacheId)
                    addProperty("temp", row.temp)
                    addProperty("humidity", row.humidity)
                    addProperty("wind_speed", row.windSpeed)
                    addProperty("wind_direction", row.windDirection)
                    addProperty("precipitation_amount", row.precipitationAmount)
                    addProperty("pressure", row.pressure)
                    addProperty("recorded_at", row.recordedAt)   // epoch ms — backend converts to CDT
                    addProperty("recorded_at_ms", row.recordedAt)
                    addProperty("latitude", row.latitude)
                    addProperty("longitude", row.longitude)
                    addProperty("is_forecast", row.isForecast)
                    addProperty("dew_point_c", row.dewPointC)
                    addProperty("elevation", row.elevation)
                    addProperty("dist_to_coast_km", row.distToCoastKm)
                    addProperty("nwp_cape_f3_6_max", row.nwpCapeF36Max)
                    addProperty("nwp_cin_f3_6_max", row.nwpCinF36Max)
                    addProperty("nwp_pwat_f3_6_max", row.nwpPwatF36Max)
                    addProperty("nwp_srh03_f3_6_max", row.nwpSrh03F36Max)
                    addProperty("nwp_li_f3_6_min", row.nwpLiF36Min)
                    addProperty("nwp_lcl_f3_6_min", row.nwpLclF36Min)
                    addProperty("nwp_available_leads", row.nwpAvailableLeads)
                    addProperty("mrms_max_dbz_75km", row.mrmsMaxDbz75km)
                })
            }

            val snapshotJson = JsonObject().apply {
                addProperty("weather_id", item.snapshot.weatherId)
                add("weather_data", weatherDataArray)
                addProperty("snapshot_type", "sync")
            }
            snapshotsArray.add(snapshotJson)
        }

        // Send the per-snapshot array to the backend
        val body = JsonObject().apply {
            add("snapshots", snapshotsArray)
        }

        val ids = unsyncedIds
        val syncResult = kotlinx.coroutines.suspendCancellableCoroutine<kotlin.Result<Unit>> { cont ->
            BackendRepository.syncSnapshots(
                deviceId = deviceId,
                body = body,
                onSuccess = { _ -> cont.resume(kotlin.Result.success(Unit)) {} },
                onError = { e -> cont.resume(kotlin.Result.failure(e)) {} },
            )
        }

        return if (syncResult.isSuccess) {
            db.offlineWeatherSnapshotDao().markSynced(ids, System.currentTimeMillis())
            Log.i(TAG, "✓ Synced ${unsyncedSnapshots.size} unsynced snapshots (${unsyncedIds.size} newly marked) to backend")
            ModelInstanceSyncManager.triggerImmediateSync(applicationContext)
            Result.success()
        } else {
            Log.e(TAG, "✗ Sync failed: ${syncResult.exceptionOrNull()?.message}")
            Result.retry()
        }
    }
}
