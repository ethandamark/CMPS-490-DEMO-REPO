package com.CMPS490.weathertracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class AppThemeMode {
    Light,
    Dark,
    HighContrast
}

@Immutable
data class WeatherAppPalette(
    val cardBackground: Color,
    val mutedText: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val outline: Color,
    val locationCardBackground: Color,
    val selectorBackground: Color,
    val selectorContent: Color,
    val backgroundTopDay: Color,
    val backgroundTopNight: Color,
    val backgroundBottomDay: Color,
    val backgroundBottomNight: Color,
    val mistTint: Color,
    val sunGlow: Color,
    val rainTint: Color,
    val stormFlash: Color,
    val radarOverlayBottom: Color
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0A4A78),
    secondary = Color(0xFF436179),
    tertiary = Color(0xFF5C5B7C),
    background = Color(0xFFF3F7FB),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF112031),
    onSurface = Color(0xFF112031)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8EC5FF),
    secondary = Color(0xFFB5C9E3),
    tertiary = Color(0xFFFFD27A),
    background = Color(0xFF091225),
    surface = Color(0xFF15233B),
    onPrimary = Color(0xFF002641),
    onSecondary = Color(0xFF0E1F31),
    onTertiary = Color(0xFF3D2800),
    onBackground = Color(0xFFF4F7FF),
    onSurface = Color(0xFFF4F7FF)
)

private val HighContrastColorScheme = darkColorScheme(
    primary = Color(0xFFFFFF00),
    secondary = Color(0xFF00E5FF),
    tertiary = Color(0xFFFF7A00),
    background = Color.Black,
    surface = Color(0xFF101010),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightPalette = WeatherAppPalette(
    cardBackground = Color(0xE6FFFFFF),
    mutedText = Color(0xFF5B7288),
    primaryText = Color(0xFF112031),
    secondaryText = Color(0xFF2A4256),
    outline = Color(0xFFB4C8DA),
    locationCardBackground = Color(0xCCF8FBFF),
    selectorBackground = Color(0xFFD7E8F8),
    selectorContent = Color(0xFF153552),
    backgroundTopDay = Color(0xFFB8E1FF),
    backgroundTopNight = Color(0xFF6A87A9),
    backgroundBottomDay = Color(0xFFFFE39B),
    backgroundBottomNight = Color(0xFFD7E4F4),
    mistTint = Color(0x66EAF4FF),
    sunGlow = Color(0xFFFFC94D),
    rainTint = Color(0xFF2D7DD2),
    stormFlash = Color(0xFFFFF2A8),
    radarOverlayBottom = Color(0xB30D1420)
)

private val DarkPalette = WeatherAppPalette(
    cardBackground = Color(0xB31E2A44),
    mutedText = Color(0xFF9EADC8),
    primaryText = Color.White,
    secondaryText = Color(0xFFF8FAFF),
    outline = Color(0x66B8CBF5),
    locationCardBackground = Color(0xB31E2A44),
    selectorBackground = Color(0xFF22314D),
    selectorContent = Color.White,
    backgroundTopDay = Color(0xFF83CFFF),
    backgroundTopNight = Color(0xFF091225),
    backgroundBottomDay = Color(0xFFF7C65A),
    backgroundBottomNight = Color(0xFF122B59),
    mistTint = Color(0x66F4F7FB),
    sunGlow = Color(0xFFFFE082),
    rainTint = Color(0xFF8AC6FF),
    stormFlash = Color(0xFFFFF3B0),
    radarOverlayBottom = Color(0x80000000)
)

private val HighContrastPalette = WeatherAppPalette(
    cardBackground = Color(0xFF111111),
    mutedText = Color(0xFFFFFF00),
    primaryText = Color.White,
    secondaryText = Color.White,
    outline = Color.White,
    locationCardBackground = Color(0xFF111111),
    selectorBackground = Color.Black,
    selectorContent = Color.White,
    backgroundTopDay = Color.Black,
    backgroundTopNight = Color.Black,
    backgroundBottomDay = Color(0xFF1F1F1F),
    backgroundBottomNight = Color(0xFF1F1F1F),
    mistTint = Color(0x2200E5FF),
    sunGlow = Color(0xFFFFFF00),
    rainTint = Color(0xFF00E5FF),
    stormFlash = Color(0xFFFFFF00),
    radarOverlayBottom = Color(0xD9000000)
)

private val LocalWeatherPalette = staticCompositionLocalOf { DarkPalette }
private val LocalWeatherThemeMode = staticCompositionLocalOf { AppThemeMode.Dark }

object WeatherTrackerThemeState {
    val palette: WeatherAppPalette
        @Composable get() = LocalWeatherPalette.current

    val mode: AppThemeMode
        @Composable get() = LocalWeatherThemeMode.current
}

@Composable
fun WeatherTrackerTheme(
    themeMode: AppThemeMode = AppThemeMode.Dark,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.Light -> LightColorScheme
        AppThemeMode.Dark -> DarkColorScheme
        AppThemeMode.HighContrast -> HighContrastColorScheme
    }
    val palette = when (themeMode) {
        AppThemeMode.Light -> LightPalette
        AppThemeMode.Dark -> DarkPalette
        AppThemeMode.HighContrast -> HighContrastPalette
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalWeatherPalette provides palette,
        LocalWeatherThemeMode provides themeMode
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
