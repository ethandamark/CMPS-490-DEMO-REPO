# Plan: On-Device ML with Device-Cache Architecture

## TL;DR
Move ML inference from the backend to the Android app using ONNX Runtime, revert from area-based to per-device weather caching, and sync the device's local snapshot cache with Supabase when on WiFi or when new location data is collected. Fetch 7-day hourly forecasts from Open-Meteo to enable forward-looking predictions and full offline operation. Remove Firebase Cloud Messaging entirely — all notifications become local.

## Phase 1 — Model Conversion & On-Device Inference

### 1.1 Convert joblib pipeline to ONNX (backend/scripts)
- Create a `convert_model_to_onnx.py` script
- Load `baseline_surface_model_3mo_6st.joblib` (890 KB)
- Use `skl2onnx` + `onnxmltools` to export the sklearn Pipeline (SimpleImputer → XGBoost) to `.onnx`
- Export `feature_cols` list, `threshold`, `experiment_name` as a sidecar JSON (`model_metadata.json`)
- Export the `IsotonicRegression` calibrator separately (or bake into ONNX graph)
- Validate ONNX output matches joblib output on test vectors (clear day + severe storm)

### 1.2 Bundle model in Android app
- Place `model.onnx` + `model_metadata.json` in `frontend/app/src/main/assets/ml/`
- Add `onnxruntime-android` dependency to `frontend/app/build.gradle.kts`
- Estimated app size increase: ~5-8 MB (ONNX Runtime) + ~1 MB (model)

### 1.3 Create Kotlin inference service
- New file: `frontend/.../weathertracker/ml/OnDevicePredictor.kt`
- Load ONNX model from assets on first use (lazy singleton)
- Accept a `Map<String, Float?>` of 26 features → return `PredictionResult(stormProbability, alertState, threshold, modelVersion)`
- Apply isotonic calibration post-inference (load calibration lookup from metadata or separate asset)
- Handle missing features with NaN fill (matches current Python behavior)

## Phase 2 — Device-Based Weather Snapshot Cache (Frontend)

### 2.1 Migrate `weather_cache` table — add ML columns
- New migration: `005_add_ml_columns_to_weather_cache.sql`
- Add nullable columns to `weather_cache`:
  - `dew_point_c DOUBLE PRECISION`
  - `elevation DOUBLE PRECISION`
  - `nwp_cape_f3_6_max DOUBLE PRECISION`
  - `nwp_cin_f3_6_max DOUBLE PRECISION`
  - `nwp_pwat_f3_6_max DOUBLE PRECISION`
  - `nwp_srh03_f3_6_max DOUBLE PRECISION`
  - `nwp_li_f3_6_min DOUBLE PRECISION`
  - `nwp_lcl_f3_6_min DOUBLE PRECISION`
  - `nwp_available_leads DOUBLE PRECISION`
  - `mrms_max_dbz_75km DOUBLE PRECISION`
  - `is_forecast BOOLEAN DEFAULT FALSE` — distinguishes forecast rows from observed data
- Existing rows get NULLs for new columns (FALSE for is_forecast) — no data loss
- Column mapping for ML feature assembly (aliased in code, not renamed in DB):
  - `temp` → `temp_c`, `humidity` → `humidity_pct`, `wind_speed` → `wind_speed_kmh`
  - `pressure` → `pressure_hPa`, `precipitation_amount` → `precip_mm`

### 2.2 Add Room database (mirrors existing Supabase tables)
- Add `androidx.room` dependencies to `build.gradle.kts`
- New file: `frontend/.../weathertracker/data/WeatherDatabase.kt` — Room database definition
- New file: `frontend/.../weathertracker/data/WeatherCacheEntity.kt` — mirrors `weather_cache` table
  - Fields: `cache_id` (UUID PK), `temp`, `humidity`, `wind_speed`, `wind_direction`, `precipitation_amount`, `pressure`, `weather_condition`, `recorded_at`, `latitude`, `longitude`, `result_level`, `result_type`, `is_forecast` + new ML columns (`dew_point_c`, `elevation`, NWP fields, radar field)
- New file: `frontend/.../weathertracker/data/OfflineWeatherSnapshotEntity.kt` — mirrors `offline_weather_snapshot` table
  - Fields: `offline_weather_id` (UUID PK), `device_id` (UUID FK), `cache_id` (UUID FK → weather_cache), `synced_at` (nullable — null means unsynced), `is_current` (Boolean)
