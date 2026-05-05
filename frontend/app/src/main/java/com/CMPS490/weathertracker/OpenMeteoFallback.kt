package com.CMPS490.weathertracker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.CMPS490.weathertracker.network.BackendConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fallback weather provider using Open-Meteo API directly.
 * Used when the backend server is unavailable.
 */
object OpenMeteoFallback {
    private const val TAG = "OpenMeteoFallback"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class FallbackWeather(
        val current: CurrentWeatherUiModel,
        val forecast: List<DailyForecastUiModel>,
    )

    suspend fun fetch(
        latitude: Double,
        longitude: Double,
        locationLabel: String,
    ): FallbackWeather? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&timezone=auto&temperature_unit=fahrenheit&wind_speed_unit=mph" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,precipitation" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,wind_speed_10m_max,relative_humidity_2m_max" +
                "&forecast_days=7"

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Open-Meteo HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")

            val tempF = current.optDouble("temperature_2m", 0.0).toInt()
            val weatherCode = current.optInt("weather_code", 0)
            val condition = wmoCondition(weatherCode)
            val weatherType = wmoWeatherType(weatherCode)
            val now = LocalDateTime.now()
            val isDaytime = now.hour in 6..19

            val highTemps = daily.getJSONArray("temperature_2m_max")
            val lowTemps = daily.getJSONArray("temperature_2m_min")

            val currentWeather = CurrentWeatherUiModel(
                location = locationLabel,
                dayDate = "${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}, " +
                    now.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US)),
                temperature = tempF,
                condition = condition,
                highTemp = highTemps.optDouble(0, tempF.toDouble()).toInt(),
                lowTemp = lowTemps.optDouble(0, (tempF - 8).toDouble()).toInt(),
                weatherType = weatherType,
                isDaytime = isDaytime,
                precipitationChance = daily.getJSONArray("precipitation_probability_max")
                    .optInt(0, 0),
            )

            val times = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val precipProbs = daily.getJSONArray("precipitation_probability_max")
            val winds = daily.getJSONArray("wind_speed_10m_max")
            val humidities = daily.getJSONArray("relative_humidity_2m_max")
            val today = LocalDate.now()

            val forecastList = (0 until times.length()).map { i ->
                val date = LocalDate.parse(times.getString(i))
                val code = codes.optInt(i, 0)
                val high = highTemps.optDouble(i, 0.0).toInt()
                val low = lowTemps.optDouble(i, 0.0).toInt()
                val wind = winds.optDouble(i, 0.0).toInt()

                DailyForecastUiModel(
                    dayLabel = if (date == today) "Today"
                    else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US),
                    dateLabel = date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)),
                    weatherType = wmoWeatherType(code),
                    highTemp = high,
                    lowTemp = low,
                    precipitationChance = precipProbs.optInt(i, 0),
                    humidity = humidities.optInt(i, 0),
                    windText = "$wind mph",
                    feelsLike = high,
                    isToday = date == today,
                )
            }

            Log.d(TAG, "✓ Fallback weather loaded: ${tempF}°F, $condition")
            FallbackWeather(current = currentWeather, forecast = forecastList)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback fetch failed: ${e.message}", e)
            null
        }
    }

    suspend fun checkBackendHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(BackendConfig.endpoint("/health"))
                .build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun wmoCondition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow Grains"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }

    private fun wmoWeatherType(code: Int): WeatherType = when (code) {
        0, 1 -> WeatherType.Sunny
        2 -> WeatherType.PartlyCloudy
        3, 45, 48, 71, 73, 75, 77, 85, 86 -> WeatherType.Cloudy
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherType.Rainy
        95, 96, 99 -> WeatherType.Stormy
        else -> WeatherType.PartlyCloudy
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NWP Fetcher — mirrors backend `_add_nwp_aggregates` for the direct fallback
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fetches NWP hourly variables from Open-Meteo and computes the 3–6 h window
 * aggregates expected by the on-device ONNX model. Used by the direct fallback
 * path so NWP features are available even when the backend is unreachable.
 * All operations are synchronous and must be called from a background thread.
 */
object NwpFetcher {

    private const val TAG = "NwpFetcher"

    data class Aggregates(
        val capeMax: Double? = null,
        val cinMax: Double? = null,
        val pwatMax: Double? = null,
        val srh03Max: Double? = null,
        val liMin: Double? = null,
        val lclMin: Double? = null,
        val availableLeads: Double = 0.0,
    )

    /**
     * Synchronously fetches NWP aggregates for [latitude]/[longitude].
     * [currentTimeIso] is the ISO-8601 hour string from the Open-Meteo current
     * response (e.g. "2026-05-04T15:00") used to locate the 3–6 h forecast window.
     * Never throws — failures are logged and return an all-null [Aggregates].
     */
    fun fetchAggregates(
        client: OkHttpClient,
        latitude: Double,
        longitude: Double,
        currentTimeIso: String,
    ): Aggregates {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&timezone=UTC" +
                "&hourly=cape,convective_inhibition,precipitable_water," +
                "storm_relative_helicity_0_to_3km,lifted_index,cloud_base" +
                "&forecast_days=1"

            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "NWP fetch failed: HTTP ${response.code}")
                return Aggregates()
            }

            val body = response.body?.string() ?: return Aggregates()
            val hourly = JSONObject(body).optJSONObject("hourly") ?: return Aggregates()
            val times = hourly.optJSONArray("time") ?: return Aggregates()

            // Locate the current hour in the times array
            var currentIndex = 0
            for (i in 0 until times.length()) {
                if (times.optString(i) == currentTimeIso) { currentIndex = i; break }
            }

            val start = minOf(currentIndex + 3, times.length())
            val end   = minOf(currentIndex + 7, times.length())

            fun finiteValues(key: String): List<Double> {
                val arr = hourly.optJSONArray(key) ?: return emptyList()
                return (start until end).mapNotNull { i ->
                    arr.optDouble(i).takeUnless { it.isNaN() || it.isInfinite() }
                }
            }

            val cape = finiteValues("cape")
            val cin  = finiteValues("convective_inhibition")
            val pwat = finiteValues("precipitable_water")
            val srh  = finiteValues("storm_relative_helicity_0_to_3km")
            val li   = finiteValues("lifted_index")
            val lcl  = finiteValues("cloud_base")
            val leads = listOf(cape.size, cin.size, pwat.size, srh.size, li.size, lcl.size)
                .minOrNull()?.toDouble() ?: 0.0

            Log.d(TAG, "\u2713 NWP aggregates fetched (leads=$leads)")
            Aggregates(
                capeMax        = cape.maxOrNull(),
                cinMax         = cin.maxOrNull(),
                pwatMax        = pwat.maxOrNull(),
                srh03Max       = srh.maxOrNull(),
                liMin          = li.minOrNull(),
                lclMin         = lcl.minOrNull(),
                availableLeads = leads,
            )
        } catch (e: Exception) {
            Log.w(TAG, "NWP fetch error: ${e.message}")
            Aggregates()
        }
    }
}
