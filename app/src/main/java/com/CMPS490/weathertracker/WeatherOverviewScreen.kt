package com.CMPS490.weathertracker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.CMPS490.weathertracker.ui.theme.ThemeMode
import com.CMPS490.weathertracker.ui.theme.WeatherTheme
import com.CMPS490.weathertracker.ui.theme.WeatherTrackerTheme
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState

@Composable
fun WeatherOverviewScreen(
    currentWeather: CurrentWeatherUiModel,
    alert: WeatherAlertUiModel?,
    forecast: List<DailyForecastUiModel>,
    userLocation: LatLng?,
    selectedThemeMode: ThemeMode,
    wantsNotifications: Boolean,
    notificationPhoneNumber: String,
    notificationSubmitInFlight: Boolean,
    notificationSubmissionStatus: String?,
    locationOptions: List<LocationOptionUiModel>,
    selectedLocationOption: LocationOptionUiModel,
    onLocationSelected: (LocationOptionUiModel) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onNotificationPhoneNumberChange: (String) -> Unit,
    onNotificationSubmit: () -> Unit,
    onLiveRadarClick: () -> Unit
) {
    val appColors = WeatherTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(appColors.backgroundGradientTop, appColors.backgroundGradientBottom)
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
                ThemeModeCard(
                    selectedThemeMode = selectedThemeMode,
                    onThemeModeSelected = onThemeModeSelected
                )
            }

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

            item {
                NotificationSignupCard(
                    wantsNotifications = wantsNotifications,
                    phoneNumber = notificationPhoneNumber,
                    submitInFlight = notificationSubmitInFlight,
                    submissionStatus = notificationSubmissionStatus,
                    onNotificationPreferenceChange = onNotificationPreferenceChange,
                    onPhoneNumberChange = onNotificationPhoneNumberChange,
                    onSubmit = onNotificationSubmit
                )
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
                    color = appColors.textMuted,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(forecast) { day ->
                ForecastRow(day)
            }
        }
    }
}

@Composable
fun ThemeModeCard(
    selectedThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit
) {
    val appColors = WeatherTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = appColors.cardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.labelMedium,
                color = appColors.textMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = themeModeLabel(selectedThemeMode))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(themeModeLabel(mode)) },
                            onClick = {
                                onThemeModeSelected(mode)
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
fun NotificationSignupCard(
    wantsNotifications: Boolean,
    phoneNumber: String,
    submitInFlight: Boolean,
    submissionStatus: String?,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val appColors = WeatherTheme.colors
    val phoneNumberComplete = phoneNumber.length == 10

    Surface(
        color = appColors.cardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Want to receive notifications?",
                        style = MaterialTheme.typography.titleMedium,
                        color = appColors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Add a phone number for future weather alert notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = appColors.textMuted
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = wantsNotifications,
                    onCheckedChange = onNotificationPreferenceChange
                )
            }

            AnimatedVisibility(visible = wantsNotifications) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { input ->
                            onPhoneNumberChange(input.filter(Char::isDigit).take(10))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Phone number") },
                        placeholder = { Text("5551234567") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Phone
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = appColors.textPrimary,
                            unfocusedTextColor = appColors.textPrimary,
                            focusedLabelColor = appColors.textPrimary,
                            unfocusedLabelColor = appColors.textMuted,
                            focusedPlaceholderColor = appColors.textMuted,
                            unfocusedPlaceholderColor = appColors.textMuted,
                            focusedBorderColor = appColors.inputBorder,
                            unfocusedBorderColor = appColors.textMuted,
                            focusedSupportingTextColor = appColors.textMuted,
                            unfocusedSupportingTextColor = appColors.textMuted,
                            errorTextColor = appColors.textPrimary,
                            errorLabelColor = appColors.alert,
                            errorBorderColor = appColors.alert,
                            errorSupportingTextColor = appColors.alert,
                            cursorColor = appColors.textPrimary
                        ),
                        supportingText = {
                            Text(
                                text = if (phoneNumberComplete) {
                                    "Ready to send to the backend."
                                } else {
                                    "Enter a 10-digit number."
                                }
                            )
                        },
                        isError = phoneNumber.isNotEmpty() && !phoneNumberComplete
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onSubmit,
                            enabled = phoneNumberComplete && !submitInFlight
                        ) {
                            if (submitInFlight) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("OK")
                            }
                        }
                    }
                    submissionStatus?.let { status ->
                        Text(
                            text = status,
                            color = if (phoneNumberComplete) appColors.textPrimary else appColors.alert,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LocationSelectorCard(
    locationOptions: List<LocationOptionUiModel>,
    selectedLocationOption: LocationOptionUiModel,
    onLocationSelected: (LocationOptionUiModel) -> Unit
) {
    val appColors = WeatherTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = appColors.cardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Location Source",
                style = MaterialTheme.typography.labelMedium,
                color = appColors.textMuted,
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
    val appColors = WeatherTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = weather.location,
            style = MaterialTheme.typography.headlineSmall,
            color = appColors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = weather.dayDate,
            style = MaterialTheme.typography.bodyMedium,
            color = appColors.textMuted
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "${weather.temperature}°",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 100.sp),
            color = appColors.textPrimary,
            fontWeight = FontWeight.Thin
        )
        Text(
            text = weather.condition,
            style = MaterialTheme.typography.titleLarge,
            color = appColors.textPrimary
        )
        Text(
            text = "H:${weather.highTemp}° L:${weather.lowTemp}°",
            style = MaterialTheme.typography.bodyLarge,
            color = appColors.textPrimary
        )
    }
}

