package com.CMPS490.weathertracker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.CMPS490.weathertracker.ui.theme.AppThemeMode
import com.CMPS490.weathertracker.ui.theme.WeatherTrackerTheme
import com.CMPS490.weathertracker.ui.theme.WeatherTrackerThemeState
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.maps.android.compose.*
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

// Storm probability threshold from model_metadata.json
private const val STORM_ALERT_THRESHOLD = 0.4901f
// Storm risk timeline bar height bounds (dp)
private const val MAX_BAR_HEIGHT_DP = 48f
private const val MIN_BAR_HEIGHT_DP = 4f
val NavyDark = Color(0xFF0A1931)
val NavyLight = Color(0xFF185ABD)
val CardBackground = Color(0xFF1E2A44).copy(alpha = 0.7f)
val MutedText = Color(0xFF9EADC8)
private val AlertGold = Color(0xFFFFD700)

@Composable
fun WeatherOverviewScreen(
    currentWeather: CurrentWeatherUiModel,
    alert: WeatherAlertUiModel?,
    forecast: List<DailyForecastUiModel>,
    userLocation: LatLng?,
    themeMode: AppThemeMode,
    locationOptions: List<LocationOptionUiModel>,
    selectedLocationOption: LocationOptionUiModel,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onLocationSelected: (LocationOptionUiModel) -> Unit,
    onLiveRadarClick: () -> Unit,
    stormRiskTimeline: List<Pair<Long, Float>> = emptyList(),
) {
    val palette = WeatherTrackerThemeState.palette
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        WeatherDynamicBackground(weather = currentWeather)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 60.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                LocationSelectorCard(
                    themeMode = themeMode,
                    locationOptions = locationOptions,
                    selectedLocationOption = selectedLocationOption,
                    onThemeModeChanged = onThemeModeChanged,
                    onLocationSelected = onLocationSelected
                )
            }

            item {
                WeatherHeaderSection(currentWeather)
            }

            if (alert != null) {
                item {
                    WeatherAlertCard(alert)
                }
            }

            item {
                LiveRadarCard(userLocation, onLiveRadarClick)
            }

            item {
                Text(
                    text = "7-DAY FORECAST",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.mutedText,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(forecast) { day ->
                ForecastRow(day)
            }

            item {
                RadarAttributionFooter()
            }

            if (stormRiskTimeline.isNotEmpty()) {
                item {
                    StormRiskTimelineCard(stormRiskTimeline)
                }
            }
        }
    }
}

