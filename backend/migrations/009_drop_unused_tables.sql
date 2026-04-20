-- ================================================================
-- Migration 009: Drop unused tables
-- ================================================================
-- Removes tables that are no longer referenced by the backend or
-- ML pipeline:
--   - offline_alert_snapshot (never used in production code)
--   - device_weather_snapshot (replaced by weather_cache)
--   - device_alert (alert delivery tracking — removed)
--   - area_weather_snapshot (ML features now computed in-memory)
--   - area_alert_state (alert lifecycle state — removed)
--   - alert_event (alert CRUD — removed)
--
-- Order matters: drop tables with foreign keys first.
-- ================================================================

-- Tables referencing alert_event must be dropped first
DROP TABLE IF EXISTS offline_alert_snapshot CASCADE;
DROP TABLE IF EXISTS device_alert CASCADE;

-- Now drop alert_event itself
DROP TABLE IF EXISTS alert_event CASCADE;

-- Drop remaining unused tables
DROP TABLE IF EXISTS device_weather_snapshot CASCADE;
DROP TABLE IF EXISTS area_weather_snapshot CASCADE;
DROP TABLE IF EXISTS area_alert_state CASCADE;
