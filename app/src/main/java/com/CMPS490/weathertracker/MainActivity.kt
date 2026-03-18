package com.CMPS490.weathertracker

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.CMPS490.weathertracker.ui.theme.WeatherTrackerTheme
import com.CMPS490.weathertracker.network.AlertFeature
import com.CMPS490.weathertracker.network.AlertsResponse
import com.CMPS490.weathertracker.network.ForecastPeriod
import com.CMPS490.weathertracker.network.ForecastResponse
import com.CMPS490.weathertracker.network.PointResponse
import com.CMPS490.weathertracker.network.RetrofitInstance
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val RADAR_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
private const val RADAR_PLAYBACK_STEP_SECONDS = 30 * 60L
private const val RADAR_PLAYBACK_TICK_MS = 1500L

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WeatherTrackerTheme {
                val navController = rememberNavController()
                val context = LocalContext.current
                val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
                var userLocation by remember { mutableStateOf<LatLng?>(null) }

                val locationPermissionState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )

                LaunchedEffect(Unit) {
                    locationPermissionState.launchMultiplePermissionRequest()
                }

                @SuppressLint("MissingPermission")
                LaunchedEffect(locationPermissionState.allPermissionsGranted) {
                    if (locationPermissionState.allPermissionsGranted) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                            if (location != null) {
                                userLocation = LatLng(location.latitude, location.longitude)
                            }
                        }
                    }
                }

                val locationOptions = remember {
                    listOf(
                        LocationOptionUiModel("Use device location", null, null, true),
                        LocationOptionUiModel("Baton Rouge, LA", 30.4515, -91.1871),
                        LocationOptionUiModel("New Orleans, LA", 29.9511, -90.0715),
                        LocationOptionUiModel("Lafayette, LA", 30.2241, -92.0198),
                        LocationOptionUiModel("Shreveport, LA", 32.5252, -93.7502),
                        LocationOptionUiModel("Lake Charles, LA", 30.2266, -93.2174),
                        LocationOptionUiModel("Monroe, LA", 32.5093, -92.1193)
                    )
                }
                var selectedLocationOption by remember { mutableStateOf(locationOptions[0]) }

                val weatherQueryLocation = when {
                    selectedLocationOption.useDeviceLocation &&
                        locationPermissionState.allPermissionsGranted &&
                        userLocation != null -> userLocation
                    selectedLocationOption.useDeviceLocation &&
                        locationPermissionState.allPermissionsGranted -> null
                    selectedLocationOption.useDeviceLocation -> null
                    else -> LatLng(
                        selectedLocationOption.latitude ?: 30.4515,
                        selectedLocationOption.longitude ?: -91.1871
                    )
                }
                val mapLocation = weatherQueryLocation ?: userLocation

                var currentWeather by remember {
                    mutableStateOf(
                        CurrentWeatherUiModel(
                            location = "Loading location...",
                            dayDate = "Loading...",
                            temperature = 0,
                            condition = "Loading weather...",
                            highTemp = 0,
                            lowTemp = 0
                        )
                    )
                }
                var alertWeather by remember { mutableStateOf<WeatherAlertUiModel?>(null) }
                var forecastWeather by remember { mutableStateOf<List<DailyForecastUiModel>>(emptyList()) }
                var activeRequestKey by remember { mutableStateOf("") }

                LaunchedEffect(weatherQueryLocation?.latitude, weatherQueryLocation?.longitude) {
                    if (weatherQueryLocation == null) {
                        currentWeather = currentWeather.copy(
                            location = if (selectedLocationOption.useDeviceLocation) {
                                "Getting your location..."
                            } else {
                                selectedLocationOption.label
                            },
                            dayDate = "Loading...",
                            condition = "Loading weather..."
                        )
                        alertWeather = null
                        forecastWeather = emptyList()
                        return@LaunchedEffect
                    }

                    val lat = weatherQueryLocation.latitude
                    val lon = weatherQueryLocation.longitude
                    val requestKey = "$lat,$lon"
                    activeRequestKey = requestKey

                    val cachedSnapshot = WeatherApiCache.get(lat, lon)
                    if (cachedSnapshot != null) {
                        currentWeather = cachedSnapshot.currentWeather
                        alertWeather = cachedSnapshot.alertWeather
                        forecastWeather = cachedSnapshot.forecastWeather
                        Log.d("WeatherCache", "USING_CACHED_DATA for requestKey=$requestKey")
                        return@LaunchedEffect
                    }

                    var requestAlert: WeatherAlertUiModel? = null

                    RetrofitInstance.api.getActiveAlertsByPoint("$lat,$lon")
                        .enqueue(object : Callback<AlertsResponse> {
                            override fun onResponse(
                                call: Call<AlertsResponse>,
                                response: Response<AlertsResponse>
                            ) {
                                if (activeRequestKey != requestKey) {
                                    return
                                }
                                requestAlert = mapAlertToUi(response.body()?.features)
                                alertWeather = requestAlert
                            }

                            override fun onFailure(call: Call<AlertsResponse>, t: Throwable) {
                                if (activeRequestKey != requestKey) {
                                    return
                                }
                                requestAlert = null
                                alertWeather = null
                            }
                        })

                    RetrofitInstance.api.getPointData(lat, lon)
                        .enqueue(object : Callback<PointResponse> {
                            override fun onResponse(
                                call: Call<PointResponse>,
                                response: Response<PointResponse>
                            ) {
                                if (activeRequestKey != requestKey) {
                                    return
                                }
                                val point = response.body()
                                val forecastUrl = point?.properties?.forecast
                                val forecastHourlyUrl = point?.properties?.forecastHourly
                                if (forecastUrl == null) {
                                    currentWeather = currentWeather.copy(condition = "No forecast available")
                                    forecastWeather = emptyList()
                                    return
                                }

                                RetrofitInstance.api.getForecastFromUrl(forecastUrl)
                                    .enqueue(object : Callback<ForecastResponse> {
                                        override fun onResponse(
                                            call: Call<ForecastResponse>,
                                            response: Response<ForecastResponse>
                                        ) {
                                            if (activeRequestKey != requestKey) {
                                                return
                                            }
                                            val periods = response.body()?.properties?.periods.orEmpty()
                                            if (periods.isEmpty()) {
                                                currentWeather = currentWeather.copy(condition = "No forecast available")
                                                forecastWeather = emptyList()
                                                return
                                            }

                                            val locationLabel = buildLocationLabel(point)
                                            currentWeather = mapCurrentWeather(locationLabel, periods)
                                            forecastWeather = mapForecast(periods)

                                            WeatherApiCache.put(
                                                lat = lat,
                                                lon = lon,
                                                currentWeather = currentWeather,
                                                alertWeather = requestAlert,
                                                forecastWeather = forecastWeather
                                            )

                                            if (
                                                !selectedLocationOption.useDeviceLocation &&
                                                coordinatesMatch(selectedLocationOption.latitude, lat) &&
                                                coordinatesMatch(selectedLocationOption.longitude, lon)
                                            ) {
                                                selectedLocationOption = selectedLocationOption.copy(label = "$locationLabel (Pinned)")
                                            }

                                            if (!forecastHourlyUrl.isNullOrBlank()) {
                                                RetrofitInstance.api.getForecastFromUrl(forecastHourlyUrl)
                                                    .enqueue(object : Callback<ForecastResponse> {
                                                        override fun onResponse(
                                                            call: Call<ForecastResponse>,
                                                            response: Response<ForecastResponse>
                                                        ) {
                                                            if (activeRequestKey != requestKey) {
                                                                return
                                                            }
                                                            val hourlyNow = response.body()?.properties?.periods?.firstOrNull()
                                                            if (hourlyNow != null) {
                                                                currentWeather = currentWeather.copy(
                                                                    temperature = hourlyNow.temperature,
                                                                    condition = hourlyNow.shortForecast
                                                                )
                                                                WeatherApiCache.put(
                                                                    lat = lat,
                                                                    lon = lon,
                                                                    currentWeather = currentWeather,
                                                                    alertWeather = requestAlert,
                                                                    forecastWeather = forecastWeather
                                                                )
                                                            }
                                                        }

                                                        override fun onFailure(call: Call<ForecastResponse>, t: Throwable) {
                                                            if (activeRequestKey != requestKey) {
                                                                return
                                                            }
                                                        }
                                                    })
                                            }
                                        }

                                        override fun onFailure(call: Call<ForecastResponse>, t: Throwable) {
                                            if (activeRequestKey != requestKey) {
                                                return
                                            }
                                            currentWeather = currentWeather.copy(condition = "Forecast fetch failed")
                                            forecastWeather = emptyList()
                                        }
                                    })
                            }

                            override fun onFailure(call: Call<PointResponse>, t: Throwable) {
                                if (activeRequestKey != requestKey) {
                                    return
                                }
                                currentWeather = currentWeather.copy(condition = "Point lookup failed")
                                forecastWeather = emptyList()
                            }
                        })
                }
                
                NavHost(navController = navController, startDestination = "weather_overview") {
                    composable("weather_overview") {
                        WeatherOverviewScreen(
                            currentWeather = currentWeather,
                            alert = alertWeather,
                            forecast = forecastWeather,
                            userLocation = mapLocation,
                            locationOptions = locationOptions,
                            selectedLocationOption = selectedLocationOption,
                            onLocationSelected = { selectedLocationOption = it },
                            onLiveRadarClick = { navController.navigate("map_screen") }
                        )
                    }
                    composable("map_screen") {
                        MapScreen(
                            onBack = { navController.popBackStack() },
                            initialLocation = mapLocation,
                            onPinSelected = { pinnedLatLng ->
                                selectedLocationOption = LocationOptionUiModel(
                                    label = "Pinned location",
                                    latitude = pinnedLatLng.latitude,
                                    longitude = pinnedLatLng.longitude,
                                    useDeviceLocation = false
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun buildLocationLabel(pointResponse: PointResponse?): String {
    val city = pointResponse?.properties?.relativeLocation?.properties?.city
    val state = pointResponse?.properties?.relativeLocation?.properties?.state
    return if (!city.isNullOrBlank() && !state.isNullOrBlank()) {
        "$city, $state"
    } else {
        "Selected location"
    }
}

private fun mapCurrentWeather(
    locationLabel: String,
    periods: List<ForecastPeriod>
): CurrentWeatherUiModel {
    val firstPeriod = periods.first()
    val dateLabel = formatDateLong(firstPeriod.startTime)
    val lowTemp = (firstPeriod.temperature - 8).coerceAtLeast(0)
    return CurrentWeatherUiModel(
        location = locationLabel,
        dayDate = dateLabel,
        temperature = firstPeriod.temperature,
        condition = firstPeriod.shortForecast,
        highTemp = firstPeriod.temperature,
        lowTemp = lowTemp
    )
}

private fun mapForecast(periods: List<ForecastPeriod>): List<DailyForecastUiModel> {
    return periods.take(7).mapIndexed { index, period ->
        val precip = period.probabilityOfPrecipitation?.value?.toInt() ?: 0
        val humidity = period.relativeHumidity?.value?.toInt() ?: 0
        val feelsLike = period.temperature
        val lowTemp = (period.temperature - 8).coerceAtLeast(0)

        DailyForecastUiModel(
            dayLabel = period.name,
            dateLabel = formatDateShort(period.startTime),
            weatherType = toWeatherType(period.shortForecast),
            highTemp = period.temperature,
            lowTemp = lowTemp,
            precipitationChance = precip,
            uvIndex = "--",
            humidity = humidity,
            windText = period.windSpeed,
            feelsLike = feelsLike,
            sunrise = "--",
            sunset = "--",
            isToday = index == 0
        )
    }
}

private fun mapAlertToUi(features: List<AlertFeature>?): WeatherAlertUiModel? {
    val first = features?.firstOrNull() ?: return null
    val title = first.properties.event ?: first.properties.headline ?: "Weather Alert"
    val description = first.properties.description ?: "No details provided."
    return WeatherAlertUiModel(title = title, description = description)
}

private fun toWeatherType(shortForecast: String): WeatherType {
    val value = shortForecast.lowercase(Locale.US)
    return when {
        value.contains("thunder") || value.contains("storm") -> WeatherType.Stormy
        value.contains("rain") || value.contains("shower") -> WeatherType.Rainy
        value.contains("cloud") -> WeatherType.Cloudy
        value.contains("sun") || value.contains("clear") -> WeatherType.Sunny
        else -> WeatherType.PartlyCloudy
    }
}

private fun formatDateShort(iso: String): String {
    return runCatching {
        val dt = LocalDateTime.parse(iso.substring(0, 19))
        dt.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }.getOrDefault(iso)
}

private fun formatDateLong(iso: String): String {
    return runCatching {
        val dt = LocalDateTime.parse(iso.substring(0, 19))
        val dayName = dt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        "$dayName, ${dt.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))}"
    }.getOrDefault("Today")
}

private fun coordinatesMatch(a: Double?, b: Double, tolerance: Double = 0.0001): Boolean {
    return a != null && abs(a - b) < tolerance
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    initialLocation: LatLng?,
    onPinSelected: (LatLng) -> Unit
) {

    val context = LocalContext.current
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    val locationPermissionState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(Unit) {
        locationPermissionState.launchMultiplePermissionRequest()
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialLocation ?: LatLng(30.2241, -92.0198), 10f)
    }

    var selectedPin by remember { mutableStateOf<LatLng?>(initialLocation) }
    var radarFrames by remember { mutableStateOf<List<RainViewerRadarFrame>>(emptyList()) }
    var selectedFrameIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isRadarEnabled by remember { mutableStateOf(false) }
    val mapStyleOptions = remember {
        runCatching {
            MapStyleOptions.loadRawResourceStyle(context, R.raw.clean_radar_map_style)
        }.getOrNull()
    }
    val radarOverlayRef = remember { mutableStateOf<TileOverlay?>(null) }
    val radarTileTemplateRef = remember { AtomicReference<String?>(null) }
    val radarEnabledRef = remember { AtomicBoolean(false) }
    val radarTileTemplate = radarFrames.getOrNull(selectedFrameIndex)?.tileTemplate
    val selectedFrameTime = radarFrames.getOrNull(selectedFrameIndex)?.epochSeconds

    LaunchedEffect(radarTileTemplate) {
        radarTileTemplateRef.set(radarTileTemplate)
        radarOverlayRef.value?.clearTileCache()
    }

    LaunchedEffect(isRadarEnabled) {
        radarEnabledRef.set(isRadarEnabled)
        if (!isRadarEnabled) {
            isPlaying = false
        }
        if (isRadarEnabled && cameraPositionState.position.zoom > RAIN_VIEWER_MAX_ZOOM.toFloat()) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                cameraPositionState.position.target,
                RAIN_VIEWER_MAX_ZOOM.toFloat()
            )
        }
        radarOverlayRef.value?.clearTileCache()
    }

    LaunchedEffect(Unit) {
        while (true) {
            fetchRainViewerFramesCached(
                forceRefresh = true,
                onSuccess = { frames ->
                    val selectedEpoch = radarFrames.getOrNull(selectedFrameIndex)?.epochSeconds
                    radarFrames = frames

                    if (frames.isEmpty()) {
                        selectedFrameIndex = 0
                        isPlaying = false
                    } else {
                        selectedFrameIndex = findPreferredFrameIndex(
                            frames = frames,
                            preferredEpochSeconds = selectedEpoch,
                            fallbackIndex = frames.lastIndex
                        )
                    }
                },
                onFailure = {
                    if (radarFrames.isEmpty()) {
                        selectedFrameIndex = 0
                        isPlaying = false
                    }
                }
            )

            delay(RADAR_REFRESH_INTERVAL_MS)
        }
    }

    LaunchedEffect(isPlaying, radarFrames.size) {
        if (!isPlaying || radarFrames.size < 2) {
            return@LaunchedEffect
        }

        while (isPlaying) {
            delay(RADAR_PLAYBACK_TICK_MS)
            val frameCount = radarFrames.size
            if (frameCount < 2) {
                isPlaying = false
                return@LaunchedEffect
            }
            selectedFrameIndex = findSteppedFrameIndex(
                frames = radarFrames,
                currentIndex = selectedFrameIndex,
                stepSeconds = RADAR_PLAYBACK_STEP_SECONDS,
                forward = true,
                wrap = true
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            radarOverlayRef.value?.remove()
            radarOverlayRef.value = null
        }
    }

    @SuppressLint("MissingPermission")
    LaunchedEffect(locationPermissionState.allPermissionsGranted) {
        if (locationPermissionState.allPermissionsGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    if (initialLocation == null) {
                        cameraPositionState.position = CameraPosition.fromLatLngZoom(userLatLng, 12f)
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapClick = { tappedLatLng ->
                selectedPin = tappedLatLng
                onPinSelected(tappedLatLng)
                onBack()
            },
            properties = MapProperties(
                isMyLocationEnabled = locationPermissionState.allPermissionsGranted,
                mapType = MapType.NORMAL,
                mapStyleOptions = mapStyleOptions,
                maxZoomPreference = if (isRadarEnabled) RAIN_VIEWER_MAX_ZOOM.toFloat() else 21f
            ),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = true
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

                        if (!radarEnabledRef.get()) {
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

            selectedPin?.let { pin ->
                Circle(
                    center = pin,
                    radius = 500.0,
                    strokeColor = Color.Cyan,
                    strokeWidth = 4f,
                    fillColor = Color.Cyan.copy(alpha = 0.25f)
                )
            }
        }

        if (isRadarEnabled) {
            RadarLegend(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
            )
        }

        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.Layers,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 8.dp)
                )
                LayerToggleButton(
                    text = "Map",
                    icon = Icons.Filled.Map,
                    selected = !isRadarEnabled,
                    onClick = { isRadarEnabled = false }
                )
                Spacer(modifier = Modifier.height(6.dp))
                LayerToggleButton(
                    text = "Radar",
                    icon = Icons.Filled.Radar,
                    selected = isRadarEnabled,
                    onClick = { isRadarEnabled = true }
                )
            }
        }

        if (isRadarEnabled) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = selectedFrameTime?.let { formatRadarFrameTime(it) } ?: "Radar frame unavailable",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                isPlaying = false
                                selectedFrameIndex = findSteppedFrameIndex(
                                    frames = radarFrames,
                                    currentIndex = selectedFrameIndex,
                                    stepSeconds = RADAR_PLAYBACK_STEP_SECONDS,
                                    forward = false,
                                    wrap = false
                                )
                            },
                            enabled = radarFrames.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FastRewind,
                                contentDescription = "Rewind",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { isPlaying = !isPlaying },
                            enabled = radarFrames.size > 1
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White
                            )
                        }

                        Slider(
                            value = selectedFrameIndex.toFloat(),
                            onValueChange = { newValue ->
                                isPlaying = false
                                selectedFrameIndex = newValue.toInt().coerceIn(0, (radarFrames.size - 1).coerceAtLeast(0))
                            },
                            valueRange = 0f..(radarFrames.size - 1).coerceAtLeast(0).toFloat(),
                            steps = (radarFrames.size - 2).coerceAtLeast(0),
                            modifier = Modifier.weight(1f),
                            enabled = radarFrames.size > 1
                        )
                    }
                }
            }
        }

        // Back Button Overlay
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = 48.dp, start = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun LayerToggleButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Color(0xFF3A78FF) else Color.Black.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .width(110.dp)
            .padding(horizontal = 2.dp)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RadarLegend(modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "Radar Intensity",
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(Color(0xFF58A6FF), "Light")
                Spacer(modifier = Modifier.width(8.dp))
                LegendSwatch(Color(0xFF29C76F), "Moderate")
                Spacer(modifier = Modifier.width(8.dp))
                LegendSwatch(Color(0xFFFFC233), "Heavy")
                Spacer(modifier = Modifier.width(8.dp))
                LegendSwatch(Color(0xFFFF5A5F), "Severe")
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatRadarFrameTime(epochSeconds: Long): String {
    return runCatching {
        val instant = Instant.ofEpochSecond(epochSeconds)
        val localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
        localDateTime.format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US))
    }.getOrDefault("Frame time unavailable")
}

