-- Migration 010: Remove alert_token from device, link model_instance to offline_weather_snapshot
-- 1. Drop alert_token column (no longer used)
ALTER TABLE device DROP COLUMN IF EXISTS alert_token;

-- 2. Add offline_weather_id FK to model_instance
--    Links each prediction to the snapshot that produced it.
ALTER TABLE model_instance
    ADD COLUMN IF NOT EXISTS offline_weather_id UUID
    REFERENCES offline_weather_snapshot(offline_weather_id);
