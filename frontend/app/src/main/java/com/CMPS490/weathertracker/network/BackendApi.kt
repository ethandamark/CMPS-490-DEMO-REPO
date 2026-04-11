package com.CMPS490.weathertracker.network

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
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
    
    // ===== DEVICE LOCATION =====
    
    /**
     * Create a new device location record
     * Required: device_id, latitude, longitude
     * Optional: captured_at (auto-generated if not provided)
     */
    @POST("device-location/create")
    fun createDeviceLocation(@Body request: JsonObject): Call<JsonObject>
    
    /**
     * Get all location records for a specific device
     */
    @GET("device-location/by-device/{device_id}")
    fun getDeviceLocationsByDevice(
        @Path("device_id") deviceId: String,
        @Query("limit") limit: Int = 100
    ): Call<JsonObject>
    
    /**
     * Get a specific device location by location_id
     */
    @GET("device-location/{location_id}")
    fun getDeviceLocationById(@Path("location_id") locationId: String): Call<JsonObject>
    
    /**
     * Get the most recent location for a device
     */
    @GET("device-location/latest/{device_id}")
    fun getLatestDeviceLocation(@Path("device_id") deviceId: String): Call<JsonObject>
    
    /**
     * Update or create the current location for a device (upsert behavior).
     * Used by background location tracking service.
     * Required: device_id, latitude, longitude
     */
    @POST("device-location/update-current")
    fun updateCurrentDeviceLocation(@Body request: JsonObject): Call<JsonObject>
    
    /**
     * Update a device location record
     * Optional fields: latitude, longitude, captured_at
     */
    @PATCH("device-location/{location_id}")
    fun updateDeviceLocation(
        @Path("location_id") locationId: String,
        @Body request: JsonObject
    ): Call<JsonObject>
    
    /**
     * Delete a device location record
     */
    @DELETE("device-location/{location_id}")
    fun deleteDeviceLocation(@Path("location_id") locationId: String): Call<JsonObject>
}
