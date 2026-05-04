package com.CMPS490.weathertracker

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
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
 * Injects 24 hours of clear-sky weather data into Room DB, runs the on-device ML predictor,
 * and logs the result. Expected outcome: tier 0 (Clear), no notification.
 *
 * Scenario: Lafayette, LA in late autumn — high pressure, dry air, light winds, no precip.
 *
 * Usage:
 *   adb shell am start -n com.CMPS490.weathertracker/.ClearSimulationActivity
 */
class ClearSimulationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ClearSimulation"

        // Lafayette, LA
        private const val LAT = 30.22
        private const val LON = -92.02

        private const val SIMULATION_SNAPSHOT_ID = "00000000-0000-0000-0000-000000000000"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "☀\uFE0F  CLEAR SIMULATION — Dry High-Pressure Day")
        Log.d(TAG, "══════════════════════════════════════════")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = WeatherDatabase.getInstance(this@ClearSimulationActivity)

                val delta = 50 * 0.009
                db.weatherCacheDao().deleteNear(
                    latMin = LAT - delta, latMax = LAT + delta,
                    lonMin = LON - delta, lonMax = LON + delta,
                )
                Log.d(TAG, "✓ Cleared existing cache near ($LAT, $LON)")

                val entries = buildClearSnapshots()
                db.weatherCacheDao().upsertAll(entries)
                Log.d(TAG, "✓ Inserted ${entries.size} clear-sky snapshots")
                uploadSentinelWeatherData(entries)

                val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
                if (features.isEmpty()) {
                    Log.e(TAG, "✗ Feature assembly returned empty")
                    return@launch
                }
                Log.d(TAG, "✓ Assembled ${features.size} features")

                val predictor = OnDevicePredictor.getInstance(this@ClearSimulationActivity)
                val result = predictor.predict(features)

                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "☀\uFE0F  SIMULATION RESULT:")
                Log.d(TAG, "   Predicted dBZ: ${result.predictedDbz}")
                Log.d(TAG, "   Tier:          ${result.alertState} (${OnDevicePredictor.tierToName(result.alertState)})")
                Log.d(TAG, "   Probability:   ${result.stormProbability}")
                Log.d(TAG, "   Threshold:     ${result.threshold}")
                Log.d(TAG, "══════════════════════════════════════════")

                if (result.alertState >= 2) {
                    Log.w(TAG, "⚠ Unexpected alert fired for clear simulation (tier=${result.alertState})")
                } else {
                    Log.d(TAG, "✓ Correctly produced no alert — tier ${result.alertState}")
                }

                sendModelInstance(result)

            } catch (e: Exception) {
                Log.e(TAG, "Clear simulation failed", e)
            }
        }

        finish()
    }

    /**
     * 24 hours of classic clear high-pressure conditions:
     * - Pressure stable ~1022–1025 hPa
     * - Humidity 35–45 %
     * - Winds calm 5–10 km/h
     * - Zero precipitation
     * - Very low CAPE, high CIN, positive Lifted Index
     */
    private fun buildClearSnapshots(): List<WeatherCacheEntity> {
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
            HourData(23, 14.0, 42.0, 1022.0,  6.0, 0.0,  2.0),
            HourData(22, 13.5, 41.0, 1022.5,  5.5, 0.0,  1.5),
            HourData(21, 13.0, 40.0, 1023.0,  5.0, 0.0,  1.0),
            HourData(20, 12.5, 39.0, 1023.0,  5.0, 0.0,  0.5),
            HourData(19, 12.0, 38.0, 1023.5,  5.0, 0.0,  0.0),
            HourData(18, 11.5, 37.0, 1024.0,  5.0, 0.0, -0.5),
            HourData(17, 11.0, 36.0, 1024.0,  5.5, 0.0, -1.0),
            HourData(16, 11.5, 36.0, 1024.5,  6.0, 0.0, -1.0),
            HourData(15, 13.0, 37.0, 1025.0,  6.5, 0.0, -0.5),
            HourData(14, 15.5, 38.0, 1025.0,  7.0, 0.0,  0.5),
            HourData(13, 18.0, 39.0, 1024.5,  8.0, 0.0,  2.0),
            HourData(12, 20.0, 40.0, 1024.0,  9.0, 0.0,  4.0),
            HourData(11, 21.5, 41.0, 1023.5,  9.5, 0.0,  5.5),
            HourData(10, 22.5, 42.0, 1023.0, 10.0, 0.0,  6.5),
            HourData( 9, 23.0, 42.0, 1023.0, 10.0, 0.0,  7.0),
            HourData( 8, 23.0, 43.0, 1022.5, 10.0, 0.0,  7.5),
            HourData( 7, 22.5, 43.0, 1022.5,  9.5, 0.0,  7.0),
            HourData( 6, 21.5, 42.0, 1023.0,  9.0, 0.0,  6.0),
            HourData( 5, 20.0, 41.0, 1023.0,  8.0, 0.0,  4.5),
            HourData( 4, 18.0, 40.0, 1023.5,  7.0, 0.0,  2.5),
            HourData( 3, 16.5, 39.0, 1024.0,  6.5, 0.0,  1.0),
            HourData( 2, 15.5, 38.0, 1024.0,  6.0, 0.0,  0.0),
            HourData( 1, 14.5, 38.0, 1024.5,  5.5, 0.0, -0.5),
            HourData( 0, 14.0, 38.0, 1024.5,  5.5, 0.0, -0.5),
        )

        return hourlyData.map { h ->
            WeatherCacheEntity(
                cacheId = UUID.randomUUID().toString(),
                temp = h.temp,
                humidity = h.humidity,
                windSpeed = h.wind,
                windDirection = 330.0,        // northerly — typical dry airmass
                precipitationAmount = h.precip,
                pressure = h.pressure,
                recordedAt = baseHour - (h.hoursAgo * hourMs),
                latitude = LAT,
                longitude = LON,
                isForecast = false,
                dewPointC = h.dewPoint,
                elevation = 12.0,
                distToCoastKm = 155.0,
                nwpCapeF36Max = 20.0,         // negligible instability
                nwpCinF36Max = -180.0,        // strong capping inversion
                nwpPwatF36Max = 12.0,         // very dry atmosphere
                nwpSrh03F36Max = 15.0,        // minimal helicity
                nwpLiF36Min = 4.5,            // positive LI — stable
                nwpLclF36Min = 2000.0,        // very high LCL — no clouds
                nwpAvailableLeads = 4.0,
                mrmsMaxDbz75km = 0.0,
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
                Log.d(TAG, "✓ Simulation sentinel updated with ${entries.size} clear-sky rows")
            } else {
                Log.w(TAG, "✗ Sentinel upload failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ Sentinel upload error: ${e.message}")
        }
    }

    private fun sendModelInstance(result: PredictionResult) {
        try {
            val authService = AuthenticationService(this)
            val deviceId = authService.getStoredDeviceId() ?: "clear-sim"
            val resultType = OnDevicePredictor.tierToName(result.alertState)
            val body = com.google.gson.JsonObject().apply {
                addProperty("version", "v1.0.0")
                addProperty("latitude", LAT)
                addProperty("longitude", LON)
                addProperty("result_level", result.alertState)
                addProperty("result_type", resultType)
                addProperty("confidence_score", result.stormProbability.toDouble())
                addProperty("predicted_dbz", result.predictedDbz.toDouble())
                addProperty("weather_id", SIMULATION_SNAPSHOT_ID)
            }
            val response = BackendRetrofitInstance.api.createModelInstance(deviceId, body).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "✓ model_instance recorded (tier=$resultType)")
            } else {
                Log.w(TAG, "✗ model_instance failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "✗ model_instance error: ${e.message}")
        }
    }
}
