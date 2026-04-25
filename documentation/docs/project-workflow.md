# WeatherTracker — Project Workflow

## System Overview

WeatherTracker is an Android application that provides real-time weather monitoring and on-device severe-storm prediction for Louisiana. The system is composed of three layers:

| Layer | Technology | Role |
|-------|-----------|------|
| **Android Client** | Kotlin, Jetpack Compose, Room DB, ONNX Runtime | UI, offline storage, on-device ML inference, background tracking |
| **Backend API** | Python FastAPI (uvicorn) | Stateless proxy to weather APIs and Supabase; orchestrates registration, syncing, and data seeding |
| **Database** | Supabase (PostgreSQL + PostgREST) | Persistent storage for users, devices, locations, weather snapshots, and ML prediction results |

### External API Dependencies

| Service | Base URL | Data Provided | Called By |
|---------|----------|---------------|-----------|
| **NWS (weather.gov)** | `https://api.weather.gov` | Grid-point lookup, hourly/daily forecasts, active severe-weather alerts | Backend (proxied to client) |
| **Open-Meteo** | `https://api.open-meteo.com/v1/forecast` | Hourly historical observations, 7-day hourly forecasts, elevation | Backend (seed endpoint) and Android client (direct) |
| **RainViewer** | `https://api.rainviewer.com` | Radar tile imagery for map overlay | Backend (proxied to client) |
| **Google Maps SDK** | Bundled in Android app | Map tiles, radar overlay canvas, tap-to-pin interaction | Android client only |

---

## 1. App Launch & Permission Flow

### First-launch sequence

```
User opens app
  │
  ├─ MainActivity.onCreate()
  │    └─ Jetpack Compose setContent()
  │
  ├─ [1] Permission Dialog
  │    ├─ Checks: ACCESS_FINE_LOCATION + POST_NOTIFICATIONS (Android 13+)
  │    ├─ If BOTH already granted → skip dialog, persist in SharedPreferences
  │    ├─ If NOT → show AlertDialog ("App Permissions")
  │    │     ├─ "Allow" → opens system Settings + launches notification permission request
  │    │     └─ "Don't Allow" → dismiss for this session only; dialog reappears next launch
  │    └─ LaunchedEffect re-checks on return from Settings
  │
  ├─ [2] Backend Connectivity Check
  │    └─ If backend unreachable → sets backendConnected=false, enters fallback mode
  │
  ├─ [3] Registration (if first run)
  │    └─ AuthenticationService.initializeFirstRun()
  │         ├─ Checks SharedPreferences for existing anon_user_id + device_id
  │         ├─ If found → restore credentials, skip registration
  │         └─ If not found → createAnonUserAndDevice() (see Section 2)
  │
  ├─ [4] Background Service Start
  │    └─ When location + background-location + notification permissions are granted
  │         AND registration is complete:
  │         → LocationTrackingService.start(context) (foreground service)
  │
  └─ [5] WorkManager Initialization
       ├─ SnapshotSyncManager     — periodic every 1 hour (requires network)
       ├─ ModelInstanceSyncManager — periodic every 1 hour (requires network)
       └─ ForecastFetcher          — periodic every 6 hours
```

### Permission side-effects

When a permission changes from denied → granted (detected via `LaunchedEffect`):

| Permission | Backend Call | Endpoint |
|-----------|-------------|----------|
| Location granted | `BackendRepository.updateDevice(locationPermissionStatus=true)` | `PATCH /supabase/device` |
| Notifications granted | `BackendRepository.updateDevice(notificationsEnabled=true)` | `PATCH /supabase/device` |

---

## 2. Registration Flow

Registration creates an anonymous identity (no login, no PII) so the system can link devices to weather data and predictions.

```
AuthenticationService.createAnonUserAndDevice()
  │
  ├─ Check current permissions at registration time:
  │    ├─ hasLocationPermission    (ACCESS_FINE_LOCATION)
  │    └─ hasNotificationPermission (POST_NOTIFICATIONS or NotificationManager)
  │
  ├─ If location granted → get fresh GPS fix (FusedLocationProviderClient, 10s timeout)
  │
  └─ BackendRepository.register(
  │       locationPermissionStatus,
  │       notificationsEnabled,
  │       latitude, longitude
  │   )
  │     │
  │     └─ POST /supabase/register ──→ Backend
  │           │
  │           ├─ Generate anon_user_id (UUID4) + device_id (UUID4)
  │           ├─ INSERT anonymous_user (status=active, created_at=now, last_active_at=now)
  │           ├─ INSERT device (platform=android, app_version=1.0, permission flags)
  │           ├─ If location granted + coords provided:
  │           │    └─ INSERT device_location (auto-created)
  │           └─ Return { userId, deviceId }
  │
  └─ Store userId + deviceId in SharedPreferences (persist across app restarts)
```

