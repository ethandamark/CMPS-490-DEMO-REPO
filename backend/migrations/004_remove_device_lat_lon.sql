-- Migration: Remove last_lat / last_lon from device table
--
-- These columns are redundant now that the device_location table
-- stores all device location data.  The ML pipeline's /predict/live
-- endpoint now falls back to device_location for coordinates.

ALTER TABLE device DROP COLUMN IF EXISTS last_lat;
ALTER TABLE device DROP COLUMN IF EXISTS last_lon;
