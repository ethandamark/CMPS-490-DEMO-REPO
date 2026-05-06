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
import com.CMPS490.weathertracker.data.WeatherCacheEntity
import com.CMPS490.weathertracker.data.WeatherDatabase
import com.CMPS490.weathertracker.ml.FeatureAssemblyService
import com.CMPS490.weathertracker.ml.OnDevicePredictor
import com.CMPS490.weathertracker.ml.PredictionResult
import com.CMPS490.weathertracker.data.HourlyPredictionEntity
import com.CMPS490.weathertracker.data.ModelInstanceEntity
import com.CMPS490.weathertracker.network.BackendRetrofitInstance
import com.CMPS490.weathertracker.sync.ModelInstanceSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Injects 24 hours of moderate-storm weather data into Room DB, runs the on-device ML predictor,
 * and fires a notification. Expected outcome: tier 2 (Moderate), ~34.8 dBZ.
 *
 * Scenario: Lafayette, LA — approaching MCS with steadily falling pressure (1006→998 hPa),
 * building southerly winds (28→35 km/h), and onset rain (1.3→3.0 mm/h).
 * NWP: CAPE=1000 J/kg, LI=−6.0, PWAT=38 mm. Calibrated against deployed ONNX model.
 *
 * Usage:
 *   adb shell am start -n com.CMPS490.weathertracker/.ModerateSimulationActivity
 */
class ModerateSimulationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ModerateSimulation"

        // Lafayette, LA
        private const val LAT = 30.22
        private const val LON = -92.02

        private const val SIMULATION_SNAPSHOT_ID = "20000000-0000-0000-0000-000000000000"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "⛈\uFE0F  MODERATE SIMULATION — Approaching MCS")
        Log.d(TAG, "══════════════════════════════════════════")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = WeatherDatabase.getInstance(this@ModerateSimulationActivity)

                val delta = 50 * 0.009
                db.weatherCacheDao().deleteNear(
                    latMin = LAT - delta, latMax = LAT + delta,
                    lonMin = LON - delta, lonMax = LON + delta,
                )
                Log.d(TAG, "✓ Cleared existing cache near ($LAT, $LON)")

                val entries = buildModerateSnapshots()
                db.weatherCacheDao().upsertAll(entries)
                Log.d(TAG, "✓ Inserted ${entries.size} moderate-storm snapshots")
                uploadSentinelWeatherData(entries)

                val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
                if (features.isEmpty()) {
                    Log.e(TAG, "✗ Feature assembly returned empty")
                    return@launch
                }
                Log.d(TAG, "✓ Assembled ${features.size} features")

                val predictor = OnDevicePredictor.getInstance(this@ModerateSimulationActivity)
                val result = predictor.predict(features)

                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "⛈\uFE0F  SIMULATION RESULT:")
                Log.d(TAG, "   Predicted dBZ: ${result.predictedDbz}")
                Log.d(TAG, "   Tier:          ${result.alertState} (${OnDevicePredictor.tierToName(result.alertState)})")
                Log.d(TAG, "   Probability:   ${result.stormProbability}")
                Log.d(TAG, "   Threshold:     ${result.threshold}")
                Log.d(TAG, "══════════════════════════════════════════")

                if (result.alertState >= 2) {
                    fireStormNotification(result.stormProbability)
                    Log.d(TAG, "\uD83D\uDD14 MODERATE NOTIFICATION FIRED!")
                } else {
                    Log.w(TAG, "⚠ Model did NOT trigger alert — probability below threshold (tier=${result.alertState})")
                }

                // Update storm risk visual display in Room
                val predHourMs = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L
                db.hourlyPredictionDao().upsert(
                    HourlyPredictionEntity(
                        timestamp = predHourMs,
                        stormProbability = result.stormProbability,
                        alertState = result.alertState,
                        modelVersion = result.modelVersion,
                    )
                )

                sendModelInstance(result)

            } catch (e: Exception) {
                Log.e(TAG, "Moderate simulation failed", e)
            }
        }

        finish()
    }

    /**
     * 24 hours of approaching MCS conditions (empirically calibrated against the ONNX model):
     * - Pressure steadily falling 1006 → 998 hPa over 24 h (storm still approaching)
     * - Humidity 83 %, temp 24 °C throughout
     * - Winds building 28 → 35 km/h from south
     * - Light rain 1.3 mm/h building to 3.0 mm/h at onset
     * - Moderate CAPE ~1000, LI –6.0, PWAT 38 mm
     * Expected model output: ~34.8 dBZ → tier 2 (moderate), notification fires.
     */
    private fun buildModerateSnapshots(): List<WeatherCacheEntity> {
        val now = System.currentTimeMillis()
        val hourMs = 3_600_000L
        val baseHour = (now / hourMs) * hourMs

        data class HourData(
            val hoursAgo: Int,
            val temp: Double,
            val humidity: Double,
            val pressure: Double,
            val wind: Double,
            val precip: Double,
            val dewPoint: Double,
        )

        val hourlyData = listOf(
            // Approaching storm — steady pressure fall, rain building
            HourData(23, 24.0, 83.0, 1006.0, 28.0, 1.3, 20.9),
            HourData(22, 24.0, 83.0, 1005.7, 28.3, 1.3, 20.9),
            HourData(21, 24.0, 83.0, 1005.3, 28.6, 1.3, 20.9),
            HourData(20, 24.0, 83.0, 1005.0, 28.9, 1.3, 20.9),
            HourData(19, 24.0, 83.0, 1004.6, 29.2, 1.3, 20.9),
            HourData(18, 24.0, 83.0, 1004.3, 29.5, 1.3, 20.9),
            HourData(17, 24.0, 83.0, 1003.9, 29.8, 1.3, 20.9),
            HourData(16, 24.0, 83.0, 1003.6, 30.1, 1.3, 20.9),
            HourData(15, 24.0, 83.0, 1003.2, 30.4, 1.3, 20.9),
            HourData(14, 24.0, 83.0, 1002.9, 30.7, 1.3, 20.9),
            HourData(13, 24.0, 83.0, 1002.5, 31.0, 1.3, 20.9),
            HourData(12, 24.0, 83.0, 1002.2, 31.3, 1.3, 20.9),
            HourData(11, 24.0, 83.0, 1001.8, 31.7, 1.3, 20.9),
            HourData(10, 24.0, 83.0, 1001.5, 32.0, 1.3, 20.9),
            HourData( 9, 24.0, 83.0, 1001.1, 32.3, 1.3, 20.9),
            HourData( 8, 24.0, 83.0, 1000.8, 32.6, 1.3, 20.9),
            HourData( 7, 24.0, 83.0, 1000.4, 32.9, 1.3, 20.9),
            HourData( 6, 24.0, 83.0, 1000.1, 33.2, 1.3, 20.9),
            HourData( 5, 24.0, 83.0,  999.7, 33.5, 1.3, 20.9),
            HourData( 4, 24.0, 83.0,  999.4, 33.8, 1.3, 20.9),
            // Storm onset — rain intensifies
            HourData( 3, 24.0, 83.0,  999.0, 34.1, 3.0, 20.9),
            HourData( 2, 24.0, 83.0,  998.7, 34.4, 3.0, 20.9),
            HourData( 1, 24.0, 83.0,  998.3, 34.7, 3.0, 20.9),
            HourData( 0, 24.0, 83.0,  998.0, 35.0, 3.0, 20.9),
        )

        return hourlyData.map { h ->
            WeatherCacheEntity(
                cacheId = UUID.randomUUID().toString(),
                temp = h.temp,
                humidity = h.humidity,
                windSpeed = h.wind,
                windDirection = 195.0,        // southerly inflow ahead of approaching MCS
                precipitationAmount = h.precip,
                pressure = h.pressure,
                recordedAt = baseHour - (h.hoursAgo * hourMs),
                latitude = LAT,
                longitude = LON,
                isForecast = false,
                dewPointC = h.dewPoint,
                elevation = 12.0,
                distToCoastKm = 155.0,
                nwpCapeF36Max = 1000.0,       // moderate instability
                nwpCinF36Max = -5.0,          // moderate cap
                nwpPwatF36Max = 38.0,         // good moisture
                nwpSrh03F36Max = 150.0,       // moderate helicity
                nwpLiF36Min = -6.0,           // negative LI — convection likely
                nwpLclF36Min = 550.0,         // moderate LCL
                nwpAvailableLeads = 4.0,
                mrmsMaxDbz75km = 35.0,        // moderate radar returns ahead of system
            )
        }
    }

    private fun uploadSentinelWeatherData(entries: List<WeatherCacheEntity>) {
        try {
            val weatherArray = com.google.gson.JsonArray()
            for (e in entries) {
                weatherArray.add(com.google.gson.JsonObject().apply {
                    addProperty("cache_id", e.cacheId)
                    e.temp?.let { addProperty("temp", it) }
                    e.humidity?.let { addProperty("humidity", it) }
                    e.windSpeed?.let { addProperty("wind_speed", it) }
                    e.windDirection?.let { addProperty("wind_direction", it) }
                    e.precipitationAmount?.let { addProperty("precipitation_amount", it) }
                    e.pressure?.let { addProperty("pressure", it) }
                    addProperty("recorded_at_ms", e.recordedAt)
                    addProperty("latitude", e.latitude)
                    addProperty("longitude", e.longitude)
                    addProperty("is_forecast", e.isForecast)
                    e.dewPointC?.let { addProperty("dew_point_c", it) }
                    e.elevation?.let { addProperty("elevation", it) }
                    e.distToCoastKm?.let { addProperty("dist_to_coast_km", it) }
                    e.nwpCapeF36Max?.let { addProperty("nwp_cape_f36_max", it) }
                    e.nwpCinF36Max?.let { addProperty("nwp_cin_f36_max", it) }
                    e.nwpPwatF36Max?.let { addProperty("nwp_pwat_f36_max", it) }
                    e.nwpSrh03F36Max?.let { addProperty("nwp_srh03_f36_max", it) }
                    e.nwpLiF36Min?.let { addProperty("nwp_li_f36_min", it) }
                    e.nwpLclF36Min?.let { addProperty("nwp_lcl_f36_min", it) }
                })
            }
            val body = com.google.gson.JsonObject().apply {
                addProperty("weather_id", SIMULATION_SNAPSHOT_ID)
                add("weather_data", weatherArray)
            }
            val response = BackendRetrofitInstance.api.updateSimulationSentinel(body).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "✓ Simulation sentinel updated with ${entries.size} moderate-storm rows")
            } else {
                Log.w(TAG, "✗ Sentinel upload failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ Sentinel upload error: ${e.message}")
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
    }

    private suspend fun sendModelInstance(result: PredictionResult) {
        try {
            val authService = AuthenticationService(this)
            val deviceId = authService.getStoredDeviceId() ?: "moderate-sim"
            val resultType = OnDevicePredictor.tierToName(result.alertState)
            val db = WeatherDatabase.getInstance(this)
            db.modelInstanceDao().upsert(
                ModelInstanceEntity(
                    instanceId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    version = "v2.0.0",
                    latitude = LAT,
                    longitude = LON,
                    resultLevel = result.alertState,
                    resultType = resultType,
                    confidenceScore = result.stormProbability,
                    createdAt = System.currentTimeMillis(),
                    weatherId = SIMULATION_SNAPSHOT_ID,
                    predictedDbz = result.predictedDbz,
                )
            )
            Log.d(TAG, "✓ model_instance stored locally (tier=$resultType)")
            ModelInstanceSyncManager.triggerImmediateSync(this)
        } catch (e: Exception) {
            Log.w(TAG, "✗ model_instance error: ${e.message}")
        }
    }
}
