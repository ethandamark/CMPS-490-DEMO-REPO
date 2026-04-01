-- Drop all existing policies
DROP POLICY IF EXISTS "Allow select on all" ON anonymous_user;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON anonymous_user;
DROP POLICY IF EXISTS "Allow update own record" ON anonymous_user;
DROP POLICY IF EXISTS "Allow delete own record" ON anonymous_user;

DROP POLICY IF EXISTS "Allow select on all" ON device;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON device;
DROP POLICY IF EXISTS "Allow update own" ON device;
DROP POLICY IF EXISTS "Allow delete own" ON device;

DROP POLICY IF EXISTS "Allow select on all" ON device_location;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON device_location;
DROP POLICY IF EXISTS "Allow update for authenticated" ON device_location;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON device_location;

DROP POLICY IF EXISTS "Allow select on all" ON weather_cache;
DROP POLICY IF EXISTS "Allow insert for service role" ON weather_cache;
DROP POLICY IF EXISTS "Allow update for service role" ON weather_cache;
DROP POLICY IF EXISTS "Allow delete for service role" ON weather_cache;

DROP POLICY IF EXISTS "Allow select on all" ON model_instance;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON model_instance;
DROP POLICY IF EXISTS "Allow update for authenticated" ON model_instance;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON model_instance;

DROP POLICY IF EXISTS "Allow select on all" ON alert_event;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON alert_event;
DROP POLICY IF EXISTS "Allow update for authenticated" ON alert_event;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON alert_event;

DROP POLICY IF EXISTS "Allow select on all" ON device_alert;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON device_alert;
DROP POLICY IF EXISTS "Allow update for authenticated" ON device_alert;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON device_alert;

DROP POLICY IF EXISTS "Allow select on all" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow update for authenticated" ON offline_weather_snapshot;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON offline_weather_snapshot;

DROP POLICY IF EXISTS "Allow select on all" ON offline_alert_snapshot;
DROP POLICY IF EXISTS "Allow insert for authenticated" ON offline_alert_snapshot;
DROP POLICY IF EXISTS "Allow update for authenticated" ON offline_alert_snapshot;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON offline_alert_snapshot;

-- ==============================================
-- anonymous_user policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON anonymous_user FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON anonymous_user FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update own record" ON anonymous_user FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete own record" ON anonymous_user FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- device policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON device FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON device FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update own" ON device FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete own" ON device FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- device_location policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON device_location FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON device_location FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON device_location FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON device_location FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- weather_cache policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON weather_cache FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON weather_cache FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON weather_cache FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON weather_cache FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- model_instance policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON model_instance FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON model_instance FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON model_instance FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON model_instance FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- alert_event policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON alert_event FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON alert_event FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON alert_event FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON alert_event FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- device_alert policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON device_alert FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON device_alert FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON device_alert FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON device_alert FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- offline_weather_snapshot policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON offline_weather_snapshot FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON offline_weather_snapshot FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON offline_weather_snapshot FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON offline_weather_snapshot FOR DELETE USING ((select auth.uid()) IS NOT NULL);

-- ==============================================
-- offline_alert_snapshot policies (optimized with subqueries)
-- ==============================================
CREATE POLICY "Allow select on all" ON offline_alert_snapshot FOR SELECT USING (true);
CREATE POLICY "Allow insert for authenticated" ON offline_alert_snapshot FOR INSERT WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow update for authenticated" ON offline_alert_snapshot FOR UPDATE USING ((select auth.uid()) IS NOT NULL) WITH CHECK ((select auth.uid()) IS NOT NULL);
CREATE POLICY "Allow delete for authenticated" ON offline_alert_snapshot FOR DELETE USING ((select auth.uid()) IS NOT NULL);
