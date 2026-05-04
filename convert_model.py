"""
Convert surface_plus_nwp_dbz_production_model_alert_tuned_balanced.joblib
-> model.onnx  +  model_metadata.json

Run from the repo root:
    python convert_model.py

Outputs:
    frontend/app/src/main/assets/ml/model.onnx
    frontend/app/src/main/assets/ml/model_metadata.json
"""

import json, pathlib, sys
import joblib, numpy as np

REPO_ROOT = pathlib.Path(__file__).resolve().parent
MODEL_PATH = REPO_ROOT / "surface_plus_nwp_dbz_production_model_alert_tuned_balanced.joblib"
ASSETS_DIR = REPO_ROOT / "frontend" / "app" / "src" / "main" / "assets" / "ml"
ONNX_OUT   = ASSETS_DIR / "model.onnx"
META_OUT   = ASSETS_DIR / "model_metadata.json"

MAX_DBZ_NORMALISE = 75.0   # practical upper bound; used to scale dBZ -> [0,1]

# ---- Load artifact ---------------------------------------------------------
print(f"Loading {MODEL_PATH.name} ...")
artifact = joblib.load(MODEL_PATH)
assert isinstance(artifact, dict), f"Expected dict, got {type(artifact)}"

pipeline            = artifact["model"]
feature_names       = list(artifact["feature_cols"])
decision_threshold  = float(artifact["decision_threshold"])
n_features          = len(feature_names)

print(f"  steps     : {[s[0] for s in pipeline.steps]}")
print(f"  features  : {n_features}")
print(f"  threshold : {decision_threshold:.6f} dBZ")

# ---- Imputer medians -------------------------------------------------------
imputer = pipeline.named_steps["imputer"]
fills   = {f: float(v) if np.isfinite(v) else 0.0
           for f, v in zip(feature_names, imputer.statistics_)}
print(f"  fills     : {len(fills)} values")

# ---- ONNX conversion via onnxmltools (proper XGBoost support) --------------
from skl2onnx         import convert_sklearn, update_registered_converter
from skl2onnx.common.data_types import FloatTensorType
from skl2onnx.common.shape_calculator import (
    calculate_linear_regressor_output_shapes,
)
from onnxmltools.convert.xgboost.operator_converters.XGBoost import convert_xgboost
from xgboost          import XGBRegressor

def xgb_regressor_shape_calc(operator):
    N = operator.inputs[0].type.shape[0]
    operator.outputs[0].type = FloatTensorType([N, 1])

update_registered_converter(
    XGBRegressor,
    "XGBoostXGBRegressor",
    xgb_regressor_shape_calc,
    convert_xgboost,
)

regressor = pipeline.named_steps["regressor"]
print("\nConverting XGBRegressor to ONNX ...")
initial_type = [("float_input", FloatTensorType([None, n_features]))]
onnx_model = convert_sklearn(
    regressor,
    initial_types=initial_type,
    target_opset={'': 12, 'ai.onnx.ml': 3},
)

ASSETS_DIR.mkdir(parents=True, exist_ok=True)
with open(ONNX_OUT, "wb") as f:
    f.write(onnx_model.SerializeToString())
print(f"  Written -> {ONNX_OUT}  ({ONNX_OUT.stat().st_size:,} bytes)")

# ---- Validate with onnxruntime ---------------------------------------------
try:
    import onnxruntime as ort
    sess    = ort.InferenceSession(str(ONNX_OUT))
    dummy   = np.zeros((1, n_features), dtype=np.float32)
    iname   = sess.get_inputs()[0].name
    outputs = sess.run(None, {iname: dummy})
    print(f"  outputs   : {[o.name for o in sess.get_outputs()]}")
    print(f"  dummy out : {outputs}")
    print("  Validation OK")
except Exception as e:
    print(f"  WARNING: validation failed: {e}")

# ---- Write metadata --------------------------------------------------------
# threshold is normalised: (stormProbability=dBZ/75) >= (threshold=dbz_thresh/75)
#                       <=> dBZ >= decision_threshold
normalised_threshold = decision_threshold / MAX_DBZ_NORMALISE

metadata = {
    "experiment_name":    "v2.0.0",
    "model_type":         "regressor",
    "feature_cols":       feature_names,
    "threshold":          normalised_threshold,
    "isotonic_table":     None,
    "imputer_fill_values": fills,
}
with open(META_OUT, "w") as f:
    json.dump(metadata, f, indent=2)
print(f"  Written -> {META_OUT}")

print(f"""
=== Conversion complete ===
  model_type  : regressor
  features    : {n_features}
  dBZ thresh  : {decision_threshold:.4f} dBZ  (normalised {normalised_threshold:.6f})
  ONNX        : {ONNX_OUT.name}
  Metadata    : {META_OUT.name}
""")
