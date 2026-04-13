-- Migration: Add ML pipeline columns and tables
--
-- 1. Add last_lat / last_lon to the existing device table so the
--    feature-assembly service can cache each device's last known position.
-- 2. Create device_weather_snapshot for hourly weather history per device.

-- ── 1. Extend device table ─────────────────────────────────────────
ALTER TABLE device
    ADD COLUMN IF NOT EXISTS last_lat DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS last_lon DOUBLE PRECISION;

-- ── 2. Weather snapshot table ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS device_weather_snapshot (
    id              SERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES device(device_id),
    timestamp       TIMESTAMPTZ NOT NULL,

    -- Current observation
    temp_c              DOUBLE PRECISION,
    dew_point_c         DOUBLE PRECISION,
    pressure_hPa        DOUBLE PRECISION,
    humidity_pct        DOUBLE PRECISION,
    wind_speed_kmh      DOUBLE PRECISION,
    precip_mm           DOUBLE PRECISION,

    -- Static / geographic
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    elevation           DOUBLE PRECISION,
    dist_to_coast_km    DOUBLE PRECISION,

    -- NWP features
    nwp_cape_f3_6_max   DOUBLE PRECISION,
    nwp_cin_f3_6_max    DOUBLE PRECISION,
    nwp_pwat_f3_6_max   DOUBLE PRECISION,
    nwp_srh03_f3_6_max  DOUBLE PRECISION,
    nwp_li_f3_6_min     DOUBLE PRECISION,
    nwp_lcl_f3_6_min    DOUBLE PRECISION,
    nwp_available_leads DOUBLE PRECISION,

    -- Radar
    mrms_max_dbz_75km   DOUBLE PRECISION,

    UNIQUE (user_id, timestamp)
);

-- Index for fast per-device history lookups
CREATE INDEX IF NOT EXISTS idx_dws_user_ts
    ON device_weather_snapshot (user_id, timestamp DESC);
