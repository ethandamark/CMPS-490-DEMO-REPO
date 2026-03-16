package com.CMPS490.weathertracker

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
                var selectedLocationOption by remember { mutableStateOf(locationOptions[3]) }

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

                    RetrofitInstance.api.getActiveAlertsByPoint("$lat,$lon")
                        .enqueue(object : Callback<AlertsResponse> {
                            override fun onResponse(
                                call: Call<AlertsResponse>,
                                response: Response<AlertsResponse>
                            ) {
                                if (activeRequestKey != requestKey) {
                                    return
                                }
                                alertWeather = mapAlertToUi(response.body()?.features)
                            }

                            override fun onFailure(call: Call<AlertsResponse>, t: Throwable) {
                                if (activeRequestKey != requestKey) {
                                    return
                                }
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
                        MapScreen(onBack = { navController.popBackStack() })
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MapScreen(onBack: () -> Unit) {

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
    val cameraPositionState = rememberCameraPositionState()

    var hazardZone by remember { mutableStateOf<HazardZone?>(null) }

    @SuppressLint("MissingPermission")
    LaunchedEffect(locationPermissionState.allPermissionsGranted) {
        if (locationPermissionState.allPermissionsGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    cameraPositionState.position = CameraPosition.fromLatLngZoom(
                        userLatLng,
                        12f // Zoom to a level where the circle is visible
                    )
                    // Create a flood hazard zone
                    hazardZone = HazardZone(
                        center = userLatLng,
                        radiusMeters = 1500.0,
                        severity = "WARNING"
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = locationPermissionState.allPermissionsGranted,
                mapType = MapType.SATELLITE
            ),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = true
            )
        ) {
            // Draw hazard zone on the map
            hazardZone?.let { zone ->
                if (zone.severity == "WARNING") {
                    Circle(
                        center = zone.center,
                        radius = zone.radiusMeters,
                        strokeColor = Color.Red,
                        strokeWidth = 4f,
                        fillColor = Color.Red.copy(alpha = 0.3f)
                    )
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
