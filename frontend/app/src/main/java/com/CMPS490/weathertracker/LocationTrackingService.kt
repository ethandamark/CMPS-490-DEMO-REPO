package com.CMPS490.weathertracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground Service for continuous background location tracking.
 * Updates device_location in the backend periodically so the system
 * knows the device's current location for alert eligibility.
 */
class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        
        // Update interval: 1 minute (in milliseconds) - for testing
        private const val LOCATION_UPDATE_INTERVAL = 1 * 60 * 1000L
        // Fastest interval: 30 seconds
        private const val FASTEST_UPDATE_INTERVAL = 30 * 1000L
        
        /**
         * Start the location tracking service
         */
        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the location tracking service
         */
        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.stopService(intent)
        }
        
        /**
         * Check if we have all required permissions for background location
         */
        fun hasRequiredPermissions(context: Context): Boolean {
            val fineLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not required before Android 10
            }
            
            return fineLocation && backgroundLocation
        }
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isTracking = false
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                Log.d(TAG, "")
                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "📍 BACKGROUND LOCATION CALLBACK TRIGGERED")
                Log.d(TAG, "══════════════════════════════════════════")
                result.lastLocation?.let { location ->
                    Log.d(TAG, "   Latitude:  ${location.latitude}")
                    Log.d(TAG, "   Longitude: ${location.longitude}")
                    Log.d(TAG, "   Accuracy:  ${location.accuracy}m")
                    Log.d(TAG, "   Time:      ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                    updateLocationInBackend(location.latitude, location.longitude)
                } ?: Log.w(TAG, "   ⚠ lastLocation was null!")
                Log.d(TAG, "")
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        createNotificationChannel()
        val notification = createNotification()
        
        // Start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        startLocationUpdates()
        
        // If killed, restart
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopLocationUpdates()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks location for weather alerts"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Weather Tracker")
            .setContentText("Monitoring location for weather alerts")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun startLocationUpdates() {
        if (isTracking) {
            Log.d(TAG, "Already tracking location")
            return
        }
        
        if (!hasRequiredPermissions(this)) {
            Log.w(TAG, "Missing location permissions, cannot start tracking")
            stopSelf()
            return
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        )
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
            .setMinUpdateDistanceMeters(0f)  // Force updates even if location hasn't changed
            .setWaitForAccurateLocation(false)
            .build()
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isTracking = true
            Log.d(TAG, "✓ Started background location tracking (interval: ${LOCATION_UPDATE_INTERVAL / 60000} min)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location updates", e)
            stopSelf()
        }
    }
    
    private fun stopLocationUpdates() {
        if (isTracking) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isTracking = false
            Log.d(TAG, "Stopped location tracking")
        }
    }
    
    private fun updateLocationInBackend(latitude: Double, longitude: Double) {
        val authService = AuthenticationService(this)
        val deviceId = authService.getStoredDeviceId()
        
        if (deviceId == null) {
            Log.w(TAG, "No device ID stored, skipping location update")
            return
        }
        
        Log.d(TAG, "Updating backend with location: device=$deviceId, lat=$latitude, lon=$longitude")
        
        BackendRepository.updateCurrentDeviceLocation(
            deviceId = deviceId,
            latitude = latitude,
            longitude = longitude,
            onSuccess = { locationId, action ->
                Log.d(TAG, "✓ Backend location $action: $locationId")
            },
            onError = { e ->
                Log.e(TAG, "✗ Backend location update failed: ${e.message}")
            }
        )
    }
}
