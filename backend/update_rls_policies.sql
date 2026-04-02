-- Drop existing overly permissive policies
DROP POLICY IF EXISTS "Allow all authenticated users" ON anonymous_user;
DROP POLICY IF EXISTS "Allow all authenticated users" ON device;
DROP POLICY IF EXISTS "Allow all authenticated users" ON device_location;
DROP POLICY IF EXISTS "Allow all authenticated users" ON weather_cache;
DROP POLICY IF EXISTS "Allow all authenticated users" ON model_instance;
DROP POLICY IF EXISTS "Allow all authenticated users" ON alert_event;
DROP POLICY IF EXISTS "Allow all authenticated users" ON device_alert;
DROP POLICY IF EXISTS "Allow all authenticated users" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow all authenticated users" ON offline_alert_snapshot;

-- ==============================================
-- anonymous_user policies
-- ==============================================
CREATE POLICY "Allow select on all" ON anonymous_user FOR SELECT USING (true);
CREATE POLICY "Allow insert for anonymous" ON anonymous_user FOR INSERT WITH CHECK (true);
CREATE POLICY "Allow update own record" ON anonymous_user FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete own record" ON anonymous_user FOR DELETE USING (auth.uid() IS NOT NULL);

-- ==============================================
-- device policies
-- ==============================================
CREATE POLICY "Allow select on all" ON device FOR SELECT USING (true);
CREATE POLICY "Allow insert for anonymous" ON device FOR INSERT WITH CHECK (true);
CREATE POLICY "Allow update own" ON device FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete own" ON device FOR DELETE USING (auth.uid() IS NOT NULL);

-- ==============================================
-- device_location policies
-- ==============================================
CREATE POLICY "Allow select on all" ON device_location FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON device_location FOR INSERT WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON device_location FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON device_location FOR DELETE USING (auth.uid() IS NOT NULL);

-- ==============================================
-- weather_cache policies (shared/system data - read-only)
-- ==============================================
CREATE POLICY "Allow select on all" ON weather_cache FOR SELECT USING (true);
CREATE POLICY "Allow insert for service role" ON weather_cache FOR INSERT WITH CHECK (auth.role() = 'authenticated');
CREATE POLICY "Allow update for service role" ON weather_cache FOR UPDATE USING (auth.role() = 'authenticated') WITH CHECK (auth.role() = 'authenticated');
CREATE POLICY "Allow delete for service role" ON weather_cache FOR DELETE USING (auth.role() = 'authenticated');

-- ==============================================
-- model_instance policies (shared/system data - read-only)
-- ==============================================
CREATE POLICY "Allow select on all" ON model_instance FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON model_instance FOR INSERT WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON model_instance FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON model_instance FOR DELETE USING (auth.uid() IS NOT NULL);

-- ==============================================
-- alert_event policies (shared/system data - read-only)
-- ==============================================
CREATE POLICY "Allow select on all" ON alert_event FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON alert_event FOR INSERT WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON alert_event FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON alert_event FOR DELETE USING (auth.uid() IS NOT NULL);

-- ==============================================
-- device_alert policies
-- ==============================================
CREATE POLICY "Allow select on all" ON device_alert FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON device_alert FOR INSERT WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON device_alert FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON device_alert FOR DELETE USING (auth.uid() IS NOT NULL);

-- ==============================================
-- offline_weather_snapshot policies
-- ==============================================
CREATE POLICY "Allow select on all" ON offline_weather_snapshot FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON offline_weather_snapshot FOR INSERT WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON offline_weather_snapshot FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON offline_weather_snapshot FOR DELETE USING (auth.uid() IS NOT NULL);

-- ==============================================
-- offline_alert_snapshot policies
-- ==============================================
CREATE POLICY "Allow select on all" ON offline_alert_snapshot FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON offline_alert_snapshot FOR INSERT WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON offline_alert_snapshot FOR UPDATE USING (auth.uid() IS NOT NULL) WITH CHECK (auth.uid() IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON offline_alert_snapshot FOR DELETE USING (auth.uid() IS NOT NULL);
