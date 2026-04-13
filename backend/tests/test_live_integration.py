"""
Integration test: end-to-end prediction with live weather + Supabase.

Creates a fake device in Supabase for Lafayette, LA (30.2241°N, 92.0198°W),
fetches real-time weather from Open-Meteo, assembles features, runs the ML
model, and stores results in Supabase — exactly as the production pipeline
does.

Cleanup:  The test automatically deletes ALL device-specific rows it created
(anonymous_user, device, device_location, device_weather_snapshot).
Area-level data (area_weather_snapshot, area_alert_state) is intentionally
left in place because it is universal / shared across devices.
"""
from __future__ import annotations

import json
import sys
import uuid
import warnings
from datetime import datetime, timezone
from pathlib import Path

import pytest
import requests

# ── Path setup ──────────────────────────────────────────────────────
_BACKEND_DIR = Path(__file__).resolve().parent.parent
_PROJECT_ROOT = _BACKEND_DIR.parent
for _p in (_BACKEND_DIR, _PROJECT_ROOT):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

warnings.filterwarnings("ignore", category=UserWarning)

from config import SUPABASE_BASE_URL, SUPABASE_API_KEY
from lib.inference import discover_latest_model, load_model_bundle, predict
from lib.ml.feature_assembly_service import (
    assemble_live_features,
    geohash_encode,
    geohash_decode,
)
from lib.ml.live_weather_service import fetch_live_prediction_input
from lib.ml.ml_prediction_service import get_predictor_client

# ── Constants ───────────────────────────────────────────────────────
LAFAYETTE_LAT = 30.2241
LAFAYETTE_LON = -92.0198


# ── Supabase helpers ────────────────────────────────────────────────

def _sb_headers(*, prefer: str | None = None) -> dict[str, str]:
    h = {"apikey": SUPABASE_API_KEY, "Content-Type": "application/json"}
    if prefer:
        h["Prefer"] = prefer
    return h


def _sb_post(table: str, payload: dict) -> dict:
    resp = requests.post(
        f"{SUPABASE_BASE_URL}/rest/v1/{table}",
        json=payload,
        headers=_sb_headers(prefer="return=representation"),
        timeout=30,
    )
    assert resp.status_code in (200, 201), (
        f"POST {table} failed ({resp.status_code}): {resp.text}"
    )
    return resp.json()[0]


def _sb_delete(table: str, column: str, value: str) -> None:
    resp = requests.delete(
        f"{SUPABASE_BASE_URL}/rest/v1/{table}?{column}=eq.{value}",
        headers=_sb_headers(),
        timeout=30,
    )
    assert resp.status_code in (200, 204), (
        f"DELETE {table} failed ({resp.status_code}): {resp.text}"
    )


def _sb_get(table: str, column: str, value: str) -> list[dict]:
    resp = requests.get(
        f"{SUPABASE_BASE_URL}/rest/v1/{table}?{column}=eq.{value}",
        headers=_sb_headers(),
        timeout=30,
    )
    return resp.json() if resp.status_code == 200 else []


# ── Fixtures ────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def model_bundle():
    """Load the real model bundle once for all tests."""
    model_path = discover_latest_model()
    assert model_path is not None, "No model bundle found under lib/models/"
    return load_model_bundle(model_path)