### Registration failure handling

- 60-second timeout on the `CountDownLatch`
- On error: logs exception, resets `registrationInProgress` flag so next `LaunchedEffect` cycle retries
- App remains functional (weather display works via fallback) but sync features are disabled without a device ID

---

## 3. Weather Display Pipeline

This is the primary user-facing data flow. It runs every time the selected weather location changes.

### Primary path (backend available)

```
Location change (device GPS update or city selection)
  │
  ├─ [Cache check] WeatherApiCache.get("%.4f,%.4f")
  │    └─ In-memory ConcurrentHashMap, 10-minute TTL
  │    └─ HIT → use cached CurrentWeatherUiModel + alerts + forecast → return
  │
  ├─ MISS ──→ Parallel data fetch:
  │
  │   ┌────────────────────────────────────────────────────────────────────┐
  │   │ [A] Alerts                                                        │
  │   │  BackendRepository.getAlerts("lat,lon")                           │
  │   │    → GET /weather/alerts?point=lat,lon                            │
  │   │      → Backend proxies to: GET api.weather.gov/alerts/active      │
  │   │    → Parse FeatureCollection → list of AlertFeature               │
  │   │                                                                    │
  │   │  FALLBACK: Backend catches ANY NWS error →                        │
  │   │    returns empty FeatureCollection (app shows no alerts,           │
  │   │    does NOT crash or show error)                                   │
  │   └────────────────────────────────────────────────────────────────────┘
  │
  │   ┌────────────────────────────────────────────────────────────────────┐
  │   │ [B] Current weather + forecast                                     │
  │   │  BackendRepository.getWeatherPoints(lat, lon)                      │
  │   │    → GET /weather/points/{lat}/{lon}                               │
  │   │      → Backend proxies to: GET api.weather.gov/points/{lat},{lon}  │
  │   │    → Extract forecastUrl + forecastHourlyUrl from response          │
  │   │                                                                     │
  │   │  BackendRepository.getForecast(forecastUrl)                         │
  │   │    → GET /weather/forecast?url={forecastUrl}                        │
  │   │      → Backend proxies to: GET {NWS forecast URL}                  │
  │   │    → Map to CurrentWeatherUiModel + list<DailyForecastUiModel>     │
  │   │                                                                     │
  │   │  BackendRepository.getForecast(forecastHourlyUrl)  [optional]       │
  │   │    → Overwrite current temp/condition with more precise hourly data │
  │   └────────────────────────────────────────────────────────────────────┘
  │
  └─ Store in WeatherApiCache (10-min TTL)
     └─ Render to Compose UI
```

### Fallback path (backend unreachable)

```
Backend connectivity lost (HTTP error / timeout)
  │
  ├─ Set backendConnected = false
  │
  ├─ Immediately switch to direct Open-Meteo:
  │    OpenMeteoFallback.fetch(lat, lon, locationLabel)
  │      → GET api.open-meteo.com/v1/forecast
  │           ?latitude={lat}&longitude={lon}
  │           &current=temperature_2m,relative_humidity_2m,weather_code,...
  │           &daily=temperature_2m_max,temperature_2m_min,weather_code,...
  │           &temperature_unit=fahrenheit&wind_speed_unit=mph
  │      → Map to CurrentWeatherUiModel + DailyForecastUiModel list
  │      → Render to UI (user sees weather data, no disruption)
  │
  └─ Start polling loop:
       every 10 seconds → OpenMeteoFallback.checkBackendHealth()
         → GET http://10.0.2.2:5000/health
         → On success: set backendConnected = true
                        increment retryTrigger → re-fetch via primary path
```

### Weather data sources comparison

| Source | Used When | Temperature | Forecast | Alerts | Radar |
|--------|----------|-------------|----------|--------|-------|
| NWS via backend | Backend online | Fahrenheit (native) | 7-day daily + hourly | Yes (active alerts) | No |
| Open-Meteo (fallback) | Backend offline | Fahrenheit (param) | 7-day daily | No | No |
| RainViewer via backend | Map screen | N/A | N/A | N/A | Yes (tile overlay) |

---

## 4. Background Location Tracking

