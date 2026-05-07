-- Migration 022: Drop the assembled_features column from offline_weather_snapshot.
-- This column was added by mistake (never populated) and is not needed.
ALTER TABLE offline_weather_snapshot
    DROP COLUMN IF EXISTS assembled_features;