@Composable
private fun WeatherDynamicBackground(weather: CurrentWeatherUiModel) {
    val palette = WeatherTrackerThemeState.palette
    val isHighContrast = WeatherTrackerThemeState.mode == AppThemeMode.HighContrast
    val transition = rememberInfiniteTransition(label = "weather_scene")
    val cloudShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cloud_shift"
    )
    val rainOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rain_offset"
    )
    val starPulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "star_pulse"
    )
    val hazeShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haze_shift"
    )

    val topColor = when {
        isHighContrast -> palette.backgroundTopNight
        weather.isDaytime && weather.weatherType == WeatherType.Stormy -> Color(0xFF324057)
        weather.isDaytime && weather.weatherType == WeatherType.Rainy -> Color(0xFF466887)
        weather.isDaytime && weather.weatherType == WeatherType.Cloudy -> Color(0xFF5B6E8A)
        weather.isDaytime && weather.weatherType == WeatherType.Sunny -> palette.backgroundTopDay
        weather.isDaytime -> Color(0xFF6DAEEA)
        weather.weatherType == WeatherType.Stormy -> Color(0xFF0D142A)
        weather.weatherType == WeatherType.Rainy -> Color(0xFF11233E)
        weather.weatherType == WeatherType.Cloudy -> Color(0xFF182744)
        else -> palette.backgroundTopNight
    }
    val bottomColor = when {
        isHighContrast -> palette.backgroundBottomNight
        weather.isDaytime && weather.weatherType == WeatherType.Sunny -> palette.backgroundBottomDay
        weather.isDaytime && weather.weatherType == WeatherType.PartlyCloudy -> Color(0xFF3D74C7)
        weather.isDaytime -> Color(0xFF234F9E)
        weather.weatherType == WeatherType.Rainy -> Color(0xFF163A69)
        weather.weatherType == WeatherType.Stormy -> Color(0xFF0F1A35)
        else -> palette.backgroundBottomNight
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = listOf(topColor, bottomColor)))
    ) {
        AtmosphericWash(weather = weather, hazeShift = hazeShift)
        if (weather.isDaytime) {
            DaylightOrb(weather)
        } else {
            NightSky(starPulse = starPulse)
        }

        CloudLayer(
            weather = weather,
            cloudShift = cloudShift,
            modifier = Modifier.fillMaxSize()
        )

        if (weather.weatherType == WeatherType.Rainy || weather.weatherType == WeatherType.Stormy) {
            RainLayer(
                intensity = if (weather.weatherType == WeatherType.Stormy) 1f else 0.65f,
                rainOffset = rainOffset,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (weather.weatherType == WeatherType.Stormy) {
            LightningAccent(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AtmosphericWash(weather: CurrentWeatherUiModel, hazeShift: Float) {
    val palette = WeatherTrackerThemeState.palette
    Canvas(modifier = Modifier.fillMaxSize()) {
        val warmHorizon = if (weather.isDaytime) Color(0x55FFDDA1) else Color(0x220F234A)
        val coolMist = when (weather.weatherType) {
            WeatherType.Rainy -> Color(0x334E6D8A)
            WeatherType.Stormy -> Color(0x22465A74)
            else -> palette.mistTint
        }
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, coolMist, warmHorizon),
                startY = size.height * 0.18f,
                endY = size.height
            ),
            size = size
        )

        val bandY = size.height * (0.62f + (0.04f * hazeShift))
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(warmHorizon, Color.Transparent),
                center = Offset(size.width * 0.5f, bandY),
                radius = size.width * 0.72f
            ),
            topLeft = Offset(-size.width * 0.1f, bandY - 180f),
            size = Size(size.width * 1.2f, 360f)
        )
    }
}

@Composable
private fun DaylightOrb(weather: CurrentWeatherUiModel) {
    val palette = WeatherTrackerThemeState.palette
    val orbColor = if (weather.weatherType == WeatherType.Sunny) palette.sunGlow else Color(0xFFFFF4D8)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width * 0.16f, size.height * 0.14f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(orbColor.copy(alpha = 0.34f), Color.Transparent),
                center = center,
                radius = size.width * 0.28f
            ),
            radius = size.width * 0.28f,
            center = center
        )
        if (weather.weatherType == WeatherType.Sunny || weather.weatherType == WeatherType.PartlyCloudy) {
            drawCircle(
                color = orbColor.copy(alpha = 0.92f),
                radius = size.width * 0.05f,
                center = center
            )
        }
    }
}

@Composable
private fun NightSky(starPulse: Float) {
    val stars = remember {
        listOf(
            0.12f to 0.12f, 0.22f to 0.19f, 0.34f to 0.09f, 0.47f to 0.16f,
            0.58f to 0.11f, 0.69f to 0.21f, 0.81f to 0.13f, 0.90f to 0.19f,
            0.16f to 0.29f, 0.31f to 0.25f, 0.72f to 0.31f, 0.86f to 0.27f
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val moonCenter = Offset(size.width * 0.17f, size.height * 0.16f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x33E7EEFF), Color.Transparent),
                center = moonCenter,
                radius = size.width * 0.16f
            ),
            radius = size.width * 0.16f,
            center = moonCenter
        )
        drawCircle(
            color = Color(0xFFF0F4FF),
            radius = size.width * 0.04f,
            center = moonCenter
        )
        drawCircle(
            color = Color(0xFFB7C3DF),
            radius = size.width * 0.04f,
            center = Offset(moonCenter.x + size.width * 0.018f, moonCenter.y - size.width * 0.008f)
        )

        stars.forEachIndexed { index, (xFraction, yFraction) ->
            val radius = if (index % 3 == 0) 2.8f else 1.6f
            drawCircle(
                color = Color.White.copy(alpha = if (index % 2 == 0) starPulse * 0.65f else 0.34f),
                radius = radius,
                center = Offset(size.width * xFraction, size.height * yFraction)
            )
        }
    }
}