`LocationTrackingService` is an Android foreground service that runs continuously after permissions are granted.

```
LocationTrackingService (foreground, START_STICKY)
  │
  ├─ FusedLocationProviderClient
  │    interval: 60 seconds
  │    fastest:  30 seconds
  │
  └─ On each location callback:
       │
       ├─ If location moved > 50 meters:
       │    BackendRepository.updateCurrentDeviceLocation(deviceId, lat, lon)
       │      → POST /device-location/update-current
       │        → Backend upserts device_location row
       │
       ├─ If hourly timer elapsed (≥1 hour since last):
       │    → storeWeatherSnapshot(runPrediction = true)
       │      (see Section 5 — ML Prediction Pipeline)
       │
       └─ If significant move > 5 km:
            → storeWeatherSnapshot(runPrediction = false)
              (store weather data only, skip prediction)
```

### Boot persistence

`BootReceiver` listens for `BOOT_COMPLETED`:  
If stored device ID exists AND required permissions are granted → restarts `LocationTrackingService`.

---

## 5. ML Prediction Pipeline (On-Device)

All machine learning inference runs on the Android device using ONNX Runtime. The backend never runs predictions — it only stores results.

### Hourly prediction cycle

```
storeWeatherSnapshot(runPrediction=true)
  │
  ├─ [1] Fetch current weather (direct API call, NOT via backend):
  │    GET api.open-meteo.com/v1/forecast
  │      ?latitude={lat}&longitude={lon}
  │      &current=temperature_2m,relative_humidity_2m,dew_point_2m,
  │               precipitation,pressure_msl,wind_speed_10m,wind_direction_10m
  │    → Parse JSON → extract current conditions
  │
  ├─ [2] Store in Room DB:
  │    ├─ WeatherCacheEntity (cache_id = UUID v5 from lat/lon/time)
  │    └─ OfflineWeatherSnapshotEntity (is_current=true)
  │
  ├─ [3] Prune old data (>24 hours) from Room cache
         Note: the Room cache retains 24h of raw rows, and that same 24h window
         is used when building sync payloads (SnapshotSyncWorker)
  │         and when querying observations for feature assembly.
  │
  ├─ [4] Backfill missed hours (if gaps > 1h detected):
  │    GET api.open-meteo.com/v1/forecast?start_date={start}&end_date={end}&hourly=...
    → Insert missing WeatherCacheEntity rows (capped at 24h)
  │
  ├─ [5] Seed 24h history (first run only):
  │    POST /devices/{device_id}/seed-weather-history
  │      → Backend fetches from Open-Meteo: 24h historical + 7-day forecast
  │      → Returns all rows → stored in Room as WeatherCacheEntity
  │
  ├─ [6] Feature assembly:
  │    FeatureAssemblyService.assembleFeatures(lat, lon)
  │      → Query Room: observations within 5km radius, up to 48 rows
  │      → If < 6 observations → supplement with forecast rows
  │      → Compute 33-feature vector:
  │           ┌──────────────────────────────────────────────────────┐
  │           │ Raw:      temp_c, pressure_hPa, humidity_pct,        │
  │           │           wind_speed_kmh, precip_mm                  │
  │           │ Precip:   precip_6h, precip_24h, precip_rate_change, │
  │           │           precip_max_3h                              │
  │           │ Pressure: pressure_change_1h/3h/6h/12h,              │
  │           │           pressure_drop_rate                         │
  │           │ Temp:     temp_dewpoint_spread,                      │
  │           │           dewpoint_spread_change                     │
  │           │ Wind:     wind_speed_change_1h/3h, wind_max_3h       │
  │           │ Temporal: hour, month, is_afternoon                  │
  │           │ Static:   latitude, longitude, elevation,            │
  │           │           dist_to_coast_km                           │
  │           │ NWP:      nwp_cape_f3_6_max, nwp_cin_f3_6_max,      │
  │           │           nwp_pwat_f3_6_max, nwp_srh03_f3_6_max,    │
  │           │           nwp_li_f3_6_min, nwp_lcl_f3_6_min,        │
  │           │           nwp_available_leads                        │
  │           └──────────────────────────────────────────────────────┘
  │
  ├─ [7] ONNX inference:
  │    OnDevicePredictor.predict(features)
  │      → Order features by model's feature_cols
  │      → Impute missing values with training-set medians
  │      → Run ONNX session (model.onnx from assets)
  │      → Raw output → isotonic calibration (lookup table interpolation)
  │      → Return PredictionResult:
  │           { stormProbability, alertState (0 or 1), threshold, modelVersion }
  │
  ├─ [8] Store prediction in Room:
  │    ├─ HourlyPredictionEntity (for storm risk timeline chart)
  │    └─ ModelInstanceEntity (for sync to Supabase)
  │
  └─ [9] If alertState == 1 (storm detected):
       → Fire Android notification with storm probability
```

