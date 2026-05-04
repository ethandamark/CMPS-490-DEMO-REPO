-- Migration 021: Add predicted_dbz column and set result_type CHECK constraint
-- Run this on your Supabase instance to support 4-tier storm risk levels (0–3).

-- 1. Add the predicted_dbz column (nullable — back-filled as null for existing rows)
ALTER TABLE model_instance
    ADD COLUMN IF NOT EXISTS predicted_dbz FLOAT;

-- 2. Set result_type to support the four severity tiers.
--    Supabase/Postgres does not support ALTER CONSTRAINT directly, so we drop
--    the old CHECK and add a new one.
ALTER TABLE model_instance
    DROP CONSTRAINT IF EXISTS model_instance_result_type_check;

ALTER TABLE model_instance
    ADD CONSTRAINT model_instance_result_type_check
    CHECK (result_type IN ('clear', 'light', 'moderate', 'severe'));
