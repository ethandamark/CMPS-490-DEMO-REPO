from __future__ import annotations

import importlib
import sys
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, Optional

import requests

from config import (
    ML_PREDICTOR_API_URL,
    ML_PREDICTOR_MODE,
    ML_PREDICTOR_REQUEST_TIMEOUT_SECONDS,
    ML_PREDICTOR_ROOT,
)


class PredictorUnavailableError(RuntimeError):
    """Raised when the backend cannot reach or load the ML predictor."""


class PredictorClient:
    def __init__(
        self,
        mode: str,
        api_url: str,
        predictor_root: Optional[str],
        request_timeout_seconds: int,
    ) -> None:
        self.mode = mode.lower().strip()
        self.api_url = api_url.rstrip("/")
        self.predictor_root = predictor_root.strip() if predictor_root else ""
        self.request_timeout_seconds = int(request_timeout_seconds)

    def _resolve_mode(self) -> str:
        if self.mode != "auto":
            return self.mode
        return "local" if self._find_predictor_root() is not None else "http"

    def _find_predictor_root(self) -> Optional[Path]:
        candidates = []
        if self.predictor_root:
            candidates.append(Path(self.predictor_root))

        here = Path(__file__).resolve()
        candidates.extend(
            [
                here.parents[1],  # lib/ folder (where inference.py lives)
                here.parent,
            ]
        )

        for root in candidates:
            try:
                inference_path = root / "inference.py"
            except Exception:
                continue
            if inference_path.exists():
                return root
        return None

    @lru_cache(maxsize=1)
    def _load_local_module(self):
        root = self._find_predictor_root()
        if root is None:
            raise PredictorUnavailableError(
                "Local ML predictor was requested, but inference.py could not be found. "
                "Set ML_PREDICTOR_ROOT or use HTTP mode."
            )

        root_str = str(root)
        if root_str not in sys.path:
            sys.path.insert(0, root_str)

        try:
            return importlib.import_module("inference")
        except Exception as exc:
            raise PredictorUnavailableError(f"Failed to import local ML predictor: {exc}") from exc

    @lru_cache(maxsize=1)
    def _load_local_bundle(self) -> Dict[str, Any]:
        module = self._load_local_module()
        model_path = getattr(module, "discover_latest_model", lambda: None)()
        if model_path is None:
            raise PredictorUnavailableError(
                "No model bundle found. Place a *_model.joblib under artifacts/severe_pipeline "
                "or set ML_PREDICTOR_ROOT to the directory containing it."
            )
        return module.load_model_bundle(model_path)

    def health(self) -> Dict[str, Any]:
        mode = self._resolve_mode()
        if mode == "local":
            bundle = self._load_local_bundle()
            return {
                "status": "ok",
                "mode": "local",
                "model_version": bundle.get("experiment_name", "unknown"),
                "threshold": bundle.get("threshold"),
                "feature_count": len(bundle.get("feature_cols", [])),
            }

        try:
            response = requests.get(
                f"{self.api_url}/health",
                timeout=self.request_timeout_seconds,
            )
            response.raise_for_status()
            data = response.json()
            data["mode"] = "http"
            return data
        except requests.RequestException as exc:
            raise PredictorUnavailableError(f"HTTP predictor health check failed: {exc}") from exc

    def metadata(self) -> Dict[str, Any]:
        mode = self._resolve_mode()
        if mode == "local":
            bundle = self._load_local_bundle()
            return {
                "mode": "local",
                "model_version": bundle.get("experiment_name", "unknown"),
                "feature_columns": list(bundle.get("feature_cols", [])),
                "threshold": bundle.get("threshold"),
                "test_start_utc": bundle.get("test_start_utc"),
            }

        try:
            response = requests.get(
                f"{self.api_url}/metadata",
                timeout=self.request_timeout_seconds,
            )
            response.raise_for_status()
            data = response.json()
            data["mode"] = "http"
            return data
        except requests.RequestException as exc:
            raise PredictorUnavailableError(f"HTTP predictor metadata request failed: {exc}") from exc

    def predict(
        self,
        weather_data: Dict[str, Any],
        alert_context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        mode = self._resolve_mode()
        if mode == "local":
            module = self._load_local_module()
            bundle = self._load_local_bundle()
            threshold_override = alert_context.get("threshold") if alert_context else None
            return module.predict(weather_data, bundle, threshold_override=threshold_override)

        payload = {"weather_data": weather_data}
        if alert_context is not None:
            payload["alert_context"] = alert_context

        try:
            response = requests.post(
                f"{self.api_url}/predict",
                json=payload,
                timeout=self.request_timeout_seconds,
            )
            response.raise_for_status()
            return response.json()
        except requests.RequestException as exc:
            raise PredictorUnavailableError(f"HTTP predictor prediction request failed: {exc}") from exc


@lru_cache(maxsize=1)
def get_predictor_client() -> PredictorClient:
    return PredictorClient(
        mode=ML_PREDICTOR_MODE,
        api_url=ML_PREDICTOR_API_URL,
        predictor_root=ML_PREDICTOR_ROOT,
        request_timeout_seconds=ML_PREDICTOR_REQUEST_TIMEOUT_SECONDS,
    )
