"""
ML Pipeline Verification Script
================================
Tests every layer of the ML stack end-to-end:

  1. Model Loading        – Can we find and load the joblib bundle?
  2. Direct Inference      – Clear-day vs severe-storm synthetic payloads
  3. Live Weather Fetch    – Open-Meteo current + 24h historical
  4. Feature Assembly      – Build 26-feature vector from live weather
  5. Full Pipeline         – fetch → assemble → predict (real weather, real model)
  6. PredictorClient       – Local-mode health / metadata / predict via the service layer

Run from the backend/ directory with the venv active:

    python test_ml_verification.py

No pytest required — pure script with colour-coded pass/fail output.
"""
from __future__ import annotations

import json
import sys
import time
import traceback
from pathlib import Path

# ── path setup ──────────────────────────────────────────────────────
_BACKEND_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _BACKEND_DIR.parent
for _p in (_BACKEND_DIR, _PROJECT_ROOT):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

# ── colours (Windows terminal safe via ANSI) ────────────────────────
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"

# ── test coordinates (Lafayette, LA — matches your device logs) ─────
TEST_LAT = 30.2241
TEST_LON = -92.0198


# ── helpers ─────────────────────────────────────────────────────────

def section(title: str):
    print(f"\n{BOLD}{CYAN}{'=' * 64}")
    print(f"  {title}")
    print(f"{'=' * 64}{RESET}")


def passed(msg: str):
    print(f"  {GREEN}✓ PASS{RESET}  {msg}")


def failed(msg: str):
    print(f"  {RED}✗ FAIL{RESET}  {msg}")


def warn(msg: str):
    print(f"  {YELLOW}⚠ WARN{RESET}  {msg}")


def info(msg: str):
    print(f"  {CYAN}ℹ{RESET}  {msg}")


results: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str = ""):
    results.append((name, ok, detail))
    if ok:
        passed(detail or name)
    else:
        failed(detail or name)


# ====================================================================
# TEST 1 — Model Loading
# ====================================================================
def test_model_loading():
    section("1 · Model Loading")
    from lib.inference import discover_latest_model, load_model_bundle

    model_path = discover_latest_model()
    if model_path is None:
        record("model_discovery", False, "No .joblib model found under lib/models/")
        return None

    info(f"Model path: {model_path}")
    record("model_discovery", True, f"Found model: {model_path.name}")

    bundle = load_model_bundle(model_path)
    keys = set(bundle.keys())
    required = {"base_model", "isotonic", "feature_cols", "threshold"}
    missing = required - keys
    if missing:
        record("bundle_keys", False, f"Missing bundle keys: {missing}")
        return None

    info(f"Experiment  : {bundle.get('experiment_name', 'N/A')}")
    info(f"Features    : {len(bundle['feature_cols'])}")
    info(f"Threshold   : {bundle['threshold']:.6f}")
    record("bundle_keys", True, f"All required keys present ({len(keys)} total)")

    return bundle


