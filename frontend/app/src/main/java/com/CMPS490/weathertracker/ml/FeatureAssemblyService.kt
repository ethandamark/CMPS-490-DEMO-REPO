package com.CMPS490.weathertracker.ml

import android.util.Log
import com.CMPS490.weathertracker.data.WeatherCacheEntity
import com.CMPS490.weathertracker.data.WeatherDatabase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.pow
import java.util.Calendar
import java.util.TimeZone

/**
 * Ports the Python FeatureAssemblyService to Kotlin, using Room instead of Supabase REST.
 * Reads the last 24 observation rows for the device's current location and builds the
 * 26-feature vector expected by model_metadata.json.
 */
class FeatureAssemblyService(private val db: WeatherDatabase) {

    companion object {
        private const val TAG = "FeatureAssemblyService"

        // Gulf coast reference points for dist_to_coast_km
        private val GULF_COAST_POINTS = listOf(
            29.25 to -89.4,
            29.5 to -90.2,
            29.6 to -91.3,
            29.7 to -92.0,
            29.8 to -93.3,
        )

        private const val DEG_PER_KM = 0.009   // ~1 km in degrees (for bounding box)
        private const val HISTORY_RADIUS_KM = 5.0

        /**
         * Fallback dew point offset when actual dew point data is unavailable.
         * A 5 °C spread corresponds to roughly 70 % relative humidity — a reasonable
         * default for humid Gulf Coast conditions when no better data is available.
         */
        private const val DEWPOINT_FALLBACK_OFFSET_C = 5f
    }

    /**
     * Build the feature vector for the most recent snapshot at [latitude]/[longitude].
     * Prefers observations but falls back to forecast data when fewer than 6
     * observation rows are available (e.g. device has been offline).
     * Returns a Map<featureName, Float?> where null means the value is unavailable
     * (the predictor fills nulls with imputer medians).
     */
    suspend fun assembleFeatures(
        latitude: Double,
        longitude: Double,
    ): Map<String, Float?> {
        val delta = HISTORY_RADIUS_KM * DEG_PER_KM
        val observations = db.weatherCacheDao().getObservationsNear(
            latMin = latitude - delta,
            latMax = latitude + delta,
            lonMin = longitude - delta,
            lonMax = longitude + delta,
            limit = 48,
        )

        val history: List<WeatherCacheEntity>
        val dataSource: String

        if (observations.size >= 6) {
            // Enough observations — use them exclusively
            history = observations
            dataSource = "observations"
        } else {
            // Offline / insufficient observations — supplement with forecasts.
            // Pull forecasts covering the past 48 h through the next hour so we have
            // a coherent timeline: past observations + the nearest forecast at or
            // just after "now" acts as the current data point.
            val nowMs = System.currentTimeMillis()
            val hourMs = (nowMs / 3_600_000L) * 3_600_000L  // round down to current hour
            val forecasts = db.weatherCacheDao().getForecastsFrom(
                latMin = latitude - delta,
                latMax = latitude + delta,
                lonMin = longitude - delta,
                lonMax = longitude + delta,
                fromTime = nowMs - 48 * 3_600_000L,
            )
            // Merge: observations first, then forecasts, deduplicate by recordedAt.
            // Only include forecasts up to the current hour so the model doesn't
            // "see" future data as if it were the present.
            val cutoff = hourMs
            val seen = observations.map { it.recordedAt }.toSet()
            val usableForecasts = forecasts
                .filter { it.recordedAt !in seen && it.recordedAt <= cutoff }
            val merged = observations + usableForecasts
            history = merged.ifEmpty { observations }
            dataSource = if (usableForecasts.isNotEmpty()) "observations+forecasts" else "observations"
        }

        if (history.isEmpty()) {
            Log.w(TAG, "No weather history found near ($latitude, $longitude)")
            return emptyMap()
        }

        // Sort oldest → newest
        val sorted = history.sortedBy { it.recordedAt }
        val current = sorted.last()
        Log.d(TAG, "📊 Using ${sorted.size} rows ($dataSource) for prediction (oldest=${sorted.first().recordedAt}, newest=${current.recordedAt})")

        // Map DB column names to model feature names
        val tempC = current.temp?.toFloat()
        val dewPointC = current.dewPointC?.toFloat()
            ?: tempC?.let { it - DEWPOINT_FALLBACK_OFFSET_C }    // fallback: temp - offset
        val pressureHpa = current.pressure?.toFloat()
        val humidityPct = current.humidity?.toFloat()
        val windSpeedKmh = current.windSpeed?.toFloat()
        val precipMm = current.precipitationAmount?.toFloat() ?: 0f

        // History series
        val pressureValues = sorted.map { it.pressure?.toFloat() }
        val precipValues = sorted.map { it.precipitationAmount?.toFloat() ?: 0f }
        val windValues = sorted.map { it.windSpeed?.toFloat() }
        val tempValues = sorted.map { it.temp?.toFloat() }
        val dewValues = sorted.map { it.dewPointC?.toFloat() ?: it.temp?.toFloat()?.let { t -> t - DEWPOINT_FALLBACK_OFFSET_C } }

        val tempSpreadValues = tempValues.zip(dewValues).map { (t, d) ->
            if (t != null && d != null) t - d else null
        }

        // Derived features
        val precip6h = precipValues.takeLast(6).sumOf { it.toDouble() }.toFloat()
        val precip24h = precipValues.sumOf { it.toDouble() }.toFloat()
        val precipMax3h = precipValues.takeLast(3).maxOrNull() ?: 0f
        val precipRateChange = safeDiff(precipValues.lastOrNull(), nthPrevious(precipValues, 1))

        val pressureChange1h = safeDiff(pressureValues.lastOrNull(), nthPrevious(pressureValues, 1))
        val pressureChange3h = safeDiff(pressureValues.lastOrNull(), nthPrevious(pressureValues, 3))
        val pressureChange6h = safeDiff(pressureValues.lastOrNull(), nthPrevious(pressureValues, 6))
        val pressureChange12h = safeDiff(pressureValues.lastOrNull(), nthPrevious(pressureValues, 12))
        val pressureDropRate = pressureChange1h?.let { -it }

        val tempDewpointSpread = if (tempC != null && dewPointC != null) tempC - dewPointC else null
        val dewpointSpreadChange = safeDiff(
            tempSpreadValues.lastOrNull() ?: tempDewpointSpread,
            nthPrevious(tempSpreadValues, 3),
        )

        val windSpeedChange1h = safeDiff(windValues.lastOrNull(), nthPrevious(windValues, 1))
        val windSpeedChange3h = safeDiff(windValues.lastOrNull(), nthPrevious(windValues, 3))
        val windMax3h = windValues.takeLast(3).filterNotNull().maxOrNull()

        // Temporal features from recorded_at (epoch millis, UTC)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = current.recordedAt
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY).toFloat()
        val month = (cal.get(Calendar.MONTH) + 1).toFloat()
        val isAfternoon = if (hour in 14f..19f) 1f else 0f

