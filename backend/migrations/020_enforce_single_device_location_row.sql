-- Ensure device_location has exactly one current row per device.
-- 1) Deduplicate historical duplicates, keeping the most recent captured_at row.
-- 2) Enforce uniqueness with a true unique constraint so PostgREST
--    on_conflict=device_id can perform atomic upserts.

WITH ranked AS (
    SELECT
        location_id,
        ROW_NUMBER() OVER (
            PARTITION BY device_id
            ORDER BY captured_at DESC NULLS LAST, location_id DESC
        ) AS rn
    FROM device_location
    WHERE device_id IS NOT NULL
)
DELETE FROM device_location dl
USING ranked r
WHERE dl.location_id = r.location_id
  AND r.rn > 1;

DROP INDEX IF EXISTS uq_device_location_device_id;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'device_location_device_id_key'
    ) THEN
        ALTER TABLE device_location
        ADD CONSTRAINT device_location_device_id_key UNIQUE (device_id);
    END IF;
END$$;
