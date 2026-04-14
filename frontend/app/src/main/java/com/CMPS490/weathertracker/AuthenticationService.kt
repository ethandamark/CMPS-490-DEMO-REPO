package com.CMPS490.weathertracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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

        // Get device location if permission granted
        var latitude: Double? = null
        var longitude: Double? = null
        if (hasLocationPermission) {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                
                // Use requestLocationUpdates with a single update to force fresh location
                // This works better with mock locations than getCurrentLocation
                @SuppressLint("MissingPermission")
                val location = withTimeoutOrNull(10_000L) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val locationRequest = LocationRequest.Builder(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            1000L
                        ).setMaxUpdates(1).build()
                        
                        val callback = object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                fusedLocationClient.removeLocationUpdates(this)
                                continuation.resume(result.lastLocation)
                            }
                        }
                        
                        fusedLocationClient.requestLocationUpdates(
                            locationRequest,
                            callback,
                            Looper.getMainLooper()
                        )
                        
                        continuation.invokeOnCancellation {
                            fusedLocationClient.removeLocationUpdates(callback)
                        }
                    }
                }
                
                if (location != null) {
                    latitude = location.latitude
                    longitude = location.longitude
                    Log.d(TAG, "  Device location obtained: lat=$latitude, lon=$longitude")
                } else {
                    Log.w(TAG, "  Location is null (GPS may not have a fix yet)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "  Failed to get device location: ${e.message}")
            }
        }

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
            latitude = latitude,
            longitude = longitude,
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

        // Move blocking await to IO thread to prevent ANR
        val timedOut = withContext(Dispatchers.IO) {
            !latch.await(60, TimeUnit.SECONDS)
        }
        if (timedOut) {
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
