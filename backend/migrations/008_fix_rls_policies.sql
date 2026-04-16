-- ================================================================
-- Migration 008: Fix RLS policies for backend API access
-- ================================================================
-- The backend uses the Supabase anon key (not an authenticated user),
-- so tables that require auth.uid() or auth.role()='authenticated'
-- for writes will reject backend requests with 401.
--
-- This adds permissive "Allow public access" policies to:
--   - weather_cache
--   - offline_weather_snapshot
--   - model_instance
--
-- This matches the pattern already used by alert_event, device_alert,
-- and device_location.
-- ================================================================

-- weather_cache: allow public read/write
DROP POLICY IF EXISTS "Allow insert for service role" ON weather_cache;
DROP POLICY IF EXISTS "Allow update for service role" ON weather_cache;
DROP POLICY IF EXISTS "Allow delete for service role" ON weather_cache;
DROP POLICY IF EXISTS "Allow select on all" ON weather_cache;
DROP POLICY IF EXISTS "Allow public access" ON weather_cache;
CREATE POLICY "Allow public access" ON weather_cache
    FOR ALL USING (true) WITH CHECK (true);

-- offline_weather_snapshot: allow public read/write
DROP POLICY IF EXISTS "Allow insert for authenticated" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow update for authenticated" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow select on all" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow public access" ON offline_weather_snapshot;
CREATE POLICY "Allow public access" ON offline_weather_snapshot
    FOR ALL USING (true) WITH CHECK (true);

-- model_instance: allow public read/write
DROP POLICY IF EXISTS "Allow insert for authenticated" ON model_instance;
DROP POLICY IF EXISTS "Allow update for authenticated" ON model_instance;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON model_instance;
DROP POLICY IF EXISTS "Allow select on all" ON model_instance;
DROP POLICY IF EXISTS "Allow public access" ON model_instance;
CREATE POLICY "Allow public access" ON model_instance
    FOR ALL USING (true) WITH CHECK (true);

-- offline_alert_snapshot: also has the same issue
DROP POLICY IF EXISTS "Allow insert for authenticated" ON offline_alert_snapshot;
DROP POLICY IF EXISTS "Allow update for authenticated" ON offline_alert_snapshot;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON offline_alert_snapshot;
DROP POLICY IF EXISTS "Allow select on all" ON offline_alert_snapshot;
DROP POLICY IF EXISTS "Allow public access" ON offline_alert_snapshot;
CREATE POLICY "Allow public access" ON offline_alert_snapshot
    FOR ALL USING (true) WITH CHECK (true);
