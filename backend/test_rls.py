"""Quick script to test if RLS is blocking inserts on weather_cache / offline_weather_snapshot."""
import asyncio
import os
import uuid

import httpx
from dotenv import load_dotenv

load_dotenv()

BASE = os.getenv("SUPABASE_BASE_URL", "")
KEY = os.getenv("SUPABASE_API_KEY", "")


async def main():
    headers = {"apikey": KEY, "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=10) as c:
        # READ test
        r = await c.get(f"{BASE}/rest/v1/weather_cache?select=cache_id&limit=5", headers=headers)
        print(f"GET weather_cache: status={r.status_code}, rows={len(r.json()) if r.status_code == 200 else r.text}")

        r2 = await c.get(f"{BASE}/rest/v1/offline_weather_snapshot?select=offline_weather_id&limit=5", headers=headers)
        print(f"GET offline_weather_snapshot: status={r2.status_code}, rows={len(r2.json()) if r2.status_code == 200 else r2.text}")

        # WRITE test with ISO timestamp (the fix)
        test_id = str(uuid.uuid4())
        r3 = await c.post(
            f"{BASE}/rest/v1/weather_cache",
            json={"cache_id": test_id, "recorded_at": "2026-04-15T06:00:00Z", "latitude": 30.0, "longitude": -92.0},
            headers={**headers, "Prefer": "return=representation,resolution=merge-duplicates"},
        )
        body = r3.json() if r3.status_code in [200, 201] else r3.text
        print(f"POST weather_cache: status={r3.status_code}, returned_rows={len(body) if isinstance(body, list) else body}")

        # Clean up
        if isinstance(body, list) and len(body) > 0:
            await c.delete(f"{BASE}/rest/v1/weather_cache?cache_id=eq.{test_id}", headers=headers)
            print("Cleaned up test row")


asyncio.run(main())