### Model details

| Property | Value |
|----------|-------|
| Format | ONNX (assets/ml/model.onnx) |
| Metadata | assets/ml/model_metadata.json |
| Version | `v1.0.0` |
| Features | 33 input features (26 base + 7 NWP passthrough) |
| Output | Binary classification probability (storm vs clear) |
| Calibration | Isotonic regression lookup table |
| Threshold | ~0.505 (from metadata) |
| Alert display threshold | 0.4901 (WeatherOverviewScreen) |

---

## 6. Data Sync Flows

Local Room DB data is periodically synced to the cloud Supabase database via two WorkManager workers.

### Snapshot sync (every 1 hour + on backend reconnect)

```
SnapshotSyncWorker.doWork()
  │
  ├─ Read all UNSYNCED snapshot rows from Room
  │    → Pre-fetch current-hour observation from Open-Meteo if not yet in Room
  │    → For each unsynced snapshot:
  │         obs_rows     = 24h observation window ending at prediction hour
  │         post_pred    = real observations that arrived AFTER prediction hour
  │         forecast_rows = forecasts for hours with no real observation yet
  │         weather_data = obs_rows + post_pred + forecast_rows
  │
  ├─ POST /devices/{device_id}/sync-snapshots
  │    Body: { snapshots: [ { weather_id, weather_data, snapshot_type }, ... ] }
  │      → Backend upserts one offline_weather_snapshot row per item in Supabase
  │
  ├─ On success: mark synced snapshots in Room
  │              → immediately chains ModelInstanceSyncManager.triggerImmediateSync()
  └─ On failure: return Result.retry() (WorkManager exponential backoff)
```

### Model instance sync (every 1 hour + on WiFi connect)

```
ModelInstanceSyncWorker.doWork()
  │
  ├─ Read all unsynced ModelInstanceEntity rows from Room
  │    → Convert to JSON array with epoch-ms timestamps
  │
  ├─ POST /devices/{device_id}/sync-model-instances
  │    Body: { instances: [...] }
  │      → Backend upserts to Supabase model_instance table
  │        (links to most recent offline_weather_snapshot)
  │
  ├─ On success: mark as synced in Room
  └─ On failure: return Result.retry()
```

### Forecast prefetch (every 6 hours)

```
ForecastFetcher.doWork()
  │
  ├─ Get last known device location from most recent snapshot in Room DB
  │
  ├─ GET api.open-meteo.com/v1/forecast
  │    ?latitude={lat}&longitude={lon}
  │    &hourly=temperature_2m,relative_humidity_2m,dew_point_2m,
  │            precipitation,pressure_msl,wind_speed_10m,wind_direction_10m
  │    &forecast_days=7
  │
  ├─ Parse hourly arrays → WeatherCacheEntity rows (isForecast=true)
  │    cache_id = UUID v5(NAMESPACE_URL, "lat|lon|time|forecast")
  │
  ├─ Upsert into Room (dedup by deterministic UUID)
  └─ Prune forecast rows older than 7 days
```

### Triggered immediate sync on backend reconnect

MainActivity polls backend health every 10 seconds while the backend is unreachable. On recovery it calls `SnapshotSyncManager.triggerImmediateSync()`, which enqueues a `OneTimeWorkRequest` (deduplicated via `ExistingWorkPolicy.KEEP`). After a successful snapshot sync, `SnapshotSyncWorker` chains a call to `ModelInstanceSyncManager.triggerImmediateSync()` to ensure FK targets exist in Supabase before model instances are uploaded.

---

## 7. Device Update & Heartbeat

### Device attribute updates

```
PATCH /supabase/device
  Body: { device_id, location_permission_status?, notifications_enabled? }
  │
  Backend:
  ├─ [1] PATCH device table (only provided fields)
  ├─ [2] Look up anon_user_id from device
  └─ [3] PATCH anonymous_user:
       ├─ last_active_at = now
       └─ status = "active" (reactivates inactive accounts)
```

### Heartbeat on app pause

