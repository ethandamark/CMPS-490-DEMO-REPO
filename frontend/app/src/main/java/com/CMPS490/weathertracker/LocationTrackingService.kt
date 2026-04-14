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
import com.CMPS490.weathertracker.data.OfflineWeatherSnapshotEntity
import com.CMPS490.weathertracker.data.WeatherCacheEntity
import com.CMPS490.weathertracker.data.WeatherDatabase
import com.CMPS490.weathertracker.ml.FeatureAssemblyService
import com.CMPS490.weathertracker.ml.OnDevicePredictor
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    // Coroutine scope tied to service lifecycle
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Last saved location — only update backend when it changes
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    // Minimum distance change (in meters) to trigger a backend update
    private val MIN_DISTANCE_CHANGE_METERS = 50f

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000L
    }
    
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

                    if (hasLocationChanged(location.latitude, location.longitude)) {
                        Log.d(TAG, "   ↗ Location changed, updating backend")
                        lastLatitude = location.latitude
                        lastLongitude = location.longitude
                        updateLocationInBackend(location.latitude, location.longitude)
                    } else {
                        Log.d(TAG, "   ⏸ Location unchanged, skipping backend update")
                    }
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
        serviceJob.cancel()
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
    
    private fun hasLocationChanged(newLat: Double, newLon: Double): Boolean {
        val prevLat = lastLatitude ?: return true  // No previous location, always update
        val prevLon = lastLongitude ?: return true

        val results = FloatArray(1)
        android.location.Location.distanceBetween(prevLat, prevLon, newLat, newLon, results)
        val distanceMeters = results[0]
        Log.d(TAG, "   Distance from last saved location: ${"%.1f".format(distanceMeters)}m (threshold: ${MIN_DISTANCE_CHANGE_METERS}m)")
        return distanceMeters >= MIN_DISTANCE_CHANGE_METERS
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
                serviceScope.launch {
                    storeWeatherSnapshot(deviceId, latitude, longitude)
                }
            },
            onError = { e ->
                Log.e(TAG, "✗ Backend location update failed: ${e.message}")
                // Still store locally even if backend fails
                serviceScope.launch {
                    storeWeatherSnapshot(deviceId, latitude, longitude)
                }
            }
        )
    }

    private suspend fun storeWeatherSnapshot(deviceId: String, latitude: Double, longitude: Double) {
        try {
            val nowMs = System.currentTimeMillis()
            val hourMs = (nowMs / MILLIS_PER_HOUR) * MILLIS_PER_HOUR  // round to current hour

            val db = WeatherDatabase.getInstance(this)

            // Fetch current weather from Open-Meteo
            val weatherData = fetchOpenMeteoWeather(latitude, longitude) ?: run {
                Log.w(TAG, "Open-Meteo fetch failed, skipping snapshot")
                return
            }

            val cacheId = UUID.randomUUID().toString()
            val cacheEntity = WeatherCacheEntity(
                cacheId = cacheId,
                temp = weatherData.optDouble("temperature_2m").takeUnless { it.isNaN() },
                humidity = weatherData.optDouble("relative_humidity_2m").takeUnless { it.isNaN() },
                windSpeed = weatherData.optDouble("wind_speed_10m").takeUnless { it.isNaN() },
                windDirection = null,
                precipitationAmount = weatherData.optDouble("precipitation").takeUnless { it.isNaN() },
                pressure = weatherData.optDouble("pressure_msl").takeUnless { it.isNaN() },
                weatherCondition = null,
                recordedAt = hourMs,
                latitude = latitude,
                longitude = longitude,
                resultLevel = null,
                resultType = null,
                isForecast = false,
                dewPointC = weatherData.optDouble("dew_point_2m").takeUnless { it.isNaN() },
                elevation = weatherData.optDouble("elevation").takeUnless { it.isNaN() },
                distToCoastKm = null,
                nwpCapeF36Max = null,
                nwpCinF36Max = null,
                nwpPwatF36Max = null,
                nwpSrh03F36Max = null,
                nwpLiF36Min = null,
                nwpLclF36Min = null,
                nwpAvailableLeads = null,
                mrmsMaxDbz75km = null,
            )

            db.weatherCacheDao().upsert(cacheEntity)

            // Clear is_current flag and insert new snapshot
            db.offlineWeatherSnapshotDao().clearCurrentFlag(deviceId)
            val snapshotId = UUID.randomUUID().toString()
            db.offlineWeatherSnapshotDao().upsertSnapshot(
                OfflineWeatherSnapshotEntity(
                    offlineWeatherId = snapshotId,
                    deviceId = deviceId,
                    cacheId = cacheId,
                    syncedAt = null,
                    isCurrent = true,
                )
            )

            // Prune observations older than 24 h
            val cutoff24h = nowMs - 24 * MILLIS_PER_HOUR
            db.weatherCacheDao().pruneOlderThan(cutoff24h)
            db.offlineWeatherSnapshotDao().pruneOld(deviceId, cutoff24h)

            // Run on-device prediction
            val featureService = FeatureAssemblyService(db)
            val features = featureService.assembleFeatures(latitude, longitude, cacheId)
            if (features.isNotEmpty()) {
                val predictor = OnDevicePredictor.getInstance(this)
                val result = predictor.predict(features)
                Log.d(TAG, "🤖 Prediction: prob=${result.stormProbability}, alert=${result.alertState}")

                // Persist prediction
                db.hourlyPredictionDao().upsert(
                    com.CMPS490.weathertracker.data.HourlyPredictionEntity(
                        timestamp = hourMs,
                        stormProbability = result.stormProbability,
                        alertState = result.alertState,
                        modelVersion = result.modelVersion,
                    )
                )
                db.hourlyPredictionDao().pruneOlderThan(nowMs - 48 * MILLIS_PER_HOUR)

                if (result.alertState == 1) {
                    fireStormNotification(result.stormProbability)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error storing weather snapshot", e)
        }
    }

    private fun fetchOpenMeteoWeather(latitude: Double, longitude: Double): JSONObject? {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&timezone=UTC&wind_speed_unit=kmh" +
                "&current=temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,pressure_msl,wind_speed_10m" +
                "&forecast_days=1"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return null
            val elevation = json.optDouble("elevation")
            current.put("elevation", elevation)
            current
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo fetch error: ${e.message}")
            null
        }
    }

    private fun fireStormNotification(probability: Float) {
        val channelId = "storm_alert_channel"
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Storm Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "On-device storm probability alerts" }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚠️ Storm Risk Detected")
            .setContentText("On-device model: ${(probability * 100).toInt()}% storm probability")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(2001, notification)
    }
}
