package com.CMPS490.weathertracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver that starts the LocationTrackingService when the device boots.
 * Ensures location tracking resumes after device restart.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed - checking if location tracking should start")
            
            // Check if we have permissions and a stored device ID
            val authService = AuthenticationService(context)
            val deviceId = authService.getStoredDeviceId()
            
            if (deviceId != null && LocationTrackingService.hasRequiredPermissions(context)) {
                Log.d(TAG, "Starting location tracking service after boot")
                LocationTrackingService.start(context)
            } else {
                Log.d(TAG, "Skipping location service start: deviceId=$deviceId, hasPermissions=${LocationTrackingService.hasRequiredPermissions(context)}")
            }
        }
    }
}
