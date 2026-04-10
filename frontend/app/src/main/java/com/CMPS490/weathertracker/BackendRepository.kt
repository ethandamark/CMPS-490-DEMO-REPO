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
     * Register anonymous user + device in one call.
     * Backend generates ALL identifiers (userId, deviceId, alertToken).
     * Frontend only sends locationPermissionStatus (a device-side fact).
     * Returns (userId, deviceId) pair on success.
     */
    fun register(
        locationPermissionStatus: Boolean = false,
        deviceToken: String? = null,
        onSuccess: (userId: String, deviceId: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Requesting backend to register user + device...")
            val record = JsonObject().apply {
                addProperty("locationPermissionStatus", locationPermissionStatus)
                deviceToken?.let { addProperty("deviceToken", it) }
            }
            
            val call = api.register(record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val userId = body.get("userId")?.asString ?: ""
                        val deviceId = body.get("deviceId")?.asString ?: ""
                        Log.d(TAG, "✓ Registered: user=$userId, device=$deviceId")
                        onSuccess(userId, deviceId)
                    } else {
                        Log.e(TAG, "✗ Registration error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Registration failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error registering: ${e.message}", e)
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
    
    
    // ===== DEVICE LOCATION OPERATIONS =====
    
    /**
     * Create a new device location record
     * Stores: location_id (auto-generated), device_id, latitude, longitude, captured_at
     */
    fun createDeviceLocation(
        deviceId: String,
        latitude: Double,
        longitude: Double,
        capturedAt: String? = null,
        onSuccess: (locationId: String, lat: Double, lon: Double, capturedAt: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Creating device location for device: $deviceId at ($latitude, $longitude)")
            val record = JsonObject().apply {
                addProperty("device_id", deviceId)
                addProperty("latitude", latitude)
                addProperty("longitude", longitude)
                capturedAt?.let { addProperty("captured_at", it) }
            }
            
            val call = api.createDeviceLocation(record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val locId = body.get("location_id")?.asString ?: ""
                        val lat = body.get("latitude")?.asDouble ?: latitude
                        val lon = body.get("longitude")?.asDouble ?: longitude
                        val captured = body.get("captured_at")?.asString ?: ""
                        Log.d(TAG, "✓ Device location created: $locId")
                        onSuccess(locId, lat, lon, captured)
                    } else {
                        Log.e(TAG, "✗ Create device location error: ${response.code()} - ${response.errorBody()?.string()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Create device location failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error creating device location: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Get all location records for a specific device
     */
    fun getDeviceLocationsByDevice(
        deviceId: String,
        limit: Int = 100,
        onSuccess: (locations: List<JsonObject>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Getting locations for device: $deviceId")
            val call = api.getDeviceLocationsByDevice(deviceId, limit)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val locationsArray = body.getAsJsonArray("locations") ?: com.google.gson.JsonArray()
                        val locationsList = mutableListOf<JsonObject>()
                        locationsArray.forEach { locationsList.add(it.asJsonObject) }
                        Log.d(TAG, "✓ Retrieved ${locationsList.size} locations for device: $deviceId")
                        onSuccess(locationsList)
                    } else {
                        Log.e(TAG, "✗ Get device locations error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Get device locations failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error getting device locations: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Get a specific device location by ID
     */
    fun getDeviceLocationById(
        locationId: String,
        onSuccess: (location: JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Getting device location: $locationId")
            val call = api.getDeviceLocationById(locationId)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val location = body.getAsJsonObject("location")
                        if (location != null) {
                            Log.d(TAG, "✓ Retrieved device location: $locationId")
                            onSuccess(location)
                        } else {
                            Log.e(TAG, "✗ Device location not found: $locationId")
                            onError(Exception("Location not found"))
                        }
                    } else {
                        Log.e(TAG, "✗ Get device location error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Get device location failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error getting device location: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Get the most recent location for a device
     */
    fun getLatestDeviceLocation(
        deviceId: String,
        onSuccess: (location: JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Getting latest location for device: $deviceId")
            val call = api.getLatestDeviceLocation(deviceId)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val location = body.getAsJsonObject("location")
                        if (location != null) {
                            Log.d(TAG, "✓ Retrieved latest location for device: $deviceId")
                            onSuccess(location)
                        } else {
                            Log.e(TAG, "✗ No location found for device: $deviceId")
                            onError(Exception("No location found"))
                        }
                    } else {
                        Log.e(TAG, "✗ Get latest location error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Get latest location failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error getting latest location: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Update a device location record
     */
    fun updateDeviceLocation(
        locationId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        capturedAt: String? = null,
        onSuccess: (location: JsonObject) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Updating device location: $locationId")
            val record = JsonObject().apply {
                latitude?.let { addProperty("latitude", it) }
                longitude?.let { addProperty("longitude", it) }
                capturedAt?.let { addProperty("captured_at", it) }
            }
            
            val call = api.updateDeviceLocation(locationId, record)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val location = body.getAsJsonObject("location")
                        if (location != null) {
                            Log.d(TAG, "✓ Updated device location: $locationId")
                            onSuccess(location)
                        } else {
                            Log.e(TAG, "✗ Device location not found after update: $locationId")
                            onError(Exception("Location not found after update"))
                        }
                    } else {
                        Log.e(TAG, "✗ Update device location error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Update device location failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error updating device location: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Delete a device location record
     */
    fun deleteDeviceLocation(
        locationId: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            Log.d(TAG, "→ Deleting device location: $locationId")
            val call = api.deleteDeviceLocation(locationId)
            call.enqueue(object : Callback<JsonObject> {
                override fun onResponse(call: Call<JsonObject>, response: Response<JsonObject>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "✓ Deleted device location: $locationId")
                        onSuccess()
                    } else {
                        Log.e(TAG, "✗ Delete device location error: ${response.code()}")
                        onError(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }
                
                override fun onFailure(call: Call<JsonObject>, t: Throwable) {
                    Log.e(TAG, "✗ Delete device location failure: ${t.message}", t)
                    onError(t as Exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error deleting device location: ${e.message}", e)
            onError(e)
        }
    }
}
