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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.CMPS490.weathertracker.AuthenticationService
import com.CMPS490.weathertracker.data.WeatherDatabase
import com.CMPS490.weathertracker.network.BackendRetrofitInstance
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Manages syncing of locally-queued model instances to the backend.
 * Follows the same pattern as SnapshotSyncManager:
 * - Periodic 1h WorkManager sync
 * - NetworkCallback for immediate sync only on genuine reconnects (lost → available)
 */
object ModelInstanceSyncManager {

    private const val TAG = "ModelInstanceSync"
    const val SYNC_WORK_NAME = "model_instance_sync_periodic"
    const val SYNC_IMMEDIATE_WORK_NAME = "model_instance_sync_immediate"

    fun init(context: Context) {
        schedulePeriodicSync(context)
        registerReconnectCallback(context)
    }

    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ModelInstanceSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Periodic model-instance sync scheduled (every 1h, requires network)")
    }

    fun triggerImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelInstanceSyncWorker>()
            .setConstraints(constraints)
            .build()

        // KEEP ensures only one immediate sync runs at a time even if the
        // callback fires multiple times in quick succession.
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "One-shot model-instance sync enqueued (deduplicated)")
    }

    private fun registerReconnectCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                // Only sync when we regain connectivity after a real loss.
                // onAvailable fires immediately on registration if already
                // connected — we must ignore that initial fire.
                private var hadLoss = false

                override fun onLost(network: Network) {
                    hadLoss = true
                    Log.d(TAG, "Network lost — will sync model instances on reconnect")
                }

                override fun onAvailable(network: Network) {
                    if (hadLoss) {
                        hadLoss = false
                        Log.d(TAG, "Network reconnected — triggering model-instance sync")
                        triggerImmediateSync(context)
                    }
                    // else: initial registration callback while already connected — skip
                }
            },
        )
    }
}

/**
 * WorkManager worker that syncs unsynced local model instances to the backend.
 */
class ModelInstanceSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ModelInstanceSyncWorker"
    }

    override suspend fun doWork(): Result {
        val authService = AuthenticationService(applicationContext)
        val deviceId = authService.getStoredDeviceId() ?: run {
            Log.w(TAG, "No device ID, skipping model-instance sync")
            return Result.success()
        }

        val db = WeatherDatabase.getInstance(applicationContext)
        val unsynced = db.modelInstanceDao().getUnsynced()

        if (unsynced.isEmpty()) {
            Log.d(TAG, "No unsynced model instances")
            return Result.success()
        }

        // Build payload
        val instancesArray = JsonArray()
        for (item in unsynced) {
            val obj = JsonObject().apply {
                addProperty("instance_id", item.instanceId)
                addProperty("version", item.version)
                addProperty("latitude", item.latitude)
                addProperty("longitude", item.longitude)
                addProperty("result_level", item.resultLevel)
                addProperty("result_type", item.resultType)
                addProperty("confidence_score", item.confidenceScore.toDouble())
                addProperty("created_at", item.createdAt)
            }
            instancesArray.add(obj)
        }

        val body = JsonObject().apply {
            add("instances", instancesArray)
        }

        // Bridge the synchronous Retrofit call into a coroutine result
        val syncResult = runCatching {
            withContext(Dispatchers.IO) {
                val response = BackendRetrofitInstance.api
                    .syncModelInstances(deviceId, body)
                    .execute()
                if (!response.isSuccessful) {
                    throw RuntimeException(
                        "HTTP ${response.code()}: ${response.errorBody()?.string()?.take(200)}"
                    )
                }
            }
        }

        return if (syncResult.isSuccess) {
            val ids = unsynced.map { it.instanceId }
            db.modelInstanceDao().markSynced(ids, System.currentTimeMillis())
            Log.i(TAG, "✓ Synced ${ids.size} model instances to backend")
            Result.success()
        } else {
            Log.e(TAG, "✗ Model-instance sync failed: ${syncResult.exceptionOrNull()?.message}")
            Result.retry()
        }
    }
}
