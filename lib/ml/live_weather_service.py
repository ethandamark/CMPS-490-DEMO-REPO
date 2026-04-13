from __future__ import annotations

from typing import Any, Dict

import requests

from config import LIVE_WEATHER_PROVIDER, LIVE_WEATHER_TIMEOUT_SECONDS, OPEN_METEO_BASE_URL


class LiveWeatherError(RuntimeError):
    """Raised when current live conditions cannot be fetched or parsed."""


def fetch_live_prediction_input(latitude: float, longitude: float) -> Dict[str, Any]:
    provider = LIVE_WEATHER_PROVIDER.lower().strip()
    if provider != "open_meteo":
        raise LiveWeatherError(f"Unsupported live weather provider: {provider}")
    return _fetch_from_open_meteo(latitude=latitude, longitude=longitude)


def _fetch_from_open_meteo(latitude: float, longitude: float) -> Dict[str, Any]:
    params = {
        "latitude": latitude,
        "longitude": longitude,
        "timezone": "UTC",
        "wind_speed_unit": "kmh",
        "current": ",".join(
            [
                "temperature_2m",
                "relative_humidity_2m",
                "dew_point_2m",
                "precipitation",
                "pressure_msl",
                "wind_speed_10m",
            ]
        ),
    }
    try:
        response = requests.get(
            OPEN_METEO_BASE_URL,
            params=params,
            timeout=LIVE_WEATHER_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        data = response.json()
    except requests.RequestException as exc:
        raise LiveWeatherError(f"Failed to fetch live weather conditions: {exc}") from exc

    current = data.get("current") or {}
    current_units = data.get("current_units") or {}
    current_time = current.get("time")
    if not current_time:
        raise LiveWeatherError("Live weather provider response did not include current.time.")

    valid_time_utc = current_time if current_time.endswith("Z") else f"{current_time}Z"
    return {
        "valid_time_utc": valid_time_utc,
        "latitude": latitude,
        "longitude": longitude,
        "current_observation": {
            "temp_c": current.get("temperature_2m"),
            "dew_point_c": current.get("dew_point_2m"),
            "pressure_hPa": current.get("pressure_msl"),
            "humidity_pct": current.get("relative_humidity_2m"),
            "wind_speed_kmh": current.get("wind_speed_10m"),
            "precip_mm": current.get("precipitation"),
        },
        "static_features": {
            "elevation": data.get("elevation"),
        },
        "nwp_features": {},
        "radar_features": {},
        "provider_metadata": {
            "provider": "open_meteo",
            "current_units": current_units,
        },
    }