@pytest.fixture(scope="module")
def fake_device():
    """Create a fake device in Supabase and tear it down after all tests.

    Yields a dict with: anon_user_id, device_id, alert_token, location_id,
    area_key, rep_lat, rep_lon.
    """
    now = datetime.now(timezone.utc).isoformat()
    anon_user_id = str(uuid.uuid4())
    device_id = str(uuid.uuid4())
    alert_token = str(uuid.uuid4())
    location_id = str(uuid.uuid4())
    area_key = geohash_encode(LAFAYETTE_LAT, LAFAYETTE_LON)
    rep_lat, rep_lon = geohash_decode(area_key)

    # 1. Create anonymous user
    _sb_post("anonymous_user", {
        "anon_user_id": anon_user_id,
        "status": "active",
        "created_at": now,
        "last_active_at": now,
        "notification_opt_in": True,
    })

    # 2. Create device
    _sb_post("device", {
        "device_id": device_id,
        "anon_user_id": anon_user_id,
        "alert_token": alert_token,
        "platform": "android",
        "app_version": "1.0-test",
        "location_permission_status": True,
        "last_seen_at": now,
        "created_at": now,
        "area_key": area_key,
    })

    # 3. Create device location
    _sb_post("device_location", {
        "location_id": location_id,
        "device_id": device_id,
        "latitude": LAFAYETTE_LAT,
        "longitude": LAFAYETTE_LON,
        "captured_at": now,
    })

    info = {
        "anon_user_id": anon_user_id,
        "device_id": device_id,
        "alert_token": alert_token,
        "location_id": location_id,
        "area_key": area_key,
        "rep_lat": rep_lat,
        "rep_lon": rep_lon,
    }

    print(f"\n{'=' * 60}")
    print("FAKE DEVICE CREATED FOR INTEGRATION TEST")
    print(f"{'=' * 60}")
    print(f"  anon_user_id : {anon_user_id}")
    print(f"  device_id    : {device_id}")
    print(f"  alert_token  : {alert_token}")
    print(f"  location_id  : {location_id}")
    print(f"  area_key     : {area_key}")
    print(f"  centroid     : ({rep_lat:.4f}, {rep_lon:.4f})")
    print(f"{'=' * 60}\n")

    yield info

    # ── Teardown: delete device-specific data ───────────────────────
    print(f"\n{'=' * 60}")
    print("CLEANING UP FAKE DEVICE DATA")
    print(f"{'=' * 60}")

    # Delete in FK-safe order: children first, then parents.
    _sb_delete("device_location", "device_id", device_id)
    print(f"  ✓ Deleted device_location for device {device_id}")

    _sb_delete("device", "device_id", device_id)
    print(f"  ✓ Deleted device {device_id}")

    _sb_delete("anonymous_user", "anon_user_id", anon_user_id)
    print(f"  ✓ Deleted anonymous_user {anon_user_id}")

    print(f"\n  ℹ Area data (area_weather_snapshot, area_alert_state) for")
    print(f"    area_key={area_key} was intentionally kept — it is universal.")
    print(f"{'=' * 60}\n")


# ── Tests ───────────────────────────────────────────────────────────