- New file: `frontend/.../weathertracker/data/WeatherCacheDao.kt` — DAO:
  - `upsert(cache: WeatherCacheEntity)`
  - `getByIds(cacheIds: List<UUID>)`
  - `pruneOlderThan(cutoff: Timestamp)`
- New file: `frontend/.../weathertracker/data/OfflineWeatherSnapshotDao.kt` — DAO:
  - `getSnapshotsForDevice(deviceId, since)` — JOIN with weather_cache, ordered by recorded_at
  - `getUnsyncedSnapshots(deviceId)` — WHERE `synced_at IS NULL`, JOIN weather_cache
  - `upsertSnapshot(snapshot)`
  - `markSynced(ids, syncedAt)` — set `synced_at` timestamp
  - `pruneOld(deviceId, cutoff)` — delete old rows + orphaned weather_cache entries
  - `clearCurrentFlag(deviceId)` — set `is_current = false` for device

### 2.3 Create Kotlin feature assembly service
- New file: `frontend/.../weathertracker/ml/FeatureAssemblyService.kt`
- Port the Python `assemble_live_features()` logic to Kotlin
- Reads from Room via DAO JOIN (offline_weather_snapshot → weather_cache)
- Maps DB column names to ML feature names (e.g., `temp` → `temp_c`, `pressure` → `pressure_hPa`)
- Port: geohash encode/decode, lag features, Gulf Coast distance, temporal features
- Input: Room snapshot history → Output: `Map<String, Float?>` (26 features)

### 2.4 Integrate weather snapshot collection into LocationTrackingService
- Modify `LocationTrackingService.kt`:
  - On each location update, fetch current weather from Open-Meteo directly
  - Round timestamp to current hour; only store one snapshot per hour
  - Insert a `weather_cache` row (weather data, `is_forecast = false`) + `offline_weather_snapshot` row (device linkage, `synced_at = null`, `is_current = true`)
  - Set previous `is_current = false` for this device
  - Prune past observation snapshots older than 24h
  - Replace any forecast row for the current hour with the real observation (`is_forecast = false` overwrites `is_forecast = true`)

### 2.5 Fetch and cache 7-day hourly forecast
- New file: `frontend/.../weathertracker/data/ForecastFetcher.kt`
- On WiFi connect or on periodic WorkManager schedule:
  - Call Open-Meteo with `forecast_days=7` for the device's current location
  - Returns 168 hourly rows (~50 KB) with temp, dew_point, pressure, humidity, wind_speed, precipitation, elevation
  - Bulk insert into `weather_cache` with `is_forecast = true`, linked via `offline_weather_snapshot`
  - Overwrite stale forecast rows (same hour) with fresher forecast data
  - Prune forecast rows whose `recorded_at` is in the past (replaced by observations or expired)
- Enables forward-looking predictions and full offline operation for up to 7 days

## Phase 3 — Supabase Sync (Account-Tied Cache)

### 3.1 Use existing `weather_cache` + `offline_weather_snapshot` tables
- No new Supabase tables needed — both already exist in the schema
- `offline_weather_snapshot.synced_at` already tracks sync state (null = unsynced)
- `offline_weather_snapshot.device_id` already links to `device` table (→ `anon_user_id`)

### 3.2 Backend sync endpoints
- New endpoint: `POST /devices/{device_id}/sync-snapshots`
  - Accepts: array of `{weather_cache: {...}, offline_snapshot: {...}}` pairs
  - Upserts into `weather_cache` then `offline_weather_snapshot` via PostgREST
  - Returns: server-side snapshot+cache pairs for this device that the client doesn't have (for hydration)
- New endpoint: `GET /devices/{device_id}/snapshots?since=<ISO_timestamp>`
  - JOINs `offline_weather_snapshot` → `weather_cache` for this device since cutoff
  - Returns full weather data for hydrating local Room DB on new install

