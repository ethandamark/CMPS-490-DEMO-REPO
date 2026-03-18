package com.CMPS490.weathertracker.network

import retrofit2.Call
import retrofit2.http.GET

interface RainViewerApi {
    @GET("public/weather-maps.json")
    fun getWeatherMaps(): Call<RainViewerResponse>
}