# ====================================================================
# TEST 2 — Direct Inference (synthetic payloads)
# ====================================================================
def test_direct_inference(bundle):
    section("2 · Direct Inference (synthetic weather)")
    if bundle is None:
        record("clear_day", False, "Skipped — no model bundle")
        record("severe_storm", False, "Skipped — no model bundle")
        return

    from lib.inference import predict

    # --- 2a: Clear day (expect alert_state=0) ---
    clear_day = {
        "temp_c": 22.0, "pressure_hPa": 1020.0, "humidity_pct": 35.0,
        "wind_speed_kmh": 8.0, "precip_mm": 0.0,
        "precip_6h": 0.0, "precip_24h": 0.0, "precip_rate_change": 0.0,
        "precip_max_3h": 0.0,
        "pressure_change_1h": 0.2, "pressure_change_3h": 0.5,
        "pressure_change_6h": 1.0, "pressure_change_12h": 1.5,
        "pressure_drop_rate": -0.2,
        "temp_dewpoint_spread": 12.0, "dewpoint_spread_change": 0.5,
        "wind_speed_change_1h": 0.0, "wind_speed_change_3h": -1.0,
        "wind_max_3h": 10.0,
        "hour": 10, "month": 4, "is_afternoon": 0,
        "latitude": TEST_LAT, "longitude": TEST_LON,
        "elevation": 10.0, "dist_to_coast_km": 120.0,
        # NWP features (calm conditions)
        "nwp_cape_f3_6_max": 50.0, "nwp_cin_f3_6_max": -120.0,
        "nwp_pwat_f3_6_max": 15.0, "nwp_srh03_f3_6_max": 20.0,
        "nwp_li_f3_6_min": 4.0, "nwp_lcl_f3_6_min": 2500.0,
        "nwp_available_leads": 4.0,
    }
    result = predict(clear_day, bundle)
    info(f"Probability : {result['storm_probability']:.4f}  (threshold {result['threshold_used']:.4f})")
    info(f"Alert state : {result['alert_state']}")
    info(f"Missing feat: {result['missing_feature_count']}")
    record(
        "clear_day",
        result["alert_state"] == 0,
        f"Clear day → alert_state={result['alert_state']}, prob={result['storm_probability']:.4f}",
    )

    # --- 2b: Severe storm (expect alert_state=1) ---
    storm = {
        "temp_c": 33.0, "pressure_hPa": 998.0, "humidity_pct": 92.0,
        "wind_speed_kmh": 55.0, "precip_mm": 18.0,
        "precip_6h": 45.0, "precip_24h": 80.0, "precip_rate_change": 12.0,
        "precip_max_3h": 25.0,
        "pressure_change_1h": -6.0, "pressure_change_3h": -14.0,
        "pressure_change_6h": -22.0, "pressure_change_12h": -30.0,
        "pressure_drop_rate": 6.0,
        "temp_dewpoint_spread": 1.0, "dewpoint_spread_change": -3.0,
        "wind_speed_change_1h": 15.0, "wind_speed_change_3h": 30.0,
        "wind_max_3h": 65.0,
        "hour": 17, "month": 6, "is_afternoon": 1,
        "latitude": TEST_LAT, "longitude": TEST_LON,
        "elevation": 10.0, "dist_to_coast_km": 120.0,
        # NWP features (severe storm conditions)
        "nwp_cape_f3_6_max": 3500.0, "nwp_cin_f3_6_max": -15.0,
        "nwp_pwat_f3_6_max": 55.0, "nwp_srh03_f3_6_max": 250.0,
        "nwp_li_f3_6_min": -6.0, "nwp_lcl_f3_6_min": 500.0,
        "nwp_available_leads": 4.0,
    }
    result = predict(storm, bundle)
    info(f"Probability : {result['storm_probability']:.4f}  (threshold {result['threshold_used']:.4f})")
    info(f"Alert state : {result['alert_state']}")
    record(
        "severe_storm",
        result["alert_state"] == 1,
        f"Severe storm → alert_state={result['alert_state']}, prob={result['storm_probability']:.4f}",
    )


# ====================================================================
# TEST 3 — Live Weather Fetch (Open-Meteo)
# ====================================================================
def test_live_weather():
    section("3 · Live Weather Fetch (Open-Meteo)")
    from lib.ml.live_weather_service import fetch_live_prediction_input, fetch_historical_hourly

    # --- 3a: Current conditions ---
    try:
        t0 = time.time()
        current = fetch_live_prediction_input(TEST_LAT, TEST_LON)
        elapsed = time.time() - t0

        obs = current.get("current_observation", {})
        info(f"Valid time   : {current['valid_time_utc']}")
        info(f"Temp         : {obs.get('temp_c')}°C")
        info(f"Pressure     : {obs.get('pressure_hPa')} hPa")
        info(f"Humidity     : {obs.get('humidity_pct')}%")
        info(f"Wind         : {obs.get('wind_speed_kmh')} km/h")
        info(f"Precip       : {obs.get('precip_mm')} mm")
        info(f"Elevation    : {current.get('static_features', {}).get('elevation')} m")
        info(f"Fetched in   : {elapsed:.2f}s")
        record("current_weather", True, f"Current obs fetched ({elapsed:.2f}s)")
    except Exception as exc:
        record("current_weather", False, f"fetch_live_prediction_input failed: {exc}")
        current = None

    # --- 3b: Historical hourly (24h) ---
    try:
        t0 = time.time()
        history = fetch_historical_hourly(TEST_LAT, TEST_LON, hours=24)
        elapsed = time.time() - t0
        info(f"History rows : {len(history)}  (expected ~24)")
        if history:
            info(f"First row    : {history[0].get('time_utc')}")
            info(f"Last row     : {history[-1].get('time_utc')}")
        record(
            "historical_weather",
            len(history) >= 12,
            f"Got {len(history)} hourly rows ({elapsed:.2f}s)",
        )
    except Exception as exc:
        record("historical_weather", False, f"fetch_historical_hourly failed: {exc}")
        history = None

    return current, history


