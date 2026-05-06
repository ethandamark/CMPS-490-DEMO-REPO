package com.CMPS490.weathertracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import com.CMPS490.weathertracker.data.ForecastFetcher
import com.CMPS490.weathertracker.data.HourlyPredictionEntity
import com.CMPS490.weathertracker.data.ModelInstanceEntity
import com.CMPS490.weathertracker.data.OfflineWeatherSnapshotEntity
import com.CMPS490.weathertracker.data.WeatherCacheEntity
import com.CMPS490.weathertracker.data.WeatherDatabase
import com.CMPS490.weathertracker.ml.FeatureAssemblyService
import com.CMPS490.weathertracker.ml.OnDevicePredictor
import com.CMPS490.weathertracker.network.BackendConfig
import com.CMPS490.weathertracker.sync.SnapshotSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Debug activity to force an ML prediction via ADB:
 *   adb shell am start -n com.CMPS490.weathertracker/.DebugPredictActivity
 *
 * Fetches live weather, stores in Room, runs ONNX prediction, logs result,
 * and fires a storm notification if alertState == 1. Finishes immediately.
 */
class DebugPredictActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DebugPredict"
        private const val DEFAULT_LAT = 30.2241
        private const val DEFAULT_LON = -92.0198
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "🔧 FORCE PREDICT triggered via Activity")
        Log.d(TAG, "══════════════════════════════════════════")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = WeatherDatabase.getInstance(this@DebugPredictActivity)
                val authService = AuthenticationService(this@DebugPredictActivity)
                val deviceId = authService.getStoredDeviceId() ?: "debug-device"

                val nowMs = System.currentTimeMillis()

                // ── Use real snapshots only — bypasses bounding-box contamination ───
                // offline_weather_snapshot is only ever written by LocationTrackingService
                // and seedHistoryIfNeeded. Simulation activities never insert here, so
                // this query is guaranteed to return uncontaminated real observation data.
                var realSnapshots = db.offlineWeatherSnapshotDao()
                    .getSnapshotsForDevice(deviceId, 48)

                if (realSnapshots.isEmpty()) {
                    // First run — seed 24h history from backend, then re-query.
                    Log.d(TAG, "No real snapshots found — seeding 24h history from backend...")
                    seedHistoryIfNeeded(deviceId, DEFAULT_LAT, DEFAULT_LON, db)
                    realSnapshots = db.offlineWeatherSnapshotDao()
                        .getSnapshotsForDevice(deviceId, 48)
                }

                if (realSnapshots.isEmpty()) {
                    Log.w(TAG, "No real snapshot data available after seeding — aborting")
                    return@launch
                }

                val mostRecentSnapshot = realSnapshots.first()
                val lat = mostRecentSnapshot.cache.latitude
                val lon = mostRecentSnapshot.cache.longitude
                val snapshotId = mostRecentSnapshot.snapshot.weatherId

                Log.d(TAG, "✓ Using ${realSnapshots.size} real snapshots for features " +
                    "(most recent: $snapshotId, recorded_at=${mostRecentSnapshot.cache.recordedAt})")

                // Fetch the exact WeatherCacheEntity rows linked to these snapshots.
                // Covers up to 48 rows = full 24h observation history + any seeded rows.
                val cacheIds = realSnapshots.map { it.snapshot.cacheId }
                val snapshotRows = db.weatherCacheDao().getByIds(cacheIds)
                Log.d(TAG, "✓ Fetched ${snapshotRows.size} cache rows for feature assembly")

                // ── Assemble features from exact snapshot rows (no bounding-box) ────
                val features = FeatureAssemblyService(db).assembleFeaturesFromRows(snapshotRows, lat, lon)
                if (features.isEmpty()) {
                    Log.w(TAG, "Feature assembly returned empty — no historical data")
                    return@launch
                }
                Log.d(TAG, "✓ Assembled ${features.size} features")

                // ── Run prediction ───────────────────────────────────────────────────
                val predictor = OnDevicePredictor.getInstance(this@DebugPredictActivity)
                val result = predictor.predict(features)
                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "🤖 PREDICTION RESULT:")
                Log.d(TAG, "   Probability: ${result.stormProbability}")
                Log.d(TAG, "   Alert State: ${result.alertState}")
                Log.d(TAG, "   Threshold:   ${result.threshold}")
                Log.d(TAG, "   Model:       ${result.modelVersion}")
                Log.d(TAG, "══════════════════════════════════════════")

                if (result.alertState >= 2) {
                    fireStormNotification(result.stormProbability)
                }

                // ── Update storm risk visual display ───────────────────────────────
                val predHourMs = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
                db.hourlyPredictionDao().upsert(
                    HourlyPredictionEntity(
                        timestamp = predHourMs,
                        stormProbability = result.stormProbability,
                        alertState = result.alertState,
                        modelVersion = result.modelVersion,
                    )
                )

                // ── Persist model instance for Supabase sync ─────────────────────────
                val resultType = OnDevicePredictor.tierToName(result.alertState)
                db.modelInstanceDao().upsert(
                    ModelInstanceEntity(
                        instanceId = java.util.UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        version = result.modelVersion,
                        latitude = lat,
                        longitude = lon,
                        resultLevel = result.alertState,
                        resultType = resultType,
                        confidenceScore = result.stormProbability,
                        createdAt = nowMs,
                        weatherId = snapshotId,
                        predictedDbz = result.predictedDbz,
                    )
                )
                Log.d(TAG, "✓ Model instance stored in Room (alertState=${result.alertState}, confidence=${result.stormProbability})")
                SnapshotSyncManager.triggerImmediateSync(this@DebugPredictActivity)
                Log.d(TAG, "✓ Sync triggered")
            } catch (e: Exception) {
                Log.e(TAG, "Force-predict failed", e)
            }
        }

        // Finish immediately so onResume() doesn't crash on API 36+
        // The coroutine continues running independently on Dispatchers.IO
        finish()
    }

    /**
     * Seeds Room DB with 24h of real observations + 7-day forecast from the backend
     * if the DB doesn't already have enough history near [lat]/[lon].
     * Mirrors LocationTrackingService.seedWeatherHistory — seeded rows are marked
     * syncedAt=now so SnapshotSyncWorker does not send them back to the backend.
     */
    private suspend fun seedHistoryIfNeeded(
        deviceId: String,
        lat: Double,
        lon: Double,
        db: WeatherDatabase,
    ) {
        // Check snapshot-linked rows specifically — simulation rows in weather_cache are
        // never inserted into offline_weather_snapshot and must not count as real history.
        val existingSnapshots = db.offlineWeatherSnapshotDao().getSnapshotsForDevice(deviceId, 48)
        if (existingSnapshots.size >= 12) {
            Log.d(TAG, "⏭ Room DB already has ${existingSnapshots.size} real snapshots, skipping seed")
            return
        }

        try {
            Log.d(TAG, "🌱 Seeding 24h weather history from backend...")
            val body = JSONObject().apply {
                put("latitude", lat)
                put("longitude", lon)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(BackendConfig.endpoint("/devices/$deviceId/seed-weather-history"))
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "🌱 Seed request failed: ${response.code}")
                return
            }

            val json = JSONObject(response.body?.string() ?: return)
            if (!json.optBoolean("success", false)) return
            val rows = json.optJSONArray("weather_rows") ?: return

            val entities = mutableListOf<WeatherCacheEntity>()
            val snapshots = mutableListOf<OfflineWeatherSnapshotEntity>()
            val nowMs = System.currentTimeMillis()

            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val recordedAtMs = row.optLong("recorded_at_ms", 0L)
                if (recordedAtMs == 0L) continue

                val isForecast = recordedAtMs > nowMs
                val cacheId = row.optString("cache_id",
                    ForecastFetcher.deterministicCacheId(lat, lon, recordedAtMs, isForecast))

                entities.add(WeatherCacheEntity(
                    cacheId = cacheId,
                    temp = row.optDouble("temp").takeUnless { it.isNaN() },
                    humidity = row.optDouble("humidity").takeUnless { it.isNaN() },
                    windSpeed = row.optDouble("wind_speed").takeUnless { it.isNaN() },
                    windDirection = row.optDouble("wind_direction").takeUnless { it.isNaN() },
                    precipitationAmount = row.optDouble("precipitation_amount").takeUnless { it.isNaN() },
                    pressure = row.optDouble("pressure").takeUnless { it.isNaN() },
                    recordedAt = recordedAtMs,
                    latitude = lat,
                    longitude = lon,
                    isForecast = isForecast,
                    dewPointC = row.optDouble("dew_point_c").takeUnless { it.isNaN() },
                    elevation = row.optDouble("elevation").takeUnless { it.isNaN() },
                    distToCoastKm = null,
                    nwpCapeF36Max = row.optDouble("nwp_cape_f3_6_max").takeUnless { it.isNaN() },
                    nwpCinF36Max = row.optDouble("nwp_cin_f3_6_max").takeUnless { it.isNaN() },
                    nwpPwatF36Max = row.optDouble("nwp_pwat_f3_6_max").takeUnless { it.isNaN() },
                    nwpSrh03F36Max = row.optDouble("nwp_srh03_f3_6_max").takeUnless { it.isNaN() },
                    nwpLiF36Min = row.optDouble("nwp_li_f3_6_min").takeUnless { it.isNaN() },
                    nwpLclF36Min = row.optDouble("nwp_lcl_f3_6_min").takeUnless { it.isNaN() },
                    nwpAvailableLeads = row.optDouble("nwp_available_leads").takeUnless { it.isNaN() },
                    mrmsMaxDbz75km = null,
                ))

                snapshots.add(OfflineWeatherSnapshotEntity(
                    weatherId = ForecastFetcher.deterministicSnapshotId(cacheId),
                    deviceId = deviceId,
                    cacheId = cacheId,
                    // Came FROM backend — mark synced so SyncWorker doesn't re-upload
                    syncedAt = nowMs,
                    isCurrent = false,
                ))
            }

            if (entities.isNotEmpty()) {
                db.weatherCacheDao().upsertAll(entities)
                db.offlineWeatherSnapshotDao().upsertSnapshots(snapshots)
                val obsCount = entities.count { !it.isForecast }
                val fcCount = entities.count { it.isForecast }
                Log.d(TAG, "🌱 Seeded $obsCount observations + $fcCount forecasts into Room DB")
            }
        } catch (e: Exception) {
            Log.w(TAG, "🌱 Seed failed: ${e.message}")
        }
    }

    private fun fetchOpenMeteoWeather(latitude: Double, longitude: Double): JSONObject? {
        // Primary: route through backend proxy
        try {
            val url = BackendConfig.endpoint("/weather/current") +
                "?lat=$latitude&lon=$longitude"
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    if (json.has("temperature_2m")) {
                        Log.d(TAG, "✓ Weather via backend proxy")
                        return json
                    }
                }
            }
            Log.d(TAG, "Backend weather proxy unavailable (${response.code}) — falling back to direct Open-Meteo")
        } catch (e: Exception) {
            Log.d(TAG, "Backend weather proxy unreachable (${e.message}) — falling back to direct Open-Meteo")
        }

        // Fallback: direct Open-Meteo
        return try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&timezone=UTC&wind_speed_unit=kmh" +
                "&current=temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,pressure_msl,wind_speed_10m,wind_direction_10m" +
                "&hourly=precipitation" +
                "&forecast_days=1"
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return null
            current.put("elevation", json.optDouble("elevation"))

            if (current.optDouble("precipitation", 0.0) == 0.0) {
                val hourly = json.optJSONObject("hourly")
                val times = hourly?.optJSONArray("time")
                val precips = hourly?.optJSONArray("precipitation")
                if (times != null && precips != null) {
                    val currentTime = current.optString("time")
                    for (i in 0 until times.length()) {
                        if (times.optString(i) == currentTime) {
                            val hourlyPrecip = precips.optDouble(i, 0.0)
                            if (hourlyPrecip > 0.0) current.put("precipitation", hourlyPrecip)
                            break
                        }
                    }
                }
            }
            // Attach NWP aggregates so the model gets the same features as via the backend proxy
            val nwp = NwpFetcher.fetchAggregates(httpClient, latitude, longitude, current.optString("time"))
            nwp.capeMax?.let  { current.put("nwp_cape_f3_6_max",  it) }
            nwp.cinMax?.let   { current.put("nwp_cin_f3_6_max",   it) }
            nwp.pwatMax?.let  { current.put("nwp_pwat_f3_6_max",  it) }
            nwp.srh03Max?.let { current.put("nwp_srh03_f3_6_max", it) }
            nwp.liMin?.let    { current.put("nwp_li_f3_6_min",    it) }
            nwp.lclMin?.let   { current.put("nwp_lcl_f3_6_min",   it) }
            current.put("nwp_available_leads", nwp.availableLeads)
            Log.d(TAG, "\u2713 Weather via direct Open-Meteo fallback (NWP leads=${nwp.availableLeads})")
            current
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo error: ${e.message}")
            null
        }
    }

    private fun fireStormNotification(probability: Float) {
        val channelId = "storm_alert_channel_v2"
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(channelId, "Storm Alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "On-device storm probability alerts"
                    setSound(soundUri, audioAttr)
                }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val tier = OnDevicePredictor.probabilityToTier(probability)
        val (notifTitle, notifText) = when (tier) {
            3    -> "🚨 Severe Storm Warning" to "Severe conditions detected — ${(probability * 100).toInt()}% storm intensity"
            else -> "⚠️ Storm Risk Detected" to "Moderate rain likely — ${(probability * 100).toInt()}% storm intensity"
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .build()

        nm.notify(2001, notification)
        Log.d(TAG, "🔔 Storm notification fired! prob=$probability")
    }
}
