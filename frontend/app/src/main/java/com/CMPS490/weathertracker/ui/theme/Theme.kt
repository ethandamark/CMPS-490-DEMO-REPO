package com.CMPS490.weathertracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    val radarOverlayBottom: Color,
)

private val FallbackLightColors = lightColorScheme(
    primary = Color(0xFF1C658C),
    secondary = Color(0xFF51606F),
    tertiary = Color(0xFF7B5693),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFDEE3EB),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF171C22),
    onSurface = Color(0xFF171C22),
    onSurfaceVariant = Color(0xFF434A53),
    outline = Color(0xFF737B86),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val FallbackDarkColors = darkColorScheme(
    primary = Color(0xFF9CD0F7),
    secondary = Color(0xFFB8C8DA),
    tertiary = Color(0xFFE2B7FD),
    background = Color(0xFF101418),
    surface = Color(0xFF141A20),
    surfaceVariant = Color(0xFF40464D),
    onPrimary = Color(0xFF00344C),
    onSecondary = Color(0xFF213240),
    onTertiary = Color(0xFF492160),
    onBackground = Color(0xFFE2E8EF),
    onSurface = Color(0xFFE2E8EF),
    onSurfaceVariant = Color(0xFFC0C7D0),
    outline = Color(0xFF8A929B),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LocalWeatherPalette = staticCompositionLocalOf {
    WeatherAppPalette(
        cardBackground = Color.White,
        mutedText = Color.Gray,
        primaryText = Color.Black,
        secondaryText = Color.Black,
        outline = Color.Gray,
        locationCardBackground = Color.White,
        selectorBackground = Color.White,
        selectorContent = Color.Black,
        radarOverlayBottom = Color.Black,
    )
}

object WeatherTrackerThemeState {
    val palette: WeatherAppPalette
        @Composable get() = LocalWeatherPalette.current
}

@Composable
fun WeatherTrackerTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> FallbackDarkColors
        else -> FallbackLightColors
    }
    val palette = WeatherAppPalette(
        cardBackground = colorScheme.surface,
        mutedText = colorScheme.onSurfaceVariant,
        primaryText = colorScheme.onSurface,
        secondaryText = colorScheme.onSurface,
        outline = colorScheme.outline.copy(alpha = 0.7f),
        locationCardBackground = colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        selectorBackground = colorScheme.surfaceVariant.copy(alpha = 0.9f),
        selectorContent = colorScheme.onSurfaceVariant,
        radarOverlayBottom = colorScheme.scrim.copy(alpha = if (darkTheme) 0.62f else 0.28f),
    )

    androidx.compose.runtime.CompositionLocalProvider(LocalWeatherPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = androidx.compose.material3.Shapes(),
            content = content,
        )
    }
}
