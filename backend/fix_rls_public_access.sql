-- Drop existing overly permissive policies
DROP POLICY IF EXISTS "Allow all authenticated users" ON device;

-- Create new policies that explicitly allow public (anon) access
-- This allows unauthenticated requests with the public API key to read and write to device table
CREATE POLICY "Allow public read write" ON device
    FOR ALL USING (true) WITH CHECK (true);

-- Also ensure insert/update/delete operations are allowed
CREATE POLICY "Allow public insert" ON device
    FOR INSERT WITH CHECK (true);

CREATE POLICY "Allow public update" ON device
    FOR UPDATE USING (true) WITH CHECK (true);

CREATE POLICY "Allow public delete" ON device
    FOR DELETE USING (true);

-- Do the same for device_location table if needed
DROP POLICY IF EXISTS "Allow all authenticated users" ON device_location;
CREATE POLICY "Allow public access" ON device_location
    FOR ALL USING (true) WITH CHECK (true);

-- And for device_alert table
DROP POLICY IF EXISTS "Allow all authenticated users" ON device_alert;
CREATE POLICY "Allow public access" ON device_alert
    FOR ALL USING (true) WITH CHECK (true);
