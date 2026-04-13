"""Unit tests for area-based prediction architecture."""
from __future__ import annotations

import datetime
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# Ensure backend/ and project root are importable
_BACKEND_DIR = Path(__file__).resolve().parent.parent
_PROJECT_ROOT = _BACKEND_DIR.parent
for _p in (_BACKEND_DIR, _PROJECT_ROOT):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))


# ── Geohash tests ──────────────────────────────────────────────────

from lib.ml.feature_assembly_service import geohash_encode, geohash_decode


class TestGeohash:
    """Geohash generation determinism and precision sensitivity."""

    def test_deterministic(self):
        """Same input always produces same hash."""
        h1 = geohash_encode(30.2241, -92.0198, precision=5)
        h2 = geohash_encode(30.2241, -92.0198, precision=5)
        assert h1 == h2

    def test_same_location_same_key(self):
        """Two users at the same location produce the same area_key."""
        h1 = geohash_encode(30.2241, -92.0198, precision=5)
        h2 = geohash_encode(30.2241, -92.0198, precision=5)
        assert h1 == h2

    def test_nearby_same_cell(self):
        """Two points very close together should land in the same cell."""
        h1 = geohash_encode(30.2241, -92.0198, precision=5)
        h2 = geohash_encode(30.2242, -92.0197, precision=5)
        assert h1 == h2

    def test_different_cells(self):
        """Two users in clearly different locations produce different keys."""
        h1 = geohash_encode(30.2241, -92.0198, precision=5)
        h2 = geohash_encode(40.7128, -74.0060, precision=5)
        assert h1 != h2

    def test_precision_affects_key_length(self):
        """Precision parameter controls geohash string length."""
        h3 = geohash_encode(30.2241, -92.0198, precision=3)
        h5 = geohash_encode(30.2241, -92.0198, precision=5)
        h7 = geohash_encode(30.2241, -92.0198, precision=7)
        assert len(h3) == 3
        assert len(h5) == 5
        assert len(h7) == 7
        assert h5.startswith(h3)

    def test_decode_roundtrip(self):
        """Decoding a geohash returns a point within the original cell."""
        lat, lon = 30.2241, -92.0198
        ghash = geohash_encode(lat, lon, precision=5)
        dec_lat, dec_lon = geohash_decode(ghash)
        # Centroid should be close to original (within cell size ~5 km)
        assert abs(dec_lat - lat) < 0.1
        assert abs(dec_lon - lon) < 0.1

    def test_re_encode_decoded_gives_same_hash(self):
        """Encoding the decoded centroid gives the same hash."""
        ghash = geohash_encode(30.2241, -92.0198, precision=5)
        dec_lat, dec_lon = geohash_decode(ghash)
        re_hash = geohash_encode(dec_lat, dec_lon, precision=5)
        assert re_hash == ghash


# ── Feature assembly with area snapshots (REST-mocked) ──────────────

def _make_snapshot_row(
    area_key="9vuf1",
    timestamp="2026-04-13T12:00:00+00:00",
    temp_c=25.0,
    dew_point_c=18.0,
    pressure_hpa=1013.0,
    humidity_pct=65.0,
    wind_speed_kmh=15.0,
    precip_mm=0.0,
    elevation=10.0,
    representative_lat=30.22,
    representative_lon=-92.02,
    **extras,
) -> dict:
    """Build a dict shaped like a PostgREST area_weather_snapshot row."""
    row = {
        "id": extras.pop("id", 1),
        "area_key": area_key,
        "timestamp": timestamp,
        "representative_lat": representative_lat,
        "representative_lon": representative_lon,
        "temp_c": temp_c,
        "dew_point_c": dew_point_c,
        "pressure_hpa": pressure_hpa,
        "humidity_pct": humidity_pct,
        "wind_speed_kmh": wind_speed_kmh,
        "precip_mm": precip_mm,
        "elevation": elevation,
        "nwp_cape_f3_6_max": None,
        "nwp_cin_f3_6_max": None,
        "nwp_pwat_f3_6_max": None,
        "nwp_srh03_f3_6_max": None,
        "nwp_li_f3_6_min": None,
        "nwp_lcl_f3_6_min": None,
        "nwp_available_leads": None,
        "mrms_max_dbz_75km": None,
    }
    row.update(extras)
    return row


