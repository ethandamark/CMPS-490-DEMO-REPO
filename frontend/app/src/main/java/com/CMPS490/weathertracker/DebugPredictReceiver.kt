package com.CMPS490.weathertracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.CMPS490.weathertracker.data.ForecastFetcher
import com.CMPS490.weathertracker.data.WeatherCacheEntity
import com.CMPS490.weathertracker.data.WeatherDatabase
import com.CMPS490.weathertracker.ml.FeatureAssemblyService
import com.CMPS490.weathertracker.ml.OnDevicePredictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Debug-only receiver to force an ML prediction cycle via ADB:
 *   adb shell am broadcast -a com.CMPS490.weathertracker.FORCE_PREDICT
 */
class DebugPredictReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugPredictReceiver"
        private const val DEFAULT_LAT = 30.2241
        private const val DEFAULT_LON = -92.0198
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "🔧 FORCE_PREDICT broadcast received")
        Log.d(TAG, "══════════════════════════════════════════")

        val pending = goAsync()
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

                val db = WeatherDatabase.getInstance(context)
                val nowMs = System.currentTimeMillis()
                val hourMs = (nowMs / 3_600_000L) * 3_600_000L
                val cacheId = ForecastFetcher.deterministicCacheId(lat, lon, hourMs, false)

                // Only upsert if the row doesn't already exist — same guard as DebugPredictActivity.
                // INSERT OR REPLACE deletes the old row first, which cascades to offline_weather_snapshot
                // and breaks the hourly prediction sync chain.
                val existingRow = db.weatherCacheDao().getByIds(listOf(cacheId)).firstOrNull()
                if (existingRow == null) {
                    val cacheEntity = WeatherCacheEntity(
                        cacheId = cacheId,
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
                        mrmsMaxDbz75km = weatherData.optDouble("mrms_max_dbz_75km").takeUnless { it.isNaN() },
                    )
                    db.weatherCacheDao().upsert(cacheEntity)
                    Log.d(TAG, "✓ Weather cached (temp=${cacheEntity.temp}, hum=${cacheEntity.humidity}, press=${cacheEntity.pressure})")
                } else {
                    Log.d(TAG, "✓ Weather already cached for this hour (preserving hourly loop snapshot)")
                }

                val features = FeatureAssemblyService(db).assembleFeatures(lat, lon)
                if (features.isEmpty()) {
                    Log.w(TAG, "Feature assembly returned empty — no historical data?")
                    return@launch
                }
                Log.d(TAG, "✓ Assembled ${features.size} features")

                val predictor = OnDevicePredictor.getInstance(context)
                val result = predictor.predict(features)
                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "🤖 Prediction: prob=${result.stormProbability}, alert=${result.alertState}, threshold=${result.threshold}")
                Log.d(TAG, "══════════════════════════════════════════")

                if (result.alertState >= 2) {
                    fireStormNotification(context, result.stormProbability)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Force-predict failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun fetchOpenMeteoWeather(latitude: Double, longitude: Double): JSONObject? {
        // Primary: route through backend proxy
        try {
            val url = com.CMPS490.weathertracker.network.BackendConfig.endpoint("/weather/current") +
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
            Log.d(TAG, "✓ Weather via direct Open-Meteo fallback")
            current
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo error: ${e.message}")
            null
        }
    }

    private fun fireStormNotification(context: Context, probability: Float) {
        val channelId = "storm_alert_channel_v2"
        val nm = context.getSystemService(NotificationManager::class.java)

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
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val tier = OnDevicePredictor.probabilityToTier(probability)
        val (notifTitle, notifText) = when (tier) {
            3    -> "🚨 Severe Storm Warning" to "Severe conditions detected — ${(probability * 100).toInt()}% storm intensity"
            else -> "⚠️ Storm Risk Detected" to "Moderate rain likely — ${(probability * 100).toInt()}% storm intensity"
        }
        val notification = NotificationCompat.Builder(context, channelId)
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