### 3.3 Android sync manager
- New file: `frontend/.../weathertracker/sync/SnapshotSyncManager.kt`
- **WiFi sync trigger**: Use `ConnectivityManager` + `NetworkCallback` to detect WiFi connection
- **Location sync trigger**: After each new snapshot insertion in `LocationTrackingService`
- Sync logic:
  1. Query `OfflineWeatherSnapshotDao.getUnsyncedSnapshots(deviceId)` (where `synced_at IS NULL`)
  2. Batch POST to `/devices/{device_id}/sync-snapshots` with weather_cache + snapshot pairs
  3. On success, call `markSynced(ids, now)` locally — sets `synced_at` timestamp
  4. On WiFi connect, also pull any server-side snapshots missing locally (hydrate Room)
- **WorkManager** for reliable background sync (survives app kill, respects battery)
  - Periodic sync constraint: `NetworkType.UNMETERED` (WiFi only)
  - One-shot sync on new snapshot: `NetworkType.CONNECTED` (any network)

### 3.4 Account binding
- Snapshots are tied to `device_id` via `offline_weather_snapshot.device_id` (→ `device.anon_user_id`)
- On new device / reinstall: pull existing snapshots from server using stored `device_id` from SharedPreferences → hydrate local Room DB
- If no stored credentials, register new device (existing flow) and start fresh cache

## Phase 4 — End-to-End Prediction Flow (On-Device)

### 4.1 Modify prediction trigger
- Currently: `BackendRepository.getPrediction()` calls `/predict/live` endpoint
- New flow — **forecast-ahead predictions**:
  1. On forecast fetch (Phase 2.5) or new observation snapshot → trigger prediction batch
  2. For the **current hour**: assemble features from Room history (observations + forecast fallback) → predict
  3. For **each future forecast hour** (up to 168h): assemble features using past observations + forecast rows as the sliding history window → predict
  4. Store results as a **risk timeline** in Room: `List<HourlyPrediction(timestamp, stormProbability, alertState)>`
  5. If any hour within the next 6h has `alertState == 1`, trigger a local notification: "Storm likely at [time]"
  6. Hourly WorkManager task re-runs current-hour prediction with latest data (observation if online, forecast if offline)
  7. Optionally POST current-hour result summary to backend for logging/analytics

### 4.2 Update UI integration
- Modify `WeatherOverviewScreen.kt` or `MainActivity.kt` to display on-device prediction results
- Show **risk timeline**: hourly storm probability chart for the next 24–48h
- Show current risk score, risk level, alert state
- Highlight hours where `alertState == 1`
- Remove or deprecate the `/predict/live` backend call for ML predictions
- Keep NWS alert fetching (government alerts) — fetched by the app via backend proxy, not pushed

### 4.3 Notification changes — all local
- **ML storm alerts**: triggered locally on-device via Android `NotificationManager` — no FCM needed
  - Proactive: "Storm risk rises to [X]% at [time]" based on forecast-ahead predictions
  - Notification cooldown: 1 per area per 6h to avoid spam
- **NWS government alerts**: fetched on-demand by the app via `BackendRepository.getAlerts()` (NWS API proxy) — displayed in UI, not push-delivered
- **Remove `WeatherTrackerMessagingService.kt`** — no longer needed (no FCM)

## Phase 5 — Backend & Firebase Cleanup

### 5.1 Deprecate server-side ML inference endpoints
- Mark `/predict/live` as deprecated (keep for backward compat or remove)
- Keep `/areas/{area_key}/prediction` and `/areas/{area_key}/history` if needed for admin/debugging
- Keep `/predict/health` and `/predict/metadata` for diagnostics
- Remove `_run_area_prediction()`, `_get_area_lock()`, single-flight lock infrastructure from `app.py`

### 5.2 Remove Firebase entirely
- All notifications are now local — no server-side push needed
- **Backend removals:**
  - Delete `backend/firebase-key.json` — service account credentials
  - Delete `backend/firebase_notifications.py` — `firebase_service` module
  - Remove `firebase-admin>=6.1.0` from `backend/requirements.txt`
  - Remove `/notifications/send-ml-alerts` endpoint from `app.py`
  - Remove all `firebase_service` imports and calls from `app.py`
