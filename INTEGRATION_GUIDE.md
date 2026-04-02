# Frontend to Backend API Migration Guide

## Overview
The frontend has been updated to route all API calls through the FastAPI backend instead of calling external APIs directly. This enables ML model integration and provides a centralized API layer.

## New Components Created

### 1. **BackendRetrofitInstance.kt**
- Retrofit instance configured to call `http://10.0.2.2:5000/` (Android emulator) 
- Base URL uses emulator alias for running backend locally

### 2. **BackendApi.kt**
- Retrofit interface defining all backend endpoints
- Routes requests to FastAPI proxy endpoints

### 3. **BackendRepository.kt**
- Centralized repository handling all API calls
- Callback-based pattern for async operations
- Includes error handling and logging

---

## API Endpoint Reference

### Weather API (Previously `https://api.weather.gov/`)
| Old Endpoint | New Backend Endpoint | Method |
|---|---|---|
| `GET /points/{lat},{lon}` | `GET /weather/points/{lat}/{lon}` | `BackendRepository.getWeatherPoints(lat, lon, ...)`  |
| `GET {forecastUrl}` | `GET /weather/forecast?url={url}` | `BackendRepository.getForecast(url, ...)` |
| `GET /alerts/active?point={point}` | `GET /weather/alerts?point={point}` | `BackendRepository.getAlerts(point, ...)` |

### RainViewer API (Previously `https://api.rainviewer.com/`)
| Old Endpoint | New Backend Endpoint | Method |
|---|---|---|
| `GET /public/weather-maps.json` | `GET /rainviewer/maps` | `BackendRepository.getWeatherMaps(...)` |

### Supabase (Previously direct to Supabase)
| Old Endpoint | New Backend Endpoint | Method |
|---|---|---|
| `POST /rest/v1/anonymous_user` | `POST /supabase/anon-user` | `BackendRepository.createAnonUser(anonUserId, ...)` |
| `POST /rest/v1/device` | `POST /supabase/device` | `BackendRepository.createDevice(deviceId, anonUserId, ...)` |

### ML Predictions (New)
| Endpoint | Method |
|---|---|
| `POST /predict` | `BackendRepository.getPrediction(requestData, ...)` |

### Health Check
| Endpoint | Method |
|---|---|
| `GET /health` | `BackendRepository.healthCheck(...)` |

---

## Migration Examples

### Example 1: Get Weather Points

**Old Code (Direct API call):**
```kotlin
val api = RetrofitInstance.api
val call = api.getPointData(lat, lon)
call.enqueue(object : Callback<PointResponse> {
    override fun onResponse(call: Call<PointResponse>, response: Response<PointResponse>) {
        // handle response
    }
    override fun onFailure(call: Call<PointResponse>, t: Throwable) {
        // handle error
    }
})
```

**New Code (Via Backend):**
```kotlin
BackendRepository.getWeatherPoints(lat, lon,
    onSuccess = { response ->
        // response is JsonObject - parse as needed
        val pointData = response.getAsJsonObject("properties")
    },
    onError = { error ->
        Log.e("Error", error.message)
    }
)
```

### Example 2: Get RainViewer Maps

**Old Code:**
```kotlin
val api = RainViewerRetrofitInstance.api
val call = api.getWeatherMaps()
call.enqueue(...)
```

**New Code:**
```kotlin
BackendRepository.getWeatherMaps(
    onSuccess = { response ->
        // parse JSON response
    },
    onError = { error ->
        // handle error
    }
)
```

### Example 3: Create Supabase User

**Old Code:**
```kotlin
val api = SupabaseConfig.getApi()
api.createAnonUser(record) // suspend function
```

**New Code:**
```kotlin
BackendRepository.createAnonUser(anonUserId,
    onSuccess = {
        Log.d("Tag", "User created successfully")
    },
    onError = { error ->
        Log.e("Tag", "Error: ${error.message}")
    }
)
```

---

## Integration Steps

1. **Update imports** - Replace old API imports with `BackendRepository`
2. **Update API calls** - Replace direct API calls with `BackendRepository` methods
3. **Handle responses** - Use JsonObject instead of typed response models (or create adapters)
4. **Start backend** - Ensure FastAPI backend is running on `:5000`
5. **Test** - Verify all API calls work through the backend

---

## Running the Backend

From project root:
```bash
cd backend
.\venv\Scripts\activate
python app.py
```

Backend will start on `http://localhost:5000`

## Configuration

### EmulatorvVirtual Device
- Default: `http://10.0.2.2:5000/`
- Change in `BackendRetrofitInstance.kt` if needed

### Physical Device
- Update base URL in `BackendRetrofitInstance.kt` to your machine's IP
- Example: `http://192.168.1.100:5000/`

---

## Next Steps

- Update screen/activity files to use `BackendRepository`
- Integrate your ML prediction model into the `/predict` endpoint
- Add caching layer if needed
- Consider adding request/response logging middleware