private fun findPreferredFrameIndex(
    frames: List<RainViewerRadarFrame>,
    preferredEpochSeconds: Long?,
    fallbackIndex: Int
): Int {
    if (frames.isEmpty()) {
        return 0
    }

    if (preferredEpochSeconds == null) {
        return fallbackIndex.coerceIn(0, frames.lastIndex)
    }

    val exactMatchIndex = frames.indexOfFirst { it.epochSeconds == preferredEpochSeconds }
    if (exactMatchIndex >= 0) {
        return exactMatchIndex
    }

    val nearest = frames
        .mapIndexed { index, frame -> index to abs(frame.epochSeconds - preferredEpochSeconds) }
        .minByOrNull { it.second }
        ?.first

    return (nearest ?: fallbackIndex).coerceIn(0, frames.lastIndex)
}

private fun findSteppedFrameIndex(
    frames: List<RainViewerRadarFrame>,
    currentIndex: Int,
    stepSeconds: Long,
    forward: Boolean,
    wrap: Boolean
): Int {
    if (frames.isEmpty()) {
        return 0
    }

    val safeIndex = currentIndex.coerceIn(0, frames.lastIndex)
    val currentEpochSeconds = frames[safeIndex].epochSeconds

    return if (forward) {
        val targetEpoch = currentEpochSeconds + stepSeconds
        val nextIndex = frames.indexOfFirst { it.epochSeconds >= targetEpoch }
        when {
            nextIndex >= 0 -> nextIndex
            wrap -> 0
            else -> frames.lastIndex
        }
    } else {
        val targetEpoch = currentEpochSeconds - stepSeconds
        val previousIndex = frames.indexOfLast { it.epochSeconds <= targetEpoch }
        when {
            previousIndex >= 0 -> previousIndex
            wrap -> frames.lastIndex
            else -> 0
        }
    }
}