@Composable
private fun CloudLayer(
    weather: CurrentWeatherUiModel,
    cloudShift: Float,
    modifier: Modifier = Modifier
) {
    val mode = WeatherTrackerThemeState.mode
    val cloudColor = when {
        mode == AppThemeMode.HighContrast -> Color.White.copy(alpha = 0.22f)
        weather.weatherType == WeatherType.Stormy -> Color(0xAA3B4B5E)
        weather.weatherType == WeatherType.Rainy -> Color(0x885D738B)
        weather.isDaytime -> Color(0x66F3F6FA)
        else -> Color(0x55687A97)
    }
    val secondaryCloudColor = cloudColor.copy(alpha = cloudColor.alpha * 0.7f)

    Canvas(modifier = modifier) {
        val travel = size.width * 0.18f
        val drift = cloudShift * travel
        val cloudBaseY = if (weather.isDaytime) size.height * 0.2f else size.height * 0.24f

        fun drawCloudBank(centerX: Float, centerY: Float, width: Float, height: Float, color: Color) {
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(centerX, centerY),
                    radius = width * 0.6f
                ),
                topLeft = Offset(centerX - width / 2f, centerY - height / 2f),
                size = Size(width, height)
            )
        }

        if (weather.weatherType != WeatherType.Sunny || !weather.isDaytime) {
            drawCloudBank(size.width * 0.24f + drift, cloudBaseY, size.width * 0.44f, size.height * 0.12f, cloudColor)
        }

        if (weather.weatherType == WeatherType.Cloudy || weather.weatherType == WeatherType.Rainy || weather.weatherType == WeatherType.Stormy || !weather.isDaytime) {
            drawCloudBank(size.width * 0.72f - drift, cloudBaseY + size.height * 0.03f, size.width * 0.54f, size.height * 0.15f, secondaryCloudColor)
        }

        if (weather.weatherType == WeatherType.Stormy || weather.weatherType == WeatherType.Cloudy) {
            drawCloudBank(size.width * 0.48f + drift * 0.55f, cloudBaseY + size.height * 0.08f, size.width * 0.6f, size.height * 0.16f, cloudColor.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun RainLayer(
    intensity: Float,
    rainOffset: Float,
    modifier: Modifier = Modifier
) {
    val palette = WeatherTrackerThemeState.palette
    Canvas(modifier = modifier) {
        val columns = 20
        val streakLength = size.height * 0.08f
        for (index in 0 until columns) {
            val x = size.width * ((index + 0.5f) / columns.toFloat())
            val laneOffset = (index % 5) * 0.13f
            val progress = ((rainOffset + laneOffset) % 1f)
            val yStart = progress * (size.height + streakLength) - streakLength
            drawLine(
                color = palette.rainTint.copy(alpha = 0.14f + (0.22f * intensity)),
                start = Offset(x, yStart),
                end = Offset(x - 16f, yStart + streakLength),
                strokeWidth = if (index % 3 == 0) 2.6f else 1.6f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun LightningAccent(modifier: Modifier = Modifier) {
    val palette = WeatherTrackerThemeState.palette
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.82f, size.height * 0.16f)
            lineTo(size.width * 0.77f, size.height * 0.28f)
            lineTo(size.width * 0.82f, size.height * 0.29f)
            lineTo(size.width * 0.75f, size.height * 0.42f)
            lineTo(size.width * 0.79f, size.height * 0.33f)
            lineTo(size.width * 0.74f, size.height * 0.33f)
            close()
        }
        drawPath(color = palette.stormFlash.copy(alpha = 0.14f), path = path)
    }
}

@Composable
fun RadarAttributionFooter() {
    val palette = WeatherTrackerThemeState.palette
    Text(
        text = "Radar data source: Rain Viewer API",
        style = MaterialTheme.typography.labelSmall,
        color = palette.mutedText,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp)
    )
}

@Composable
fun StormRiskTimelineCard(timeline: List<Pair<Long, Float>>) {
    val hourFormat = SimpleDateFormat("ha", Locale.US)
    Surface(
        color = CardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "STORM RISK TIMELINE",
                style = MaterialTheme.typography.labelMedium,
                color = MutedText,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                timeline.takeLast(24).forEach { (timestamp, probability) ->
                    val isAlert = probability >= STORM_ALERT_THRESHOLD
                    val barColor = if (isAlert) AlertGold else NavyLight
                    val barHeight = (probability * MAX_BAR_HEIGHT_DP).coerceAtLeast(MIN_BAR_HEIGHT_DP)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .height(barHeight.dp)
                                .fillMaxWidth(0.6f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(barColor),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = hourFormat.format(Date(timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Yellow bars indicate storm risk above threshold",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun LocationSelectorCard(
    themeMode: AppThemeMode,
    locationOptions: List<LocationOptionUiModel>,
    selectedLocationOption: LocationOptionUiModel,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onLocationSelected: (LocationOptionUiModel) -> Unit
) {
    val palette = WeatherTrackerThemeState.palette
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = palette.locationCardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Location Source",
                style = MaterialTheme.typography.labelMedium,
                color = palette.mutedText,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = palette.selectorBackground,
                        contentColor = palette.selectorContent
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.outline)
                ) {
                    Text(text = selectedLocationOption.label)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    locationOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onLocationSelected(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Theme",
                style = MaterialTheme.typography.labelMedium,
                color = palette.mutedText,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppThemeMode.entries.forEach { option ->
                    FilterChip(
                        selected = themeMode == option,
                        onClick = { onThemeModeChanged(option) },
                        label = { Text(option.label()) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = palette.selectorBackground,
                            labelColor = palette.selectorContent
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherHeaderSection(weather: CurrentWeatherUiModel) {
    val palette = WeatherTrackerThemeState.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = weather.location,
            style = MaterialTheme.typography.headlineSmall,
            color = palette.primaryText,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = weather.dayDate,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.mutedText
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "${weather.temperature}°",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 100.sp),
            color = palette.primaryText,
            fontWeight = FontWeight.Thin
        )
        Text(
            text = weather.condition,
            style = MaterialTheme.typography.titleLarge,
            color = palette.primaryText
        )
        Text(
            text = "H:${weather.highTemp}° L:${weather.lowTemp}°",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.primaryText
        )
    }
}

@Composable
fun WeatherAlertCard(alert: WeatherAlertUiModel) {
    val palette = WeatherTrackerThemeState.palette
    var expanded by remember { mutableStateOf(false) }
    val isWarning = alert.title.contains("warning", ignoreCase = true)
    val isWatch = alert.title.contains("watch", ignoreCase = true)
    val isAdvisory = alert.title.contains("advisory", ignoreCase = true)

    val accentColor = when {
        isWarning -> Color(0xFFFF7A7A)
        isWatch -> Color(0xFFFFB74D)
        isAdvisory -> Color(0xFFFFE082)
        else -> AlertGold
    }
    val cardColor = when {
        isWarning -> Color(0xFF5A1E26).copy(alpha = 0.72f)
        isWatch -> Color(0xFF5A3A1A).copy(alpha = 0.72f)
        isAdvisory -> Color(0xFF5B4B1A).copy(alpha = 0.72f)
        else -> palette.cardBackground
    }
    val detailTextColor = Color(0xFFF8FAFF)
    val subtitleColor = Color(0xFFE3E8F5)

    Surface(
        color = cardColor,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alert.title,
                        color = accentColor,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (expanded) "Tap to hide details" else "Tap to view details",
                        color = subtitleColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = accentColor
                )
            }

            AnimatedVisibility(visible = expanded) {
                Text(
                    text = alert.description,
                    color = detailTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
fun LiveRadarCard(userLocation: LatLng?, onClick: () -> Unit) {
    val palette = WeatherTrackerThemeState.palette
    val context = androidx.compose.ui.platform.LocalContext.current
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userLocation ?: LatLng(30.2241, -92.0198), 10f)
    }
    val mapStyleOptions = remember {
        runCatching {
            MapStyleOptions.loadRawResourceStyle(context, R.raw.clean_radar_map_style)
        }.getOrNull()
    }
    var radarTileTemplate by remember { mutableStateOf<String?>(null) }
    val radarOverlayRef = remember { mutableStateOf<TileOverlay?>(null) }
    val radarTileTemplateRef = remember { AtomicReference<String?>(null) }

    LaunchedEffect(radarTileTemplate) {
        radarTileTemplateRef.set(radarTileTemplate)
        radarOverlayRef.value?.clearTileCache()
    }

    LaunchedEffect(Unit) {
        fetchRainViewerFramesCached(
            forceRefresh = false,
            onSuccess = { frames ->
                radarTileTemplate = frames.lastOrNull()?.tileTemplate
            },
            onFailure = {
                radarTileTemplate = null
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            radarOverlayRef.value?.remove()
            radarOverlayRef.value = null
        }
    }

    // Update camera if user location changes
    LaunchedEffect(userLocation) {
        userLocation?.let {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 10f)
        }
    }

    Surface(
        color = palette.cardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (userLocation != null) {
                GoogleMap(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        mapType = MapType.NORMAL,
                        isMyLocationEnabled = false,
                        mapStyleOptions = mapStyleOptions
                    ),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        scrollGesturesEnabled = false,
                        zoomGesturesEnabled = false,
                        tiltGesturesEnabled = false,
                        rotationGesturesEnabled = false,
                        myLocationButtonEnabled = false
                    )
                ) {
                    MapEffect(Unit) { googleMap ->
                        if (radarOverlayRef.value != null) {
                            return@MapEffect
                        }

                        val provider = object : UrlTileProvider(256, 256) {
                            override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
                                if (zoom !in RAIN_VIEWER_MIN_ZOOM..RAIN_VIEWER_MAX_ZOOM) {
                                    return null
                                }

                                val tileTemplate = radarTileTemplateRef.get()
                                if (tileTemplate.isNullOrBlank()) {
                                    return null
                                }

                                val urlText = tileTemplate
                                    .replace("{x}", x.toString())
                                    .replace("{y}", y.toString())
                                    .replace("{z}", zoom.toString())
                                return runCatching { URL(urlText) }.getOrNull()
                            }
                        }

                        radarOverlayRef.value = googleMap.addTileOverlay(
                            TileOverlayOptions()
                                .tileProvider(provider)
                                .transparency(0.35f)
                                .zIndex(1f)
                        )
                    }

                    Circle(
                        center = userLocation,
                        radius = 1500.0,
                        strokeColor = Color.Red,
                        strokeWidth = 2f,
                        fillColor = Color.Red.copy(alpha = 0.2f)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(palette.backgroundTopNight, palette.cardBackground)
                            )
                        )
                )
            }

            // Overlay to make it look like a button and capture clicks
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, palette.radarOverlayBottom)
                        )
                    )
                    .clickable { onClick() }
            )
            
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.BottomStart)
            ) {
                Text(
                    text = "Live Radar",
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap to expand",
                    color = palette.mutedText,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.OpenInFull,
                contentDescription = null,
                tint = palette.primaryText,
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopEnd)
                    .size(20.dp)
            )
        }
    }
}

