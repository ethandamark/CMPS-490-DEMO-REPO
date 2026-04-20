-- Migration 012: Drop the Supabase weather_cache table
--
-- weather_cache is now local-only (Room DB on the device).
-- All weather data reaching Supabase is archived as JSONB inside
-- offline_weather_snapshot.weather_data.

-- Drop indexes first
DROP INDEX IF EXISTS idx_weather_cache_is_forecast;
DROP INDEX IF EXISTS idx_weather_cache_location_time;

-- Drop the table
DROP TABLE IF EXISTS weather_cache;
