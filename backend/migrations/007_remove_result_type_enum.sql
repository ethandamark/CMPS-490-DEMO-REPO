-- ================================================================
-- Migration 007: Remove result_type_enum, use TEXT with CHECK
-- ================================================================
-- The frontend sends 'storm' or 'clear' as result_type values,
-- but the old enum only allowed ('storm', 'flood'). This migration
-- drops the enum type and replaces it with a TEXT column using a
-- CHECK constraint with the correct values.
-- ================================================================

-- 1. Alter model_instance: convert enum column to TEXT
ALTER TABLE model_instance
    ALTER COLUMN result_type TYPE TEXT USING result_type::TEXT;

ALTER TABLE model_instance
    DROP CONSTRAINT IF EXISTS model_instance_result_type_check;

ALTER TABLE model_instance
    ADD CONSTRAINT model_instance_result_type_check
    CHECK (result_type IN ('storm', 'clear'));

-- 2. Alter weather_cache: convert enum column to TEXT (if it exists)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'weather_cache' AND column_name = 'result_type'
    ) THEN
        ALTER TABLE weather_cache
            ALTER COLUMN result_type TYPE TEXT USING result_type::TEXT;
    END IF;
END $$;

-- 3. Drop the enum type (no longer used anywhere)
DROP TYPE IF EXISTS result_type_enum;
