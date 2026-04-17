"""
Convert the baseline_surface sklearn pipeline to ONNX format for on-device inference.

Usage:
    python backend/scripts/convert_model_to_onnx.py

Outputs:
    frontend/app/src/main/assets/ml/model.onnx
    frontend/app/src/main/assets/ml/model_metadata.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_REPO_ROOT / "backend"))
sys.path.insert(0, str(_REPO_ROOT))

import joblib
import numpy as np
from lib.inference import discover_latest_model

OUT_DIR = _REPO_ROOT / "frontend" / "app" / "src" / "main" / "assets" / "ml"
ONNX_PATH = OUT_DIR / "model.onnx"
METADATA_PATH = OUT_DIR / "model_metadata.json"


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # Auto-discover latest model
    MODEL_PATH = discover_latest_model()
    if MODEL_PATH is None:
        print("ERROR: No model bundle found under lib/models/")
        sys.exit(1)

    print(f"Loading model bundle from: {MODEL_PATH}")
    bundle = joblib.load(MODEL_PATH)

    feature_cols: list[str] = list(bundle["feature_cols"])
    threshold: float = float(bundle["threshold"])
    experiment_name: str = str(bundle.get("experiment_name", "unknown"))
    base_model = bundle["base_model"]
    isotonic = bundle.get("isotonic")

    print(f"  experiment_name : {experiment_name}")
    print(f"  threshold       : {threshold}")
    print(f"  feature_cols    : {feature_cols}")

    # ── Convert pipeline to ONNX ────────────────────────────────────
    # The pipeline is SimpleImputer → XGBClassifier.
    # We embed the imputer fill-values in model_metadata.json and export
    # only the XGBClassifier to ONNX (the Kotlin side applies imputation
    # before feeding the tensor).
    imputer = None
    xgb_classifier = None
    imputer_fill_values: dict[str, float] = {}

    if hasattr(base_model, "steps"):
        for step_name, step_obj in base_model.steps:
            step_type = type(step_obj).__name__
            if "Imputer" in step_type:
                imputer = step_obj
            elif "XGB" in step_type or "xgb" in step_type:
                xgb_classifier = step_obj

    # Extract imputer fill values
    if imputer is not None and hasattr(imputer, "statistics_"):
        for col, val in zip(feature_cols, imputer.statistics_):
            imputer_fill_values[col] = float(val)
        print(f"  Imputer strategy: {imputer.strategy}, extracted {len(imputer_fill_values)} fill values")

    # Export XGBoost model to ONNX
    if xgb_classifier is not None:
        try:
            from onnxmltools.convert import convert_xgboost
            from onnxmltools.convert.common.data_types import FloatTensorType

            n_features = len(feature_cols)
            initial_type = [("float_input", FloatTensorType([None, n_features]))]
            onnx_model = convert_xgboost(
                xgb_classifier,
                initial_types=initial_type,
                target_opset=15,
            )
            with open(ONNX_PATH, "wb") as f:
                f.write(onnx_model.SerializeToString())
            print(f"✓ ONNX model written to: {ONNX_PATH}")
            print(f"  File size: {ONNX_PATH.stat().st_size / 1024:.1f} KB")
        except Exception as exc:
            print(f"⚠ ONNX export failed ({type(exc).__name__}): {exc}")
    else:
        print("⚠ Could not find XGBClassifier step in pipeline")

    # ── Export isotonic calibration lookup table ──────────────────────
    iso_table: list[dict] | None = None
    if isotonic is not None:
        try:
            X_cal = isotonic.X_thresholds_
            Y_cal = isotonic.y_thresholds_
            iso_table = [
                {"raw": float(x), "cal": float(y)}
                for x, y in zip(X_cal, Y_cal)
            ]
        except AttributeError:
            # Fallback: sample the isotonic regressor over [0, 1]
            xs = np.linspace(0.0, 1.0, 101)
            ys = isotonic.predict(xs)
            iso_table = [
                {"raw": float(x), "cal": float(y)}
                for x, y in zip(xs, ys)
            ]

    # ── Write metadata JSON ───────────────────────────────────────────
    metadata = {
        "experiment_name": experiment_name,
        "feature_cols": feature_cols,
        "threshold": threshold,
        "isotonic_table": iso_table,
        "imputer_fill_values": imputer_fill_values if imputer_fill_values else None,
    }

    with open(METADATA_PATH, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)
    print(f"✓ Metadata written to: {METADATA_PATH}")


if __name__ == "__main__":
    main()