def _mock_response(status_code=200, json_data=None):
    resp = MagicMock()
    resp.status_code = status_code
    resp.json.return_value = json_data if json_data is not None else []
    resp.text = ""
    return resp


class TestFeatureAssemblyArea:
    """Feature assembly works with area snapshots (mocked REST)."""

    @patch("lib.ml.feature_assembly_service._http")
    def test_single_snapshot_produces_features(self, mock_http):
        """A single stored snapshot should yield a valid feature dict."""
        from lib.ml.feature_assembly_service import assemble_live_features

        # DELETE (prune) → success, POST (upsert) → success
        mock_http.delete.return_value = _mock_response(200)
        mock_http.post.return_value = _mock_response(201)

        # GET (history) returns the snapshot that was just upserted
        history_row = _make_snapshot_row()
        mock_http.get.return_value = _mock_response(200, [history_row])

        request_data = {
            "valid_time_utc": "2026-04-13T12:00:00Z",
            "current_observation": {
                "temp_c": 25.0,
                "dew_point_c": 18.0,
                "pressure_hPa": 1013.0,
                "humidity_pct": 65.0,
                "wind_speed_kmh": 15.0,
                "precip_mm": 0.0,
            },
            "static_features": {"elevation": 10.0},
            "nwp_features": {},
            "radar_features": {},
        }

        result = assemble_live_features(
            area_key="9vuf1",
            representative_lat=30.22,
            representative_lon=-92.02,
            request_data=request_data,
        )

        assert result["temp_c"] == 25.0
        assert result["pressure_hPa"] == 1013.0
        assert result["latitude"] == 30.22
        assert result["longitude"] == -92.02
        assert "precip_24h" in result
        assert "pressure_change_6h" in result

    @patch("lib.ml.feature_assembly_service._http")
    def test_multiple_snapshots_compute_lag_features(self, mock_http):
        """With 6 historical rows + current, lag features should be non-None."""
        from lib.ml.feature_assembly_service import assemble_live_features

        base_time = datetime.datetime(2026, 4, 13, 6, 0, 0, tzinfo=datetime.timezone.utc)

        # Build 7 history rows (6 historical + 1 current) ordered desc
        history_rows = []
        for i in range(6, -1, -1):
            ts = base_time + datetime.timedelta(hours=i)
            history_rows.append(
                _make_snapshot_row(
                    id=i + 1,
                    timestamp=ts.isoformat(),
                    temp_c=20.0 + i,
                    pressure_hpa=1013.0 - i * 0.5,
                    wind_speed_kmh=10.0 + i,
                    precip_mm=0.0 if i < 4 else 1.0,
                )
            )

        mock_http.delete.return_value = _mock_response(200)
        mock_http.post.return_value = _mock_response(201)
        mock_http.get.return_value = _mock_response(200, history_rows)

        request_data = {
            "valid_time_utc": (base_time + datetime.timedelta(hours=6)).isoformat(),
            "current_observation": {
                "temp_c": 26.0,
                "dew_point_c": 18.0,
                "pressure_hPa": 1010.0,
                "humidity_pct": 70.0,
                "wind_speed_kmh": 16.0,
                "precip_mm": 2.0,
            },
            "static_features": {"elevation": 10.0},
            "nwp_features": {},
            "radar_features": {},
        }

        result = assemble_live_features(
            area_key="9vuf1",
            representative_lat=30.22,
            representative_lon=-92.02,
            request_data=request_data,
        )

        assert result["pressure_change_6h"] is not None
        assert result["precip_6h"] > 0
        assert result["wind_speed_change_3h"] is not None
