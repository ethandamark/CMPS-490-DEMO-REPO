-- Migration 013: Convert snapshot_type from TEXT to ENUM
-- Restricts values to 'seed', 'hourly', 'sync' for data integrity.

-- ── 1. Create the enum type ──
CREATE TYPE snapshot_type_enum AS ENUM ('seed', 'hourly', 'sync');

-- ── 2. Convert the column, casting existing values ──
ALTER TABLE offline_weather_snapshot
    ALTER COLUMN snapshot_type DROP DEFAULT,
    ALTER COLUMN snapshot_type TYPE snapshot_type_enum
        USING snapshot_type::snapshot_type_enum,
    ALTER COLUMN snapshot_type SET DEFAULT 'hourly';