# ====================================================================
# TEST 4 — Feature Assembly (26-feature vector from live data)
# ====================================================================
def test_feature_assembly(current, history):
    section("4 · Feature Assembly (build 26-feature vector)")
    if current is None:
        record("feature_assembly", False, "Skipped — no live weather data")
        return None

    from lib.ml.feature_assembly_service import geohash_encode

    area_key = geohash_encode(TEST_LAT, TEST_LON)
    info(f"Area key     : {area_key}")

    # Build a feature vector directly from the current observation +
    # historical data WITHOUT needing Supabase snapshots.  This tests
    # the computation logic independently.
    obs = current.get("current_observation", {})
    static = current.get("static_features", {})

    # If we have historical data, compute derived features from it
    features: dict = {}
    if history and len(history) >= 2:
        latest = history[-1]
        features["temp_c"] = latest.get("temp_c")
        features["pressure_hPa"] = latest.get("pressure_hPa")
        features["humidity_pct"] = latest.get("humidity_pct")
        features["wind_speed_kmh"] = latest.get("wind_speed_kmh")
        features["precip_mm"] = latest.get("precip_mm")
        features["elevation"] = latest.get("elevation")

        # Precipitation aggregates
        precips = [h.get("precip_mm") or 0 for h in history]
        features["precip_6h"] = sum(precips[-6:])
        features["precip_24h"] = sum(precips)
        features["precip_rate_change"] = (precips[-1] - precips[-2]) if len(precips) >= 2 else 0
        features["precip_max_3h"] = max(precips[-3:]) if len(precips) >= 3 else precips[-1]

        # Pressure changes
        pressures = [h.get("pressure_hPa") for h in history]
        p_now = pressures[-1]
        features["pressure_change_1h"] = (p_now - pressures[-2]) if len(pressures) >= 2 and pressures[-2] else None
        features["pressure_change_3h"] = (p_now - pressures[-4]) if len(pressures) >= 4 and pressures[-4] else None
        features["pressure_change_6h"] = (p_now - pressures[-7]) if len(pressures) >= 7 and pressures[-7] else None
        features["pressure_change_12h"] = (p_now - pressures[-13]) if len(pressures) >= 13 and pressures[-13] else None
        features["pressure_drop_rate"] = -features["pressure_change_1h"] if features["pressure_change_1h"] is not None else None

        # Temperature-dewpoint spread
        dew = latest.get("dew_point_c")
        temp = latest.get("temp_c")
        if temp is not None and dew is not None:
            features["temp_dewpoint_spread"] = temp - dew
        else:
            features["temp_dewpoint_spread"] = None

        prev_dew = history[-2].get("dew_point_c") if len(history) >= 2 else None
        prev_temp = history[-2].get("temp_c") if len(history) >= 2 else None
        if temp is not None and dew is not None and prev_temp is not None and prev_dew is not None:
            features["dewpoint_spread_change"] = (temp - dew) - (prev_temp - prev_dew)
        else:
            features["dewpoint_spread_change"] = None

        # Wind changes
        winds = [h.get("wind_speed_kmh") or 0 for h in history]
        features["wind_speed_change_1h"] = winds[-1] - winds[-2] if len(winds) >= 2 else 0
        features["wind_speed_change_3h"] = winds[-1] - winds[-4] if len(winds) >= 4 else 0
        features["wind_max_3h"] = max(winds[-3:]) if len(winds) >= 3 else winds[-1]

        # Temporal
        import datetime
        now = datetime.datetime.now(datetime.timezone.utc)
        features["hour"] = now.hour
        features["month"] = now.month
        features["is_afternoon"] = 1 if 12 <= now.hour < 24 else 0

        # Static / spatial
        features["latitude"] = TEST_LAT
        features["longitude"] = TEST_LON

        # Distance to coast
        from lib.ml.feature_assembly_service import _distance_to_gulf_coast
        features["dist_to_coast_km"] = _distance_to_gulf_coast(TEST_LAT, TEST_LON)
    else:
        # Fallback: use current obs directly with defaults for derived
        features = {
            "temp_c": obs.get("temp_c"),
            "pressure_hPa": obs.get("pressure_hPa"),
            "humidity_pct": obs.get("humidity_pct"),
            "wind_speed_kmh": obs.get("wind_speed_kmh"),
            "precip_mm": obs.get("precip_mm"),
            "elevation": static.get("elevation"),
            "latitude": TEST_LAT,
            "longitude": TEST_LON,
            "hour": 12, "month": 4, "is_afternoon": 0,
        }
        warn("Not enough history — using current obs with missing derived features")

    # Count how many of the 26 expected features we have
    expected_26 = [
        "temp_c", "pressure_hPa", "humidity_pct", "wind_speed_kmh",
        "precip_mm", "precip_6h", "precip_24h", "precip_rate_change",
        "precip_max_3h", "pressure_change_1h", "pressure_change_3h",
        "pressure_change_6h", "pressure_change_12h", "pressure_drop_rate",
        "temp_dewpoint_spread", "dewpoint_spread_change",
        "wind_speed_change_1h", "wind_speed_change_3h", "wind_max_3h",
        "hour", "month", "is_afternoon",
        "latitude", "longitude", "elevation", "dist_to_coast_km",
    ]
    present = sum(1 for f in expected_26 if features.get(f) is not None)
    missing = [f for f in expected_26 if features.get(f) is None]

    info(f"Features set : {present}/{len(expected_26)}")
    if missing:
        info(f"Missing      : {missing}")

    record(
        "feature_assembly",
        present >= 20,
        f"Assembled {present}/{len(expected_26)} features from live data",
    )
    return features