- **Frontend removals:**
  - Delete `frontend/.../weathertracker/WeatherTrackerMessagingService.kt` — FCM handler
  - Remove `BackendRepository.registerDeviceToken()`, `sendNotification()`, `sendWeatherAlert()` methods
  - Remove FCM token fetch from `AuthenticationService.kt` (`FirebaseMessaging.getInstance().token`)
  - Remove `firebase-messaging` dependency from `build.gradle.kts`
  - Remove `<service>` declaration for `WeatherTrackerMessagingService` from `AndroidManifest.xml`
  - Delete `frontend/app/google-services.json` (only needed for Firebase; Maps uses a separate API key)
  - Remove `com.google.gms.google-services` plugin from `build.gradle.kts` (if no other Firebase services used)
- **Schema:** `device_token` column removal (see 5.3)

### 5.3 Remove `device_token` column from `device` table
- `device_token` stored the FCM registration token — no longer needed
- New migration: `006_remove_device_token.sql`
  ```sql
  ALTER TABLE device DROP COLUMN IF EXISTS device_token;
  ```
- Update `backend/create_schema.sql` to remove `device_token TEXT` from the `device` table definition
- Update `backend/app.py`:
  - Remove any references to `device_token` in device registration / update endpoints
  - Remove `device_token` from select queries (e.g., `/notifications/send-ml-alerts` already removed in 5.2)
- Update frontend `BackendRepository.register()` — remove `deviceToken` parameter from registration call
- Update `AuthenticationService.kt` — remove FCM token retrieval logic

### 5.4 Simplify backend dependencies
- `lib/ml/` modules become optional (only needed for model conversion script)
- `area_weather_snapshot` and `area_alert_state` tables can be dropped or kept for analytics
- Backend becomes primarily: auth, location tracking, weather proxy, NWS alerts API proxy, snapshot sync

## Relevant Files

**New files to create:**
- `backend/scripts/convert_model_to_onnx.py` — one-time model conversion
- `backend/migrations/005_add_ml_columns_to_weather_cache.sql` — add ML columns + `is_forecast` to weather_cache
- `backend/migrations/006_remove_device_token.sql` — drop device_token from device table
- `frontend/.../weathertracker/ml/OnDevicePredictor.kt` — ONNX inference wrapper
- `frontend/.../weathertracker/ml/FeatureAssemblyService.kt` — Kotlin feature assembly
- `frontend/.../weathertracker/data/WeatherDatabase.kt` — Room DB
- `frontend/.../weathertracker/data/WeatherCacheEntity.kt` — mirrors weather_cache table
- `frontend/.../weathertracker/data/OfflineWeatherSnapshotEntity.kt` — mirrors offline_weather_snapshot table
- `frontend/.../weathertracker/data/WeatherCacheDao.kt` — weather cache DAO
- `frontend/.../weathertracker/data/OfflineWeatherSnapshotDao.kt` — snapshot DAO
- `frontend/.../weathertracker/data/ForecastFetcher.kt` — 7-day forecast fetch + cache
- `frontend/.../weathertracker/sync/SnapshotSyncManager.kt` — sync orchestration
- `frontend/app/src/main/assets/ml/model.onnx` — converted model
- `frontend/app/src/main/assets/ml/model_metadata.json` — feature cols, threshold

**Files to modify:**
- `frontend/app/build.gradle.kts` — add Room, ONNX Runtime, WorkManager deps; remove firebase-messaging, google-services plugin
- `frontend/.../LocationTrackingService.kt` — add snapshot collection + sync trigger
- `frontend/.../BackendRepository.kt` — add sync endpoints; remove `registerDeviceToken()`, `sendNotification()`, `sendWeatherAlert()`
- `frontend/.../AuthenticationService.kt` — remove FCM token fetch
- `frontend/.../MainActivity.kt` — integrate on-device prediction display + risk timeline
- `frontend/.../WeatherOverviewScreen.kt` — show risk timeline, current prediction
- `frontend/.../AndroidManifest.xml` — remove WeatherTrackerMessagingService declaration; add network state permission if needed
- `backend/app.py` — add sync endpoints; remove `/notifications/send-ml-alerts`, Firebase imports, `device_token` references; deprecate ML inference endpoints
- `backend/requirements.txt` — remove `firebase-admin`
- `backend/create_schema.sql` — update weather_cache definition with new ML columns + `is_forecast`; remove `device_token` from device table

**Files to delete:**
- `backend/firebase-key.json` — Firebase service account credentials
- `backend/firebase_notifications.py` — Firebase notification module
- `frontend/.../weathertracker/WeatherTrackerMessagingService.kt` — FCM message handler
- `frontend/app/google-services.json` — Firebase config (only if no other Firebase services used)

