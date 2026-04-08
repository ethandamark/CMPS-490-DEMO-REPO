package com.CMPS490.weathertracker.network

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import com.google.gson.JsonObject

/**
 * Retrofit interface for backend FastAPI endpoints
 * All API calls route through the local FastAPI backend
 */
interface BackendApi {
    
    // ===== HEALTH CHECK =====
    @GET("health")
    fun healthCheck(): Call<JsonObject>
    
    // ===== WEATHER API PROXIES =====
    @GET("weather/points/{lat}/{lon}")
    fun getWeatherPoints(
        @Path("lat") lat: Double,
        @Path("lon") lon: Double
    ): Call<JsonObject>
    
    @GET("weather/forecast")
    fun getForecast(
        @Query("url") forecastUrl: String
    ): Call<JsonObject>
    
    @GET("weather/alerts")
    fun getAlerts(
        @Query("point") point: String
    ): Call<JsonObject>
    
    // ===== RAINVIEWER API PROXY =====
    @GET("rainviewer/maps")
    fun getWeatherMaps(): Call<JsonObject>
    
    // ===== SUPABASE PROXIES =====
    @POST("supabase/register")
    fun register(@Body record: JsonObject): Call<JsonObject>
    
    // ===== ML PREDICTION =====
    @POST("predict")
    fun getPrediction(@Body request: JsonObject): Call<JsonObject>
    
    // ===== FIREBASE NOTIFICATIONS =====
    @POST("notifications/register-device")
    fun registerDeviceToken(@Body request: JsonObject): Call<JsonObject>
    
    @POST("notifications/send")
    fun sendNotification(@Body request: JsonObject): Call<JsonObject>
    
    @POST("notifications/weather-alert")
    fun sendWeatherAlert(@Body request: JsonObject): Call<JsonObject>
}
