from __future__ import annotations

import datetime
from typing import Any, Dict, List

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


def fetch_historical_hourly(
    latitude: float,
    longitude: float,
    hours: int = 24,
) -> List[Dict[str, Any]]:
    """Fetch the last *hours* of hourly weather observations from Open-Meteo.

    Returns a list of dicts (oldest → newest), each containing:
        time_utc, temp_c, dew_point_c, pressure_hPa, humidity_pct,
        wind_speed_kmh, precip_mm, elevation.
    Units already match the model's expectations.
    """
    now_utc = datetime.datetime.now(datetime.timezone.utc)
    start_utc = now_utc - datetime.timedelta(hours=hours)

    params = {
        "latitude": latitude,
        "longitude": longitude,
        "timezone": "UTC",
        "wind_speed_unit": "kmh",
        "start_date": start_utc.strftime("%Y-%m-%d"),
        "end_date": now_utc.strftime("%Y-%m-%d"),
        "hourly": ",".join([
            "temperature_2m",
            "relative_humidity_2m",
            "dew_point_2m",
            "surface_pressure",
            "wind_speed_10m",
            "precipitation",
        ]),
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
        raise LiveWeatherError(
            f"Failed to fetch historical weather: {exc}"
        ) from exc

    hourly = data.get("hourly") or {}
    times = hourly.get("time") or []
    if not times:
        raise LiveWeatherError("Historical weather response contained no hourly data.")

    elevation = data.get("elevation")
    result: List[Dict[str, Any]] = []

    for i, iso_time in enumerate(times):
        t = datetime.datetime.fromisoformat(iso_time).replace(
            tzinfo=datetime.timezone.utc,
        )
        if t < start_utc or t > now_utc:
            continue
        result.append({
            "time_utc": t,
            "temp_c": hourly.get("temperature_2m", [None])[i],
            "dew_point_c": hourly.get("dew_point_2m", [None])[i],
            "pressure_hPa": hourly.get("surface_pressure", [None])[i],
            "humidity_pct": hourly.get("relative_humidity_2m", [None])[i],
            "wind_speed_kmh": hourly.get("wind_speed_10m", [None])[i],
            "precip_mm": hourly.get("precipitation", [None])[i],
            "elevation": elevation,
        })

    # Keep only the most recent *hours* entries
    return result[-hours:]
