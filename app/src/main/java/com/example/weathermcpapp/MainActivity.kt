package com.example.weathermcpapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.weathermcpapp.network.ForecastResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import androidx.compose.material3.MaterialTheme
import com.example.weathermcpapp.network.RetrofitInstance
import com.example.weathermcpapp.network.WeatherApi
import com.example.weathermcpapp.network.PointResponse

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
fun WeatherScreen() {
    var weatherText by remember { mutableStateOf("Loading weather...") }

    // Make API call once when Composable enters composition
    LaunchedEffect(Unit) {
        RetrofitInstance.api.getPointData(30.2241, -92.0198)
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
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Weather Forecast:",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = weatherText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
fun WeatherScreenPreview() {
    MaterialTheme {
        WeatherScreen()
    }
}