@Composable
fun WeatherAlertCard(alert: WeatherAlertUiModel) {
    val appColors = WeatherTheme.colors
    Surface(
        color = appColors.cardBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, appColors.alert.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = appColors.alert,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = alert.title,
                    color = appColors.alert,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = alert.description,
                    color = appColors.textPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun LiveRadarCard(userLocation: LatLng?, onClick: () -> Unit) {
    val appColors = WeatherTheme.colors
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userLocation ?: LatLng(30.2241, -92.0198), 10f)
    }

    LaunchedEffect(userLocation) {
        userLocation?.let {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 10f)
        }
    }

    Surface(
        color = appColors.cardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    mapType = MapType.SATELLITE,
                    isMyLocationEnabled = false
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
                userLocation?.let {
                    Circle(
                        center = it,
                        radius = 1500.0,
                        strokeColor = Color.Red,
                        strokeWidth = 2f,
                        fillColor = Color.Red.copy(alpha = 0.2f)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, appColors.mapOverlay)
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
                    color = appColors.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap to expand",
                    color = appColors.textMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Icon(
                imageVector = Icons.Rounded.OpenInFull,
                contentDescription = null,
                tint = appColors.textPrimary,
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
    val appColors = WeatherTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Surface(
            color = if (day.isToday) appColors.cardBackground else Color.Transparent,
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
                        color = appColors.textPrimary,
                        fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = day.dateLabel,
                        color = appColors.textMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.weight(1.5f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = getWeatherIcon(day.weatherType),
                        contentDescription = null,
                        tint = appColors.textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    if (day.precipitationChance > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${day.precipitationChance}%",
                            color = appColors.precipitation,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "${day.highTemp}°", color = appColors.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${day.lowTemp}°", color = appColors.textMuted)
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
    val appColors = WeatherTheme.colors
    Surface(
        color = appColors.cardBackgroundStrong.copy(alpha = 0.85f),
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                DetailItem(label = "Feels Like", value = "${day.feelsLike}°", modifier = Modifier.weight(1f))
                DetailItem(label = "Wind", value = day.windText, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String, modifier: Modifier = Modifier) {
    val appColors = WeatherTheme.colors
    Column(modifier = modifier) {
        Text(text = label, color = appColors.textMuted, style = MaterialTheme.typography.labelSmall)
        Text(text = value, color = appColors.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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

private fun themeModeLabel(mode: ThemeMode): String {
    return when (mode) {
        ThemeMode.Light -> "Light Mode"
        ThemeMode.Dark -> "Dark Mode"
        ThemeMode.HighContrast -> "High Contrast Mode"
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
        DailyForecastUiModel("Today", "Oct 23", WeatherType.PartlyCloudy, 82, 64, 10, "12 mph", 80, true),
        DailyForecastUiModel("Tuesday", "Oct 24", WeatherType.Sunny, 85, 66, 0, "8 mph", 84),
        DailyForecastUiModel("Wednesday", "Oct 25", WeatherType.Rainy, 76, 62, 80, "15 mph", 75)
    )

    WeatherTrackerTheme(themeMode = ThemeMode.Dark) {
        WeatherOverviewScreen(
            currentWeather = mockCurrent,
            alert = mockAlert,
            forecast = mockForecast,
            userLocation = LatLng(30.2241, -92.0198),
            selectedThemeMode = ThemeMode.Dark,
            wantsNotifications = true,
            notificationPhoneNumber = "3375551234",
            notificationSubmitInFlight = false,
            notificationSubmissionStatus = "Phone number saved. Connect the backend endpoint in MainActivity to submit it.",
            locationOptions = listOf(
                LocationOptionUiModel("Use device location", null, null, true),
                LocationOptionUiModel("Baton Rouge, LA", 30.4515, -91.1871),
                LocationOptionUiModel("Lafayette, LA", 30.2241, -92.0198)
            ),
            selectedLocationOption = LocationOptionUiModel("Baton Rouge, LA", 30.4515, -91.1871),
            onLocationSelected = {},
            onThemeModeSelected = {},
            onNotificationPreferenceChange = {},
            onNotificationPhoneNumberChange = {},
            onNotificationSubmit = {},
            onLiveRadarClick = {}
        )
    }
}
