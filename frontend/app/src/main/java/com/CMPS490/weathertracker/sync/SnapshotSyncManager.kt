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
        val unsynced = db.offlineWeatherSnapshotDao().getUnsyncedSnapshots(deviceId)

        if (unsynced.isEmpty()) {
            Log.d(TAG, "No unsynced snapshots")
            return Result.success()
        }

        // Build payload
        val snapshotsArray = JsonArray()
        for (item in unsynced) {
            val cacheJson = JsonObject().apply {
                addProperty("cache_id", item.cache.cacheId)
                addProperty("temp", item.cache.temp)
                addProperty("humidity", item.cache.humidity)
                addProperty("wind_speed", item.cache.windSpeed)
                addProperty("precipitation_amount", item.cache.precipitationAmount)
                addProperty("pressure", item.cache.pressure)
                addProperty("recorded_at", item.cache.recordedAt)
                addProperty("latitude", item.cache.latitude)
                addProperty("longitude", item.cache.longitude)
                addProperty("is_forecast", item.cache.isForecast)
                addProperty("dew_point_c", item.cache.dewPointC)
                addProperty("elevation", item.cache.elevation)
            }
            val snapshotJson = JsonObject().apply {
                addProperty("offline_weather_id", item.snapshot.offlineWeatherId)
                addProperty("device_id", item.snapshot.deviceId)
                addProperty("cache_id", item.snapshot.cacheId)
                addProperty("is_current", item.snapshot.isCurrent)
            }
            val entry = JsonObject().apply {
                add("weather_cache", cacheJson)
                add("offline_snapshot", snapshotJson)
            }
            snapshotsArray.add(entry)
        }

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            BackendRepository.syncSnapshots(
                deviceId = deviceId,
                snapshotsJson = snapshotsArray,
                onSuccess = { _ ->
                    val now = System.currentTimeMillis()
                    val ids = unsynced.map { it.snapshot.offlineWeatherId }
                    kotlinx.coroutines.runBlocking {
                        db.offlineWeatherSnapshotDao().markSynced(ids, now)
                    }
                    Log.i(TAG, "✓ Synced ${ids.size} snapshots to backend")
                    cont.resume(Result.success()) {}
                },
                onError = { e ->
                    Log.e(TAG, "✗ Sync failed: ${e.message}")
                    cont.resume(Result.retry()) {}
                },
            )
        }
    }
}
