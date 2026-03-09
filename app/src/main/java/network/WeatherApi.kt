package com.example.weathermcpapp.network

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

interface WeatherApi {

    @GET("points/{lat},{lon}")
    fun getPointData(
        @Path("lat") lat: Double,
        @Path("lon") lon: Double
    ): Call<PointResponse>

    @GET
    fun getForecastFromUrl(
        @retrofit2.http.Url url: String
    ): Call<ForecastResponse>
}

