-- Migration 018: Drop stale ML pipeline columns from device table
-- These columns (area_key, last_notified_utc, notification_cooldown_until_utc)
-- were added in migration 003 for area-based predictions but are no longer used.

ALTER TABLE device
    DROP COLUMN IF EXISTS area_key,
    DROP COLUMN IF EXISTS last_notified_utc,
    DROP COLUMN IF EXISTS notification_cooldown_until_utc;
