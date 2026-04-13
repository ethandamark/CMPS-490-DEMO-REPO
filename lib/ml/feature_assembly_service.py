from __future__ import annotations

import datetime
import logging
import math
from typing import Any, Dict, Iterable, List

import requests as _http

from config import AREA_CELL_PRECISION, SUPABASE_BASE_URL, SUPABASE_API_KEY


# ── Supabase REST helpers ───────────────────────────────────────────

_SB_HEADERS: dict[str, str] = {
    "apikey": SUPABASE_API_KEY,
    "Content-Type": "application/json",
}
_SB_TIMEOUT = 15


logger = logging.getLogger(__name__)


class FeatureAssemblyError(ValueError):
    """Raised when the backend cannot build a valid model feature payload."""


# ── Geohash implementation ──────────────────────────────────────────
# Lightweight base-32 geohash so we don't need a third-party package.

_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def geohash_encode(latitude: float, longitude: float, precision: int | None = None) -> str:
    """Return a geohash string for *latitude* / *longitude*.

    ``precision`` defaults to ``AREA_CELL_PRECISION`` from config.
    """
    if precision is None:
        precision = AREA_CELL_PRECISION

    lat_range = (-90.0, 90.0)
    lon_range = (-180.0, 180.0)
    bits = [16, 8, 4, 2, 1]
    hash_chars: list[str] = []
    is_lon = True
    bit_idx = 0
    char_val = 0

    while len(hash_chars) < precision:
        if is_lon:
            mid = (lon_range[0] + lon_range[1]) / 2.0
            if longitude >= mid:
                char_val |= bits[bit_idx]
                lon_range = (mid, lon_range[1])
            else:
                lon_range = (lon_range[0], mid)
        else:
            mid = (lat_range[0] + lat_range[1]) / 2.0
            if latitude >= mid:
                char_val |= bits[bit_idx]
                lat_range = (mid, lat_range[1])
            else:
                lat_range = (lat_range[0], mid)
        is_lon = not is_lon
        bit_idx += 1
        if bit_idx == 5:
            hash_chars.append(_BASE32[char_val])
            bit_idx = 0
            char_val = 0

    return "".join(hash_chars)


def geohash_decode(ghash: str) -> tuple[float, float]:
    """Return the (latitude, longitude) centroid of a geohash cell."""
    lat_range = [-90.0, 90.0]
    lon_range = [-180.0, 180.0]
    is_lon = True

    for ch in ghash:
        val = _BASE32.index(ch)
        for bit in [16, 8, 4, 2, 1]:
            if is_lon:
                mid = (lon_range[0] + lon_range[1]) / 2.0
                if val & bit:
                    lon_range[0] = mid
                else:
                    lon_range[1] = mid
            else:
                mid = (lat_range[0] + lat_range[1]) / 2.0
                if val & bit:
                    lat_range[0] = mid
                else:
                    lat_range[1] = mid
            is_lon = not is_lon

    return (lat_range[0] + lat_range[1]) / 2.0, (lon_range[0] + lon_range[1]) / 2.0


# ── Helpers ─────────────────────────────────────────────────────────

def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    lat1_rad = math.radians(lat1)
    lon1_rad = math.radians(lon1)
    lat2_rad = math.radians(lat2)
    lon2_rad = math.radians(lon2)
    dlat = lat2_rad - lat1_rad
    dlon = lon2_rad - lon1_rad
    a = math.sin(dlat / 2.0) ** 2 + math.cos(lat1_rad) * math.cos(lat2_rad) * math.sin(dlon / 2.0) ** 2
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return r * c


def _distance_to_gulf_coast(lat: float, lon: float) -> float:
    coast_points = [
        (29.25, -89.4),
        (29.5, -90.2),
        (29.6, -91.3),
        (29.7, -92.0),
        (29.8, -93.3),
    ]
    return min(_haversine_km(lat, lon, clat, clon) for clat, clon in coast_points)


def _parse_valid_time(value: str) -> datetime.datetime:
    try:
        return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise FeatureAssemblyError("valid_time_utc must be an ISO 8601 timestamp.") from exc


def _coerce_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _last_n(values: Iterable[float | None], n: int, fill_value: float | None = None) -> List[float | None]:
    items = list(values)
    window = items[-n:]
    if fill_value is None:
        return window
    return [fill_value if value is None else value for value in window]


def _nth_previous(values: List[float | None], n: int) -> float | None:
    if len(values) <= n:
        return None
    return values[-(n + 1)]


def _safe_diff(current: float | None, previous: float | None) -> float | None:
    if current is None or previous is None:
        return None
    return current - previous


def _safe_max(values: Iterable[float | None]) -> float | None:
    filtered = [value for value in values if value is not None]
    return max(filtered) if filtered else None


