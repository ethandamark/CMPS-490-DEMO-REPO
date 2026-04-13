"""
Inference helper for severe_pipeline model bundles.

This script loads the model bundle produced by:
  severe_pipeline.py train_eval

Bundle format (joblib dict):
  - base_model
  - isotonic
  - feature_cols
  - threshold
  - experiment_name
  - test_start_utc
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence

import joblib
import numpy as np


REQUIRED_BUNDLE_KEYS = {
    "base_model",
    "isotonic",
    "feature_cols",
    "threshold",
}


def _to_float(value: Any) -> float:
    if value is None:
        return float("nan")
    if isinstance(value, (bool, np.bool_)):
        return float(int(value))
    if isinstance(value, (int, float, np.integer, np.floating)):
        return float(value)
    s = str(value).strip()
    if s == "":
        return float("nan")
    return float(s)


def _get_payload_value(payload: Mapping[str, Any], key: str) -> Any:
    # Direct hit
    if key in payload:
        return payload[key]

    # Common aliases used by app/backend payloads
    alias_map: Dict[str, Sequence[str]] = {
        "pressure_hPa": ("pressure_hpa", "pressure", "pressure_mb"),
        "wind_speed_kmh": ("wind_kmh", "wind_speed"),
        "temp_c": ("temperature_c", "temperature"),
        "precip_mm": ("rain_mm", "precip"),
        "latitude": ("lat",),
        "longitude": ("lon", "lng"),
    }
    for alias in alias_map.get(key, ()):
        if alias in payload:
            return payload[alias]

    # Unit conversion support: if m/s is provided, convert to km/h.
    if key == "wind_speed_kmh" and "wind_speed_mps" in payload:
        try:
            return float(payload["wind_speed_mps"]) * 3.6
        except Exception:
            return None

    return None


def load_model_bundle(model_path: Path) -> Dict[str, Any]:
    bundle = joblib.load(model_path)
    if not isinstance(bundle, dict):
        raise TypeError(f"Model file is not a bundle dict: {model_path}")

    missing = sorted(REQUIRED_BUNDLE_KEYS - set(bundle.keys()))
    if missing:
        raise KeyError(f"Bundle missing keys {missing}: {model_path}")
    return bundle


def discover_latest_model() -> Optional[Path]:
    candidates = list(Path("artifacts/severe_pipeline").glob("**/*_model.joblib"))
    if not candidates:
        return None
    return max(candidates, key=lambda p: p.stat().st_mtime)


def predict(payload: Mapping[str, Any], bundle: Dict[str, Any], threshold_override: Optional[float] = None) -> Dict[str, Any]:
    feature_cols = list(bundle["feature_cols"])
    missing_features = []
    values = []

    for f in feature_cols:
        raw = _get_payload_value(payload, f)
        if raw is None:
            missing_features.append(f)
        values.append(_to_float(raw))

    x = np.array(values, dtype=float).reshape(1, -1)

    base_model = bundle["base_model"]
    isotonic = bundle["isotonic"]

    raw_prob = float(base_model.predict_proba(x)[0, 1])
    calibrated_prob = float(np.clip(isotonic.predict(np.array([raw_prob], dtype=float))[0], 0.0, 1.0))

    threshold = float(bundle["threshold"] if threshold_override is None else threshold_override)
    alert_state = int(calibrated_prob >= threshold)

    return {
        "storm_probability": calibrated_prob,
        "raw_probability": raw_prob,
        "alert_state": alert_state,
        "threshold_used": threshold,
        "model_version": str(bundle.get("experiment_name", "unknown")),
        "test_start_utc": bundle.get("test_start_utc"),
        "feature_count": len(feature_cols),
        "missing_feature_count": len(missing_features),
        "missing_features": missing_features,
    }


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Run inference with severe_pipeline model bundle")
    p.add_argument(
        "--model-path",
        type=Path,
        default=None,
        help="Path to *_model.joblib. If omitted, auto-discovers latest under artifacts/severe_pipeline.",
    )
    p.add_argument("--input-json", type=str, default=None, help="Inline JSON payload string")
    p.add_argument("--input-file", type=Path, default=None, help="JSON file path with payload")
    p.add_argument("--threshold", type=float, default=None, help="Optional threshold override")
    p.add_argument("--pretty", action="store_true", help="Pretty-print output JSON")
    return p.parse_args()


def main() -> int:
    args = parse_args()

    model_path = args.model_path or discover_latest_model()
    if model_path is None:
        raise FileNotFoundError(
            "No model found. Provide --model-path or place a bundle under artifacts/severe_pipeline."
        )

    if args.input_json and args.input_file:
        raise ValueError("Use either --input-json or --input-file, not both.")

    if args.input_json:
        payload = json.loads(args.input_json)
    elif args.input_file:
        payload = json.loads(args.input_file.read_text(encoding="utf-8"))
    else:
        # Demo payload for quick sanity checks
        payload = {
            "temp_c": 30.0,
            "pressure_hPa": 1005.0,
            "humidity_pct": 82.0,
            "wind_speed_kmh": 22.0,
            "precip_mm": 1.2,
            "hour": 18,
            "month": 6,
            "latitude": 30.45,
            "longitude": -91.15,
            "elevation": 15.0,
            "dist_to_coast_km": 110.0,
        }

    bundle = load_model_bundle(model_path)
    result = predict(payload, bundle, threshold_override=args.threshold)
    result["model_path"] = str(model_path)

    if args.pretty:
        print(json.dumps(result, indent=2))
    else:
        print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
