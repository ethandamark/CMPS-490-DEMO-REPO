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
import com.CMPS490.weathertracker.data.ForecastFetcher.Companion.deterministicCacheId
import com.CMPS490.weathertracker.data.ForecastFetcher.Companion.deterministicSnapshotId
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        const val MILLIS_PER_HOUR = 3_600_000L
        const val ACTION_FORCE_PREDICT = "com.CMPS490.weathertracker.FORCE_PREDICT"

        private const val PREFS_NAME = "location_tracking_prefs"
        private const val PREF_LAST_SNAPSHOT_TIME = "last_snapshot_time_ms"
        private const val PREF_LAST_SNAPSHOT_LAT = "last_snapshot_lat"
        private const val PREF_LAST_SNAPSHOT_LON = "last_snapshot_lon"
        private const val PREF_HAS_SEEDED_HISTORY = "has_seeded_history"
        
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
    // Minimum distance change (in meters) to trigger a new weather snapshot (no prediction)
    private val MIN_SNAPSHOT_DISTANCE_METERS = 5000f

    // Whether we've already seeded 24h of weather history (persisted across restarts)
    private val hasSeededHistory = AtomicBoolean(false)

    // Track when we last ran a weather snapshot + prediction (persisted across restarts)
    private var lastSnapshotTimeMs: Long = 0L

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Restore the last snapshot time so a service restart doesn't look like
        // the hourly timer has elapsed and trigger an immediate prediction.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastSnapshotTimeMs = prefs.getLong(PREF_LAST_SNAPSHOT_TIME, 0L)
        val savedLat = prefs.getFloat(PREF_LAST_SNAPSHOT_LAT, Float.MIN_VALUE)
        val savedLon = prefs.getFloat(PREF_LAST_SNAPSHOT_LON, Float.MIN_VALUE)
        if (savedLat != Float.MIN_VALUE && savedLon != Float.MIN_VALUE) {
            lastSnapshotLatitude = savedLat.toDouble()
            lastSnapshotLongitude = savedLon.toDouble()
        }
        if (prefs.getBoolean(PREF_HAS_SEEDED_HISTORY, false)) {
            hasSeededHistory.set(true)
        }
        Log.d(TAG, "Restored lastSnapshotTimeMs = $lastSnapshotTimeMs, lastSnapshotLat = $lastSnapshotLatitude, lastSnapshotLon = $lastSnapshotLongitude")
        
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

                    val locationChanged = hasLocationChanged(location.latitude, location.longitude)
                    val significantMove = hasMovedSignificantly(location.latitude, location.longitude)
                    val hourElapsed = System.currentTimeMillis() - lastSnapshotTimeMs >= MILLIS_PER_HOUR

                    // Always update stored coordinates
                    lastLatitude = location.latitude
                    lastLongitude = location.longitude

                    // Update backend location if device moved
                    if (locationChanged) {
                        Log.d(TAG, "   ↗ Location changed, updating backend")
                        updateLocationInBackend(location.latitude, location.longitude)
                    }

                    val authService = AuthenticationService(this@LocationTrackingService)
                    val deviceId = authService.getStoredDeviceId() ?: "debug-device"

                    // Run prediction only on the hourly timer
                    if (hourElapsed) {
                        Log.d(TAG, "   ⏰ Hourly prediction timer — running weather snapshot + prediction")
                        lastSnapshotLatitude = location.latitude
                        lastSnapshotLongitude = location.longitude
                        saveSnapshotLocation(location.latitude, location.longitude)
                        serviceScope.launch {
                            storeWeatherSnapshot(deviceId, location.latitude, location.longitude, runPrediction = true)
                        }
                    } else if (significantMove) {
                        // Moved 5+ km — update backend location + grab a fresh weather snapshot
                        Log.d(TAG, "   📍 Significant move (5km+), updating location + storing snapshot (no prediction)")
                        updateLocationInBackend(location.latitude, location.longitude)
                        lastSnapshotLatitude = location.latitude
                        lastSnapshotLongitude = location.longitude
                        saveSnapshotLocation(location.latitude, location.longitude)
                        serviceScope.launch {
                            storeWeatherSnapshot(deviceId, location.latitude, location.longitude, runPrediction = false)
                        }
                    } else if (!locationChanged) {
                        Log.d(TAG, "   ⏸ Next prediction in ${(MILLIS_PER_HOUR - (System.currentTimeMillis() - lastSnapshotTimeMs)) / 60000} min")
                    }
                } ?: Log.w(TAG, "   ⚠ lastLocation was null!")
                Log.d(TAG, "")
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        // Handle force-predict action (ADB debug trigger)
        if (intent?.action == ACTION_FORCE_PREDICT) {
            Log.d(TAG, "🔧 FORCE_PREDICT action received")
            serviceScope.launch {
                val lat = lastLatitude ?: 30.2241  // default to Lafayette, LA
                val lon = lastLongitude ?: -92.0198
                val authService = AuthenticationService(this@LocationTrackingService)
                val deviceId = authService.getStoredDeviceId() ?: "debug-device"
                Log.d(TAG, "🔧 Running forced prediction at ($lat, $lon)")
                storeWeatherSnapshot(deviceId, lat, lon)
            }
            return START_STICKY
        }

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
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Tracks location for weather alerts"
                setShowBadge(false)
                setSound(null, null)
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
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
    
    // Last location where we stored a weather snapshot (for 5km threshold, persisted across restarts)
    private var lastSnapshotLatitude: Double? = null
    private var lastSnapshotLongitude: Double? = null

    private fun saveSnapshotLocation(lat: Double, lon: Double) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(PREF_LAST_SNAPSHOT_LAT, lat.toFloat())
            .putFloat(PREF_LAST_SNAPSHOT_LON, lon.toFloat())
            .apply()
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

    private fun hasMovedSignificantly(newLat: Double, newLon: Double): Boolean {
        val prevLat = lastSnapshotLatitude ?: return true
        val prevLon = lastSnapshotLongitude ?: return true

        val results = FloatArray(1)
        android.location.Location.distanceBetween(prevLat, prevLon, newLat, newLon, results)
        return results[0] >= MIN_SNAPSHOT_DISTANCE_METERS
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

    private suspend fun storeWeatherSnapshot(deviceId: String, latitude: Double, longitude: Double, runPrediction: Boolean = true) {
        try {
            val nowMs = System.currentTimeMillis()
            val hourMs = (nowMs / MILLIS_PER_HOUR) * MILLIS_PER_HOUR  // round to current hour

            val db = WeatherDatabase.getInstance(this)

            // Fetch current weather from Open-Meteo
            val weatherData = fetchOpenMeteoWeather(latitude, longitude)

            if (weatherData == null) {
                Log.w(TAG, "Open-Meteo fetch failed (offline?) — running prediction from cached/forecast data")
                // Skip snapshot storage but still attempt prediction with existing Room data
                if (runPrediction) {
                    val featureService = FeatureAssemblyService(db)
                    val features = featureService.assembleFeatures(latitude, longitude)
                    if (features.isNotEmpty()) {
                        val predictor = OnDevicePredictor.getInstance(this)
                        val result = predictor.predict(features)
                        Log.d(TAG, "🤖 Offline prediction: prob=${result.stormProbability}, alert=${result.alertState}")

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

                        val resultType = if (result.alertState == 1) "storm" else "clear"
                        db.modelInstanceDao().upsert(
                            com.CMPS490.weathertracker.data.ModelInstanceEntity(
                                instanceId = UUID.randomUUID().toString(),
                                deviceId = deviceId,
                                version = result.modelVersion,
                                latitude = latitude,
                                longitude = longitude,
                                resultLevel = result.alertState,
                                resultType = resultType,
                                confidenceScore = result.stormProbability,
                                createdAt = nowMs,
                            )
                        )
                        db.modelInstanceDao().pruneOld(nowMs - 48 * MILLIS_PER_HOUR)
                    } else {
                        Log.w(TAG, "No cached/forecast data available for offline prediction")
                    }
                }
                return
            }

            val cacheId = deterministicCacheId(latitude, longitude, hourMs, false)
            val cacheEntity = WeatherCacheEntity(
                cacheId = cacheId,
                temp = weatherData.optDouble("temperature_2m").takeUnless { it.isNaN() },
                humidity = weatherData.optDouble("relative_humidity_2m").takeUnless { it.isNaN() },
                windSpeed = weatherData.optDouble("wind_speed_10m").takeUnless { it.isNaN() },
                windDirection = weatherData.optDouble("wind_direction_10m").takeUnless { it.isNaN() },
                precipitationAmount = weatherData.optDouble("precipitation").takeUnless { it.isNaN() },
                pressure = weatherData.optDouble("pressure_msl").takeUnless { it.isNaN() },
                recordedAt = hourMs,
                latitude = latitude,
                longitude = longitude,
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
            val snapshotId = deterministicSnapshotId(cacheId)
            db.offlineWeatherSnapshotDao().upsertSnapshot(
                OfflineWeatherSnapshotEntity(
                    weatherId = snapshotId,
                    deviceId = deviceId,
                    cacheId = cacheId,
                    syncedAt = null,
                    isCurrent = true,
                )
            )

            // Prune observations older than 48 h
            val cutoff48h = nowMs - 48 * MILLIS_PER_HOUR
            db.weatherCacheDao().pruneOlderThan(cutoff48h)
            db.offlineWeatherSnapshotDao().pruneOld(deviceId, cutoff48h)

            // Backfill any hours we missed while offline with actual observations
            backfillMissedObservations(deviceId, latitude, longitude, hourMs, db)

            // Seed 24h of historical weather on first run so predictor has history
            seedWeatherHistory(deviceId, latitude, longitude, db)

            // Run on-device prediction only when requested (hourly timer)
            if (runPrediction) {
                val featureService = FeatureAssemblyService(db)
                val features = featureService.assembleFeatures(latitude, longitude)
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

                    // Store model instance locally — sync worker will push to backend
                    val resultType = if (result.alertState == 1) "storm" else "clear"
                    db.modelInstanceDao().upsert(
                        com.CMPS490.weathertracker.data.ModelInstanceEntity(
                            instanceId = java.util.UUID.randomUUID().toString(),
                            deviceId = deviceId,
                            version = result.modelVersion,
                            latitude = latitude,
                            longitude = longitude,
                            resultLevel = result.alertState,
                            resultType = resultType,
                            confidenceScore = result.stormProbability,
                            createdAt = nowMs,
                        )
                    )
                    // Prune synced model instances older than 48h
                    db.modelInstanceDao().pruneOld(nowMs - 48 * MILLIS_PER_HOUR)
                }
            } else {
                Log.d(TAG, "📦 Snapshot stored (prediction skipped)")
            }

            // Mark snapshot time so hourly timer knows when we last ran (persist across restarts)
            lastSnapshotTimeMs = System.currentTimeMillis()
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putLong(PREF_LAST_SNAPSHOT_TIME, lastSnapshotTimeMs)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error storing weather snapshot", e)
        }
    }

    private fun fetchOpenMeteoWeather(latitude: Double, longitude: Double): JSONObject? {
        return try {
            // Also request hourly precipitation so we can use the current-hour value as a
            // fallback. Open-Meteo's current.precipitation is "sum of the preceding hour",
            // so it reads 0 whenever rain started within the current clock hour.
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&timezone=UTC&wind_speed_unit=kmh" +
                "&current=temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,pressure_msl,wind_speed_10m,wind_direction_10m" +
                "&hourly=precipitation" +
                "&forecast_days=1"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return null
            val elevation = json.optDouble("elevation")
            current.put("elevation", elevation)

            // If the preceding-hour aggregate is 0, check the current hour's hourly
            // forecast value which captures ongoing rain within the current hour.
            val currentPrecip = current.optDouble("precipitation", 0.0)
            if (currentPrecip == 0.0) {
                val hourly = json.optJSONObject("hourly")
                val times = hourly?.optJSONArray("time")
                val precips = hourly?.optJSONArray("precipitation")
                if (times != null && precips != null) {
                    val currentTime = current.optString("time") // "2024-01-15T14:00"
                    for (i in 0 until times.length()) {
                        if (times.optString(i) == currentTime) {
                            val hourlyPrecip = precips.optDouble(i, 0.0)
                            if (hourlyPrecip > 0.0) {
                                current.put("precipitation", hourlyPrecip)
                            }
                            break
                        }
                    }
                }
            }

            current
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo fetch error: ${e.message}")
            null
        }
    }

    /**
     * When back online, check for gaps in observation data (hours missed while offline)
     * and backfill them with actual weather from Open-Meteo, replacing forecast-only rows.
     * Only backfills gaps > 1 hour, up to 48 hours.
     */
    private suspend fun backfillMissedObservations(
        deviceId: String,
        latitude: Double,
        longitude: Double,
        currentHourMs: Long,
        db: WeatherDatabase,
    ) {
        try {
            val delta = 5.0 * 0.009
            val observations = db.weatherCacheDao().getObservationsNear(
                latMin = latitude - delta,
                latMax = latitude + delta,
                lonMin = longitude - delta,
                lonMax = longitude + delta,
                limit = 48,
            )
            if (observations.isEmpty()) return

            // Find the most recent observation BEFORE the one we just stored (currentHourMs)
            val previousObs = observations
                .filter { it.recordedAt < currentHourMs }
                .maxByOrNull { it.recordedAt }
                ?: return

            val gapHours = ((currentHourMs - previousObs.recordedAt) / MILLIS_PER_HOUR).toInt()
            if (gapHours <= 1) return  // no gap to fill

            val hoursToFetch = gapHours.coerceAtMost(48)
            Log.d(TAG, "🔄 Detected ${gapHours}h observation gap — backfilling $hoursToFetch hours from Open-Meteo")

            // Fetch hourly historical data from Open-Meteo for the gap period
            val startHourMs = previousObs.recordedAt + MILLIS_PER_HOUR
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val startDate = sdf.format(java.util.Date(startHourMs))
            val endDate = sdf.format(java.util.Date(currentHourMs))

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&timezone=UTC&wind_speed_unit=kmh" +
                "&start_date=$startDate&end_date=$endDate" +
                "&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,pressure_msl,wind_speed_10m,wind_direction_10m"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "🔄 Backfill fetch failed: ${response.code}")
                return
            }
            val body = response.body?.string() ?: return
            val json = JSONObject(body)
            val hourly = json.optJSONObject("hourly") ?: return
            val elevation = json.optDouble("elevation")

            val times = hourly.optJSONArray("time") ?: return
            val temps = hourly.optJSONArray("temperature_2m")
            val humidities = hourly.optJSONArray("relative_humidity_2m")
            val dewPoints = hourly.optJSONArray("dew_point_2m")
            val precips = hourly.optJSONArray("precipitation")
            val pressures = hourly.optJSONArray("pressure_msl")
            val windSpeeds = hourly.optJSONArray("wind_speed_10m")
            val windDirs = hourly.optJSONArray("wind_direction_10m")

            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

            val entities = mutableListOf<WeatherCacheEntity>()
            val snapshots = mutableListOf<OfflineWeatherSnapshotEntity>()

            for (i in 0 until times.length()) {
                val timeStr = times.optString(i) ?: continue
                val rowMs = isoFormat.parse(timeStr)?.time ?: continue
                // Only backfill hours in the gap (after last obs, before current hour)
                if (rowMs <= previousObs.recordedAt || rowMs >= currentHourMs) continue

                val cacheId = deterministicCacheId(latitude, longitude, rowMs, false)
                entities.add(
                    WeatherCacheEntity(
                        cacheId = cacheId,
                        temp = temps?.optDouble(i)?.takeUnless { it.isNaN() },
                        humidity = humidities?.optDouble(i)?.takeUnless { it.isNaN() },
                        windSpeed = windSpeeds?.optDouble(i)?.takeUnless { it.isNaN() },
                        windDirection = windDirs?.optDouble(i)?.takeUnless { it.isNaN() },
                        precipitationAmount = precips?.optDouble(i)?.takeUnless { it.isNaN() },
                        pressure = pressures?.optDouble(i)?.takeUnless { it.isNaN() },
                        recordedAt = rowMs,
                        latitude = latitude,
                        longitude = longitude,
                        isForecast = false,
                        dewPointC = dewPoints?.optDouble(i)?.takeUnless { it.isNaN() },
                        elevation = elevation.takeUnless { it.isNaN() },
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
                )
                snapshots.add(
                    OfflineWeatherSnapshotEntity(
                        weatherId = deterministicSnapshotId(cacheId),
                        deviceId = deviceId,
                        cacheId = cacheId,
                        syncedAt = null,
                        isCurrent = false,
                    )
                )
            }

            if (entities.isNotEmpty()) {
                db.weatherCacheDao().upsertAll(entities)
                for (snap in snapshots) {
                    db.offlineWeatherSnapshotDao().upsertSnapshot(snap)
                }
                Log.d(TAG, "🔄 Backfilled ${entities.size} actual observations for offline gap")
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔄 Backfill error: ${e.message}")
        }
    }

    /**
     * On first run, call the backend to fetch 24 h of historical weather
     * from Open-Meteo → Supabase → Room DB.  Skips if Room already has ≥ 12 rows.
     */
    private suspend fun seedWeatherHistory(
        deviceId: String,
        latitude: Double,
        longitude: Double,
        db: WeatherDatabase,
    ) {
        if (!hasSeededHistory.compareAndSet(false, true)) return

        // Check if Room DB already has enough observations
        val delta = 5.0 * 0.009
        val existing = db.weatherCacheDao().getObservationsNear(
            latMin = latitude - delta,
            latMax = latitude + delta,
            lonMin = longitude - delta,
            lonMax = longitude + delta,
        )
        if (existing.size >= 12) {
            Log.d(TAG, "⏭ Room DB already has ${existing.size} observations, skipping history seed")
            return
        }

        try {
            Log.d(TAG, "🌱 Seeding 24h weather history from backend...")

            val jsonBody = JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
            }
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://10.0.2.2:5000/devices/$deviceId/seed-weather-history")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "🌱 Seed request failed: ${response.code}")
                return
            }

            val body = response.body?.string() ?: return
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return

            val rows = json.optJSONArray("weather_rows") ?: return
            val entities = mutableListOf<WeatherCacheEntity>()
            val snapshots = mutableListOf<OfflineWeatherSnapshotEntity>()

            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val recordedAtMs = row.optLong("recorded_at_ms", 0L)
                if (recordedAtMs == 0L) continue

                val isForecast = row.optBoolean("is_forecast", false)
                val cacheId = row.optString("cache_id",
                    deterministicCacheId(latitude, longitude, recordedAtMs, isForecast))

                entities.add(
                    WeatherCacheEntity(
                        cacheId = cacheId,
                        temp = row.optDouble("temp").takeUnless { it.isNaN() },
                        humidity = row.optDouble("humidity").takeUnless { it.isNaN() },
                        windSpeed = row.optDouble("wind_speed").takeUnless { it.isNaN() },
                        windDirection = row.optDouble("wind_direction").takeUnless { it.isNaN() },
                        precipitationAmount = row.optDouble("precipitation_amount").takeUnless { it.isNaN() },
                        pressure = row.optDouble("pressure").takeUnless { it.isNaN() },
                        recordedAt = recordedAtMs,
                        latitude = latitude,
                        longitude = longitude,
                        isForecast = isForecast,
                        dewPointC = row.optDouble("dew_point_c").takeUnless { it.isNaN() },
                        elevation = row.optDouble("elevation").takeUnless { it.isNaN() },
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
                )

                snapshots.add(
                    OfflineWeatherSnapshotEntity(
                        weatherId = deterministicSnapshotId(cacheId),
                        deviceId = deviceId,
                        cacheId = cacheId,
                        // Mark as already synced — this data came FROM the backend,
                        // so SnapshotSyncWorker must not send it back again.
                        syncedAt = System.currentTimeMillis(),
                        isCurrent = false,
                    )
                )
            }

            if (entities.isNotEmpty()) {
                db.weatherCacheDao().upsertAll(entities)
                db.offlineWeatherSnapshotDao().upsertSnapshots(snapshots)
                val obsCount = entities.count { !it.isForecast }
                val fcCount = entities.count { it.isForecast }
                Log.d(TAG, "🌱 Seeded $obsCount observations + $fcCount forecasts into Room DB")
            }
            // Persist the seeded flag so service restarts don't re-seed
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_HAS_SEEDED_HISTORY, true)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "🌱 Weather history seed error", e)
            // Reset flag (memory + prefs) so the seed is retried next time
            hasSeededHistory.set(false)
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_HAS_SEEDED_HISTORY, false)
                .apply()
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
