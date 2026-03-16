package com.CMPS490.weathertracker.network

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface WeatherApi {
    @GET("points/{lat},{lon}")
    fun getPointData(
        @Path("lat") lat: Double,
        @Path("lon") lon: Double
    ): Call<PointResponse>

    @GET
    fun getForecastFromUrl(
        @Url url: String
    ): Call<ForecastResponse>

    @GET("alerts/active")
    fun getActiveAlertsByPoint(
        @Query("point") point: String
    ): Call<AlertsResponse>
}