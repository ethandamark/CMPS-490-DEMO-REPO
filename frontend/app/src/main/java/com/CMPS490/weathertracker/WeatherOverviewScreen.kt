package com.CMPS490.weathertracker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.CMPS490.weathertracker.ui.theme.WeatherTrackerTheme
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
val AlertGold = Color(0xFFFFD700)
val MutedText = Color(0xFF9EADC8)

@Composable
fun WeatherOverviewScreen(
    currentWeather: CurrentWeatherUiModel,
    alert: WeatherAlertUiModel?,
    forecast: List<DailyForecastUiModel>,
    userLocation: LatLng?,
    locationOptions: List<LocationOptionUiModel>,
    selectedLocationOption: LocationOptionUiModel,
    onLocationSelected: (LocationOptionUiModel) -> Unit,
    onLiveRadarClick: () -> Unit,
    stormRiskTimeline: List<Pair<Long, Float>> = emptyList(),
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(NavyDark, NavyLight)
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 60.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                LocationSelectorCard(
                    locationOptions = locationOptions,
                    selectedLocationOption = selectedLocationOption,
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
                    color = MutedText,
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
fun RadarAttributionFooter() {
    Text(
        text = "Radar data source: Rain Viewer API",
        style = MaterialTheme.typography.labelSmall,
        color = MutedText,
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
    locationOptions: List<LocationOptionUiModel>,
    selectedLocationOption: LocationOptionUiModel,
    onLocationSelected: (LocationOptionUiModel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = CardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Location Source",
                style = MaterialTheme.typography.labelMedium,
                color = MutedText,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
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
        }
    }
}

@Composable
fun WeatherHeaderSection(weather: CurrentWeatherUiModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = weather.location,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = weather.dayDate,
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "${weather.temperature}°",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 100.sp),
            color = Color.White,
            fontWeight = FontWeight.Thin
        )
        Text(
            text = weather.condition,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Text(
            text = "H:${weather.highTemp}° L:${weather.lowTemp}°",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}

@Composable
fun WeatherAlertCard(alert: WeatherAlertUiModel) {
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
        else -> CardBackground
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
        color = CardBackground,
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
                                colors = listOf(NavyDark, CardBackground)
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
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
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
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap to expand",
                    color = MutedText,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.OpenInFull,
                contentDescription = null,
                tint = Color.White,
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
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Surface(
            color = if (day.isToday) CardBackground else Color.Transparent,
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
                        color = Color.White,
                        fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(text = day.dateLabel, color = MutedText, style = MaterialTheme.typography.bodySmall)
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
                            color = Color(0xFF64B5F6),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "${day.highTemp}°", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${day.lowTemp}°", color = MutedText)
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
    Surface(
        color = CardBackground.copy(alpha = 0.4f),
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                DetailItem(label = "UV Index", value = day.uvIndex, modifier = Modifier.weight(1f))
                DetailItem(label = "Humidity", value = "${day.humidity}%", modifier = Modifier.weight(1f))
                DetailItem(label = "Wind", value = day.windText, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                DetailItem(label = "Feels Like", value = "${day.feelsLike}°", modifier = Modifier.weight(1f))
                DetailItem(label = "Sunrise", value = day.sunrise, modifier = Modifier.weight(1f))
                DetailItem(label = "Sunset", value = day.sunset, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, color = MutedText, style = MaterialTheme.typography.labelSmall)
        Text(text = value, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
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
        lowTemp = 64
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
            locationOptions = listOf(
                LocationOptionUiModel("Use device location", null, null, true),
                LocationOptionUiModel("Baton Rouge, LA", 30.4515, -91.1871),
                LocationOptionUiModel("Lafayette, LA", 30.2241, -92.0198)
            ),
            selectedLocationOption = LocationOptionUiModel("Baton Rouge, LA", 30.4515, -91.1871),
            onLocationSelected = {},
            onLiveRadarClick = {}
        )
    }
}
