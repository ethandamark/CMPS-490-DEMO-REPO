"""
SQLAlchemy models and session factory for the ML pipeline.

These models give the feature-assembly layer direct SQL access to
Supabase's PostgreSQL database (the same instance the REST API uses).
"""
from __future__ import annotations

from sqlalchemy import (
    Column,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    create_engine,
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
    ``last_lat`` / ``last_lon`` are new columns added by the migration in
    ``migrations/002_add_ml_columns.sql``.
    """
    __tablename__ = "device"

    id = Column("device_id", String, primary_key=True)
    last_lat = Column(Float, nullable=True)
    last_lon = Column(Float, nullable=True)

    snapshots = relationship(
        "DeviceWeatherSnapshot",
        back_populates="user",
        order_by="DeviceWeatherSnapshot.timestamp",
    )


class DeviceWeatherSnapshot(Base):
    """
    Hourly weather observations stored per device for ML feature assembly.
    Created by ``migrations/002_add_ml_columns.sql``.
    """
    __tablename__ = "device_weather_snapshot"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String, ForeignKey("device.device_id"), nullable=False)
    timestamp = Column(DateTime(timezone=True), nullable=False)

    # Current observation fields
    temp_c = Column(Float, nullable=True)
    dew_point_c = Column(Float, nullable=True)
    pressure_hPa = Column(Float, nullable=True)
    humidity_pct = Column(Float, nullable=True)
    wind_speed_kmh = Column(Float, nullable=True)
    precip_mm = Column(Float, nullable=True)

    # Static / geographic
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    elevation = Column(Float, nullable=True)
    dist_to_coast_km = Column(Float, nullable=True)

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

    user = relationship("UserDevice", back_populates="snapshots")


# ── Engine & Session ────────────────────────────────────────────────

engine = create_engine(SUPABASE_DB_URL, pool_pre_ping=True)


def get_session() -> Session:
    """Return a new SQLAlchemy session bound to the Supabase postgres DB."""
    return sessionmaker(bind=engine)()
