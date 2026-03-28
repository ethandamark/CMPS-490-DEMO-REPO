package com.CMPS490.weathertracker

import android.util.Log
import com.google.gson.annotations.SerializedName
import java.time.Instant

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
    @SerializedName("alert_token")
    val alertToken: String? = null,
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

object SupabaseRepository {
    private const val TAG = "SupabaseRepository"

    suspend fun createAnonUser(anonUserId: String): Result<Unit> {
        return try {
            Log.d(TAG, "→ Attempting to create anonymous user with ID: $anonUserId")
            val api = SupabaseConfig.getApi()
            val record = AnonymousUserRecord(
                anonUserId = anonUserId,
                status = "active"
            )

            Log.d(TAG, "  Sending anonymous_user insert request to Supabase...")
            try {
                api.createAnonUser(record)
            } catch (e: Exception) {
                // Supabase returns empty body on 201 Created - this causes GSON EOF error
                // But the INSERT actually succeeds, so we can safely ignore this
                if (e.message?.contains("End of input") == true) {
                    Log.d(TAG, "✓ Anonymous user created successfully (empty response is expected)")
                } else {
                    throw e
                }
            }
            Log.d(TAG, "✓ Anonymous user created successfully: $anonUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to create anonymous user: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun createDevice(deviceId: String, anonUserId: String): Result<Unit> {
        return try {
            Log.d(TAG, "→ Attempting to create device with ID: $deviceId")
            Log.d(TAG, "  Linking to anonymous user: $anonUserId")
            val api = SupabaseConfig.getApi()
            val record = DeviceRecord(
                deviceId = deviceId,
                anonUserId = anonUserId,
                platform = "android"
            )

            Log.d(TAG, "  Sending device insert request to Supabase...")
            try {
                api.createDevice(record)
            } catch (e: Exception) {
                // Supabase returns empty body on 201 Created - this causes GSON EOF error
                // But the INSERT actually succeeds, so we can safely ignore this
                if (e.message?.contains("End of input") == true) {
                    Log.d(TAG, "✓ Device created successfully (empty response is expected)")
                } else {
                    throw e
                }
            }
            Log.d(TAG, "✓ Device created successfully: $deviceId linked to user: $anonUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to create device: ${e.message}", e)
            Result.failure(e)
        }
    }
}
