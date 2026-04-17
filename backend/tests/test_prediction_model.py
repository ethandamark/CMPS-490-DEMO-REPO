"""
Test the ML prediction model with falsified weather data.

Two scenarios:
  1. CLEAR DAY  — calm, dry, high-pressure conditions → no storm → no notification
  2. SEVERE STORM — hot, humid, rapidly dropping pressure, heavy rain → storm → notification

Both tests feed data directly into the real model bundle and assert
that alert_state is 0 (no storm) or 1 (storm).  Console output shows
the full prediction result and whether a notification should be sent.
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import pytest

# ── Path setup ──────────────────────────────────────────────────────
_BACKEND_DIR = Path(__file__).resolve().parent.parent
_PROJECT_ROOT = _BACKEND_DIR.parent
for _p in (_BACKEND_DIR, _PROJECT_ROOT):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

warnings.filterwarnings("ignore", category=UserWarning)

from lib.inference import discover_latest_model, load_model_bundle, predict


# ── Fixtures ────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def model_bundle():
    """Load the real model bundle once for all tests in this module."""
    model_path = discover_latest_model()
    assert model_path is not None, "No model bundle found under lib/models/"
    return load_model_bundle(model_path)


# ── Falsified weather payloads ──────────────────────────────────────

CLEAR_DAY_WEATHER = {
    # Calm, dry spring morning — no storm indicators whatsoever
    "temp_c": 22.0,
    "pressure_hPa": 1020.0,
    "humidity_pct": 35.0,
    "wind_speed_kmh": 8.0,
    "precip_mm": 0.0,
    "precip_6h": 0.0,
    "precip_24h": 0.0,
    "precip_rate_change": 0.0,
    "precip_max_3h": 0.0,
    "pressure_change_1h": 0.2,        # rising pressure
    "pressure_change_3h": 0.5,
    "pressure_change_6h": 1.0,
    "pressure_change_12h": 1.5,
    "pressure_drop_rate": -0.2,        # negative = rising
    "temp_dewpoint_spread": 12.0,      # very dry air
    "dewpoint_spread_change": 0.5,
    "wind_speed_change_1h": 0.0,
    "wind_speed_change_3h": -1.0,
    "wind_max_3h": 10.0,
    "hour": 10,
    "month": 4,
    "is_afternoon": 0,
    "latitude": 30.22,
    "longitude": -92.02,
    "elevation": 10.0,
    "dist_to_coast_km": 120.0,
    # NWP features (calm conditions)
    "nwp_cape_f3_6_max": 50.0,
    "nwp_cin_f3_6_max": -120.0,
    "nwp_pwat_f3_6_max": 15.0,
    "nwp_srh03_f3_6_max": 20.0,
    "nwp_li_f3_6_min": 4.0,
    "nwp_lcl_f3_6_min": 2500.0,
    "nwp_available_leads": 4.0,
}

SEVERE_STORM_WEATHER = {
    # Hot, humid summer afternoon with rapidly dropping pressure,
    # heavy rain, and strong winds — textbook severe storm setup
    "temp_c": 33.0,
    "pressure_hPa": 998.0,
    "humidity_pct": 92.0,
    "wind_speed_kmh": 55.0,
    "precip_mm": 18.0,
    "precip_6h": 45.0,
    "precip_24h": 80.0,
    "precip_rate_change": 12.0,
    "precip_max_3h": 25.0,
    "pressure_change_1h": -6.0,        # rapidly falling
    "pressure_change_3h": -14.0,
    "pressure_change_6h": -22.0,
    "pressure_change_12h": -30.0,
    "pressure_drop_rate": 6.0,
    "temp_dewpoint_spread": 1.0,       # near-saturated air
    "dewpoint_spread_change": -3.0,
    "wind_speed_change_1h": 15.0,
    "wind_speed_change_3h": 30.0,
    "wind_max_3h": 65.0,
    "hour": 17,
    "month": 6,
    "is_afternoon": 1,
    "latitude": 30.22,
    "longitude": -92.02,
    "elevation": 10.0,
    "dist_to_coast_km": 120.0,
    # NWP features (severe storm conditions)
    "nwp_cape_f3_6_max": 3500.0,
    "nwp_cin_f3_6_max": -15.0,
    "nwp_pwat_f3_6_max": 55.0,
    "nwp_srh03_f3_6_max": 250.0,
    "nwp_li_f3_6_min": -6.0,
    "nwp_lcl_f3_6_min": 500.0,
    "nwp_available_leads": 4.0,
}


# ── Tests ───────────────────────────────────────────────────────────

class TestPredictionModel:
    """Run the real ML model against fabricated weather scenarios."""

    def test_clear_day_no_storm(self, model_bundle):
        """Clear-day conditions should predict NO storm → no notification."""
        result = predict(CLEAR_DAY_WEATHER, model_bundle)

        print("\n" + "=" * 60)
        print("SCENARIO: Clear Day (no storm expected)")
        print("=" * 60)
        print(json.dumps(result, indent=2, default=str))
        print(f"\n  Storm probability : {result['storm_probability']:.4f}")
        print(f"  Threshold         : {result['threshold_used']:.4f}")
        print(f"  Alert state       : {result['alert_state']}")
        if result["alert_state"] == 0:
            print("  >> NO STORM detected — notification will NOT be sent")
        else:
            print("  >> STORM detected — notification WOULD be sent")
        print("=" * 60)

        assert result["alert_state"] == 0, (
            f"Expected no storm (alert_state=0) but got alert_state={result['alert_state']} "
            f"with probability {result['storm_probability']:.4f}"
        )
        assert result["storm_probability"] < result["threshold_used"]

    def test_severe_storm_alert(self, model_bundle):
        """Severe storm conditions should predict STORM → notification sent."""
        result = predict(SEVERE_STORM_WEATHER, model_bundle)

        print("\n" + "=" * 60)
        print("SCENARIO: Severe Storm (storm expected)")
        print("=" * 60)
        print(json.dumps(result, indent=2, default=str))
        print(f"\n  Storm probability : {result['storm_probability']:.4f}")
        print(f"  Threshold         : {result['threshold_used']:.4f}")
        print(f"  Alert state       : {result['alert_state']}")
        if result["alert_state"] == 1:
            print("  >> STORM detected — notification SHOULD be sent to users")
        else:
            print("  >> NO STORM detected — notification will NOT be sent")
        print("=" * 60)

        assert result["alert_state"] == 1, (
            f"Expected storm (alert_state=1) but got alert_state={result['alert_state']} "
            f"with probability {result['storm_probability']:.4f}"
        )
        assert result["storm_probability"] >= result["threshold_used"]
