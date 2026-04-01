-- Enable RLS on all tables
ALTER TABLE anonymous_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE device ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_location ENABLE ROW LEVEL SECURITY;
ALTER TABLE weather_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_alert ENABLE ROW LEVEL SECURITY;
ALTER TABLE offline_weather_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE offline_alert_snapshot ENABLE ROW LEVEL SECURITY;

-- Create basic permissive policy for all tables (allows all authenticated users)
CREATE POLICY "Allow all authenticated users" ON anonymous_user
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON device
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON device_location
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON weather_cache
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON model_instance
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON alert_event
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON device_alert
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON offline_weather_snapshot
    FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow all authenticated users" ON offline_alert_snapshot
    FOR ALL USING (true) WITH CHECK (true);
