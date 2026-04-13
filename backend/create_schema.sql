-- ================================================================
-- Weather Tracker — Comprehensive Supabase Schema
-- ================================================================
-- This file defines ALL tables for a fresh Supabase instance.
-- It merges the original ER-diagram tables with the ML pipeline
-- tables (previously added via migrations 001-003).
--
-- Run once on a clean database.  For existing deployments, apply
-- the individual migration files in backend/migrations/ instead.
-- ================================================================

-- ── Enum types ─────────────────────────────────────────────────────
CREATE TYPE status_enum AS ENUM ('active', 'inactive');
CREATE TYPE platform_enum AS ENUM ('android', 'ios');
CREATE TYPE weather_condition_enum AS ENUM ('rain', 'clean');
CREATE TYPE result_type_enum AS ENUM ('storm', 'flood');
CREATE TYPE alert_type_enum AS ENUM ('storm', 'flood');
CREATE TYPE delivery_status_enum AS ENUM ('pending', 'sent', 'failed');

--------------------------------------------------
-- 1. Anonymous User
--------------------------------------------------
CREATE TABLE anonymous_user (
    anon_user_id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    last_active_at TIMESTAMP,
    notification_opt_in BOOLEAN,
    status status_enum
);

--------------------------------------------------
-- 2. Device
--    Includes ML pipeline columns (area_key,
--    last_notified_utc, notification_cooldown_until_utc).
--    Device location is stored in the device_location table.
--------------------------------------------------
CREATE TABLE device (
    device_id UUID PRIMARY KEY,
    anon_user_id UUID UNIQUE REFERENCES anonymous_user(anon_user_id),
    alert_token UUID UNIQUE DEFAULT gen_random_uuid(),
    device_token TEXT,
    platform platform_enum,
    app_version VARCHAR(50),
    location_permission_status BOOLEAN,
    last_seen_at TIMESTAMP,
    created_at TIMESTAMP,

    -- ML pipeline columns (migrations 002 + 003)
    area_key                        TEXT,
    last_notified_utc               TIMESTAMPTZ,
    notification_cooldown_until_utc TIMESTAMPTZ
);

--------------------------------------------------
-- 3. Device Location
--------------------------------------------------
CREATE TABLE device_location (
    location_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    captured_at TIMESTAMP
);

--------------------------------------------------
-- 4. Weather Cache
--------------------------------------------------
CREATE TABLE weather_cache (
    cache_id UUID PRIMARY KEY,
    temp DECIMAL(5,2),
    humidity DECIMAL(5,2),
    wind_speed DECIMAL(6,2),
    wind_direction DECIMAL(5,2),
    precipitation_amount DECIMAL(6,2),
    pressure DECIMAL(7,2),
    weather_condition weather_condition_enum,
    recorded_at TIMESTAMP,
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    result_level INT CHECK (result_level BETWEEN 0 AND 5),
    result_type result_type_enum
);

--------------------------------------------------
-- 5. Model Instance
--------------------------------------------------
CREATE TABLE model_instance (
    instance_id UUID PRIMARY KEY,
    version VARCHAR(50),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    result_level INT CHECK (result_level BETWEEN 0 AND 5),
    result_type result_type_enum,
    confidence_score DECIMAL(5,4),
    created_at TIMESTAMP
);

--------------------------------------------------
-- 6. Alert Event
--------------------------------------------------
CREATE TABLE alert_event (
    alert_id UUID PRIMARY KEY,
    instance_id UUID REFERENCES model_instance(instance_id),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    alert_type alert_type_enum,
    severity_level INT CHECK (severity_level BETWEEN 0 AND 5),
    created_at TIMESTAMP,
    expires_at TIMESTAMP
);

--------------------------------------------------
-- 7. Device Alert
--------------------------------------------------
CREATE TABLE device_alert (
    device_alert_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    alert_id UUID REFERENCES alert_event(alert_id),
    delivery_status delivery_status_enum,
    sent_at TIMESTAMP
);

--------------------------------------------------
-- 8. Offline Weather Snapshot
--------------------------------------------------
CREATE TABLE offline_weather_snapshot (
    offline_weather_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    cache_id UUID REFERENCES weather_cache(cache_id),
    synced_at TIMESTAMP,
    is_current BOOLEAN
);

--------------------------------------------------
-- 9. Offline Alert Snapshot
--------------------------------------------------
CREATE TABLE offline_alert_snapshot (
    offline_alert_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    alert_id UUID REFERENCES alert_event(alert_id),
    synced_at TIMESTAMP,
    is_current BOOLEAN
);

-- ================================================================
-- ML Pipeline Tables (from migrations 002 + 003)
-- ================================================================

--------------------------------------------------
-- 10. Device Weather Snapshot (LEGACY — replaced by Area Weather
--     Snapshot, kept for backward compatibility / data preservation)
--------------------------------------------------
CREATE TABLE device_weather_snapshot (
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

CREATE INDEX idx_dws_user_ts
    ON device_weather_snapshot (user_id, timestamp DESC);

--------------------------------------------------
-- 11. Area Weather Snapshot
--     Hourly weather observations stored per geohash area cell.
--------------------------------------------------
CREATE TABLE area_weather_snapshot (
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

CREATE INDEX idx_aws_area_ts
    ON area_weather_snapshot (area_key, timestamp DESC);

CREATE INDEX idx_aws_created
    ON area_weather_snapshot (created_at);

--------------------------------------------------
-- 12. Area Alert State
--     Alert lifecycle state scoped to a geographic area cell.
--------------------------------------------------
CREATE TABLE area_alert_state (
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

CREATE INDEX idx_aas_area_key
    ON area_alert_state (area_key);