        // Static features
        val elevation = current.elevation?.toFloat()
        val distToCoast = current.distToCoastKm?.toFloat()
            ?: distanceToGulfCoast(latitude, longitude).toFloat()

        val result = mapOf(
            "temp_c" to tempC,
            "pressure_hPa" to pressureHpa,
            "humidity_pct" to humidityPct,
            "wind_speed_kmh" to windSpeedKmh,
            "precip_mm" to precipMm,
            "precip_6h" to precip6h,
            "precip_24h" to precip24h,
            "precip_rate_change" to precipRateChange,
            "precip_max_3h" to precipMax3h,
            "pressure_change_1h" to pressureChange1h,
            "pressure_change_3h" to pressureChange3h,
            "pressure_change_6h" to pressureChange6h,
            "pressure_change_12h" to pressureChange12h,
            "pressure_drop_rate" to pressureDropRate,
            "temp_dewpoint_spread" to tempDewpointSpread,
            "dewpoint_spread_change" to dewpointSpreadChange,
            "wind_speed_change_1h" to windSpeedChange1h,
            "wind_speed_change_3h" to windSpeedChange3h,
            "wind_max_3h" to windMax3h,
            "hour" to hour,
            "month" to month,
            "is_afternoon" to isAfternoon,
            "latitude" to latitude.toFloat(),
            "longitude" to longitude.toFloat(),
            "elevation" to elevation,
            "dist_to_coast_km" to distToCoast,
            // NWP features (pass through from DB when available)
            "nwp_cape_f3_6_max" to current.nwpCapeF36Max?.toFloat(),
            "nwp_cin_f3_6_max" to current.nwpCinF36Max?.toFloat(),
            "nwp_pwat_f3_6_max" to current.nwpPwatF36Max?.toFloat(),
            "nwp_srh03_f3_6_max" to current.nwpSrh03F36Max?.toFloat(),
            "nwp_li_f3_6_min" to current.nwpLiF36Min?.toFloat(),
            "nwp_lcl_f3_6_min" to current.nwpLclF36Min?.toFloat(),
            "nwp_available_leads" to current.nwpAvailableLeads?.toFloat(),
        )

        // Log all feature values for debugging
        for ((name, value) in result) {
            Log.d(TAG, "  feature: $name = $value")
        }

        return result
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun safeDiff(current: Float?, previous: Float?): Float? {
        if (current == null || previous == null) return null
        return current - previous
    }

    private fun <T> nthPrevious(list: List<T?>, n: Int): T? {
        if (list.size <= n) return null
        return list[list.size - 1 - n]
    }

    private fun distanceToGulfCoast(lat: Double, lon: Double): Double =
        GULF_COAST_POINTS.minOf { (clat, clon) -> haversineKm(lat, lon, clat, clon) }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }
}
