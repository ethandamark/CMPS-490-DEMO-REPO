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


# ── Feature assembly with area snapshots ────────────────────────────

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from database import Base, AreaWeatherSnapshot, AreaAlertState


@pytest.fixture
def db_session():
    """In-memory SQLite session for testing."""
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    yield session
    session.close()


class TestFeatureAssemblyArea:
    """Feature assembly works with area snapshots."""

    def test_single_snapshot_produces_features(self, db_session):
        """A single stored snapshot should yield a valid feature dict."""
        from lib.ml.feature_assembly_service import assemble_live_features

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
            db=db_session,
            area_key="9vuf1",
            representative_lat=30.22,
            representative_lon=-92.02,
            request_data=request_data,
        )
        db_session.commit()

        assert result["temp_c"] == 25.0
        assert result["pressure_hPa"] == 1013.0
        assert result["latitude"] == 30.22
        assert result["longitude"] == -92.02
        assert "precip_24h" in result
        assert "pressure_change_6h" in result

    def test_multiple_snapshots_compute_lag_features(self, db_session):
        """With 6 historical rows + current, lag features should be non-None."""
        from lib.ml.feature_assembly_service import assemble_live_features

        base_time = datetime.datetime(2026, 4, 13, 6, 0, 0, tzinfo=datetime.timezone.utc)

        # Insert 6 historical snapshots
        for i in range(6):
            ts = base_time + datetime.timedelta(hours=i)
            snap = AreaWeatherSnapshot(
                area_key="9vuf1",
                timestamp=ts,
                representative_lat=30.22,
                representative_lon=-92.02,
                temp_c=20.0 + i,
                dew_point_c=15.0,
                pressure_hPa=1013.0 - i * 0.5,
                humidity_pct=60.0,
                wind_speed_kmh=10.0 + i,
                precip_mm=0.0 if i < 4 else 1.0,
                elevation=10.0,
            )
            db_session.add(snap)
        db_session.flush()

        # Now assemble for hour 7
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
            db=db_session,
            area_key="9vuf1",
            representative_lat=30.22,
            representative_lon=-92.02,
            request_data=request_data,
        )
        db_session.commit()

        assert result["pressure_change_6h"] is not None
        assert result["precip_6h"] > 0
        assert result["wind_speed_change_3h"] is not None


# ── Area alert state lifecycle ──────────────────────────────────────

class TestAreaAlertState:
    """Alert lifecycle transitions on area_alert_state."""

    def test_create_default_state(self, db_session):
        """New area starts with alert_active=False."""
        state = AreaAlertState(area_key="9vuf1", alert_active=False)
        db_session.add(state)
        db_session.commit()

        loaded = db_session.query(AreaAlertState).filter_by(area_key="9vuf1").first()
        assert loaded is not None
        assert loaded.alert_active is False
        assert loaded.last_risk_score is None

    def test_activate_alert(self, db_session):
        """Setting alert_active=True persists."""
        state = AreaAlertState(area_key="9vuf1", alert_active=False)
        db_session.add(state)
        db_session.flush()

        state.alert_active = True
        state.last_risk_score = 0.85
        state.last_risk_level = "high"
        state.last_prediction_utc = datetime.datetime.now(datetime.timezone.utc)
        db_session.commit()

        loaded = db_session.query(AreaAlertState).filter_by(area_key="9vuf1").first()
        assert loaded.alert_active is True
        assert loaded.last_risk_score == 0.85
        assert loaded.last_risk_level == "high"

    def test_unique_area_key(self, db_session):
        """Duplicate area_key should raise IntegrityError."""
        from sqlalchemy.exc import IntegrityError

        db_session.add(AreaAlertState(area_key="9vuf1", alert_active=False))
        db_session.flush()

        db_session.add(AreaAlertState(area_key="9vuf1", alert_active=True))
        with pytest.raises(IntegrityError):
            db_session.flush()
        db_session.rollback()
