#!/usr/bin/env python3
import os
from supabase import create_client

# Load Supabase config
url = 'http://127.0.0.1:54321'
key = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InN0ZXN0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE2OTc2MDAwMDAsImV4cCI6MTc5OTk5OTk5OX0.PLACEHOLDER'

supabase = create_client(url, key)

# Query devices with alert_token
try:
    response = supabase.table('device').select('device_id, alert_token, created_at').order('created_at', desc=True).limit(5).execute()
    
    if response.data:
        print(f"\n✓ Found {len(response.data)} devices:\n")
        for i, device in enumerate(response.data, 1):
            print(f"{i}. Device ID: {device['device_id']}")
            token = device.get('alert_token')
            if token:
                print(f"   ✓ Alert Token: {token[:30]}...")
            else:
                print(f"   ✗ Alert Token: NULL (warning)")
            print()
    else:
        print("\n✗ No devices found in database")
        
except Exception as e:
    print(f"\n✗ Error querying database: {e}")
