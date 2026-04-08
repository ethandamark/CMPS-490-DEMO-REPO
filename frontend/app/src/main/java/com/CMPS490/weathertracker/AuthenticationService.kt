package com.CMPS490.weathertracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuthenticationService(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_ANON_USER_ID = "anon_user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val TAG = "AuthenticationService"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun initializeFirstRun(): Pair<String, String> {
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "Starting authentication initialization...")
        val existingUserId = getStoredAnonUserId()
        val existingDeviceId = getStoredDeviceId()

        return if (existingUserId != null && existingDeviceId != null) {
            Log.d(TAG, "✓ EXISTING CREDENTIALS FOUND")
            Log.d(TAG, "  Anon User ID: $existingUserId")
            Log.d(TAG, "  Device ID:    $existingDeviceId")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            existingUserId to existingDeviceId
        } else {
            Log.d(TAG, "⚠ FIRST RUN DETECTED - Requesting backend to create account and device")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            createAnonUserAndDevice()
        }
    }

    private suspend fun createAnonUserAndDevice(): Pair<String, String> {
        Log.d(TAG, "Requesting backend to register user + device...")
        val latch = CountDownLatch(1)
        var error: Exception? = null
        var anonUserId: String? = null
        var deviceId: String? = null

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "  Location permission granted: $hasLocationPermission")

        // Get FCM token before registering so device_token is filled at creation
        var fcmToken: String? = null
        try {
            fcmToken = withContext(Dispatchers.IO) {
                Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
            }
            Log.d(TAG, "  FCM token obtained: ${fcmToken?.take(20)}...")
        } catch (e: Exception) {
            Log.w(TAG, "  FCM token fetch failed, will be null at creation", e)
        }
        Log.d(TAG, "  Sending deviceToken to backend: ${if (fcmToken != null) "present (${fcmToken!!.length} chars)" else "NULL"}")

        BackendRepository.register(
            locationPermissionStatus = hasLocationPermission,
            deviceToken = fcmToken,
            onSuccess = { userId, devId ->
                anonUserId = userId
                deviceId = devId
                latch.countDown()
            },
            onError = { e ->
                error = e
                latch.countDown()
            }
        )

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw Exception("Timeout waiting for registration")
        }
        if (error != null) {
            Log.e(TAG, "✗ Registration failed", error)
            throw error!!
        }
        Log.d(TAG, "✓ Registered by backend: user=$anonUserId, device=$deviceId")
        Log.d(TAG, "")

        // Store the backend-generated IDs locally for future use
        Log.d(TAG, "Storing backend-generated credentials locally...")
        prefs.edit().apply {
            putString(KEY_ANON_USER_ID, anonUserId)
            putString(KEY_DEVICE_ID, deviceId)
            apply()
        }
        Log.d(TAG, "✓ Credentials stored locally")
        Log.d(TAG, "")

        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "✓ AUTHENTICATION SETUP COMPLETE")
        Log.d(TAG, "  Anon User ID: $anonUserId")
        Log.d(TAG, "  Device ID:    $deviceId")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        return anonUserId!! to deviceId!!
    }

    fun getStoredAnonUserId(): String? = prefs.getString(KEY_ANON_USER_ID, null)
    fun getStoredDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
}
