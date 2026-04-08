package com.CMPS490.weathertracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

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
            Log.d(TAG, "⚠ FIRST RUN DETECTED - Creating new anonymous account and device")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            createAnonUserAndDevice()
        }
    }

    private suspend fun createAnonUserAndDevice(): Pair<String, String> {
        val anonUserId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID().toString()

        Log.d(TAG, "Generated UUIDs:")
        Log.d(TAG, "  Anon User ID: $anonUserId")
        Log.d(TAG, "  Device ID:    $deviceId")
        Log.d(TAG, "")

        // Check location permission status
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Location permission status: $hasLocationPermission")

        try {
            // Create anonymous user in database
            Log.d(TAG, "[1/2] Creating anonymous user record in Supabase...")
            val userResult = SupabaseRepository.createAnonUser(anonUserId)
            if (userResult.isFailure) {
                Log.e(TAG, "✗ Failed to create anonymous user", userResult.exceptionOrNull())
                throw userResult.exceptionOrNull() ?: Exception("Unknown error creating user")
            }
            Log.d(TAG, "")

            // Create device linked to user with location permission status
            Log.d(TAG, "[2/2] Creating device record in Supabase...")
            val deviceResult = SupabaseRepository.createDevice(deviceId, anonUserId, hasLocationPermission)
            if (deviceResult.isFailure) {
                Log.e(TAG, "✗ Failed to create device", deviceResult.exceptionOrNull())
                throw deviceResult.exceptionOrNull() ?: Exception("Unknown error creating device")
            }
            Log.d(TAG, "")

            // Store locally for future use
            Log.d(TAG, "Storing credentials locally in SharedPreferences...")
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
            return anonUserId to deviceId
        } catch (e: Exception) {
            Log.e(TAG, "═══════════════════════════════════════════════════════════")
            Log.e(TAG, "✗ AUTHENTICATION SETUP FAILED")
            Log.e(TAG, "Error: ${e.message}", e)
            Log.e(TAG, "═══════════════════════════════════════════════════════════")
            throw e
        }
    }

    fun getStoredAnonUserId(): String? = prefs.getString(KEY_ANON_USER_ID, null)
    fun getStoredDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
}