**Files to reference (port logic from):**
- `lib/ml/feature_assembly_service.py` — port geohash, lag computation, coast distance to Kotlin
- `lib/inference.py` — reference for feature mapping, calibration logic
- `lib/ml/live_weather_service.py` — reference for Open-Meteo API call format

## Verification

1. **Model conversion**: Run `convert_model_to_onnx.py`, compare ONNX output vs joblib output on both test vectors (clear day: ~4.8%, severe storm: ~83.8%)
2. **On-device inference**: Unit test `OnDevicePredictor` with same test vectors in `androidTest/`
3. **Feature assembly**: Unit test Kotlin `FeatureAssemblyService` against Python output for identical input snapshots
4. **Room DB**: Unit test DAO operations — upsert, query 48h window, prune, mark synced, forecast vs observation overwrites
5. **Forecast fetching**: Unit test `ForecastFetcher` — mock Open-Meteo, verify 168 rows inserted with `is_forecast=true`
6. **Forecast-ahead predictions**: Unit test batch prediction over 168 hours, verify risk timeline output
7. **Local notifications**: Manual test — trigger high-risk forecast hour, verify Android notification fires
8. **Sync flow**: Integration test — insert unsynced snapshots, trigger sync, verify server receives them and local rows marked synced
9. **WiFi trigger**: Manual test — toggle WiFi on/off, verify sync fires on reconnect
10. **End-to-end**: Install app, wait for forecast fetch + prediction cycle, verify risk timeline displays in UI
11. **Offline 7-day**: Put device in airplane mode, verify cached 7-day forecast still allows predictions and risk timeline renders
12. **Firebase removal**: Verify app builds cleanly without Firebase dependencies, no runtime crashes from missing FCM

## Decisions

- **Revert to per-device model**: Area-based geohash grouping is removed for on-device use. Each device maintains its own snapshot history.
- **Reuse `weather_cache` + `offline_weather_snapshot` tables**: Add ML columns to `weather_cache` via migration. `offline_weather_snapshot` already has `device_id`, `synced_at`, `is_current` — perfect for device-tied sync tracking. No new tables created.
- **`device_weather_snapshot` table deprecated**: Kept in schema but no longer actively used — `weather_cache` + `offline_weather_snapshot` replaces its role.
- **Drop area tables later**: `area_weather_snapshot` and `area_alert_state` can be dropped after migration is complete.
- **Open-Meteo direct from device**: The app will call Open-Meteo directly for current conditions and 7-day forecasts (no backend proxy needed for weather data).
- **Firebase fully removed**: All notifications are local (Android NotificationManager). No FCM dependency. Backend drops firebase-admin, firebase-key.json, firebase_notifications.py. Frontend drops WeatherTrackerMessagingService.kt, google-services.json, firebase-messaging dependency.
- **`device_token` column removed**: No longer needed since notifications are local. Migration 006 drops the column.
- **7-day forecast caching**: Open-Meteo provides 168-hour hourly forecasts. Cached locally with `is_forecast=true`. Allows proactive warnings and offline prediction for the next 7 days.
- **Forecast-ahead predictions replace push alerts**: Instead of reacting to current conditions via FCM, the app proactively scans all 168 forecast hours and presents a risk timeline with local notifications for upcoming high-risk periods.
- **Model updates**: Future model updates require an app update (or a dynamic model download endpoint — out of scope for now).

## Further Considerations

1. **Isotonic calibration in ONNX**: sklearn's `IsotonicRegression` may not convert cleanly to ONNX. Alternative: export the calibration mapping as a lookup table in `model_metadata.json` and interpolate in Kotlin.
2. **NWP features**: Currently all NWP features (`nwp_cape_f3_6_max`, etc.) are `None`/`NaN` — the model handles this via `SimpleImputer`. If NWP data becomes available later, both the on-device collector and sync schema would need updating.
3. **Battery impact**: WorkManager periodic work (every 3-6 hours for forecasts, hourly for observations) is more battery-friendly than continuous polling. Monitor actual battery usage during testing.
4. **NWS alerts**: Government weather alerts are currently not addressed after Firebase removal. Consider fetching them directly from NWS API on-device, or via a lightweight backend proxy endpoint, as a follow-up task.
