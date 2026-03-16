package com.example.weathermcpapp

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.weathermcpapp.network.AlertFeature
import com.example.weathermcpapp.network.AlertsResponse
import com.example.weathermcpapp.network.ForecastResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import androidx.compose.material3.MaterialTheme
import com.example.weathermcpapp.network.RetrofitInstance
import com.example.weathermcpapp.network.PointResponse

data class LouisianaLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WeatherScreen()
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WeatherScreen() {
    val louisianaLocations = listOf(
        LouisianaLocation("Baton Rouge, LA", 30.4515, -91.1871),
        LouisianaLocation("New Orleans, LA", 29.9511, -90.0715),
        LouisianaLocation("Lafayette, LA", 30.2241, -92.0198),
        LouisianaLocation("Shreveport, LA", 32.5252, -93.7502),
        LouisianaLocation("Lake Charles, LA", 30.2266, -93.2174),
        LouisianaLocation("Monroe, LA", 32.5093, -92.1193)
    )

    var selectedLocation by remember { mutableStateOf(louisianaLocations.first()) }
    var weatherText by remember { mutableStateOf("Loading weather...") }
    var alerts by remember { mutableStateOf<List<AlertFeature>>(emptyList()) }
    var alertsStatusText by remember { mutableStateOf("Loading alerts...") }
    var isMainAlertExpanded by remember { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedLocation) {
        weatherText = "Loading weather..."
        alerts = emptyList()
        alertsStatusText = "Loading alerts..."
        isMainAlertExpanded = false

        RetrofitInstance.api.getActiveAlertsByPoint(
            "${selectedLocation.latitude},${selectedLocation.longitude}"
        ).enqueue(object : Callback<AlertsResponse> {
            override fun onResponse(
                call: Call<AlertsResponse>,
                response: Response<AlertsResponse>
            ) {
                val activeAlerts = response.body()?.features.orEmpty()
                alerts = activeAlerts
                alertsStatusText = if (activeAlerts.isEmpty()) {
                    "No active alerts for this location."
                } else {
                    ""
                }
            }

            override fun onFailure(call: Call<AlertsResponse>, t: Throwable) {
                alerts = emptyList()
                alertsStatusText = "Alerts fetch failed: ${t.message}"
            }
        })

        RetrofitInstance.api.getPointData(selectedLocation.latitude, selectedLocation.longitude)
            .enqueue(object : Callback<PointResponse> {
                override fun onResponse(
                    call: Call<PointResponse>,
                    response: Response<PointResponse>
                ) {
                    val forecastUrl = response.body()?.properties?.forecast
                    if (forecastUrl != null) {
                        RetrofitInstance.api.getForecastFromUrl(forecastUrl)
                            .enqueue(object : Callback<ForecastResponse> {
                                override fun onResponse(
                                    call: Call<ForecastResponse>,
                                    forecastResponse: Response<ForecastResponse>
                                ) {
                                    val firstPeriod =
                                        forecastResponse.body()?.properties?.periods?.firstOrNull()
                                    weatherText =
                                        firstPeriod?.detailedForecast ?: "No forecast available"
                                }

                                override fun onFailure(call: Call<ForecastResponse>, t: Throwable) {
                                    weatherText = "Forecast fetch failed: ${t.message}"
                                }
                            })
                    } else {
                        weatherText = "Could not get forecast URL"
                    }
                }

                override fun onFailure(call: Call<PointResponse>, t: Throwable) {
                    weatherText = "Point fetch failed: ${t.message}"
                }
            })
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        ExposedDropdownMenuBox(
            expanded = isMenuExpanded,
            onExpandedChange = { isMenuExpanded = !isMenuExpanded }
        ) {
            OutlinedTextField(
                value = selectedLocation.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Select Louisiana location") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = isMenuExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )

            ExposedDropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false }
            ) {
                louisianaLocations.forEach { location ->
                    DropdownMenuItem(
                        text = { Text(location.name) },
                        onClick = {
                            selectedLocation = location
                            isMenuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Weather Forecast:",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Location: ${selectedLocation.name}",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = weatherText,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Weather Alerts:",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        val mainAlert = alerts.firstOrNull()
        if (mainAlert != null) {
            val alertTitle = mainAlert.properties.event
                ?: mainAlert.properties.headline
                ?: "Weather Alert"
            val alertDescription = mainAlert.properties.description ?: "No details provided."

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isMainAlertExpanded = !isMainAlertExpanded }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = alertTitle,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isMainAlertExpanded) "Tap to hide details" else "Tap to view details",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (isMainAlertExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = alertDescription,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } else {
            Text(
                text = alertsStatusText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WeatherScreenPreview() {
    MaterialTheme {
        WeatherScreen()
    }
}