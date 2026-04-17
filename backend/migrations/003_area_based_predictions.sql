-- Migration: Switch from per-device to area-based predictions
--
-- 1. Create area_weather_snapshot table (replaces device_weather_snapshot).
-- 2. Create area_alert_state table (replaces ml_* columns on device).
-- 3. Add area_key, last_notified_utc, notification_cooldown_until_utc to device.

-- ── 1. Area weather snapshot table ─────────────────────────────────
CREATE TABLE IF NOT EXISTS area_weather_snapshot (
    id                  SERIAL PRIMARY KEY,
    area_key            TEXT NOT NULL,
    timestamp           TIMESTAMPTZ NOT NULL,

    representative_lat  DOUBLE PRECISION,
    representative_lon  DOUBLE PRECISION,

    -- Current observation
    temp_c              DOUBLE PRECISION,
    dew_point_c         DOUBLE PRECISION,
    pressure_hPa        DOUBLE PRECISION,
    humidity_pct        DOUBLE PRECISION,
    wind_speed_kmh      DOUBLE PRECISION,
    precip_mm           DOUBLE PRECISION,

    -- Static / geographic
    elevation           DOUBLE PRECISION,

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

    created_at          TIMESTAMPTZ DEFAULT now(),

    UNIQUE (area_key, timestamp)
);

CREATE INDEX IF NOT EXISTS idx_aws_area_ts
    ON area_weather_snapshot (area_key, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_aws_created
    ON area_weather_snapshot (created_at);

-- ── 2. Area alert state table ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS area_alert_state (
    id                      SERIAL PRIMARY KEY,
    area_key                TEXT NOT NULL UNIQUE,

    alert_active            BOOLEAN NOT NULL DEFAULT FALSE,
    last_alert_start_utc    TIMESTAMPTZ,
    last_alert_end_utc      TIMESTAMPTZ,
    cooldown_until_utc      TIMESTAMPTZ,
    last_observed_storm_utc TIMESTAMPTZ,

    model_version           TEXT,
    last_prediction_utc     TIMESTAMPTZ,
    last_risk_score         DOUBLE PRECISION,
    last_risk_level         TEXT,

    updated_at              TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_aas_area_key
    ON area_alert_state (area_key);

-- ── 3. Extend device table ─────────────────────────────────────────
ALTER TABLE device
    ADD COLUMN IF NOT EXISTS area_key TEXT,
    ADD COLUMN IF NOT EXISTS last_notified_utc TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS notification_cooldown_until_utc TIMESTAMPTZ;
