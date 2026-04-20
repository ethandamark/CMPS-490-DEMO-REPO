package com.CMPS490.weathertracker

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
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
import com.CMPS490.weathertracker.network.QuantitativeValue
import com.CMPS490.weathertracker.network.AlertProperties
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import com.google.gson.Gson
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private const val RADAR_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
private const val RADAR_PLAYBACK_STEP_SECONDS = 30 * 60L
private const val RADAR_PLAYBACK_TICK_MS = 1500L
private const val SAVED_LOCATIONS_PREF_KEY = "saved_locations"
private val gson = Gson()

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
                var registrationComplete by remember { mutableStateOf(false) }
                var storedDeviceId by remember { mutableStateOf<String?>(null) }
                var permissionDialogShown by remember { mutableStateOf(false) }
                var backendConnected by remember { mutableStateOf(true) }
                var retryTrigger by remember { mutableStateOf(0) }

                // Use SharedPreferences to persist permission request state across Activity recreation
                val prefs = remember { context.getSharedPreferences("weather_tracker_prefs", android.content.Context.MODE_PRIVATE) }
                var locationPromptAnswered by remember { 
                    mutableStateOf(prefs.getBoolean("location_prompt_answered", false)) 
                }
                var showLocationDialog by remember { mutableStateOf(false) }
                var notificationPromptAnswered by remember {
                    mutableStateOf(prefs.getBoolean("notification_prompt_answered", false))
                }

                val locationPermissionState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                ) {
                    // This callback fires when returning from Settings
                    permissionDialogShown = true
                }

                // Background location permission state (for checking only, not requesting)
                val backgroundLocationPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rememberPermissionState(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    null
                }

                // Notification permission launcher (Android 13+)
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted ->
                    notificationPromptAnswered = true
                    prefs.edit().putBoolean("notification_prompt_answered", true).apply()
                    if (granted) {
                        Log.d("MainActivity", "✓ Notification permission granted")
                        // Update device in Supabase
                        storedDeviceId?.let { deviceId ->
                            BackendRepository.updateDevice(
                                deviceId = deviceId,
                                notificationsEnabled = true,
                                onSuccess = { Log.d("MainActivity", "✓ notifications_enabled set to true") },
                                onError = { e -> Log.e("MainActivity", "✗ Failed to update notifications_enabled: ${e.message}") }
                            )
                        }
                    } else {
                        Log.d("MainActivity", "Notification permission denied")
                    }
                }

                // Function to open app's location permission settings directly
                // This opens Settings where user sees ALL options:
                // "Allow all the time", "Allow only while using the app", "Ask every time", "Don't allow"
                fun openLocationPermissionSettings() {
                    try {
                        // Try to open the app permissions page directly (shows list of permissions)
                        // User just needs to tap "Location" to see all 4 options
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                        
                        // Show a toast to guide the user
                        android.widget.Toast.makeText(
                            context,
                            "Tap 'Permissions' then 'Location'",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Could not open settings: ${e.message}")
                    }
                }

                // Check if notifications are enabled at the OS level
                val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    true // Pre-Android 13: notifications always allowed
                }

                // Show permission dialog on every launch until BOTH permissions are granted
                LaunchedEffect(locationPermissionState.allPermissionsGranted, notificationsGranted) {
                    if (locationPermissionState.allPermissionsGranted && notificationsGranted) {
                        // Both granted — persist and skip dialog forever
                        permissionDialogShown = true
                        locationPromptAnswered = true
                        notificationPromptAnswered = true
                        prefs.edit()
                            .putBoolean("location_prompt_answered", true)
                            .putBoolean("notification_prompt_answered", true)
                            .apply()
                    } else if (!locationPromptAnswered || !notificationsGranted || !locationPermissionState.allPermissionsGranted) {
                        showLocationDialog = true
                    }
                }

                // Combined Location + Notification Permission Dialog
                if (showLocationDialog) {
                    AlertDialog(
                        onDismissRequest = { 
                            // User dismissed — allow the rest of the app to proceed
                            // but do NOT persist to SharedPreferences so dialog shows again next launch
                            showLocationDialog = false
                            locationPromptAnswered = true
                            notificationPromptAnswered = true
                            permissionDialogShown = true
                        },
                        title = {
                            Text(
                                text = "App Permissions",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        },
                        text = {
                            Text(
                                text = "WeatherTracker needs your permission to:\n\n" +
                                    "• Access your location for weather tracking\n" +
                                    "• Send notifications for severe weather alerts\n\n" +
                                    "Tap 'Allow' to open Settings and grant these permissions.",
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showLocationDialog = false
                                    locationPromptAnswered = true
                                    notificationPromptAnswered = true
                                    // Do NOT persist — LaunchedEffect will persist once both are granted
                                    // Open app's permission settings for location
                                    openLocationPermissionSettings()
                                    // Also request notification permission (Android 13+)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                )
                            ) {
                                Text("Allow", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    // User declined — allow app to proceed this session
                                    // but dialog will reappear on next launch
                                    showLocationDialog = false
                                    locationPromptAnswered = true
                                    notificationPromptAnswered = true
                                    permissionDialogShown = true
                                }
                            ) {
                                Text("Don't Allow", color = Color.White.copy(alpha = 0.7f))
                            }
                        },
                        containerColor = Color(0xFF1E3A5F),
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                // Check permission state when returning from Settings
                LaunchedEffect(locationPermissionState.allPermissionsGranted) {
                    if (locationPermissionState.allPermissionsGranted && locationPromptAnswered) {
                        permissionDialogShown = true
                        // Update location_permission_status in Supabase
                        storedDeviceId?.let { deviceId ->
                            BackendRepository.updateDevice(
                                deviceId = deviceId,
                                locationPermissionStatus = true,
                                onSuccess = { Log.d("MainActivity", "✓ location_permission_status set to true") },
                                onError = { e -> Log.e("MainActivity", "✗ Failed to update location_permission_status: ${e.message}") }
                            )
                        }
                    }
                }

                // Start background location service when all permissions are granted
                LaunchedEffect(
                    locationPermissionState.allPermissionsGranted,
                    backgroundLocationPermissionState?.status?.isGranted,
                    registrationComplete
                ) {
                    val hasBackgroundPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        backgroundLocationPermissionState?.status?.isGranted == true
                    
                    if (locationPermissionState.allPermissionsGranted && 
                        hasBackgroundPermission && 
                        registrationComplete) {
                        Log.d("MainActivity", "✓ All location permissions granted - starting background tracking service")
                        LocationTrackingService.start(context)
                    }
                }

                // Track if registration is in progress to prevent duplicate calls
                var registrationInProgress by remember { mutableStateOf(false) }

                // Run registration AFTER location dialog is answered (for new users)
                // Or immediately if credentials already exist
                // Also retries when backend connection is restored after being down
                LaunchedEffect(locationPromptAnswered, permissionDialogShown, backendConnected) {
                    // Skip if already registered or registration in progress
                    if (registrationComplete || registrationInProgress) return@LaunchedEffect
                    
                    val authService = AuthenticationService(context)
                    val existingDeviceId = authService.getStoredDeviceId()
                    
                    // If we already have credentials, just restore them and mark complete
                    if (existingDeviceId != null) {
                        Log.d("MainActivity", "✓ Existing credentials found: $existingDeviceId")
                        storedDeviceId = existingDeviceId
                        registrationComplete = true
                        return@LaunchedEffect
                    }
                    
                    // For new users, wait for location dialog to be answered
                    val permissionFlowComplete = locationPromptAnswered || permissionDialogShown
                    if (!permissionFlowComplete) {
                        return@LaunchedEffect
                    }
                    
                    // Set in-progress flag BEFORE starting registration to prevent duplicates
                    registrationInProgress = true
                    
                    Log.d("MainActivity", "\n\n")
                    Log.d("MainActivity", "╔═══════════════════════════════════════════════════════════╗")
                    Log.d("MainActivity", "║         STARTING WEATHER TRACKER APPLICATION             ║")
                    Log.d("MainActivity", "╚═══════════════════════════════════════════════════════════╝")
                    Log.d("MainActivity", "")
                    Log.d("MainActivity", "Permission granted=${locationPermissionState.allPermissionsGranted}")
                    
                    try {
                        val (userId, deviceId) = authService.initializeFirstRun()
                        storedDeviceId = deviceId
                        registrationComplete = true
                        Log.d("MainActivity", "")
                        Log.d("MainActivity", "✓ App ready to use! You are authenticated.")
                        Log.d("MainActivity", "")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "")
                        Log.e("MainActivity", "✗ Authentication failed - ${e.message}", e)
                        Log.e("MainActivity", "Make sure the backend server is running: 'python app.py'")
                        Log.e("MainActivity", "")
                        registrationInProgress = false // Allow retry on failure
                    }
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

                // Continuously update UI location AND backend when device location changes
                @SuppressLint("MissingPermission")
                DisposableEffect(locationPermissionState.allPermissionsGranted, storedDeviceId) {
                    var locationCallback: com.google.android.gms.location.LocationCallback? = null
                    
                    if (locationPermissionState.allPermissionsGranted) {
                        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                            10_000L // Update every 10 seconds for UI
                        )
                            .setMinUpdateIntervalMillis(5_000L)
                            .setMinUpdateDistanceMeters(0f)
                            .build()
                        
                        locationCallback = object : com.google.android.gms.location.LocationCallback() {
                            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                                result.lastLocation?.let { location ->
                                    val newLocation = LatLng(location.latitude, location.longitude)
                                    if (userLocation != newLocation) {
                                        Log.d("MainActivity", "📍 UI location updated: lat=${location.latitude}, lon=${location.longitude}")
                                        userLocation = newLocation
                                        
                                        // Also update the backend with the new location
                                        storedDeviceId?.let { deviceId ->
                                            Log.d("MainActivity", "📤 Sending location to backend: device=$deviceId")
                                            BackendRepository.updateCurrentDeviceLocation(
                                                deviceId = deviceId,
                                                latitude = location.latitude,
                                                longitude = location.longitude,
                                                onSuccess = { locationId, action ->
                                                    Log.d("MainActivity", "✓ Backend location $action: $locationId")
                                                },
                                                onError = { e ->
                                                    Log.e("MainActivity", "✗ Backend location update failed: ${e.message}")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        fusedLocationClient.requestLocationUpdates(
                            locationRequest,
                            locationCallback,
                            android.os.Looper.getMainLooper()
                        )
                    }
                    
                    onDispose {
                        locationCallback?.let {
                            fusedLocationClient.removeLocationUpdates(it)
                        }
                    }
                }

                val defaultLocationOptions = remember {
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
                var savedLocationOptions by remember { mutableStateOf(loadSavedLocationOptions(prefs)) }
                val locationOptions = remember(savedLocationOptions) {
                    defaultLocationOptions + savedLocationOptions
                }
                var selectedLocationOption by remember { mutableStateOf(defaultLocationOptions[0]) }

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
                val selectedLocationLabel = selectedLocationOption.label
                val canSaveCurrentLocation = weatherQueryLocation != null &&
                    savedLocationOptions.none { option ->
                        option.latitude != null &&
                            option.longitude != null &&
                            coordinatesMatch(option.latitude, weatherQueryLocation.latitude) &&
                            coordinatesMatch(option.longitude, weatherQueryLocation.longitude)
                    }

                var currentWeather by remember {
                    mutableStateOf(
                        CurrentWeatherUiModel(
                            location = "Loading location...",
                            dayDate = "Loading...",
                            temperature = 0,
                            condition = "Loading weather...",
                            highTemp = 0,
                            lowTemp = 0,
                            weatherType = WeatherType.PartlyCloudy,
                            isDaytime = true,
                            precipitationChance = 0
                        )
                    )
                }
                var alertWeather by remember { mutableStateOf<WeatherAlertUiModel?>(null) }
                var forecastWeather by remember { mutableStateOf<List<DailyForecastUiModel>>(emptyList()) }
                var activeRequestKey by remember { mutableStateOf("") }
                var locationName by remember { mutableStateOf("") }
                var stormRiskTimeline by remember { mutableStateOf<List<Pair<Long, Float>>>(emptyList()) }
                val currentViewLabel = when {
                    locationName.isNotBlank() -> locationName
                    !selectedLocationOption.useDeviceLocation -> selectedLocationLabel
                    else -> ""
                }

                // Load storm risk timeline from Room DB
                LaunchedEffect(storedDeviceId) {
                    val deviceId = storedDeviceId ?: return@LaunchedEffect
                    val db = com.CMPS490.weathertracker.data.WeatherDatabase.getInstance(context)
                    val nowMs = System.currentTimeMillis()
                    val predictions = db.hourlyPredictionDao().getPredictionsFrom(nowMs - LocationTrackingService.MILLIS_PER_HOUR)
                    stormRiskTimeline = predictions.map { it.timestamp to it.stormProbability }
                }

                // Initialize WorkManager tasks
                LaunchedEffect(registrationComplete) {
                    if (!registrationComplete) return@LaunchedEffect
                    com.CMPS490.weathertracker.sync.SnapshotSyncManager.init(context)
                    com.CMPS490.weathertracker.sync.ModelInstanceSyncManager.init(context)
                    val forecastRequest = androidx.work.PeriodicWorkRequestBuilder<com.CMPS490.weathertracker.data.ForecastFetcher>(
                        6, java.util.concurrent.TimeUnit.HOURS
                    ).setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    ).build()
                    androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                        com.CMPS490.weathertracker.data.ForecastFetcher.WORK_NAME,
                        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                        forecastRequest,
                    )
                }

                LaunchedEffect(
                    selectedLocationOption.useDeviceLocation,
                    selectedLocationOption.latitude,
                    selectedLocationOption.longitude
                ) {
                    locationName = ""
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
                }

                // Fetch location name using reverse geocoding
                LaunchedEffect(weatherQueryLocation?.latitude, weatherQueryLocation?.longitude) {
                    if (weatherQueryLocation != null) {
                        val name = GeocodingHelper.getLocationName(
                            context,
                            weatherQueryLocation.latitude,
                            weatherQueryLocation.longitude
                        )
                        locationName = name.takeUnless { it.startsWith("Lat:") }.orEmpty()
                        if (locationName.isNotEmpty()) {
                            // Only overwrite the header when geocoding returned a human-readable place name.
                            currentWeather = currentWeather.copy(location = locationName)
                        }
                    }
                }

                LaunchedEffect(weatherQueryLocation?.latitude, weatherQueryLocation?.longitude, retryTrigger) {
                    if (weatherQueryLocation == null) {
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

                    // Get weather alerts from backend
                    BackendRepository.getAlerts("$lat,$lon",
                        onSuccess = { response ->
                            if (activeRequestKey != requestKey) {
                                return@getAlerts
                            }
                            try {
                                val featuresArray = response.getAsJsonArray("features") ?: return@getAlerts
                                val features = featuresArray.mapNotNull { element ->
                                    val obj = element.asJsonObject
                                    val props = obj.getAsJsonObject("properties")
                                    if (props != null) {
                                        AlertFeature(
                                            properties = AlertProperties(
                                                event = props.get("event")?.asString,
                                                headline = props.get("headline")?.asString,
                                                description = props.get("description")?.asString
                                            )
                                        )
                                    } else null
                                }
                                requestAlert = mapAlertToUi(features.ifEmpty { null })
                                alertWeather = requestAlert
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error parsing alerts: ${e.message}")
                                alertWeather = null
                            }
                        },
                        onError = { error ->
                            if (activeRequestKey != requestKey) {
                                return@getAlerts
                            }
                            Log.e("MainActivity", "Alerts request failed: ${error.message}")
                            alertWeather = null
                        }
                    )

                    // Get weather points from backend
                    BackendRepository.getWeatherPoints(lat, lon,
                        onSuccess = { response ->
                            if (activeRequestKey != requestKey) {
                                return@getWeatherPoints
                            }
                            val point = runCatching {
                                gson.fromJson(response, PointResponse::class.java)
                            }.onFailure { error ->
                                Log.e("MainActivity", "Failed to parse point response: ${error.message}. Raw body: $response", error)
                            }.getOrNull()
                            val forecastUrl = point?.properties?.forecast
                            val forecastHourlyUrl = point?.properties?.forecastHourly
                            
                            if (forecastUrl == null) {
                                Log.e("MainActivity", "Point response missing forecast URL. Raw body: $response")
                                currentWeather = currentWeather.copy(condition = "No forecast available")
                                forecastWeather = emptyList()
                                return@getWeatherPoints
                            }

                            // Get forecast from backend
                            BackendRepository.getForecast(forecastUrl,
                                onSuccess = { forecastResponse ->
                                    if (activeRequestKey != requestKey) {
                                        return@getForecast
                                    }
                                    val forecast = runCatching {
                                        gson.fromJson(forecastResponse, ForecastResponse::class.java)
                                    }.onFailure { error ->
                                        Log.e("MainActivity", "Failed to parse forecast response: ${error.message}. Raw body: $forecastResponse", error)
                                    }.getOrNull()
                                    val forecastPeriods = forecast?.properties?.periods.orEmpty()
                                    if (forecastPeriods.isEmpty()) {
                                        Log.e("MainActivity", "Forecast response missing periods. Raw body: $forecastResponse")
                                        currentWeather = currentWeather.copy(condition = "No forecast available")
                                        forecastWeather = emptyList()
                                        return@getForecast
                                    }

                                    // Prefer a city/state label from geocoding or the weather point response.
                                    val pointLocationLabel = buildLocationLabel(point)
                                    val locationLabel = when {
                                        locationName.isNotBlank() -> locationName
                                        pointLocationLabel != "Selected location" -> pointLocationLabel
                                        else -> selectedLocationLabel
                                    }
                                    currentWeather = mapCurrentWeather(locationLabel, forecastPeriods)
                                    forecastWeather = mapForecast(forecastPeriods)
                                    backendConnected = true

                                    WeatherApiCache.put(
                                        lat = lat,
                                        lon = lon,
                                        currentWeather = currentWeather,
                                        alertWeather = requestAlert,
                                        forecastWeather = forecastWeather
                                    )

                                    if (forecastHourlyUrl != null) {
                                        BackendRepository.getForecast(forecastHourlyUrl,
                                            onSuccess = { hourlyResponse ->
                                                if (activeRequestKey != requestKey) {
                                                    return@getForecast
                                                }
                                                val hourlyForecast = runCatching {
                                                    gson.fromJson(hourlyResponse, ForecastResponse::class.java)
                                                }.onFailure { error ->
                                                    Log.e("MainActivity", "Failed to parse hourly forecast response: ${error.message}. Raw body: $hourlyResponse", error)
                                                }.getOrNull()
                                                val hourlyNow = hourlyForecast?.properties?.periods?.firstOrNull()
                                                if (hourlyNow != null) {
                                                    currentWeather = currentWeather.copy(
                                                        temperature = hourlyNow.temperature,
                                                        condition = hourlyNow.shortForecast,
                                                        weatherType = toWeatherType(hourlyNow.shortForecast),
                                                        isDaytime = hourlyNow.isDaytime,
                                                        precipitationChance = hourlyNow.probabilityOfPrecipitation?.value?.toInt() ?: 0
                                                    )
                                                    WeatherApiCache.put(
                                                        lat = lat,
                                                        lon = lon,
                                                        currentWeather = currentWeather,
                                                        alertWeather = requestAlert,
                                                        forecastWeather = forecastWeather
                                                    )
                                                }
                                            },
                                            onError = { error ->
                                                if (activeRequestKey != requestKey) {
                                                    return@getForecast
                                                }
                                                Log.e("MainActivity", "Hourly forecast error: ${error.message}")
                                            }
                                        )
                                    }
                                },
                                onError = { error ->
                                    if (activeRequestKey != requestKey) {
                                        return@getForecast
                                    }
                                    Log.e("MainActivity", "Forecast error: ${error.message}")
                                    forecastWeather = emptyList()
                                }
                            )
                        },
                        onError = { error ->
                            if (activeRequestKey != requestKey) {
                                return@getWeatherPoints
                            }
                            Log.e("MainActivity", "Backend connection error: ${error.message}")
                            backendConnected = false
                            forecastWeather = emptyList()
                        }
                    )
                }

                // Fallback: fetch from Open-Meteo when backend is unavailable + retry every 10s
                LaunchedEffect(backendConnected, weatherQueryLocation?.latitude, weatherQueryLocation?.longitude) {
                    if (backendConnected || weatherQueryLocation == null) return@LaunchedEffect

                    val lat = weatherQueryLocation.latitude
                    val lon = weatherQueryLocation.longitude
                    val label = locationName.ifBlank { "Current Location" }

                    val fallback = OpenMeteoFallback.fetch(lat, lon, label)
                    if (fallback != null) {
                        currentWeather = fallback.current
                        forecastWeather = fallback.forecast
                    }

                    // Retry backend connection every 10 seconds
                    while (true) {
                        delay(10_000)
                        if (OpenMeteoFallback.checkBackendHealth()) {
                            Log.d("MainActivity", "✓ Backend connection restored")
                            backendConnected = true
                            retryTrigger++
                            break
                        }
                        Log.d("MainActivity", "⏳ Backend still unreachable, retrying in 10s...")
                    }
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
                            canSaveCurrentLocation = canSaveCurrentLocation,
                            onSaveCurrentLocation = {
                                val locationToSave = weatherQueryLocation ?: return@WeatherOverviewScreen
                                val baseLabel = currentViewLabel.ifBlank {
                                    "Saved ${locationToSave.latitude.formatCoordinate()}, ${locationToSave.longitude.formatCoordinate()}"
                                }
                                val uniqueLabel = makeUniqueLocationLabel(baseLabel, locationOptions)
                                val savedOption = LocationOptionUiModel(
                                    label = uniqueLabel,
                                    latitude = locationToSave.latitude,
                                    longitude = locationToSave.longitude,
                                    useDeviceLocation = false
                                )
                                savedLocationOptions = savedLocationOptions + savedOption
                                persistSavedLocationOptions(prefs, savedLocationOptions)
                                selectedLocationOption = savedOption
                            },
                            onLiveRadarClick = { navController.navigate("map_screen") },
                            stormRiskTimeline = stormRiskTimeline,
                            backendConnected = backendConnected,
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

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "→ App paused - updating last_seen_at timestamp")
        
        // Update last_seen_at when app is paused/closed
        lifecycleScope.launch {
            try {
                val authService = AuthenticationService(this@MainActivity)
                val deviceId = authService.getStoredDeviceId()
                
                if (deviceId != null) {
                    val currentTimestamp = Instant.now().toString()
                    val result = SupabaseRepository.updateDevice(
                        deviceId = deviceId,
                        lastSeenAt = currentTimestamp
                    )
                    
                    if (result.isSuccess) {
                        Log.d("MainActivity", "✓ Updated last_seen_at: $currentTimestamp")
                    } else {
                        Log.e("MainActivity", "✗ Failed to update last_seen_at: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    Log.d("MainActivity", "⚠ No device ID found - skipping last_seen_at update")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "✗ Error updating last_seen_at: ${e.message}", e)
            }
        }
    }

}

private fun mapCurrentWeather(
    locationLabel: String,
    periods: List<ForecastPeriod>
): CurrentWeatherUiModel {
    val firstPeriod = periods.first()
    val dateLabel = formatDateLong(firstPeriod.startTime)
    val lowTemp = (firstPeriod.temperature - 8).coerceAtLeast(0)
    val weatherType = toWeatherType(firstPeriod.shortForecast)
    val precipitationChance = firstPeriod.probabilityOfPrecipitation?.value?.toInt() ?: 0
    return CurrentWeatherUiModel(
        location = locationLabel,
        dayDate = dateLabel,
        temperature = firstPeriod.temperature,
        condition = firstPeriod.shortForecast,
        highTemp = firstPeriod.temperature,
        lowTemp = lowTemp,
        weatherType = weatherType,
        isDaytime = firstPeriod.isDaytime,
        precipitationChance = precipitationChance
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

private fun buildLocationLabel(pointResponse: PointResponse?): String {
    val city = pointResponse?.properties?.relativeLocation?.properties?.city
    val state = pointResponse?.properties?.relativeLocation?.properties?.state
    return if (!city.isNullOrBlank() && !state.isNullOrBlank()) {
        "$city, $state"
    } else {
        "Selected location"
    }
}

private fun loadSavedLocationOptions(prefs: SharedPreferences): List<LocationOptionUiModel> {
    val raw = prefs.getString(SAVED_LOCATIONS_PREF_KEY, null) ?: return emptyList()
    return runCatching {
        val jsonArray = org.json.JSONArray(raw)
        buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(index)
                add(
                    LocationOptionUiModel(
                        label = item.getString("label"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        useDeviceLocation = false
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun persistSavedLocationOptions(
    prefs: SharedPreferences,
    savedLocations: List<LocationOptionUiModel>
) {
    val jsonArray = org.json.JSONArray()
    savedLocations.forEach { option ->
        if (option.latitude != null && option.longitude != null && !option.useDeviceLocation) {
            jsonArray.put(
                org.json.JSONObject().apply {
                    put("label", option.label)
                    put("latitude", option.latitude)
                    put("longitude", option.longitude)
                }
            )
        }
    }
    prefs.edit().putString(SAVED_LOCATIONS_PREF_KEY, jsonArray.toString()).apply()
}

private fun makeUniqueLocationLabel(
    baseLabel: String,
    existingOptions: List<LocationOptionUiModel>
): String {
    if (existingOptions.none { it.label == baseLabel }) {
        return baseLabel
    }
    var suffix = 2
    while (existingOptions.any { it.label == "$baseLabel ($suffix)" }) {
        suffix += 1
    }
    return "$baseLabel ($suffix)"
}

private fun Double.formatCoordinate(): String = String.format(Locale.US, "%.3f", this)
