"""
Centralised configuration for the Weather Tracker backend.

Every value is read from environment variables (via .env) so nothing
secret is hard-coded.  Missing non-critical values fall back to sensible
defaults for local development.
"""
from __future__ import annotations

import os
from dotenv import load_dotenv

load_dotenv()

# ── Supabase ────────────────────────────────────────────────────────
SUPABASE_BASE_URL: str = os.getenv("SUPABASE_BASE_URL", "http://localhost:54321")
SUPABASE_API_KEY: str = os.getenv("SUPABASE_API_KEY", "")
SUPABASE_DB_URL: str = os.getenv(
    "SUPABASE_DB_URL",
    "postgresql://postgres:postgres@localhost:54322/postgres",
)

# ── Firebase ────────────────────────────────────────────────────────
FIREBASE_CREDENTIALS_PATH: str = os.getenv("FIREBASE_CREDENTIALS_PATH", "")

# ── ML Predictor ────────────────────────────────────────────────────
ML_PREDICTOR_MODE: str = os.getenv("ML_PREDICTOR_MODE", "auto")
ML_PREDICTOR_API_URL: str = os.getenv("ML_PREDICTOR_API_URL", "http://localhost:8000")
ML_PREDICTOR_ROOT: str = os.getenv("ML_PREDICTOR_ROOT", "")
ML_PREDICTOR_REQUEST_TIMEOUT_SECONDS: int = int(
    os.getenv("ML_PREDICTOR_REQUEST_TIMEOUT_SECONDS", "30")
)

# ── Live Weather ────────────────────────────────────────────────────
LIVE_WEATHER_PROVIDER: str = os.getenv("LIVE_WEATHER_PROVIDER", "open_meteo")
LIVE_WEATHER_TIMEOUT_SECONDS: int = int(
    os.getenv("LIVE_WEATHER_TIMEOUT_SECONDS", "15")
)
OPEN_METEO_BASE_URL: str = os.getenv(
    "OPEN_METEO_BASE_URL",
    "https://api.open-meteo.com/v1/forecast",
)
LOCATION_CHANGE_THRESHOLD_KM: float = float(
    os.getenv("LOCATION_CHANGE_THRESHOLD_KM", "50")
)

# ── Area-based prediction ───────────────────────────────────────────
AREA_CELL_PRECISION: int = int(os.getenv("AREA_CELL_PRECISION", "5"))