class TestLiveIntegration:
    """End-to-end integration tests against Supabase + live weather."""

    def test_device_exists_in_supabase(self, fake_device):
        """Verify the fake device was actually written to Supabase."""
        devices = _sb_get("device", "device_id", fake_device["device_id"])
        assert len(devices) == 1, "Fake device not found in Supabase"
        assert devices[0]["area_key"] == fake_device["area_key"]

        locations = _sb_get("device_location", "device_id", fake_device["device_id"])
        assert len(locations) >= 1, "Device location not found in Supabase"
        assert float(locations[0]["latitude"]) == pytest.approx(LAFAYETTE_LAT, abs=0.01)
        assert float(locations[0]["longitude"]) == pytest.approx(LAFAYETTE_LON, abs=0.01)

        print("  ✓ Device and location verified in Supabase")

    def test_fetch_live_weather(self, fake_device):
        """Fetch real-time weather for Lafayette, LA from Open-Meteo."""
        live_input = fetch_live_prediction_input(
            latitude=LAFAYETTE_LAT,
            longitude=LAFAYETTE_LON,
        )

        obs = live_input["current_observation"]
        print(f"\n{'=' * 60}")
        print("LIVE WEATHER — Lafayette, LA")
        print(f"{'=' * 60}")
        print(f"  Time          : {live_input['valid_time_utc']}")
        print(f"  Temperature   : {obs['temp_c']}°C")
        print(f"  Dew Point     : {obs['dew_point_c']}°C")
        print(f"  Pressure      : {obs['pressure_hPa']} hPa")
        print(f"  Humidity      : {obs['humidity_pct']}%")
        print(f"  Wind Speed    : {obs['wind_speed_kmh']} km/h")
        print(f"  Precipitation : {obs['precip_mm']} mm")
        print(f"  Elevation     : {live_input['static_features']['elevation']} m")
        print(f"{'=' * 60}")

        assert obs["temp_c"] is not None, "Temperature was None"
        assert obs["pressure_hPa"] is not None, "Pressure was None"
        assert obs["humidity_pct"] is not None, "Humidity was None"

    def test_feature_assembly_with_supabase(self, fake_device):
        """Assemble ML features using live weather + Supabase snapshots."""
        live_input = fetch_live_prediction_input(
            latitude=LAFAYETTE_LAT,
            longitude=LAFAYETTE_LON,
        )

        features = assemble_live_features(
            area_key=fake_device["area_key"],
            representative_lat=fake_device["rep_lat"],
            representative_lon=fake_device["rep_lon"],
            request_data=live_input,
        )

        print(f"\n{'=' * 60}")
        print("ASSEMBLED FEATURES")
        print(f"{'=' * 60}")
        for k, v in sorted(features.items()):
            print(f"  {k:30s} : {v}")
        print(f"{'=' * 60}")

        assert "temp_c" in features
        assert "pressure_hPa" in features
        assert "latitude" in features
        assert "longitude" in features
        assert features["temp_c"] is not None

    def test_end_to_end_prediction(self, fake_device, model_bundle):
        """Full pipeline: live weather → features → ML prediction → result.

        This is the core integration test that proves the entire approach
        works against the real Supabase database and live weather data.
        """
        # 1. Fetch live weather
        live_input = fetch_live_prediction_input(
            latitude=LAFAYETTE_LAT,
            longitude=LAFAYETTE_LON,
        )

        # 2. Assemble features (writes snapshot to Supabase)
        features = assemble_live_features(
            area_key=fake_device["area_key"],
            representative_lat=fake_device["rep_lat"],
            representative_lon=fake_device["rep_lon"],
            request_data=live_input,
        )

        # 3. Run prediction through the local model bundle
        result = predict(features, model_bundle)

        print(f"\n{'=' * 60}")
        print("END-TO-END PREDICTION RESULT — Lafayette, LA (Live Weather)")
        print(f"{'=' * 60}")
        print(json.dumps(result, indent=2, default=str))
        print(f"\n  Storm probability : {result['storm_probability']:.4f}")
        print(f"  Raw probability   : {result['raw_probability']:.4f}")
        print(f"  Threshold         : {result['threshold_used']:.4f}")
        print(f"  Alert state       : {result['alert_state']}")

        if result["alert_state"] == 1:
            print("  >> STORM detected — notification WOULD be sent")
        else:
            print("  >> NO STORM detected — no notification needed")

        if result.get("missing_features"):
            print(f"  Missing features  : {result['missing_features']}")
        print(f"{'=' * 60}")

        # The prediction must succeed and return valid values
        assert "storm_probability" in result
        assert "alert_state" in result
        assert result["storm_probability"] >= 0.0
        assert result["storm_probability"] <= 1.0
        assert result["alert_state"] in (0, 1)

    def test_predictor_client_prediction(self, fake_device):
        """Run prediction via PredictorClient (matches production code path)."""
        # 1. Fetch live weather
        live_input = fetch_live_prediction_input(
            latitude=LAFAYETTE_LAT,
            longitude=LAFAYETTE_LON,
        )

        # 2. Assemble features
        features = assemble_live_features(
            area_key=fake_device["area_key"],
            representative_lat=fake_device["rep_lat"],
            representative_lon=fake_device["rep_lon"],
            request_data=live_input,
        )

        # 3. Run via PredictorClient (same as app.py uses)
        predictor = get_predictor_client()
        result = predictor.predict(weather_data=features)

        print(f"\n{'=' * 60}")
        print("PREDICTOR CLIENT RESULT (production code path)")
        print(f"{'=' * 60}")
        print(json.dumps(result, indent=2, default=str))
        print(f"{'=' * 60}")

        assert "storm_probability" in result
        assert "alert_state" in result
        assert result["alert_state"] in (0, 1)

    def test_area_snapshot_written(self, fake_device):
        """Verify that the feature assembly step wrote a snapshot to Supabase."""
        snapshots = _sb_get(
            "area_weather_snapshot", "area_key", fake_device["area_key"]
        )
        assert len(snapshots) >= 1, (
            f"No area_weather_snapshot rows found for area_key={fake_device['area_key']}"
        )

        latest = snapshots[0]
        print(f"\n  ✓ Found {len(snapshots)} snapshot(s) for area {fake_device['area_key']}")
        print(f"    Latest timestamp: {latest.get('timestamp')}")
        print(f"    Temp: {latest.get('temp_c')}°C, Pressure: {latest.get('pressure_hpa')} hPa")
