#!/usr/bin/env python3
"""
Apply RLS policy fixes to allow public access to device table
"""
import psycopg2
import os

# Database connection parameters
DB_HOST = "127.0.0.1"
DB_PORT = 54322
DB_USER = "postgres"
DB_PASSWORD = "postgres"
DB_NAME = "postgres"

# SQL fixes
sql_fixes = """
-- Drop existing policies
DROP POLICY IF EXISTS "Allow all authenticated users" ON device;

-- Create new policies that explicitly allow public (anon) access
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
"""

try:
    # Connect to database
    conn = psycopg2.connect(
        host=DB_HOST,
        port=DB_PORT,
        user=DB_USER,
        password=DB_PASSWORD,
        database=DB_NAME
    )
    
    cursor = conn.cursor()
    
    # Execute the SQL fixes
    cursor.execute(sql_fixes)
    conn.commit()
    
    print("✓ RLS policies updated successfully!")
    print("✓ Device table now allows public write access")
    
    cursor.close()
    conn.close()
    
except Exception as e:
    print(f"✗ Error applying RLS fixes: {e}")
    import traceback
    traceback.print_exc()
    exit(1)
