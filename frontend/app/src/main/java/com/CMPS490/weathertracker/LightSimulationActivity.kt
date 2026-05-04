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
 * Injects 24 hours of light-rain weather data into Room DB, runs the on-device ML predictor,
 * and logs the result. Expected outcome: tier 1 (Light), no notification.
 *
 * Scenario: Lafayette, LA — weak frontal passage, light stratiform rain, moderate humidity.
 *
 * Usage:
 *   adb shell am start -n com.CMPS490.weathertracker/.LightSimulationActivity
 */
class LightSimulationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LightSimulation"

        // Lafayette, LA
        private const val LAT = 30.22
        private const val LON = -92.02

        private const val SIMULATION_SNAPSHOT_ID = "10000000-0000-0000-0000-000000000000"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "\uD83C\uDF26\uFE0F  LIGHT SIMULATION — Approaching Weak Front")
        Log.d(TAG, "══════════════════════════════════════════")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = WeatherDatabase.getInstance(this@LightSimulationActivity)

                val delta = 50 * 0.009
                db.weatherCacheDao().deleteNear(
                    latMin = LAT - delta, latMax = LAT + delta,
                    lonMin = LON - delta, lonMax = LON + delta,
                )
                Log.d(TAG, "✓ Cleared existing cache near ($LAT, $LON)")

                val entries = buildLightSnapshots()
                db.weatherCacheDao().upsertAll(entries)
                Log.d(TAG, "✓ Inserted ${entries.size} light-rain snapshots")
                uploadSentinelWeatherData(entries)

                val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
                if (features.isEmpty()) {
                    Log.e(TAG, "✗ Feature assembly returned empty")
                    return@launch
                }
                Log.d(TAG, "✓ Assembled ${features.size} features")

                val predictor = OnDevicePredictor.getInstance(this@LightSimulationActivity)
                val result = predictor.predict(features)

                Log.d(TAG, "══════════════════════════════════════════")
                Log.d(TAG, "\uD83C\uDF26\uFE0F  SIMULATION RESULT:")
                Log.d(TAG, "   Predicted dBZ: ${result.predictedDbz}")
                Log.d(TAG, "   Tier:          ${result.alertState} (${OnDevicePredictor.tierToName(result.alertState)})")
                Log.d(TAG, "   Probability:   ${result.stormProbability}")
                Log.d(TAG, "   Threshold:     ${result.threshold}")
                Log.d(TAG, "══════════════════════════════════════════")

                if (result.alertState >= 2) {
                    Log.w(TAG, "⚠ Unexpected alert fired for light simulation (tier=${result.alertState})")
                } else {
                    Log.d(TAG, "✓ Correctly produced no alert — tier ${result.alertState}")
                }

                sendModelInstance(result)

            } catch (e: Exception) {
                Log.e(TAG, "Light simulation failed", e)
            }
        }

        finish()
    }

    /**
     * 24 hours of approaching weak front with steady light rain:
     * - Pressure falling 1005 → 1002 hPa (slow approach)
     * - Humidity 72 %
     * - Winds building 15 → 20 km/h (southerly)
     * - Steady precip 1.0 mm/h throughout
     * - CAPE=600, LI=-2.5, PWAT=30 (moderate pre-frontal moisture)
     *
     * Calibrated against deployed ONNX model: ~25.5 dBZ [light], tier 1, no notification.
     */
    private fun buildLightSnapshots(): List<WeatherCacheEntity> {
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
            HourData(23, 21.0, 72.0, 1005.0, 15.0, 1.0, 14.5),
            HourData(22, 21.0, 72.0, 1004.9, 15.2, 1.0, 14.5),
            HourData(21, 21.0, 72.0, 1004.7, 15.4, 1.0, 14.5),
            HourData(20, 21.0, 72.0, 1004.6, 15.7, 1.0, 14.5),
            HourData(19, 21.0, 72.0, 1004.5, 15.9, 1.0, 14.5),
            HourData(18, 21.0, 72.0, 1004.3, 16.1, 1.0, 14.5),
            HourData(17, 21.0, 72.0, 1004.2, 16.3, 1.0, 14.5),
            HourData(16, 21.0, 72.0, 1004.1, 16.5, 1.0, 14.5),
            HourData(15, 21.0, 72.0, 1004.0, 16.7, 1.0, 14.5),
            HourData(14, 21.0, 72.0, 1003.8, 17.0, 1.0, 14.5),
            HourData(13, 21.0, 72.0, 1003.7, 17.2, 1.0, 14.5),
            HourData(12, 21.0, 72.0, 1003.6, 17.4, 1.0, 14.5),
            HourData(11, 21.0, 72.0, 1003.4, 17.6, 1.0, 14.5),
            HourData(10, 21.0, 72.0, 1003.3, 17.8, 1.0, 14.5),
            HourData( 9, 21.0, 72.0, 1003.2, 18.0, 1.0, 14.5),
            HourData( 8, 21.0, 72.0, 1003.0, 18.3, 1.0, 14.5),
            HourData( 7, 21.0, 72.0, 1002.9, 18.5, 1.0, 14.5),
            HourData( 6, 21.0, 72.0, 1002.8, 18.7, 1.0, 14.5),
            HourData( 5, 21.0, 72.0, 1002.7, 18.9, 1.0, 14.5),
            HourData( 4, 21.0, 72.0, 1002.5, 19.1, 1.0, 14.5),
            HourData( 3, 21.0, 72.0, 1002.4, 19.3, 1.0, 14.5),
            HourData( 2, 21.0, 72.0, 1002.3, 19.6, 1.0, 14.5),
            HourData( 1, 21.0, 72.0, 1002.1, 19.8, 1.0, 14.5),
            HourData( 0, 21.0, 72.0, 1002.0, 20.0, 1.0, 14.5),
        )

        return hourlyData.map { h ->
            WeatherCacheEntity(
                cacheId = UUID.randomUUID().toString(),
                temp = h.temp,
                humidity = h.humidity,
                windSpeed = h.wind,
                windDirection = 195.0,        // south-southwesterly — pre-frontal
                precipitationAmount = h.precip,
                pressure = h.pressure,
                recordedAt = baseHour - (h.hoursAgo * hourMs),
                latitude = LAT,
                longitude = LON,
                isForecast = false,
                dewPointC = h.dewPoint,
                elevation = 12.0,
                distToCoastKm = 155.0,
                nwpCapeF36Max = 600.0,        // moderate instability
                nwpCinF36Max = -20.0,         // weak cap
                nwpPwatF36Max = 30.0,         // moderate moisture
                nwpSrh03F36Max = 80.0,        // low-moderate helicity
                nwpLiF36Min = -2.5,           // mildly negative LI
                nwpLclF36Min = 800.0,         // low LCL (humid)
                nwpAvailableLeads = 4.0,
                mrmsMaxDbz75km = 25.0,        // light stratiform radar returns
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
                Log.d(TAG, "✓ Simulation sentinel updated with ${entries.size} light-rain rows")
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
            val deviceId = authService.getStoredDeviceId() ?: "light-sim"
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
