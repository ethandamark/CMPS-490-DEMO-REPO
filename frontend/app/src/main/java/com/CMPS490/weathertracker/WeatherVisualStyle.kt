package com.CMPS490.weathertracker

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

@Immutable
data class WeatherVisualStyle(
    val gradientColors: List<Color>,
    val accentTint: Color,
    val icon: ImageVector,
    val overlayAlpha: Float,
)

@Composable
fun weatherVisualStyleFor(
    weather: CurrentWeatherUiModel,
    hasSevereAlert: Boolean,
): WeatherVisualStyle {
    val scheme = MaterialTheme.colorScheme
    val accent = if (hasSevereAlert) scheme.error else scheme.primary
    return when (weather.weatherType) {
        WeatherType.Sunny -> WeatherVisualStyle(
            gradientColors = listOf(scheme.primaryContainer, scheme.surfaceContainerHigh),
            accentTint = accent,
            icon = Icons.Default.Brightness5,
            overlayAlpha = 0.10f,
        )
        WeatherType.Rainy -> WeatherVisualStyle(
            gradientColors = listOf(scheme.secondaryContainer, scheme.surfaceContainer),
            accentTint = accent,
            icon = Icons.Default.Water,
            overlayAlpha = 0.08f,
        )
        WeatherType.Stormy -> WeatherVisualStyle(
            gradientColors = listOf(scheme.errorContainer, scheme.surfaceContainerHigh),
            accentTint = scheme.error,
            icon = Icons.Default.Warning,
            overlayAlpha = 0.12f,
        )
        WeatherType.Cloudy -> WeatherVisualStyle(
            gradientColors = listOf(scheme.surfaceVariant, scheme.surfaceContainerHigh),
            accentTint = accent,
            icon = Icons.Default.Cloud,
            overlayAlpha = 0.06f,
        )
        WeatherType.PartlyCloudy -> WeatherVisualStyle(
            gradientColors = listOf(scheme.tertiaryContainer, scheme.surfaceContainerHigh),
            accentTint = accent,
            icon = Icons.Default.CloudQueue,
            overlayAlpha = 0.07f,
        )
    }
}