# ====================================================================
# TEST 5 — Full Pipeline (live weather → features → prediction)
# ====================================================================
def test_full_pipeline(bundle, features):
    section("5 · Full Pipeline (live weather → model prediction)")
    if bundle is None:
        record("full_pipeline", False, "Skipped — no model bundle")
        return
    if features is None:
        record("full_pipeline", False, "Skipped — no feature vector")
        return

    from lib.inference import predict

    result = predict(features, bundle)

    info(f"Raw prob     : {result['raw_probability']:.4f}")
    info(f"Calibrated   : {result['storm_probability']:.4f}")
    info(f"Threshold    : {result['threshold_used']:.4f}")
    info(f"Alert state  : {result['alert_state']}  ({'STORM' if result['alert_state'] == 1 else 'CLEAR'})")
    info(f"Model ver    : {result['model_version']}")
    info(f"Missing feat : {result['missing_feature_count']}  {result['missing_features']}")

    # NWP features (nwp_cape, nwp_cin, etc.) are not available from
    # Open-Meteo live data, so up to 7 missing is acceptable — the
    # model's SimpleImputer handles them with mean imputation.
    ok = (
        0.0 <= result["storm_probability"] <= 1.0
        and result["alert_state"] in (0, 1)
        and result["missing_feature_count"] <= 7
    )
    record(
        "full_pipeline",
        ok,
        f"Live prediction: prob={result['storm_probability']:.4f}, "
        f"alert={'STORM' if result['alert_state'] else 'CLEAR'}, "
        f"missing={result['missing_feature_count']}",
    )


