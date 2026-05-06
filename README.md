# TempestAI

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

### Weather (NWS Proxies)

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/weather/forecast` | NWS gridpoint forecast |
| `GET` | `/weather/hourly` | NWS hourly forecast |
| `GET` | `/weather/alerts` | NWS active alerts for a point |
| `GET` | `/weather/points` | NWS grid-point metadata |
| `GET` | `/weather/stations` | NWS stations |
| `GET` | `/weather/observations` | NWS observations |
| `GET` | `/weather/zones` | NWS zones |
| `GET` | `/weather/products` | NWS products |

### Radar

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/rainviewer/maps` | RainViewer radar tile manifest (5-min TTL cache) |

### Device Registration & Management

| Method | Route | Description |
|--------|-------|-------------|
| `POST` | `/supabase/register` | Register anonymous user + device |
| `PATCH` | `/supabase/device` | Update device metadata |
| `GET` | `/supabase/device/{device_id}` | Fetch device record |
| `GET` | `/supabase/snapshots/{device_id}` | Fetch offline snapshots for a device |

### Device Location

| Method | Route | Description |
|--------|-------|-------------|
| `POST` | `/device-location/update-current` | Upsert current GPS position — at most one row per device |

### ML & Sync

| Method | Route | Description |
|--------|-------|-------------|
| `POST` | `/devices/{device_id}/seed-weather-history` | Seed 24 h of Open-Meteo weather history into Supabase |
| `POST` | `/devices/{device_id}/sync-snapshots` | Bulk upload offline weather snapshots |
| `POST` | `/devices/{device_id}/model-instance` | Save a single on-device ML prediction result |
| `POST` | `/devices/{device_id}/sync-model-instances` | Bulk upload model instance predictions |

## Database

Supabase PostgreSQL + Android Room DB. See [documentation/docs/db.md](documentation/docs/db.md) for the full schema.

**Supabase tables:** `anonymous_user`, `device`, `device_location`, `offline_weather_snapshot`, `model_instance`

**Room DB tables (24 h retention, pruned each location update):** `weather_cache`, `offline_weather_snapshot`, `model_instance`, `hourly_prediction`

## ML Pipeline

The model was trained offline using XGBoost + scikit-learn (saved as a joblib artifact), then converted to ONNX format and bundled directly into the Android app (`assets/ml/model.onnx`). All inference runs on-device — no data is ever sent to a server for prediction.

1. **Observation collection** — last 24 h of `weather_cache` rows from Room DB (max 24 records)
2. **Feature assembly** — 26 base features: raw (5) · precipitation (4) · pressure (5) · wind/temp (5) · temporal (3) · static/geographic (4)
3. **NWP passthrough** — 7 features direct from Open-Meteo: CAPE · CIN · PWAT · SRH03 · LI · LCL · `nwp_available` flag
4. **ONNX inference + isotonic calibration** — `model.onnx` (v2.0.0) loaded via ONNX Runtime; raw probability mapped through a lookup table to a calibrated score
5. **Result storage** — `model_instance` written to Room DB and synced to Supabase hourly
6. **Alert decision** — notification fires when calibrated probability ≥ 0.4901 or an active NWS alert exists for the area

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
- **ML:** XGBoost + scikit-learn (offline training, joblib export → ONNX conversion), ONNX Runtime (on-device inference), pandas, numpy
- **Database:** Supabase (PostgreSQL), Row-Level Security
- **Weather Data:** National Weather Service API, Open-Meteo API, RainViewer API

## Contributors
- Roland Okungbowa
- Abigail Choate
- Ethan Marks
- Rayden Farmer
- Michael Bray
- Tucker Styles