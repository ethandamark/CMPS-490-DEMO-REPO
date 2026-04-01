package com.CMPS490.weathertracker.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode {
    Light,
    Dark,
    HighContrast
}

@Immutable
data class WeatherAppColors(
    val backgroundGradientTop: Color,
    val backgroundGradientBottom: Color,
    val cardBackground: Color,
    val cardBackgroundStrong: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val alert: Color,
    val outline: Color,
    val inputBorder: Color,
    val precipitation: Color,
    val mapOverlay: Color
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1557C0),
    secondary = Color(0xFF4D6FA9),
    tertiary = Color(0xFF0F3A79),
    background = Color(0xFFF3F7FF),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF10213A),
    onSurface = Color(0xFF10213A)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8CB8FF),
    secondary = Color(0xFFB7C7E6),
    tertiary = Color(0xFFE0EBFF),
    background = Color(0xFF071323),
    surface = Color(0xFF13233E),
    onPrimary = Color(0xFF04111F),
    onSecondary = Color(0xFF04111F),
    onBackground = Color.White,
    onSurface = Color.White
)

private val HighContrastColorScheme = darkColorScheme(
    primary = Color(0xFFFFFF00),
    secondary = Color(0xFF00E5FF),
    tertiary = Color.White,
    background = Color.Black,
    surface = Color(0xFF0D0D0D),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightAppColors = WeatherAppColors(
    backgroundGradientTop = Color(0xFFEAF2FF),
    backgroundGradientBottom = Color(0xFFBDD8FF),
    cardBackground = Color(0xFFFFFFFF).copy(alpha = 0.94f),
    cardBackgroundStrong = Color(0xFFDDEBFF),
    textPrimary = Color(0xFF10213A),
    textMuted = Color(0xFF5F7390),
    accent = Color(0xFF1557C0),
    alert = Color(0xFFB96A00),
    outline = Color(0x331557C0),
    inputBorder = Color(0xFF7FA6E5),
    precipitation = Color(0xFF0A84C6),
    mapOverlay = Color(0x660D1B2D)
)

private val DarkAppColors = WeatherAppColors(
    backgroundGradientTop = Color(0xFF0A1931),
    backgroundGradientBottom = Color(0xFF185ABD),
    cardBackground = Color(0xFF1E2A44).copy(alpha = 0.7f),
    cardBackgroundStrong = Color(0xFF243658),
    textPrimary = Color.White,
    textMuted = Color(0xFF9EADC8),
    accent = Color(0xFF8CB8FF),
    alert = Color(0xFFFFD700),
    outline = Color(0x40FFFFFF),
    inputBorder = Color.White,
    precipitation = Color(0xFF64B5F6),
    mapOverlay = Color(0x80000000)
)

private val HighContrastAppColors = WeatherAppColors(
    backgroundGradientTop = Color.Black,
    backgroundGradientBottom = Color(0xFF111111),
    cardBackground = Color(0xFF050505),
    cardBackgroundStrong = Color(0xFF111111),
    textPrimary = Color.White,
    textMuted = Color(0xFFFFFF00),
    accent = Color(0xFF00E5FF),
    alert = Color(0xFFFFFF00),
    outline = Color.White,
    inputBorder = Color(0xFF00E5FF),
    precipitation = Color(0xFF00E5FF),
    mapOverlay = Color(0xAA000000)
)

private val LocalWeatherAppColors = staticCompositionLocalOf { DarkAppColors }

object WeatherTheme {
    val colors: WeatherAppColors
        @Composable
        get() = LocalWeatherAppColors.current
}

@Composable
fun WeatherTrackerTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    content: @Composable () -> Unit
) {
    val colorScheme: ColorScheme
    val appColors: WeatherAppColors

    when (themeMode) {
        ThemeMode.Light -> {
            colorScheme = LightColorScheme
            appColors = LightAppColors
        }
        ThemeMode.Dark -> {
            colorScheme = DarkColorScheme
            appColors = DarkAppColors
        }
        ThemeMode.HighContrast -> {
            colorScheme = HighContrastColorScheme
            appColors = HighContrastAppColors
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalWeatherAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
