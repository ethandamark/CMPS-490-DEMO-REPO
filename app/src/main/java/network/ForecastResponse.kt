package com.example.weathermcpapp.network

data class ForecastResponse(
    val properties: ForecastProperties
)

data class ForecastProperties(
    val periods: List<Period>
)

data class Period(
    val name: String,
    val detailedForecast: String
)