```
MainActivity.onPause()
  └─ SupabaseRepository.updateDevice(deviceId)
       → BackendRepository.updateDevice(deviceId)
         → PATCH /supabase/device  (no fields, just triggers last_active_at refresh)
```

### Automatic deactivation

A PostgreSQL function `deactivate_stale_accounts()` marks accounts as `inactive` when `last_active_at > 30 days`. Scheduled via `pg_cron` (daily at 3 AM UTC) if the extension is available; otherwise must be run manually.

---

## 8. Radar / Map Display

```
MapScreen (Google Maps + Compose Maps SDK)
  │
  ├─ Radar frames:
  │    RainViewerApiCache.fetchRainViewerFramesCached()
  │      → In-memory volatile cache (5-min TTL)
  │      → MISS: BackendRepository.getWeatherMaps()
  │        → GET /rainviewer/maps
  │          → Backend proxies: GET api.rainviewer.com/public/weather-maps.json
  │        → Parse response → list of RainViewerRadarFrame (time + tileTemplate)
  │
  ├─ Radar overlay:
  │    TileOverlay using RainViewer tile URLs:
  │      https://tilecache.rainviewer.com/.../{z}/{x}/{y}/1/1_1.png
  │    Playback: step through frames every 1500ms (30-min intervals)
  │    Auto-refresh: reload frames every 5 minutes
  │
  └─ Tap-to-pin:
       User taps map → creates marker at location
       → Selects that location for weather display (returns to WeatherOverviewScreen)
```

---

## 9. Debug & Test Utilities

### DebugPredictActivity

Trigger: `adb shell am start -n com.CMPS490.weathertracker/.DebugPredictActivity`

```
Fetches live Open-Meteo weather for Lafayette LA (30.2241, -92.0198)
  → Stores in Room
  → Assembles features
  → Runs ONNX prediction
  → Fires notification if alertState == 1
  → Records model_instance via POST /devices/{device_id}/model-instance
  → finish() (activity closes immediately; coroutine continues on Dispatchers.IO)
```

### StormSimulationActivity

Trigger: `adb shell am start -n com.CMPS490.weathertracker/.StormSimulationActivity`

```
Injects 24 hours of Hurricane Katrina-like synthetic weather into Room
  Location: New Orleans (29.95, -90.07)
  Data: pressure 990→920 hPa, winds 45→185 km/h, heavy rain
        Extreme CAPE/helicity values to trigger storm detection
  → Clears existing cache near location
  → Runs ONNX prediction on synthetic data
  → Fires "SEVERE STORM WARNING" notification if triggered
  → Records model_instance with sentinel weather_id (00000000-...)
```

### DebugPredictReceiver

Trigger: `adb shell am broadcast -a com.CMPS490.weathertracker.FORCE_PREDICT`  
Same pipeline as DebugPredictActivity but via BroadcastReceiver (uses `goAsync()`).

---

## 10. Database Schema

### Tables

| Table | Purpose | Key Relationships |
|-------|---------|-------------------|
| `anonymous_user` | One per app install; tracks status and activity | Root entity |
| `device` | One per user; stores platform, permissions, notification flags | FK → anonymous_user |
| `device_location` | Current GPS coordinates — one row per device (unique constraint on `device_id`) | FK → device |
| `offline_weather_snapshot` | Bundled weather data (JSONB) synced from device | FK → device |
| `model_instance` | Individual ML prediction results | FK → offline_weather_snapshot |

### Entity relationships

```
anonymous_user (1) ──── (1) device
                              │
                    (1) ──── (1) device_location   ← unique constraint on device_id
                              │
                    (1) ──── (N) offline_weather_snapshot
                                          │
                                (1) ──── (N) model_instance
```

### Room (local) vs Supabase (cloud) tables

| Room Entity | Supabase Table | Sync Direction |
|-------------|---------------|----------------|
| WeatherCacheEntity | (embedded in offline_weather_snapshot.weather_data JSONB) | Device → Cloud |
| OfflineWeatherSnapshotEntity | offline_weather_snapshot | Device → Cloud |
| HourlyPredictionEntity | *(local only — storm risk timeline)* | Not synced |
| ModelInstanceEntity | model_instance | Device → Cloud |

> **`WeatherCacheEntity` schema note:** The Room table includes a `mrms_max_dbz_75km` column (serialized as `mrms_max_dbz_75km` in the weather_data JSONB payload). It is always `null` in the current release and is **not** part of the 33-feature ML input vector — it is reserved for a future MRMS radar reflectivity integration.

---

## 11. Complete API Endpoint Reference

