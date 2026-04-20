-- Migration 014: Drop legacy cache_id column from offline_weather_snapshot
-- This column is no longer used; weather data is stored as JSONB in weather_data.

ALTER TABLE offline_weather_snapshot
    DROP COLUMN IF EXISTS cache_id;
