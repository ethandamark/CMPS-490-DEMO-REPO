package com.CMPS490.weathertracker.ml

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.CMPS490.weathertracker.data.WeatherCacheEntity
import com.CMPS490.weathertracker.data.WeatherDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the on-device ML prediction pipeline.
 *
 * Exercises the full flow: synthetic weather data → Room DB →
 * FeatureAssemblyService → OnDevicePredictor (ONNX) → PredictionResult.
 *
 * Run on an emulator or device:
 *   ./gradlew connectedAndroidTest --tests "*.ml.OnDevicePredictorTest"
 */
@RunWith(AndroidJUnit4::class)
class OnDevicePredictorTest {

    private lateinit var db: WeatherDatabase
    private lateinit var predictor: OnDevicePredictor

    // Lafayette, LA – matches backend test coordinates
    private val LAT = 30.2241
    private val LON = -92.0198

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        predictor = OnDevicePredictor.getInstance(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Scenario tests ──────────────────────────────────────────────

    @Test
    fun clearDay_shouldNotTriggerAlert() = runBlocking {
        // 24 hours of stable, calm weather – no convective indicators
        val baseTime = System.currentTimeMillis()
        val rows = (0 until 24).map { i ->
            makeCacheRow(
                id = "clear_$i",
                recordedAt = baseTime - (23 - i) * 3_600_000L,
                temp = 25.0,
                humidity = 40.0,
                windSpeed = 8.0,
                precip = 0.0,
                pressure = 1018.0,
                dewPoint = 12.0,          // wide spread → dry air
                cape = 50.0,              // negligible instability
                cin = -200.0,             // strong cap
                pwat = 15.0,              // low moisture column
                srh = 10.0,              // low helicity
                li = 5.0,                // positive LI → stable
                lcl = 3000.0,            // high LCL → dry
                leads = 4.0,
                dbz = 10.0,
            )
        }
        db.weatherCacheDao().upsertAll(rows)

        val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
        assertFalse("Feature map should not be empty", features.isEmpty())

        val result = predictor.predict(features)
        println("[ClearDay] prob=${result.stormProbability}, alert=${result.alertState}, threshold=${result.threshold}")

        assertEquals("Clear day should NOT trigger alert", 0, result.alertState)
        assertTrue(
            "Storm probability (${result.stormProbability}) should be below threshold (${result.threshold})",
            result.stormProbability < result.threshold,
        )
    }

    @Test
    fun severeStorm_shouldTriggerAlert() = runBlocking {
        // 24 hours of deteriorating weather approaching a severe thunderstorm
        val baseTime = System.currentTimeMillis()
        val rows = (0 until 24).map { i ->
            val t = i / 23.0 // progression 0 → 1
            makeCacheRow(
                id = "storm_$i",
                recordedAt = baseTime - (23 - i) * 3_600_000L,
                temp = 28.0 + t * 6.0,            // 28 → 34 °C
                humidity = 70.0 + t * 28.0,        // 70 → 98 %
                windSpeed = 10.0 + t * 45.0,       // 10 → 55 km/h
                precip = t * 15.0,                 // 0 → 15 mm
                pressure = 1015.0 - t * 25.0,      // 1015 → 990 hPa
                dewPoint = 27.0 + t * 5.0,         // 27 → 32 °C (tight spread)
                cape = 3500.0,                     // extreme instability
                cin = -10.0,                       // practically no cap
                pwat = 55.0,                       // very high moisture
                srh = 350.0,                       // strong rotation signal
                li = -8.0,                         // deeply negative → very unstable
                lcl = 500.0,                       // low LCL → near saturation
                leads = 4.0,
                dbz = 55.0,                        // convective echoes
            )
        }
        db.weatherCacheDao().upsertAll(rows)

        val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
        assertFalse("Feature map should not be empty", features.isEmpty())

        val result = predictor.predict(features)
        println("[SevereStorm] prob=${result.stormProbability}, alert=${result.alertState}, threshold=${result.threshold}")

        assertEquals("Severe storm should trigger alert", 1, result.alertState)
        assertTrue(
            "Storm probability (${result.stormProbability}) should exceed threshold (${result.threshold})",
            result.stormProbability >= result.threshold,
        )
    }

    // ── Smoke / sanity tests ────────────────────────────────────────

    @Test
    fun onnxModel_loadsAndProducesValidProbability() = runBlocking {
        val row = makeCacheRow(
            id = "smoke_0",
            recordedAt = System.currentTimeMillis(),
            temp = 20.0, humidity = 50.0, windSpeed = 10.0,
            precip = 0.0, pressure = 1013.0, dewPoint = 10.0,
        )
        db.weatherCacheDao().upsert(row)

        val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
        assertFalse("Feature map should not be empty", features.isEmpty())

        val result = predictor.predict(features)
        assertTrue("Probability should be in [0,1]", result.stormProbability in 0f..1f)
        assertTrue("Threshold should be positive", result.threshold > 0f)
        assertTrue("Model version should not be blank", result.modelVersion.isNotBlank())
    }

    @Test
    fun featureAssembly_computesDerivedFeatures() = runBlocking {
        // Insert 6 hours of data with linearly changing pressure and wind
        val baseTime = System.currentTimeMillis()
        val rows = (0 until 6).map { i ->
            makeCacheRow(
                id = "derived_$i",
                recordedAt = baseTime - (5 - i) * 3_600_000L,
                temp = 20.0 + i,
                humidity = 60.0,
                windSpeed = 10.0 + i * 2.0,
                precip = if (i >= 4) 5.0 else 0.0,
                pressure = 1013.0 - i * 2.0,
                dewPoint = 15.0,
            )
        }
        db.weatherCacheDao().upsertAll(rows)

        val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)

        // Verify derived features are computed (not null)
        assertNotNull("pressure_change_1h", features["pressure_change_1h"])
        assertNotNull("pressure_change_3h", features["pressure_change_3h"])
        assertNotNull("wind_speed_change_1h", features["wind_speed_change_1h"])
        assertNotNull("precip_6h", features["precip_6h"])

        // Pressure is dropping → pressure_change should be negative
        val pc1h = features["pressure_change_1h"]!!
        assertTrue("Pressure should be dropping (change=$pc1h)", pc1h < 0)
    }

