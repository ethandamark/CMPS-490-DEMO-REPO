# Weather Tracker - ML Prediction System

A comprehensive weather tracking and risk analysis application with integrated machine learning predictions.

## Project Structure

```
├── frontend/                 # Android application (Kotlin/Jetpack Compose)
│   ├── app/                 # Android app module
│   ├── build.gradle.kts     # Root build configuration
│   ├── settings.gradle.kts  # Project settings
│   ├── gradle/              # Gradle wrapper and dependencies
│   ├── gradlew              # Gradle wrapper script
│   └── gradle.properties    # Gradle properties
│
├── backend/                 # Python backend with ML model integration
│   ├── app.py              # Flask API server
│   ├── requirements.txt     # Python dependencies
│   ├── .env.example        # Environment variables template
│   ├── venv/               # Python virtual environment
│   ├── models/             # ML models directory
│   ├── supabase/           # Supabase configuration
│   ├── *.sql               # Database initialization scripts
│   └── README.md           # Backend documentation
│
└── documentation/          # Project documentation
    ├── docs/               # Technical documentation
    └── README.md           # Original project README
```

## Quick Start

### Frontend (Android)

```bash
cd frontend
./gradlew build
```

### Backend (Python ML)

```bash
cd backend
.\venv\Scripts\activate  # On Windows
source venv/bin/activate  # On macOS/Linux
python app.py
```

The API will be available at `http://localhost:5000`

## Backend API Endpoints

- **GET** `/health` - Health check
- **POST** `/predict` - ML model predictions (payload structure TBD)

## Environment Setup

### Backend Configuration

1. Copy the template:
```bash
cd backend
cp .env.example .env
```

2. Edit `.env` with your configuration

## Technologies

- **Frontend**: Android, Kotlin, Jetpack Compose, Google Maps API, Retrofit
- **Backend**: Flask, Python
- **ML Libraries**: scikit-learn, pandas, numpy
- **Database**: Supabase PostgreSQL
- **Weather APIs**: RainViewer, OpenWeather

## Next Steps

1. Integrate your Python ML prediction model into `backend/models/`
2. Update `backend/app.py` to load and use your model
3. Test the `/predict` endpoint with your model's input format
4. Connect the Android frontend to the backend API

## Contributors

CMPS 490 Senior Project Team
