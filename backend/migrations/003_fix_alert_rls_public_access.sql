-- Fix RLS policies for alert_event and device_alert to allow public (anon key) access.
-- The backend uses the Supabase anon key (not an authenticated user),
-- so policies requiring auth.uid() block inserts/updates/deletes.

-- alert_event: allow public access for all operations
DROP POLICY IF EXISTS "Allow insert for authenticated" ON alert_event;
DROP POLICY IF EXISTS "Allow update for authenticated" ON alert_event;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON alert_event;
DROP POLICY IF EXISTS "Allow select on all" ON alert_event;
DROP POLICY IF EXISTS "Allow all authenticated users" ON alert_event;
DROP POLICY IF EXISTS "Allow public access" ON alert_event;

CREATE POLICY "Allow public access" ON alert_event
    FOR ALL USING (true) WITH CHECK (true);

-- device_alert: allow public access for all operations
DROP POLICY IF EXISTS "Allow insert for authenticated" ON device_alert;
DROP POLICY IF EXISTS "Allow update for authenticated" ON device_alert;
DROP POLICY IF EXISTS "Allow delete for authenticated" ON device_alert;
DROP POLICY IF EXISTS "Allow select on all" ON device_alert;
DROP POLICY IF EXISTS "Allow all authenticated users" ON device_alert;
DROP POLICY IF EXISTS "Allow public access" ON device_alert;

CREATE POLICY "Allow public access" ON device_alert
    FOR ALL USING (true) WITH CHECK (true);