    @Test
    fun featureAssembly_emptyDb_returnsEmptyMap() = runBlocking {
        // No rows inserted – should gracefully return empty
        val features = FeatureAssemblyService(db).assembleFeatures(LAT, LON)
        assertTrue("Empty DB should produce empty feature map", features.isEmpty())
    }

    @Test
    fun predict_withAllNwpFeatures_returnsResult() = runBlocking {
        // Directly supply all 33 features (including NWP) to the predictor
        // This bypasses FeatureAssemblyService to verify the ONNX model handles them
        val allFeatures = mapOf<String, Float?>(
            "temp_c" to 30f,
            "pressure_hPa" to 995f,
            "humidity_pct" to 95f,
            "wind_speed_kmh" to 50f,
            "precip_mm" to 12f,
            "precip_6h" to 45f,
            "precip_24h" to 80f,
            "precip_rate_change" to 5f,
            "precip_max_3h" to 20f,
            "pressure_change_1h" to -4f,
            "pressure_change_3h" to -12f,
            "pressure_change_6h" to -18f,
            "pressure_change_12h" to -22f,
            "pressure_drop_rate" to 4f,
            "temp_dewpoint_spread" to 2f,
            "dewpoint_spread_change" to -3f,
            "wind_speed_change_1h" to 15f,
            "wind_speed_change_3h" to 30f,
            "wind_max_3h" to 55f,
            "hour" to 16f,
            "month" to 7f,
            "is_afternoon" to 1f,
            "latitude" to 30.22f,
            "longitude" to -92.02f,
            "elevation" to 10f,
            "dist_to_coast_km" to 150f,
            // NWP features
            "nwp_cape_f3_6_max" to 3500f,
            "nwp_cin_f3_6_max" to -10f,
            "nwp_pwat_f3_6_max" to 55f,
            "nwp_srh03_f3_6_max" to 350f,
            "nwp_li_f3_6_min" to -8f,
            "nwp_lcl_f3_6_min" to 500f,
            "nwp_available_leads" to 4f,
        )

        val result = predictor.predict(allFeatures)
        assertTrue("Probability should be in [0,1]", result.stormProbability in 0f..1f)
        println("[FullFeatures] prob=${result.stormProbability}, alert=${result.alertState}")
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun makeCacheRow(
        id: String,
        recordedAt: Long,
        temp: Double,
        humidity: Double,
        windSpeed: Double,
        precip: Double,
        pressure: Double,
        dewPoint: Double,
        cape: Double? = null,
        cin: Double? = null,
        pwat: Double? = null,
        srh: Double? = null,
        li: Double? = null,
        lcl: Double? = null,
        leads: Double? = null,
        dbz: Double? = null,
    ) = WeatherCacheEntity(
        cacheId = id,
        temp = temp,
        humidity = humidity,
        windSpeed = windSpeed,
        windDirection = 180.0,
        precipitationAmount = precip,
        pressure = pressure,
        recordedAt = recordedAt,
        latitude = LAT,
        longitude = LON,
        isForecast = false,
        dewPointC = dewPoint,
        elevation = 10.0,
        distToCoastKm = 150.0,
        nwpCapeF36Max = cape,
        nwpCinF36Max = cin,
        nwpPwatF36Max = pwat,
        nwpSrh03F36Max = srh,
        nwpLiF36Min = li,
        nwpLclF36Min = lcl,
        nwpAvailableLeads = leads,
        mrmsMaxDbz75km = dbz,
    )
}
