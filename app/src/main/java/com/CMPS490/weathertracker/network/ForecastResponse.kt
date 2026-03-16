package com.CMPS490.weathertracker.network

data class ForecastResponse(
    val properties: ForecastProperties
)

data class ForecastProperties(
    val periods: List<ForecastPeriod>
)

data class ForecastPeriod(
    val name: String,
    val startTime: String,
    val isDaytime: Boolean,
    val temperature: Int,
    val temperatureUnit: String,
    val windSpeed: String,
    val shortForecast: String,
    val detailedForecast: String,
    val probabilityOfPrecipitation: QuantitativeValue?,
    val relativeHumidity: QuantitativeValue?
)

data class QuantitativeValue(
    val value: Double?
)