### Backend FastAPI endpoints

| # | Method | Path | Source API | Purpose |
|---|--------|------|-----------|---------|
| 1 | `GET` | `/health` | — | Liveness check |
| 2 | `GET` | `/weather/points/{lat}/{lon}` | NWS | Grid-point lookup → forecast URLs |
| 3 | `GET` | `/weather/forecast?url=` | NWS | Proxy any NWS forecast URL |
| 4 | `GET` | `/weather/alerts?point=` | NWS | Active weather alerts (graceful fallback) |
| 5 | `GET` | `/rainviewer/maps` | RainViewer | Radar tile map data |
| 6 | `POST` | `/supabase/register` | Supabase | Create anonymous user + device |
| 7 | `PATCH` | `/supabase/device` | Supabase | Update device attrs + heartbeat |
| 8 | `POST` | `/device-location/create` | Supabase | New location record |
| 9 | `GET` | `/device-location/by-device/{id}` | Supabase | Location history |
| 10 | `GET` | `/device-location/{id}` | Supabase | Single location |
| 11 | `PATCH` | `/device-location/{id}` | Supabase | Update location |
| 12 | `DELETE` | `/device-location/{id}` | Supabase | Delete location |
| 13 | `GET` | `/device-location/latest/{id}` | Supabase | Most recent location |
| 14 | `POST` | `/device-location/update-current` | Supabase | Upsert current location |
| 15 | `POST` | `/devices/{id}/sync-snapshots` | Supabase | Upload weather snapshots |
| 16 | `GET` | `/devices/{id}/snapshots` | Supabase | Fetch snapshots |
| 17 | `POST` | `/devices/{id}/seed-weather-history` | Open-Meteo + Supabase | Seed 24h history + 7-day forecast |
| 18 | `POST` | `/devices/{id}/model-instance` | Supabase | Record single prediction |
| 19 | `POST` | `/devices/{id}/sync-model-instances` | Supabase | Batch-sync predictions |

### Direct Open-Meteo calls from Android (no backend proxy)

| Caller | Parameters | Purpose |
|--------|-----------|---------|
| LocationTrackingService | `current=temp,humidity,dew_point,precip,pressure,wind_speed,wind_dir` | Hourly observation for ML pipeline |
| LocationTrackingService (backfill) | `past_hours={n}&hourly=...` | Fill gaps in observation history |
| ForecastFetcher | `forecast_days=7&hourly=...` | Prefetch 7-day forecast for offline ML |
| OpenMeteoFallback | `current=...&daily=...&temperature_unit=fahrenheit&wind_speed_unit=mph` | Fallback weather display when backend is down |
| DebugPredictActivity | `current=...&forecast_days=1` | Debug prediction data source |

---

## 12. Fallback & Resilience Summary

| Scenario | Detection | Fallback Behavior |
|----------|-----------|-------------------|
| **Backend down** | HTTP error / timeout on any backend call | Switch to `OpenMeteoFallback.fetch()` for weather display; poll backend health every 10s; auto-restore on recovery |
| **NWS alerts API error** | Backend catches any exception | Returns empty `FeatureCollection` → UI shows no alerts (no crash) |
| **NWS forecast API error** | HTTP error propagated | Weather display shows loading state or stale cache |
| **Open-Meteo direct call fails** | Exception in LocationTrackingService | Prediction still attempted from cached/forecast Room data |
| **ML model unavailable** | ONNX session fails to load | `OnDevicePredictor` returns probability=0, alertState=0 (silent no-storm) |
| **Feature assembly < 6 observations** | Query returns fewer rows | Supplements with forecast data from Room DB |
| **Sync fails (no network)** | WorkManager retry | `Result.retry()` with exponential backoff; WiFi callback triggers immediate retry on reconnect |
| **Registration fails** | Exception or timeout (60s) | Resets `registrationInProgress` flag; next `LaunchedEffect` cycle retries; weather display still works via fallback |
| **Device reboot** | `BOOT_COMPLETED` broadcast | `BootReceiver` restarts `LocationTrackingService` if credentials + permissions exist |
| **Account inactive > 30 days** | `deactivate_stale_accounts()` cron | Status set to `inactive`; next heartbeat (`PATCH /supabase/device`) reactivates to `active` |
| **Weather cache miss** | `WeatherApiCache` 10-min TTL | Fresh NWS fetch via backend proxy chain |
| **Radar cache miss** | `RainViewerApiCache` 5-min TTL | Fresh RainViewer fetch via backend proxy |
