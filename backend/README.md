# Weather Tracker Backend

FastAPI backend for the Weather Tracker application, serving as an API proxy layer and ML prediction server.

## Overview

This backend provides:
- **API Proxying** - Routes requests to Weather API, RainViewer, and Supabase
- **ML Integration** - `POST /predict` endpoint for weather predictions
- **Centralized Auth** - Stores and manages API keys securely
- **Error Handling** - Unified error responses across all APIs

## Setup

### Prerequisites
- Python 3.9 or higher
- pip

### Installation

1. Create and activate virtual environment:
```bash
python3 -m venv .venv
# On Windows
venv\Scripts\activate
# On macOS/Linux
source .venv/bin/activate
```

2. Activate the virtual environment:
```powershell
# Windows (PowerShell)
./.venv/Scripts/Activate.ps1

# Windows (Command Prompt)
.venv\Scripts\activate.bat

# macOS/Linux
source .venv/bin/activate
```

3. Install dependencies:
```bash
pip install -r requirements.txt
```

4. Configure environment variables (optional):
```bash
cp .env.example .env
# Edit .env with your configuration (Supabase URL, API keys, etc.)
```

## Running the Backend

```bash
python app.py
```

The server will start on `http://localhost:5000` by default.

Keep this terminal running while you test the Android app. In a second terminal, verify the backend is reachable:

```bash
cd backend
source .venv/bin/activate
curl http://localhost:5000/health
```

If you are using the Android emulator, the frontend should target `http://10.0.2.2:5000/`. If `/health` is not reachable on your computer, the app will show that it is not connected to the server.

**Interactive API Documentation:**
- Swagger UI: `http://localhost:5000/docs`
- ReDoc: `http://localhost:5000/redoc`

## API Endpoints

### Health Check
- **GET** `/health` - Check if the server is running
  - Response: `{"status": "healthy"}`

### Weather API Proxies
All endpoints proxy to `https://api.weather.gov`

- **GET** `/weather/points/{lat}/{lon}` - Get weather point data
  - Params: `lat` (double), `lon` (double)
  
- **GET** `/weather/forecast` - Get forecast for a location
  - Query: `url` - Forecast URL from points endpoint
  
- **GET** `/weather/alerts` - Get active weather alerts
  - Query: `point` - Point identifier (e.g., "40.7128,-74.0060")

### RainViewer API Proxy
Proxies to `https://api.rainviewer.com`

- **GET** `/rainviewer/maps` - Get weather radar/rainfall maps
  - Returns available maps with timestamps and tile information

### Supabase Proxies
Automatically includes Supabase API key (stored on backend for security)

- **POST** `/supabase/anon-user` - Create anonymous user
  - Body: `{"anonUserId": "user-id", "status": "active"}`
  
- **POST** `/supabase/device` - Create device record
  - Body: `{"deviceId": "device-id", "anonUserId": "user-id", "platform": "android", ...}`

### ML Prediction (TODO)
- **POST** `/predict` - Get weather prediction from ML model
  - Body: (Update based on your model's input fields)
  - Response: `{"prediction": "prediction_value"}`

## ML Model Integration

To integrate your Python ML model:

1. Place your model file in `backend/models/`

2. Create a predictor class (e.g., `models/predictor.py`):
```python
class WeatherPredictor:
    def __init__(self, model_path='./models/model.pkl'):
        self.model = joblib.load(model_path)
    
    def predict(self, input_data):
        return self.model.predict(input_data)
```

3. Update `app.py` - Uncomment the ML section and define your input/output models

## Frontend Integration

The Android frontend calls all APIs through this backend:
- See `INTEGRATION_GUIDE.md` in project root for migration details
- Frontend uses `BackendRepository` to make all API calls
- Base URL: `http://10.0.2.2:5000/` (emulator) or your machine IP (physical device)

## Architecture

```
Frontend (Android) → Backend (FastAPI) → External APIs
                                      ├→ Weather API
                                      ├→ RainViewer
                                      ├→ Supabase
                                      └→ ML Model
```

## Development

### Enable Hot Reload
Set `DEBUG=True` in `.env`:
```env
DEBUG=True
PORT=5000
```

Server will automatically reload when files change.

### Test Endpoints
Use Swagger UI at `http://localhost:5000/docs` to test endpoints interactively

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | 5000 | Server port |
| `DEBUG` | False | Enable hot reload |
| `SUPABASE_BASE_URL` | http://localhost:54321 | Supabase instance URL |
| `SUPABASE_API_KEY` | (see .env.example) | Supabase API key |

## Troubleshooting

### Port Already in Use
```powershell
# Windows - Find process on port 5000
netstat -ano | findstr :5000
# Kill process
taskkill /PID <PID> /F
```

### CORS Issues
CORS is enabled for all origins. If issues persist:
- Verify frontend base URL matches `BackendRetrofitInstance.kt`
- Check backend is accessible from your device/emulator

### Supabase Connection Errors
- Verify `SUPABASE_BASE_URL` in `.env`
- Check `SUPABASE_API_KEY` is valid
- Ensure Supabase instance is running (if local)

### API Timeouts
- Check internet connection
- Verify external API endpoints are accessible
- Look for rate limiting issues

### Predictions
- **POST** `/predict` - Get ML model predictions
  - Request body: Update `PredictionRequest` model in `app.py` with required fields
  - Response: `{"prediction": "..."}`

## ML Model Integration

Place your trained ML model in the `models/` directory. Update `app.py` to:
1. Import your model predictor class
2. Load the model during app initialization
3. Implement the prediction logic

## Project Structure

```
backend/
├── app.py                 # Main FastAPI application
├── requirements.txt       # Python dependencies
├── .env.example          # Environment variables template
├── models/               # ML models directory (create as needed)
├── supabase/             # Supabase configuration
└── *.sql                 # Database initialization scripts
```
