package com.CMPS490.weathertracker.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.CMPS490.weathertracker.AuthenticationService
import com.CMPS490.weathertracker.BackendRepository
import com.CMPS490.weathertracker.data.WeatherDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit

/**
 * Manages syncing of offline weather snapshots to the backend.
 * - Registers a WiFi NetworkCallback to trigger sync on WiFi connect.
 * - Schedules periodic WorkManager sync.
 * - On sync: posts unsynced local snapshots to backend, marks them synced locally.
 */
object SnapshotSyncManager {

    private const val TAG = "SnapshotSyncManager"
    const val SYNC_WORK_NAME = "snapshot_sync_periodic"

    fun init(context: Context) {
        schedulePeriodicSync(context)
        registerWifiCallback(context)
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

        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "One-shot sync enqueued")
    }

    private fun registerWifiCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "WiFi connected — triggering sync")
                    triggerImmediateSync(context)
                }
            },
        )
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

    override suspend fun doWork(): Result {
        val authService = AuthenticationService(applicationContext)
        val deviceId = authService.getStoredDeviceId() ?: run {
            Log.w(TAG, "No device ID, skipping sync")
            return Result.success()
        }

        val db = WeatherDatabase.getInstance(applicationContext)

        // Send ALL cached snapshots (observations + forecasts) so the backend gets full history
        val allSnapshots = db.offlineWeatherSnapshotDao().getSnapshotsForDevice(deviceId, 250)

        if (allSnapshots.isEmpty()) {
            Log.d(TAG, "No snapshots to sync")
            return Result.success()
        }

        // Find which ones haven't been synced yet (to mark after success)
        val unsyncedIds = allSnapshots
            .filter { it.snapshot.syncedAt == null }
            .map { it.snapshot.weatherId }

        if (unsyncedIds.isEmpty()) {
            Log.d(TAG, "All snapshots already synced")
            return Result.success()
        }

        // Bundle ALL cache rows into ONE weather_data JSONB array
        val weatherDataArray = JsonArray()
        for (item in allSnapshots) {
            val cacheJson = JsonObject().apply {
                addProperty("cache_id", item.cache.cacheId)
                addProperty("temp", item.cache.temp)
                addProperty("humidity", item.cache.humidity)
                addProperty("wind_speed", item.cache.windSpeed)
                addProperty("wind_direction", item.cache.windDirection)
                addProperty("precipitation_amount", item.cache.precipitationAmount)
                addProperty("pressure", item.cache.pressure)
                addProperty("recorded_at", item.cache.recordedAt)
                addProperty("latitude", item.cache.latitude)
                addProperty("longitude", item.cache.longitude)
                addProperty("is_forecast", item.cache.isForecast)
                addProperty("dew_point_c", item.cache.dewPointC)
                addProperty("elevation", item.cache.elevation)
                addProperty("dist_to_coast_km", item.cache.distToCoastKm)
                addProperty("nwp_cape_f3_6_max", item.cache.nwpCapeF36Max)
                addProperty("nwp_cin_f3_6_max", item.cache.nwpCinF36Max)
                addProperty("nwp_pwat_f3_6_max", item.cache.nwpPwatF36Max)
                addProperty("nwp_srh03_f3_6_max", item.cache.nwpSrh03F36Max)
                addProperty("nwp_li_f3_6_min", item.cache.nwpLiF36Min)
                addProperty("nwp_lcl_f3_6_min", item.cache.nwpLclF36Min)
                addProperty("nwp_available_leads", item.cache.nwpAvailableLeads)
                addProperty("mrms_max_dbz_75km", item.cache.mrmsMaxDbz75km)
            }
            weatherDataArray.add(cacheJson)
        }

        // Send ONE snapshot with all weather data bundled as JSONB
        val body = JsonObject().apply {
            add("weather_data", weatherDataArray)
            addProperty("snapshot_type", "sync")
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
            Log.i(TAG, "✓ Synced ${allSnapshots.size} snapshots (${ids.size} newly marked) to backend")
            Result.success()
        } else {
            Log.e(TAG, "✗ Sync failed: ${syncResult.exceptionOrNull()?.message}")
            Result.retry()
        }
    }
}
