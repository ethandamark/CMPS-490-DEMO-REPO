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

MODEL_PATH = _REPO_ROOT / "lib" / "models" / "baseline_surface_model_3mo_6st.joblib"
OUT_DIR = _REPO_ROOT / "frontend" / "app" / "src" / "main" / "assets" / "ml"
ONNX_PATH = OUT_DIR / "model.onnx"
METADATA_PATH = OUT_DIR / "model_metadata.json"


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

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

    # ── Convert sklearn pipeline → ONNX ──────────────────────────────
    try:
        from skl2onnx import convert_sklearn
        from skl2onnx.common.data_types import FloatTensorType

        n_features = len(feature_cols)
        initial_type = [("float_input", FloatTensorType([None, n_features]))]
        onnx_model = convert_sklearn(base_model, initial_types=initial_type, target_opset=17)

        with open(ONNX_PATH, "wb") as f:
            f.write(onnx_model.SerializeToString())
        print(f"✓ ONNX model written to: {ONNX_PATH}")
    except ImportError:
        print("⚠ skl2onnx not installed — skipping ONNX export (model.onnx not written)")
        print("  Install with: pip install skl2onnx onnx")

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
    }

    with open(METADATA_PATH, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)
    print(f"✓ Metadata written to: {METADATA_PATH}")


if __name__ == "__main__":
    main()
