package com.CMPS490.weathertracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import com.CMPS490.weathertracker.network.BackendRetrofitInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Injects 24 hours of pre-computed Hurricane Katrina-like weather data into Room DB,
 * runs the on-device ML predictor, and fires a storm notification if triggered.
 *
 * Usage:
 *   adb shell am start -n com.CMPS490.weathertracker/.StormSimulationActivity
 *
 * This does NOT modify the model threshold. It uses real-world extreme weather
 * conditions modeled after Hurricane Katrina's approach to the Gulf Coast
 * (Aug 29, 2005, New Orleans area) to organically trigger a storm prediction.
 */
class StormSimulationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "StormSimulation"

        // New Orleans area — Katrina landfall
        private const val LAT = 29.95
        private const val LON = -90.07
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "🌀 STORM SIMULATION — Hurricane Katrina")
        Log.d(TAG, "══════════════════════════════════════════")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = WeatherDatabase.getInstance(this@StormSimulationActivity)

                // Clear existing cache near this location so our data dominates
                val delta = 50 * 0.009
                db.weatherCacheDao().deleteNear(
                    latMin = LAT - delta,
                    latMax = LAT + delta,
                    lonMin = LON - delta,
                    lonMax = LON + delta,
                )
                Log.d(TAG, "✓ Cleared existing cache near ($LAT, $LON)")

                // Insert 24 hours of Hurricane Katrina-like conditions
                val entries = buildKatrinaSnapshots()
                db.weatherCacheDao().upsertAll(entries)
                Log.d(TAG, "✓ Inserted ${entries.size} Katrina weather snapshots")

                // Assemble features and run prediction
                val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
                if (features.isEmpty()) {
                    Log.e(TAG, "✗ Feature assembly returned empty")
                    return@launch
                }
                Log.d(TAG, "✓ Assembled ${features.size} features")

                val predictor = OnDevicePredictor.getInstance(this@StormSimulationActivity)
                val result = predictor.predict(features)

                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "🌀 SIMULATION RESULT:")
                Log.d(TAG, "   Probability: ${result.stormProbability}")
                Log.d(TAG, "   Alert State: ${result.alertState}")
                Log.d(TAG, "   Threshold:   ${result.threshold}")
                Log.d(TAG, "   Model:       ${result.modelVersion}")
                Log.d(TAG, "══════════════════════════════════════════")

                if (result.alertState == 1) {
                    fireStormNotification(result.stormProbability)
                    Log.d(TAG, "🔔 STORM NOTIFICATION FIRED!")
                } else {
                    Log.w(TAG, "⚠ Model did NOT trigger alert — probability below threshold")
                }

                // Record model instance in Supabase
                sendModelInstance(result)

            } catch (e: Exception) {
                Log.e(TAG, "Storm simulation failed", e)
            }
        }

        // Finish immediately (API 36+ requirement)
        finish()
    }

    /**
     * Builds 24 hourly WeatherCacheEntity rows simulating Hurricane Katrina's
     * approach and landfall on the Gulf Coast.
     *
     * Data based on historical NWS/NOAA reports for Aug 28-29, 2005:
     * - Pressure dropped from ~990 hPa to ~920 hPa over 24h
     * - Sustained winds 80-200+ km/h
     * - Continuous heavy rainfall (10-30 mm/h)
     * - Near-100% humidity, saturated dew point
     * - Extreme CAPE (3000-4500 J/kg), very negative LI (-8 to -10)
     */
    private fun buildKatrinaSnapshots(): List<WeatherCacheEntity> {
        val now = System.currentTimeMillis()
        val hourMs = 3_600_000L

        // Set base time to 3 PM UTC in August (peak afternoon convection)
        // We'll make the last entry "now" and work backwards
        val baseHour = (now / hourMs) * hourMs

        // 24 hours of escalating hurricane conditions (oldest → newest)
        // Each row: temp, humidity, pressure, wind, precip, dewPoint
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
            // Outer bands arrive — conditions deteriorating
            HourData(23, 30.5, 88.0, 990.0,  45.0,  2.0, 28.0),
            HourData(22, 30.2, 89.0, 988.5,  48.0,  3.0, 28.0),
            HourData(21, 30.0, 90.0, 986.0,  52.0,  5.0, 28.5),
            HourData(20, 29.8, 91.0, 983.0,  56.0,  7.0, 28.5),
            HourData(19, 29.5, 92.0, 980.0,  60.0,  8.0, 28.5),
            HourData(18, 29.2, 93.0, 977.0,  65.0, 10.0, 28.5),
            // Eye wall bands — heavy rain, wind ramping
            HourData(17, 29.0, 94.0, 973.0,  72.0, 12.0, 28.5),
            HourData(16, 28.8, 95.0, 969.0,  78.0, 15.0, 28.0),
            HourData(15, 28.5, 95.0, 965.0,  85.0, 18.0, 28.0),
            HourData(14, 28.3, 96.0, 960.0,  92.0, 20.0, 27.8),
            HourData(13, 28.0, 96.0, 955.0, 100.0, 22.0, 27.5),
            HourData(12, 27.8, 97.0, 950.0, 110.0, 25.0, 27.5),
            // Core approach — extreme conditions
            HourData(11, 27.5, 97.0, 945.0, 120.0, 28.0, 27.2),
            HourData(10, 27.2, 98.0, 940.0, 130.0, 30.0, 27.0),
            HourData( 9, 27.0, 98.0, 935.0, 140.0, 32.0, 26.8),
            HourData( 8, 26.8, 98.0, 932.0, 148.0, 33.0, 26.5),
            HourData( 7, 26.5, 99.0, 928.0, 155.0, 35.0, 26.3),
            HourData( 6, 26.2, 99.0, 925.0, 160.0, 35.0, 26.0),
            // Landfall — peak intensity
            HourData( 5, 26.0, 99.0, 922.0, 170.0, 38.0, 25.8),
            HourData( 4, 25.8, 99.0, 920.0, 180.0, 40.0, 25.5),
            HourData( 3, 25.5, 99.0, 920.0, 185.0, 42.0, 25.3),
            // Post-landfall — still extreme but weakening slightly
            HourData( 2, 25.5, 99.0, 922.0, 175.0, 38.0, 25.3),
            HourData( 1, 25.8, 98.0, 925.0, 160.0, 35.0, 25.5),
            HourData( 0, 26.0, 98.0, 928.0, 150.0, 30.0, 25.8),
        )

        return hourlyData.map { h ->
            WeatherCacheEntity(
                cacheId = UUID.randomUUID().toString(),
                temp = h.temp,
                humidity = h.humidity,
                windSpeed = h.wind,
                windDirection = 180.0, // southerly (typical for hurricane approach)
                precipitationAmount = h.precip,
                pressure = h.pressure,
                recordedAt = baseHour - (h.hoursAgo * hourMs),
                latitude = LAT,
                longitude = LON,
                isForecast = false,
                dewPointC = h.dewPoint,
                elevation = 1.0,  // New Orleans — near sea level
                distToCoastKm = 15.0,  // very close to coast
                // Extreme NWP values — high instability
                nwpCapeF36Max = 4200.0,   // extreme CAPE
                nwpCinF36Max = -0.5,      // negligible CIN (no cap)
                nwpPwatF36Max = 65.0,     // very high precipitable water
                nwpSrh03F36Max = 350.0,   // high storm-relative helicity
                nwpLiF36Min = -9.5,       // deeply negative lifted index
                nwpLclF36Min = 300.0,     // low LCL (near surface condensation)
                nwpAvailableLeads = 6.0,  // all forecast leads available
                mrmsMaxDbz75km = null,
            )
        }
    }

    private fun sendModelInstance(result: PredictionResult) {
        try {
            val authService = AuthenticationService(this)
            val deviceId = authService.getStoredDeviceId() ?: "storm-sim"
            val resultType = if (result.alertState == 1) "storm" else "clear"
            val body = com.google.gson.JsonObject().apply {
                addProperty("version", "v1.0.0")
                addProperty("latitude", LAT)
                addProperty("longitude", LON)
                addProperty("result_level", result.alertState)
                addProperty("result_type", resultType)
                addProperty("confidence_score", result.stormProbability.toDouble())
            }
            val response = BackendRetrofitInstance.api.createModelInstance(deviceId, body).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "✓ model_instance recorded")
            } else {
                Log.w(TAG, "✗ model_instance failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ model_instance error: ${e.message}")
        }
    }

    private fun fireStormNotification(probability: Float) {
        val channelId = "storm_alert_channel"
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Storm Alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "On-device storm probability alerts" }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚠️ SEVERE STORM WARNING")
            .setContentText("Hurricane-force conditions: ${(probability * 100).toInt()}% storm probability")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(2001, notification)
    }
}
