-- ================================================================
-- Migration 005: Add ML Features and Forecast Flag to weather_cache
-- ================================================================
-- This migration extends the weather_cache table to store:
-- 1. Raw observation features needed by the ML model
-- 2. Static/geographic features (elevation, distance to coast)
-- 3. NWP (numerical weather prediction) features
-- 4. Radar features (MRMS reflectivity)
-- 5. is_forecast flag to distinguish observations from forecast data
--
-- Purpose: Support on-device ML inference by storing complete
-- weather feature vectors that can be synced to Android and used
-- for local prediction, plus 7-day forecast data.
--
-- Added columns:
-- - dew_point_c: Dew point temperature in Celsius
-- - elevation: Elevation above sea level in meters
-- - dist_to_coast_km: Distance to nearest coast in kilometers
-- - nwp_cape_f3_6_max: CAPE (Convective Available PE) 0-3h max
-- - nwp_cin_f3_6_max: CIN (Convective INhibition) 0-3h max
-- - nwp_pwat_f3_6_max: PWAT (Precipitable Water) 0-3h max
-- - nwp_srh03_f3_6_max: SRH (Storm Relative Helicity) 0-3h max
-- - nwp_li_f3_6_min: LI (Lifted Index) 0-3h min
-- - nwp_lcl_f3_6_min: LCL (Lifted Condensation Level) 0-3h min
-- - nwp_available_leads: Available forecast lead time
-- - mrms_max_dbz_75km: Max radar reflectivity (dBZ) in 75km radius
-- - is_forecast: TRUE if data is from Open-Meteo forecast, FALSE if observation
-- ================================================================

ALTER TABLE weather_cache
    ADD COLUMN IF NOT EXISTS dew_point_c DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS elevation DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS dist_to_coast_km DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS nwp_cape_f3_6_max DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS nwp_cin_f3_6_max DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS nwp_pwat_f3_6_max DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS nwp_srh03_f3_6_max DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS nwp_li_f3_6_min DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS nwp_lcl_f3_6_min DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS nwp_available_leads DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS mrms_max_dbz_75km DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS is_forecast BOOLEAN DEFAULT FALSE;

-- Create index for efficient queries of forecast data
CREATE INDEX IF NOT EXISTS idx_weather_cache_is_forecast
    ON weather_cache (is_forecast, recorded_at DESC);

-- Create index for queries by device location and time
CREATE INDEX IF NOT EXISTS idx_weather_cache_location_time
    ON weather_cache (latitude, longitude, recorded_at DESC);
