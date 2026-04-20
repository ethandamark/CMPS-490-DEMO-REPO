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
CREATE TYPE snapshot_type_enum AS ENUM ('seed', 'hourly', 'sync');

--------------------------------------------------
-- 1. Anonymous User
--------------------------------------------------
CREATE TABLE anonymous_user (
    anon_user_id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    last_active_at TIMESTAMP,
    status status_enum DEFAULT 'active'
);

--------------------------------------------------
-- 2. Device
--    Device location is stored in the device_location table.
--------------------------------------------------
CREATE TABLE device (
    device_id UUID PRIMARY KEY,
    anon_user_id UUID UNIQUE REFERENCES anonymous_user(anon_user_id),
    platform platform_enum,
    app_version VARCHAR(50),
    location_permission_status BOOLEAN,
    notifications_enabled BOOLEAN DEFAULT FALSE
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
-- 4. Offline Weather Snapshot  (archive — carries weather data as JSONB)
--    weather_cache is local-only (Room DB); all Supabase weather data
--    lives here as JSONB.
--------------------------------------------------
CREATE TABLE offline_weather_snapshot (
    weather_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    synced_at TIMESTAMP,
    is_current BOOLEAN,
    weather_data JSONB,                   -- array of weather readings archived at sync time
    snapshot_type snapshot_type_enum DEFAULT 'hourly'
);

--------------------------------------------------
-- 5. Model Instance
--------------------------------------------------
CREATE TABLE model_instance (
    instance_id UUID PRIMARY KEY,
    weather_id UUID REFERENCES offline_weather_snapshot(weather_id),
    version VARCHAR(50),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    result_level INT CHECK (result_level BETWEEN 0 AND 5),
    result_type TEXT CHECK (result_type IN ('storm', 'clear')),
    confidence_score DECIMAL(5,4),
    created_at TIMESTAMP
);

-- ── Sentinel snapshot for storm simulations ────────────────────────
-- Model instances from simulations FK to this dummy row instead of
-- real weather snapshots.
INSERT INTO offline_weather_snapshot (
    weather_id, device_id, synced_at, is_current, weather_data, snapshot_type
) VALUES (
    '00000000-0000-0000-0000-000000000000',
    NULL, NULL, FALSE, '[]'::jsonb, 'seed'
) ON CONFLICT (weather_id) DO NOTHING;

-- ── Role grants ────────────────────────────────────────────────────
-- Supabase routes requests through anon / authenticated / service_role.
-- Without these grants, RLS policies alone are not enough.
GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role;
GRANT ALL ON ALL TABLES    IN SCHEMA public TO anon, authenticated, service_role;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO anon, authenticated, service_role;

-- ── Row Level Security ─────────────────────────────────────────────
-- Enable RLS on every table and add a permissive "allow all" policy.
-- The backend uses the Supabase anon key, so public access is required.
ALTER TABLE anonymous_user           ENABLE ROW LEVEL SECURITY;
ALTER TABLE device                   ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_location          ENABLE ROW LEVEL SECURITY;
ALTER TABLE offline_weather_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_instance           ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow all" ON anonymous_user           FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "Allow all" ON device                   FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "Allow all" ON device_location          FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "Allow all" ON offline_weather_snapshot  FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "Allow all" ON model_instance           FOR ALL USING (true) WITH CHECK (true);
