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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
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
                val lat = DEFAULT_LAT
                val lon = DEFAULT_LON
                Log.d(TAG, "Fetching weather for ($lat, $lon)")

                val weatherData = fetchOpenMeteoWeather(lat, lon)
                if (weatherData == null) {
                    Log.e(TAG, "Open-Meteo fetch failed")
                    return@launch
                }

                val db = WeatherDatabase.getInstance(this@DebugPredictActivity)
                val nowMs = System.currentTimeMillis()
                val hourMs = (nowMs / 3_600_000L) * 3_600_000L

                val cacheEntity = WeatherCacheEntity(
                    cacheId = UUID.randomUUID().toString(),
                    temp = weatherData.optDouble("temperature_2m").takeUnless { it.isNaN() },
                    humidity = weatherData.optDouble("relative_humidity_2m").takeUnless { it.isNaN() },
                    windSpeed = weatherData.optDouble("wind_speed_10m").takeUnless { it.isNaN() },
                    windDirection = weatherData.optDouble("wind_direction_10m").takeUnless { it.isNaN() },
                    precipitationAmount = weatherData.optDouble("precipitation").takeUnless { it.isNaN() },
                    pressure = weatherData.optDouble("pressure_msl").takeUnless { it.isNaN() },
                    recordedAt = hourMs,
                    latitude = lat,
                    longitude = lon,
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
                Log.d(TAG, "✓ Weather cached: temp=${cacheEntity.temp}°C, humidity=${cacheEntity.humidity}%, pressure=${cacheEntity.pressure}hPa")

                val features = FeatureAssemblyService(db).assembleFeatures(lat, lon)
                if (features.isEmpty()) {
                    Log.w(TAG, "Feature assembly returned empty — no historical data")
                    return@launch
                }
                Log.d(TAG, "✓ Assembled ${features.size} features")

                val predictor = OnDevicePredictor.getInstance(this@DebugPredictActivity)
                val result = predictor.predict(features)
                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "🤖 PREDICTION RESULT:")
                Log.d(TAG, "   Probability: ${result.stormProbability}")
                Log.d(TAG, "   Alert State: ${result.alertState}")
                Log.d(TAG, "   Threshold:   ${result.threshold}")
                Log.d(TAG, "   Model:       ${result.modelVersion}")
                Log.d(TAG, "══════════════════════════════════════════")

                if (result.alertState == 1) {
                    fireStormNotification(result.stormProbability)
                }

                // Record model instance in Supabase via backend
                sendModelInstance(lat, lon, result)
            } catch (e: Exception) {
                Log.e(TAG, "Force-predict failed", e)
            }
        }

        // Finish immediately so onResume() doesn't crash on API 36+
        // The coroutine continues running independently on Dispatchers.IO
        finish()
    }

    private fun sendModelInstance(
        latitude: Double,
        longitude: Double,
        result: PredictionResult,
    ) {
        try {
            val authService = AuthenticationService(this)
            val deviceId = authService.getStoredDeviceId() ?: "debug-device"
            val resultType = if (result.alertState == 1) "storm" else "clear"
            val body = com.google.gson.JsonObject().apply {
                addProperty("version", "v1.0.0")
                addProperty("latitude", latitude)
                addProperty("longitude", longitude)
                addProperty("result_level", result.alertState)
                addProperty("result_type", resultType)
                addProperty("confidence_score", result.stormProbability.toDouble())
            }
            val call = BackendRetrofitInstance.api.createModelInstance(deviceId, body)
            val response = call.execute()
            if (response.isSuccessful) {
                Log.d(TAG, "✓ model_instance recorded: level=${result.alertState}, confidence=${result.stormProbability}")
            } else {
                Log.w(TAG, "✗ model_instance failed: ${response.code()} ${response.errorBody()?.string()?.take(200)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ model_instance error: ${e.message}")
        }
    }

    private fun fetchOpenMeteoWeather(latitude: Double, longitude: Double): JSONObject? {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&timezone=UTC&wind_speed_unit=kmh" +
                "&current=temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,pressure_msl,wind_speed_10m,wind_direction_10m" +
                "&forecast_days=1"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return null
            current.put("elevation", json.optDouble("elevation"))
            current
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo error: ${e.message}")
            null
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
            .setContentTitle("⚠️ Storm Risk Detected")
            .setContentText("On-device model: ${(probability * 100).toInt()}% storm probability")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(2001, notification)
        Log.d(TAG, "🔔 Storm notification fired! prob=$probability")
    }
}
