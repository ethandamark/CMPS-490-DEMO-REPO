package com.CMPS490.weathertracker

import android.util.Log
import com.CMPS490.weathertracker.network.BackendRetrofitInstance
import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Backend API Repository
 * Central repository for all API calls routed through the FastAPI backend
 * Handles Weather API, RainViewer, and Supabase operations
 */
object BackendRepository {
    private const val TAG = "BackendRepository"
    private val api = BackendRetrofitInstance.api
    
    // ===== WEATHER API OPERATIONS =====
    
    /**
     * Get weather point data for given coordinates
     */
    fun getWeatherPoints(
        lat: Double,
        lon: Double,
        onSuccess: (JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val call = api.getWeatherPoints(lat, lon)
        call.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful && response.body() != null) {
                    Log.d(TAG, "✓ Weather points retrieved successfully")
                    onSuccess(response.body()!!)
                } else {
                    Log.e(TAG, "✗ Weather points error: ${response.code()}")
                    onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            }
            
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                Log.e(TAG, "✗ Weather points failure: ${t.message}", t)
                onError(t as Exception)
            }
        })
    }
    
    /**
     * Get forecast for given forecast URL
     */
    fun getForecast(
        forecastUrl: String,
        onSuccess: (JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val call = api.getForecast(forecastUrl)
        call.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful && response.body() != null) {
                    Log.d(TAG, "✓ Forecast retrieved successfully")
                    onSuccess(response.body()!!)
                } else {
                    Log.e(TAG, "✗ Forecast error: ${response.code()}")
                    onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            }
            
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                Log.e(TAG, "✗ Forecast failure: ${t.message}", t)
                onError(t as Exception)
            }
        })
    }
    
    /**
     * Get active weather alerts for a point
     */
    fun getAlerts(
        point: String,
        onSuccess: (JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val call = api.getAlerts(point)
        call.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful && response.body() != null) {
                    Log.d(TAG, "✓ Alerts retrieved successfully")
                    onSuccess(response.body()!!)
                } else {
                    Log.e(TAG, "✗ Alerts error: ${response.code()}")
                    onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            }
            
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                Log.e(TAG, "✗ Alerts failure: ${t.message}", t)
                onError(t as Exception)
            }
        })
    }
    
    
    // ===== RAINVIEWER API OPERATIONS =====
    
    /**
     * Get RainViewer weather maps
     */
    fun getWeatherMaps(
        onSuccess: (JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val call = api.getWeatherMaps()
        call.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful && response.body() != null) {
                    Log.d(TAG, "✓ Weather maps retrieved successfully")
                    onSuccess(response.body()!!)
                } else {
                    Log.e(TAG, "✗ Weather maps error: ${response.code()}")
                    onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            }
            
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                Log.e(TAG, "✗ Weather maps failure: ${t.message}", t)
                onError(t as Exception)
            }
        })
    }
    
    
    // ===== SUPABASE OPERATIONS =====
    
    /**
     * Create anonymous user via backend proxy
     */
    fun createAnonUser(
        anonUserId: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Creating anonymous user: $anonUserId via backend")
            val record = JsonObject().apply {
                addProperty("anonUserId", anonUserId)
                addProperty("status", "active")
            }
            
            val call = api.createAnonUser(record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✓ Anonymous user created successfully: $anonUserId")
                        onSuccess()
                    } else {
                        Log.e(TAG, "✗ User creation error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ User creation failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error creating anonymous user: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Create device via backend proxy
     */
    fun createDevice(
        deviceId: String,
        anonUserId: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Creating device: $deviceId linked to user: $anonUserId via backend")
            val record = JsonObject().apply {
                addProperty("deviceId", deviceId)
                addProperty("anonUserId", anonUserId)
                addProperty("platform", "android")
                addProperty("appVersion", "1.0")
            }
            
            val call = api.createDevice(record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✓ Device created successfully: $deviceId")
                        onSuccess()
                    } else {
                        Log.e(TAG, "✗ Device creation error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Device creation failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error creating device: ${e.message}", e)
            onError(e)
        }
    }
    
    
    // ===== ML PREDICTION =====
    
    /**
     * Get ML prediction from backend
     */
    fun getPrediction(
        requestData: JsonObject,
        onSuccess: (JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val call = api.getPrediction(requestData)
        call.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful && response.body() != null) {
                    Log.d(TAG, "✓ Prediction retrieved successfully")
                    onSuccess(response.body()!!)
                } else {
                    Log.e(TAG, "✗ Prediction error: ${response.code()}")
                    onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            }
            
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                Log.e(TAG, "✗ Prediction failure: ${t.message}", t)
                onError(t as Exception)
            }
        })
    }
    
    
    // ===== FIREBASE NOTIFICATIONS =====
    
    /**
     * Register device FCM token with backend
     */
    fun registerDeviceToken(
        deviceToken: String,
        deviceId: String,
        userId: String? = null,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Registering device token for: $deviceId")
            val record = JsonObject().apply {
                addProperty("device_token", deviceToken)
                addProperty("device_id", deviceId)
                userId?.let { addProperty("user_id", it) }
            }
            
            val call = api.registerDeviceToken(record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✓ Device token registered successfully")
                        onSuccess()
                    } else {
                        Log.e(TAG, "✗ Token registration error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Token registration failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error registering device token: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Send a notification via backend
     */
    fun sendNotification(
        deviceToken: String,
        title: String,
        body: String,
        data: Map<String, String>? = null,
        notificationType: String = "alert",
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Sending notification: $title")
            val record = JsonObject().apply {
                addProperty("device_token", deviceToken)
                addProperty("title", title)
                addProperty("body", body)
                addProperty("notification_type", notificationType)
                data?.let {
                    val dataObj = JsonObject()
                    it.forEach { (k, v) -> dataObj.addProperty(k, v) }
                    add("data", dataObj)
                }
            }
            
            val call = api.sendNotification(record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✓ Notification sent successfully")
                        onSuccess()
                    } else {
                        Log.e(TAG, "✗ Notification send error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Notification send failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error sending notification: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Send a weather alert notification
     */
    fun sendWeatherAlert(
        deviceToken: String,
        location: String,
        alertType: String,
        description: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Sending weather alert: $alertType for $location")
            val record = JsonObject().apply {
                addProperty("device_token", deviceToken)
                addProperty("location", location)
                addProperty("alert_type", alertType)
                addProperty("description", description)
            }
            
            val call = api.sendWeatherAlert(record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✓ Weather alert sent successfully")
                        onSuccess()
                    } else {
                        Log.e(TAG, "✗ Weather alert send error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Weather alert send failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error sending weather alert: ${e.message}", e)
            onError(e)
        }
    }
    
    
    // ===== HEALTH CHECK =====
    
    /**
     * Check if backend is healthy
     */
    fun healthCheck(
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val call = api.healthCheck()
        call.enqueue(object : Callback<JsonObject> {
            override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                if (response.isSuccessful) {
                    Log.d(TAG, "✓ Backend is healthy")
                    onSuccess()
                } else {
                    Log.e(TAG, "✗ Backend health check failed: ${response.code()}")
                    onError(Exception("Backend unhealthy: ${response.code()}"))
                }
            }
            
            override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                Log.e(TAG, "✗ Backend health check failure: ${t.message}", t)
                onError(t as Exception)
            }
        })
    }
}
