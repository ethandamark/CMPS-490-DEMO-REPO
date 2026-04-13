"""
SQLAlchemy models and session factory for the ML pipeline.

These models give the feature-assembly layer direct SQL access to
Supabase's PostgreSQL database (the same instance the REST API uses).
"""
from __future__ import annotations

from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    create_engine,
    func,
)
from sqlalchemy.orm import Session, declarative_base, relationship, sessionmaker

from config import SUPABASE_DB_URL

Base = declarative_base()


# ── ORM Models ──────────────────────────────────────────────────────

class UserDevice(Base):
    """
    Maps to the existing ``device`` table.

    Only the columns the ML pipeline actually touches are declared here;
    SQLAlchemy will silently ignore the rest of the table's columns.
    """
    __tablename__ = "device"

    id = Column("device_id", String, primary_key=True)
    area_key = Column(String, nullable=True)

    # Per-user notification deduplication
    last_notified_utc = Column(DateTime(timezone=True), nullable=True)
    notification_cooldown_until_utc = Column(DateTime(timezone=True), nullable=True)


class AreaWeatherSnapshot(Base):
    """
    Hourly weather observations stored per geographic area cell for ML
    feature assembly.  Replaces the old per-device snapshot table.
    """
    __tablename__ = "area_weather_snapshot"

    id = Column(Integer, primary_key=True, autoincrement=True)
    area_key = Column(String, nullable=False, index=True)
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)

    representative_lat = Column(Float, nullable=True)
    representative_lon = Column(Float, nullable=True)

    # Current observation fields
    temp_c = Column(Float, nullable=True)
    dew_point_c = Column(Float, nullable=True)
    pressure_hPa = Column(Float, nullable=True)
    humidity_pct = Column(Float, nullable=True)
    wind_speed_kmh = Column(Float, nullable=True)
    precip_mm = Column(Float, nullable=True)

    # Static / geographic
    elevation = Column(Float, nullable=True)

    # NWP (Numerical Weather Prediction) features
    nwp_cape_f3_6_max = Column(Float, nullable=True)
    nwp_cin_f3_6_max = Column(Float, nullable=True)
    nwp_pwat_f3_6_max = Column(Float, nullable=True)
    nwp_srh03_f3_6_max = Column(Float, nullable=True)
    nwp_li_f3_6_min = Column(Float, nullable=True)
    nwp_lcl_f3_6_min = Column(Float, nullable=True)
    nwp_available_leads = Column(Float, nullable=True)

    # Radar
    mrms_max_dbz_75km = Column(Float, nullable=True)

    created_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)

    __table_args__ = (
        UniqueConstraint("area_key", "timestamp", name="uq_area_snapshot_key_ts"),
    )


class AreaAlertState(Base):
    """
    Alert lifecycle state scoped to a geographic area cell.
    Replaces the per-device ml_* columns that used to live on UserDevice.
    """
    __tablename__ = "area_alert_state"

    id = Column(Integer, primary_key=True, autoincrement=True)
    area_key = Column(String, unique=True, nullable=False, index=True)

    alert_active = Column(Boolean, default=False, nullable=False)
    last_alert_start_utc = Column(DateTime(timezone=True), nullable=True)
    last_alert_end_utc = Column(DateTime(timezone=True), nullable=True)
    cooldown_until_utc = Column(DateTime(timezone=True), nullable=True)
    last_observed_storm_utc = Column(DateTime(timezone=True), nullable=True)

    model_version = Column(String, nullable=True)
    last_prediction_utc = Column(DateTime(timezone=True), nullable=True)
    last_risk_score = Column(Float, nullable=True)
    last_risk_level = Column(String, nullable=True)

    updated_at = Column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
    )


# ── Legacy table (kept for reference / safe rollout) ────────────────

class DeviceWeatherSnapshot(Base):
    """DEPRECATED — replaced by AreaWeatherSnapshot."""
    __tablename__ = "device_weather_snapshot"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String, ForeignKey("device.device_id"), nullable=False)
    timestamp = Column(DateTime(timezone=True), nullable=False)

    temp_c = Column(Float, nullable=True)
    dew_point_c = Column(Float, nullable=True)
    pressure_hPa = Column(Float, nullable=True)
    humidity_pct = Column(Float, nullable=True)
    wind_speed_kmh = Column(Float, nullable=True)
    precip_mm = Column(Float, nullable=True)

    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    elevation = Column(Float, nullable=True)
    dist_to_coast_km = Column(Float, nullable=True)

    nwp_cape_f3_6_max = Column(Float, nullable=True)
    nwp_cin_f3_6_max = Column(Float, nullable=True)
    nwp_pwat_f3_6_max = Column(Float, nullable=True)
    nwp_srh03_f3_6_max = Column(Float, nullable=True)
    nwp_li_f3_6_min = Column(Float, nullable=True)
    nwp_lcl_f3_6_min = Column(Float, nullable=True)
    nwp_available_leads = Column(Float, nullable=True)

    mrms_max_dbz_75km = Column(Float, nullable=True)


# ── Engine & Session ────────────────────────────────────────────────

engine = create_engine(SUPABASE_DB_URL, pool_pre_ping=True)


def get_session() -> Session:
    """Return a new SQLAlchemy session bound to the Supabase postgres DB."""
    return sessionmaker(bind=engine)()