@Composable
fun ForecastRow(day: DailyForecastUiModel) {
    val palette = WeatherTrackerThemeState.palette
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Surface(
            color = if (day.isToday) palette.cardBackground else Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = day.dayLabel,
                        color = palette.primaryText,
                        fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(text = day.dateLabel, color = palette.mutedText, style = MaterialTheme.typography.bodySmall)
                }
                
                Row(
                    modifier = Modifier.weight(1.5f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = getWeatherIcon(day.weatherType),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    if (day.precipitationChance > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${day.precipitationChance}%",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "${day.highTemp}°", color = palette.primaryText, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${day.lowTemp}°", color = palette.mutedText)
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            ForecastDayExpandedDetails(day)
        }
    }
}

@Composable
fun ForecastDayExpandedDetails(day: DailyForecastUiModel) {
    val palette = WeatherTrackerThemeState.palette
    Surface(
        color = palette.cardBackground.copy(alpha = 0.72f),
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                DetailItem(label = "Wind", value = day.windText, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(12.dp))
                DetailItem(label = "Feels Like", value = "${day.feelsLike}°", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = WeatherTrackerThemeState.palette
    Column(modifier = modifier) {
        Text(text = label, color = palette.mutedText, style = MaterialTheme.typography.labelSmall)
        Text(text = value, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun AppThemeMode.label(): String = when (this) {
    AppThemeMode.Light -> "Light"
    AppThemeMode.Dark -> "Dark"
    AppThemeMode.HighContrast -> "Contrast"
}

private fun getWeatherIcon(type: WeatherType): ImageVector {
    return when (type) {
        WeatherType.Sunny -> Icons.Default.Brightness5
        WeatherType.Cloudy -> Icons.Default.Cloud
        WeatherType.Rainy -> Icons.Default.Water
        WeatherType.Stormy -> Icons.Default.Warning
        WeatherType.PartlyCloudy -> Icons.Default.CloudQueue
    }
}

@Preview
@Composable
fun PreviewWeatherOverview() {
    val mockCurrent = CurrentWeatherUiModel(
        location = "Baton Rouge, Louisiana",
        dayDate = "Monday, October 23",
        temperature = 78,
        condition = "Partly Cloudy",
        highTemp = 82,
        lowTemp = 64,
        weatherType = WeatherType.PartlyCloudy,
        isDaytime = true,
        precipitationChance = 10
    )
    val mockAlert = WeatherAlertUiModel(
        title = "Wind Advisory",
        description = "Gusts up to 35 mph possible this afternoon"
    )
    val mockForecast = listOf(
        DailyForecastUiModel("Today", "Oct 23", WeatherType.PartlyCloudy, 82, 64, 10, "3 Moderate", 45, "12 mph", 80, "7:12 AM", "6:34 PM", true),
        DailyForecastUiModel("Tuesday", "Oct 24", WeatherType.Sunny, 85, 66, 0, "6 High", 40, "8 mph", 84, "7:13 AM", "6:33 PM"),
        DailyForecastUiModel("Wednesday", "Oct 25", WeatherType.Rainy, 76, 62, 80, "1 Low", 85, "15 mph", 75, "7:14 AM", "6:32 PM")
    )

    WeatherTrackerTheme {
        WeatherOverviewScreen(
            currentWeather = mockCurrent,
            alert = mockAlert,
            forecast = mockForecast,
            userLocation = LatLng(30.2241, -92.0198),
            themeMode = AppThemeMode.Dark,
            locationOptions = listOf(
                LocationOptionUiModel("Use device location", null, null, true),
                LocationOptionUiModel("Baton Rouge, LA", 30.4515, -91.1871),
                LocationOptionUiModel("Lafayette, LA", 30.2241, -92.0198)
            ),
            selectedLocationOption = LocationOptionUiModel("Baton Rouge, LA", 30.4515, -91.1871),
            onThemeModeChanged = {},
            onLocationSelected = {},
            onLiveRadarClick = {}
        )
    }
}