# ====================================================================
# TEST 6 — PredictorClient (service-layer local mode)
# ====================================================================
def test_predictor_client():
    section("6 · PredictorClient (local mode via service layer)")
    from lib.ml.ml_prediction_service import PredictorClient, PredictorUnavailableError
    from config import (
        ML_PREDICTOR_API_URL,
        ML_PREDICTOR_MODE,
        ML_PREDICTOR_ROOT,
        ML_PREDICTOR_REQUEST_TIMEOUT_SECONDS,
    )

    client = PredictorClient(
        mode="local",
        api_url=ML_PREDICTOR_API_URL,
        predictor_root=ML_PREDICTOR_ROOT,
        request_timeout_seconds=ML_PREDICTOR_REQUEST_TIMEOUT_SECONDS,
    )

    # --- 6a: Health check ---
    try:
        health = client.health()
        info(f"Status       : {health.get('status')}")
        info(f"Mode         : {health.get('mode')}")
        info(f"Model ver    : {health.get('model_version')}")
        info(f"Threshold    : {health.get('threshold')}")
        info(f"Feature cnt  : {health.get('feature_count')}")
        record("client_health", health.get("status") == "ok", f"Health: {health.get('status')}")
    except PredictorUnavailableError as exc:
        record("client_health", False, f"PredictorUnavailableError: {exc}")
        return

    # --- 6b: Metadata ---
    try:
        meta = client.metadata()
        info(f"Columns      : {len(meta.get('feature_columns', []))} features")
        info(f"Threshold    : {meta.get('threshold')}")
        record("client_metadata", True, f"{len(meta.get('feature_columns', []))} feature columns returned")
    except Exception as exc:
        record("client_metadata", False, str(exc))

    # --- 6c: Predict via client ---
    try:
        storm_payload = {
            "temp_c": 33.0, "pressure_hPa": 998.0, "humidity_pct": 92.0,
            "wind_speed_kmh": 55.0, "precip_mm": 18.0,
            "precip_6h": 45.0, "precip_24h": 80.0, "precip_rate_change": 12.0,
            "precip_max_3h": 25.0,
            "pressure_change_1h": -6.0, "pressure_change_3h": -14.0,
            "pressure_change_6h": -22.0, "pressure_change_12h": -30.0,
            "pressure_drop_rate": 6.0,
            "temp_dewpoint_spread": 1.0, "dewpoint_spread_change": -3.0,
            "wind_speed_change_1h": 15.0, "wind_speed_change_3h": 30.0,
            "wind_max_3h": 65.0,
            "hour": 17, "month": 6, "is_afternoon": 1,
            "latitude": TEST_LAT, "longitude": TEST_LON,
            "elevation": 10.0, "dist_to_coast_km": 120.0,
            # NWP features (severe storm)
            "nwp_cape_f3_6_max": 3500.0, "nwp_cin_f3_6_max": -15.0,
            "nwp_pwat_f3_6_max": 55.0, "nwp_srh03_f3_6_max": 250.0,
            "nwp_li_f3_6_min": -6.0, "nwp_lcl_f3_6_min": 500.0,
            "nwp_available_leads": 4.0,
        }
        pred = client.predict(storm_payload)
        info(f"Probability  : {pred['storm_probability']:.4f}")
        info(f"Alert state  : {pred['alert_state']}")
        record(
            "client_predict",
            pred["alert_state"] == 1,
            f"Client predict → alert_state={pred['alert_state']}, prob={pred['storm_probability']:.4f}",
        )
    except Exception as exc:
        record("client_predict", False, str(exc))


# ====================================================================
# SUMMARY
# ====================================================================
def print_summary():
    section("SUMMARY")
    total = len(results)
    passed_count = sum(1 for _, ok, _ in results if ok)
    failed_count = total - passed_count

    for name, ok, detail in results:
        status = f"{GREEN}PASS{RESET}" if ok else f"{RED}FAIL{RESET}"
        print(f"  [{status}] {name}: {detail}")

    print()
    colour = GREEN if failed_count == 0 else RED
    print(f"  {BOLD}{colour}{passed_count}/{total} tests passed{RESET}")
    if failed_count:
        print(f"  {RED}{failed_count} test(s) failed{RESET}")
    print()


# ====================================================================
# MAIN
# ====================================================================
if __name__ == "__main__":
    print(f"\n{BOLD}ML Pipeline Verification — Weather Tracker{RESET}")
    print(f"Test location: {TEST_LAT}, {TEST_LON}  (Lafayette, LA)")
    print(f"{'─' * 64}")

    try:
        bundle = test_model_loading()
        test_direct_inference(bundle)
        current, history = test_live_weather()
        features = test_feature_assembly(current, history)
        test_full_pipeline(bundle, features)
        test_predictor_client()
    except Exception:
        print(f"\n{RED}Unexpected error:{RESET}")
        traceback.print_exc()

    print_summary()
    sys.exit(0 if all(ok for _, ok, _ in results) else 1)
