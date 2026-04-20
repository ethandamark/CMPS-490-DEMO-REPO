package com.CMPS490.weathertracker

import android.util.Log
import com.google.gson.annotations.SerializedName
import java.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class AnonymousUserRecord(
    @SerializedName("anon_user_id")
    val anonUserId: String,
    @SerializedName("created_at")
    val createdAt: String = Instant.now().toString(),
    @SerializedName("last_active_at")
    val lastActiveAt: String? = null,
    @SerializedName("notification_opt_in")
    val notificationOptIn: Boolean? = null,
    @SerializedName("status")
    val status: String = "active"
)

data class DeviceRecord(
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("anon_user_id")
    val anonUserId: String,
    @SerializedName("platform")
    val platform: String = "android",
    @SerializedName("app_version")
    val appVersion: String = "1.0",
    @SerializedName("location_permission_status")
    val locationPermissionStatus: Boolean? = null,
    @SerializedName("last_seen_at")
    val lastSeenAt: String? = null,
    @SerializedName("created_at")
    val createdAt: String = Instant.now().toString()
)

data class DeviceUpdateRecord(
    @SerializedName("location_permission_status")
    val locationPermissionStatus: Boolean? = null,
    @SerializedName("last_seen_at")
    val lastSeenAt: String? = null
)

object SupabaseRepository {
    private const val TAG = "SupabaseRepository"

    suspend fun createAnonUser(anonUserId: String): Result<Unit> {
        val error = UnsupportedOperationException(
            "Direct Supabase access is no longer configured. Use BackendRepository.register instead."
        )
        Log.e(TAG, "✗ Failed to create anonymous user: ${error.message}")
        return Result.failure(error)
    }

    suspend fun createDevice(deviceId: String, anonUserId: String, locationPermissionStatus: Boolean? = null): Result<Unit> {
        val error = UnsupportedOperationException(
            "Direct Supabase access is no longer configured. Use BackendRepository.register instead."
        )
        Log.e(TAG, "✗ Failed to create device: ${error.message}")
        return Result.failure(error)
    }

    suspend fun updateDevice(
        deviceId: String,
        locationPermissionStatus: Boolean? = null,
        lastSeenAt: String? = null
    ): Result<Unit> {
        return try {
            Log.d(TAG, "→ Updating device: $deviceId")
            if (locationPermissionStatus != null) {
                Log.d(TAG, "  - Location permission status: $locationPermissionStatus")
            }
            if (lastSeenAt != null) {
                Log.d(TAG, "  - Last seen at: $lastSeenAt")
            }
            
            // Call BackendRepository.updateDevice() using suspendCancellableCoroutine
            suspendCancellableCoroutine<Result<Unit>> { continuation ->
                BackendRepository.updateDevice(
                    deviceId = deviceId,
                    locationPermissionStatus = locationPermissionStatus,
                    lastSeenAt = lastSeenAt,
                    onSuccess = {
                        Log.d(TAG, "✓ Device updated successfully: $deviceId")
                        continuation.resume(Result.success(Unit))
                    },
                    onError = { error ->
                        Log.e(TAG, "✗ Failed to update device: ${error.message}", error)
                        continuation.resume(Result.failure(error))
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to update device: ${e.message}", e)
            Result.failure(e)
        }
    }
}
