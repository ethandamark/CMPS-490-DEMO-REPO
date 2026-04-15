"""Check active RLS policies on weather_cache and offline_weather_snapshot."""
import os
import psycopg2
from dotenv import load_dotenv

load_dotenv()
DB_URL = os.getenv("SUPABASE_DB_URL", "")

print(f"DB_URL: {DB_URL[:30]}...")
conn = psycopg2.connect(DB_URL)
cur = conn.cursor()

# Check if RLS is enabled
cur.execute("""
SELECT relname, relrowsecurity, relforcerowsecurity
FROM pg_class
WHERE relname IN ('weather_cache', 'offline_weather_snapshot')
""")
print("=== RLS enabled? ===")
for row in cur.fetchall():
    print(f"  {row[0]}: rls={row[1]}, force={row[2]}")

# Check policies
cur.execute("""
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
FROM pg_policies
WHERE tablename IN ('weather_cache', 'offline_weather_snapshot')
ORDER BY tablename, policyname
""")
rows = cur.fetchall()
print(f"\n=== Policies ({len(rows)} found) ===")
for row in rows:
    print(f"  {row[1]} | {row[2]} | perm={row[3]} | roles={row[4]} | cmd={row[5]} | USING={row[6]} | CHECK={row[7]}")
conn.close()