def _to_record(snapshot: Dict[str, Any]) -> Dict[str, Any]:
    """Convert a Supabase REST row dict into the internal record format."""
    dew_point = _coerce_float(snapshot.get("dew_point_c"))
    temp_c = _coerce_float(snapshot.get("temp_c"))
    if dew_point is None and temp_c is not None:
        dew_point = temp_c - 5.0

    return {
        "timestamp": snapshot.get("timestamp"),
        "temp_c": temp_c,
        "dew_point_c": dew_point,
        "pressure_hPa": _coerce_float(snapshot.get("pressure_hpa")),
        "humidity_pct": _coerce_float(snapshot.get("humidity_pct")),
        "wind_speed_kmh": _coerce_float(snapshot.get("wind_speed_kmh")),
        "precip_mm": _coerce_float(snapshot.get("precip_mm")),
        "latitude": _coerce_float(snapshot.get("representative_lat")),
        "longitude": _coerce_float(snapshot.get("representative_lon")),
        "elevation": _coerce_float(snapshot.get("elevation")),
        "nwp_cape_f3_6_max": _coerce_float(snapshot.get("nwp_cape_f3_6_max")),
        "nwp_cin_f3_6_max": _coerce_float(snapshot.get("nwp_cin_f3_6_max")),
        "nwp_pwat_f3_6_max": _coerce_float(snapshot.get("nwp_pwat_f3_6_max")),
        "nwp_srh03_f3_6_max": _coerce_float(snapshot.get("nwp_srh03_f3_6_max")),
        "nwp_li_f3_6_min": _coerce_float(snapshot.get("nwp_li_f3_6_min")),
        "nwp_lcl_f3_6_min": _coerce_float(snapshot.get("nwp_lcl_f3_6_min")),
        "nwp_available_leads": _coerce_float(snapshot.get("nwp_available_leads")),
        "mrms_max_dbz_75km": _coerce_float(snapshot.get("mrms_max_dbz_75km")),
    }


# ── Public API ──────────────────────────────────────────────────────

