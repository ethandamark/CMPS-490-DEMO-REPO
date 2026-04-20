-- Migration 011: Snapshot-as-archive + local-only cache
--
-- 1. Remove weather columns from model_instance (denormalization reverted)
-- 2. Add weather_data JSONB + snapshot_type to offline_weather_snapshot
-- 3. Drop cache_id FK from offline_weather_snapshot (snapshots are self-contained archives)

-- ── 1. Revert weather columns on model_instance ──
ALTER TABLE model_instance
    DROP COLUMN IF EXISTS temp,
    DROP COLUMN IF EXISTS humidity,
    DROP COLUMN IF EXISTS wind_speed,
    DROP COLUMN IF EXISTS wind_direction,
    DROP COLUMN IF EXISTS pressure,
    DROP COLUMN IF EXISTS precipitation_amount;

-- ── 2. Add JSONB weather archive + snapshot_type to offline_weather_snapshot ──
ALTER TABLE offline_weather_snapshot
    ADD COLUMN IF NOT EXISTS weather_data JSONB,
    ADD COLUMN IF NOT EXISTS snapshot_type TEXT DEFAULT 'hourly';

-- ── 3. Make cache_id nullable (no longer a strict FK for new rows) ──
ALTER TABLE offline_weather_snapshot
    ALTER COLUMN cache_id DROP NOT NULL;

-- Drop the old FK constraint on cache_id so snapshots are self-contained
DO $$ BEGIN
    ALTER TABLE offline_weather_snapshot DROP CONSTRAINT IF EXISTS offline_weather_snapshot_cache_id_fkey;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;
