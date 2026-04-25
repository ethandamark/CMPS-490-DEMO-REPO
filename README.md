# Weather Tracker

An Android weather tracking application with on-device ML severe-weather predictions, backed by a FastAPI server and Supabase PostgreSQL.

## Project Structure

```
├── frontend/                 # Android app (Kotlin / Jetpack Compose)
│   ├── app/
│   │   └── src/main/
│   │       ├── assets/ml/   # ONNX model + metadata for on-device inference
│   │       ├── java/com/CMPS490/weathertracker/
│   │       │   ├── data/    # Room DB entities, DAOs, ForecastFetcher
│   │       │   ├── ml/      # FeatureAssemblyService, on-device prediction
│   │       │   ├── network/ # Retrofit API interface + client
│   │       │   └── sync/    # SnapshotSyncManager (WorkManager + Reconnection to Backend)
│   │       └── res/         # Drawables, mipmaps, values
│   ├── build.gradle.kts
│   └── gradle/              # Version catalog + wrapper
│
├── backend/                  # FastAPI server (Python 3.10+)
│   ├── app.py               # All API routes
│   ├── create_schema.sql    # Full database schema
│   ├── migrations/          # Incremental SQL migrations
│   ├── tests/               # pytest integration tests
│   ├── requirements.txt
│   └── .env.example
│
├── lib/                      # Shared Python ML library
│   ├── inference.py          # Joblib model loading + predict()
│   └── ml/                   # Feature assembly, live weather, prediction service
│
├── supabase/                 # Supabase edge functions
│   └── functions/
│       └── sendTestAlert/    # FCM push notification test
│
└── documentation/            # Project docs (db, ml, prediction testing)
```

## Quick Start

### Prerequisites

- Android Studio (Ladybug+) with an emulator or physical device
- Python 3.10+ with `pip`
- Supabase CLI (`supabase start` for local PostgreSQL on port 54322)
- A Google Maps API key in `frontend/local.properties`

### 1. Start Supabase

```bash
cd backend
supabase start
```

Apply the schema on a fresh database:

```bash
psql -h 127.0.0.1 -p 54322 -U postgres -d postgres -f create_schema.sql
```

### 2. Start the Backend

```powershell
cd backend
.\venv\Scripts\Activate.ps1   # Windows
# source .venv/bin/activate   # macOS / Linux
python app.py
```

The API runs at `http://localhost:5000`. Verify with:

```bash
curl http://localhost:5000/health
```

The Android emulator reaches the host backend at `http://10.0.2.2:5000`.

### 3. Build & Run the Frontend

```bash
cd frontend
./gradlew assembleDebug
```

Install on a connected emulator/device via Android Studio or `adb install`.

## Backend API Endpoints

### Core

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/health` | Health check |

### Weather & Radar (NWS / RainViewer Proxies)

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/weather/points/{lat}/{lon}` | NWS grid-point metadata |
| `GET` | `/weather/forecast` | NWS forecast from grid URL |
| `GET` | `/weather/alerts` | NWS active alerts for a point |
| `GET` | `/rainviewer/maps` | RainViewer radar tile URLs |

### Device Registration & Management

| Method | Route | Description |
|--------|-------|-------------|
| `POST` | `/supabase/register` | Register anonymous user + device |
| `PATCH` | `/supabase/device` | Update device attributes (permissions, last seen) |

### Device Location

| Method | Route | Description |
|--------|-------|-------------|
| `POST` | `/device-location/create` | Create a location record |
| `POST` | `/device-location/update-current` | Upsert current GPS position (background tracking) |
| `GET` | `/device-location/by-device/{device_id}` | All locations for a device |
| `GET` | `/device-location/latest/{device_id}` | Most recent location |
| `GET` | `/device-location/{location_id}` | Single location |
| `PATCH` | `/device-location/{location_id}` | Update a location |
| `DELETE` | `/device-location/{location_id}` | Delete a location |

### Alerts

| Method | Route | Description |
|--------|-------|-------------|
| `POST` | `/alerts/create` | Create an ML-generated alert event |
| `GET` | `/alerts/active` | Active (non-expired) alerts, optionally filtered by radius |
| `GET` | `/alerts/{alert_id}` | Single alert |
| `DELETE` | `/alerts/{alert_id}` | Delete an alert |
| `GET` | `/device-alerts/{device_id}` | Delivery records for a device |
| `GET` | `/device-alerts/by-alert/{alert_id}` | Delivery status per device for an alert |
| `DELETE` | `/device-alerts/cleanup` | Purge old device-alert rows (30-day default) |

### ML & Sync

| Method | Route | Description |
|--------|-------|-------------|
| `POST` | `/devices/{device_id}/seed-weather-history` | Seed 24 h of Open-Meteo weather into Supabase |
| `POST` | `/devices/{device_id}/sync-snapshots` | Upsert weather cache + offline snapshots from client |
| `GET` | `/devices/{device_id}/snapshots` | Retrieve offline weather snapshots |
| `POST` | `/devices/{device_id}/model-instance` | Record an on-device ML prediction result |

## Database

Supabase PostgreSQL with 12 tables. See [documentation/docs/db.md](documentation/docs/db.md) for the full schema.

**Core tables:** `anonymous_user`, `device`, `device_location`, `weather_cache`, `model_instance`, `alert_event`, `device_alert`, `offline_weather_snapshot`

## ML Pipeline

The app runs a 37-feature XGBoost model on-device via ONNX Runtime:

1. **Weather history** is seeded from Open-Meteo (24 h hourly) at first launch
2. **Feature assembly** builds the input vector (surface obs, derived rates, temporal, geographic, NWP, radar)
3. **On-device inference** produces a storm probability + calibrated confidence via isotonic regression
4. **Results** are stored in `model_instance` and synced to Supabase

Server-side joblib models (`lib/models/`) support the same pipeline for testing and comparison.

## Offline / Fallback Behavior

- When the backend is unreachable, the app fetches current weather and a 7-day forecast directly from the **Open-Meteo API** and displays a "Connecting to backend…" banner
- The backend is health-checked every 10 seconds; on reconnection the app automatically switches back and retries registration
- GPS location tracking starts as soon as permissions are granted — it does not wait for backend registration

## Environment Variables

Copy the template and fill in your values:

```bash
cp backend/.env.example backend/.env
```

| Variable | Description |
|----------|-------------|
| `PORT` | Server port (default `5000`) |
| `DEBUG` | Enable debug logging (`True` / `False`) |
| `SUPABASE_BASE_URL` | Supabase REST API URL (local: `http://localhost:54321`) |
| `SUPABASE_API_KEY` | Supabase anon/public key |
| `WEATHER_API_BASE` | NWS API base URL |
| `RAINVIEWER_API_BASE` | RainViewer API base URL |
| `FIREBASE_CONFIG` | Firebase service account JSON (for push notifications) |

## Technologies

- **Frontend:** Kotlin, Jetpack Compose (Material 3), Google Maps SDK, Room, Retrofit / OkHttp, ONNX Runtime, WorkManager, Accompanist Permissions
- **Backend:** FastAPI, Uvicorn, httpx
- **ML:** XGBoost, scikit-learn, ONNX, joblib, pandas, numpy
- **Database:** Supabase (PostgreSQL), Row-Level Security
- **Weather Data:** National Weather Service API, Open-Meteo API, RainViewer API

## Contributors
