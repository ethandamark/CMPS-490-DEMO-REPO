package com.CMPS490.weathertracker

import androidx.compose.runtime.Immutable

@Immutable
data class CurrentWeatherUiModel(
    val location: String,
    val dayDate: String,
    val temperature: Int,
    val condition: String,
    val highTemp: Int,
    val lowTemp: Int
)

@Immutable
data class WeatherAlertUiModel(
    val title: String,
    val description: String
)

@Immutable
data class DailyForecastUiModel(
    val dayLabel: String,
    val dateLabel: String,
    val weatherType: WeatherType,
    val highTemp: Int,
    val lowTemp: Int,
    val precipitationChance: Int,
    val uvIndex: String,
    val humidity: Int,
    val windText: String,
    val feelsLike: Int,
    val sunrise: String,
    val sunset: String,
    val isToday: Boolean = false
)

@Immutable
data class LocationOptionUiModel(
    val label: String,
    val latitude: Double?,
    val longitude: Double?,
    val useDeviceLocation: Boolean = false
)

enum class WeatherType {
    Sunny, Cloudy, Rainy, Stormy, PartlyCloudy
}