def assemble_live_features(
    area_key: str,
    representative_lat: float,
    representative_lon: float,
    request_data: Dict[str, Any],
) -> Dict[str, Any]:
    """Build the full ML feature vector for an area cell.

    Parameters
    ----------
    area_key : str
        Geohash key for the area.
    representative_lat, representative_lon : float
        Cell centroid coordinates (used for static feature calculations).
    request_data : dict
        Output of ``fetch_live_prediction_input()`` (current observation,
        static features, NWP, radar, etc.).

    Returns
    -------
    dict
        Feature vector ready for the ML predictor.
    """
    valid_time = _parse_valid_time(request_data["valid_time_utc"])
    obs = request_data.get("current_observation") or {}
    static = request_data.get("static_features") or {}
    nwp = request_data.get("nwp_features") or {}
    radar = request_data.get("radar_features") or {}

    latitude = representative_lat
    longitude = representative_lon

    temp_c = _coerce_float(obs.get("temp_c"))
    dew_point_c = _coerce_float(obs.get("dew_point_c"))
    temp_dewpoint_spread = _coerce_float(obs.get("temp_dewpoint_spread"))
    if dew_point_c is None and temp_c is not None and temp_dewpoint_spread is not None:
        dew_point_c = temp_c - temp_dewpoint_spread
    if dew_point_c is None and temp_c is not None:
        dew_point_c = temp_c - 5.0

    dist_to_coast_km = _coerce_float(static.get("dist_to_coast_km"))
    if dist_to_coast_km is None:
        dist_to_coast_km = _distance_to_gulf_coast(latitude, longitude)

    sb_url = f"{SUPABASE_BASE_URL}/rest/v1/area_weather_snapshot"

    # ── Prune snapshots older than 48 hours ─────────────────────────
    cutoff = valid_time - datetime.timedelta(hours=48)
    _http.delete(
        sb_url,
        params={"area_key": f"eq.{area_key}", "timestamp": f"lt.{cutoff.isoformat()}"},
        headers=_SB_HEADERS,
        timeout=_SB_TIMEOUT,
    )

    # ── Upsert current hour's snapshot ──────────────────────────────
    snapshot_data = {
        "area_key": area_key,
        "timestamp": valid_time.isoformat(),
        "representative_lat": latitude,
        "representative_lon": longitude,
        "temp_c": temp_c,
        "dew_point_c": dew_point_c,
        "pressure_hpa": _coerce_float(obs.get("pressure_hPa")),
        "humidity_pct": _coerce_float(obs.get("humidity_pct")),
        "wind_speed_kmh": _coerce_float(obs.get("wind_speed_kmh")),
        "precip_mm": _coerce_float(obs.get("precip_mm")),
        "elevation": _coerce_float(static.get("elevation")),
        "nwp_cape_f3_6_max": _coerce_float(nwp.get("nwp_cape_f3_6_max")),
        "nwp_cin_f3_6_max": _coerce_float(nwp.get("nwp_cin_f3_6_max")),
        "nwp_pwat_f3_6_max": _coerce_float(nwp.get("nwp_pwat_f3_6_max")),
        "nwp_srh03_f3_6_max": _coerce_float(nwp.get("nwp_srh03_f3_6_max")),
        "nwp_li_f3_6_min": _coerce_float(nwp.get("nwp_li_f3_6_min")),
        "nwp_lcl_f3_6_min": _coerce_float(nwp.get("nwp_lcl_f3_6_min")),
        "nwp_available_leads": _coerce_float(nwp.get("nwp_available_leads")),
        "mrms_max_dbz_75km": _coerce_float(radar.get("mrms_max_dbz_75km")),
    }
    _http.post(
        sb_url,
        params={"on_conflict": "area_key,timestamp"},
        json=snapshot_data,
        headers={**_SB_HEADERS, "Prefer": "resolution=merge-duplicates"},
        timeout=_SB_TIMEOUT,
    )

    # ── Build history window (last 24 hours) ────────────────────────
    resp = _http.get(
        sb_url,
        params={
            "area_key": f"eq.{area_key}",
            "timestamp": f"lte.{valid_time.isoformat()}",
            "order": "timestamp.desc",
            "limit": "24",
        },
        headers=_SB_HEADERS,
        timeout=_SB_TIMEOUT,
    )
    if resp.status_code != 200:
        raise FeatureAssemblyError(f"Failed to query snapshot history: {resp.text}")
    history = [_to_record(row) for row in reversed(resp.json())]
    current = history[-1]

    temp_spreads: List[float | None] = []
    pressure_values: List[float | None] = []
    precip_values: List[float | None] = []
    wind_values: List[float | None] = []

    for row in history:
        row_temp = row["temp_c"]
        row_dew = row["dew_point_c"]
        spread = None if row_temp is None or row_dew is None else row_temp - row_dew
        temp_spreads.append(spread)
        pressure_values.append(row["pressure_hPa"])
        precip_values.append(row["precip_mm"])
        wind_values.append(row["wind_speed_kmh"])

    pressure_change_1h = _safe_diff(pressure_values[-1], _nth_previous(pressure_values, 1))
    weather_data = {
        "valid_time_utc": valid_time.astimezone(datetime.timezone.utc).isoformat(),
        "temp_c": current["temp_c"],
        "pressure_hPa": current["pressure_hPa"],
        "humidity_pct": current["humidity_pct"],
        "wind_speed_kmh": current["wind_speed_kmh"],
        "precip_mm": current["precip_mm"] if current["precip_mm"] is not None else 0.0,
        "precip_6h": sum(_last_n(precip_values, 6, fill_value=0.0)),
        "precip_24h": sum(_last_n(precip_values, 24, fill_value=0.0)),
        "precip_rate_change": _safe_diff(precip_values[-1], _nth_previous(precip_values, 1)),
        "precip_max_3h": max(_last_n(precip_values, 3, fill_value=0.0)),
        "pressure_change_1h": pressure_change_1h,
        "pressure_change_3h": _safe_diff(pressure_values[-1], _nth_previous(pressure_values, 3)),
        "pressure_change_6h": _safe_diff(pressure_values[-1], _nth_previous(pressure_values, 6)),
        "pressure_change_12h": _safe_diff(pressure_values[-1], _nth_previous(pressure_values, 12)),
        "pressure_drop_rate": None if pressure_change_1h is None else -pressure_change_1h,
        "temp_dewpoint_spread": temp_spreads[-1],
        "dewpoint_spread_change": _safe_diff(temp_spreads[-1], _nth_previous(temp_spreads, 3)),
        "wind_speed_change_1h": _safe_diff(wind_values[-1], _nth_previous(wind_values, 1)),
        "wind_speed_change_3h": _safe_diff(wind_values[-1], _nth_previous(wind_values, 3)),
        "wind_max_3h": _safe_max(_last_n(wind_values, 3)),
        "hour": valid_time.astimezone(datetime.timezone.utc).hour,
        "month": valid_time.astimezone(datetime.timezone.utc).month,
        "is_afternoon": int(14 <= valid_time.astimezone(datetime.timezone.utc).hour <= 19),
        "latitude": latitude,
        "longitude": longitude,
        "elevation": current["elevation"],
        "dist_to_coast_km": dist_to_coast_km,
        "nwp_cape_f3_6_max": current["nwp_cape_f3_6_max"],
        "nwp_cin_f3_6_max": current["nwp_cin_f3_6_max"],
        "nwp_pwat_f3_6_max": current["nwp_pwat_f3_6_max"],
        "nwp_srh03_f3_6_max": current["nwp_srh03_f3_6_max"],
        "nwp_li_f3_6_min": current["nwp_li_f3_6_min"],
        "nwp_lcl_f3_6_min": current["nwp_lcl_f3_6_min"],
        "nwp_available_leads": current["nwp_available_leads"],
        "mrms_max_dbz_75km": current["mrms_max_dbz_75km"],
    }
    return weather_data
