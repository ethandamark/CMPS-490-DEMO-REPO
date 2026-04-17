"""Check RLS status and existing policies."""
import psycopg2
import os
from dotenv import load_dotenv

load_dotenv()
conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
cur = conn.cursor()

# Check which tables have RLS enabled
cur.execute(
    "SELECT tablename, rowsecurity FROM pg_tables "
    "WHERE schemaname = 'public' ORDER BY tablename"
)
print("=== RLS Status ===")
for r in cur.fetchall():
    print(f"  {r[0]}: RLS={'ON' if r[1] else 'OFF'}")

# Check existing policies
cur.execute(
    "SELECT tablename, policyname, permissive, roles, cmd, qual, with_check "
    "FROM pg_policies WHERE schemaname = 'public' ORDER BY tablename, policyname"
)
print("\n=== Existing Policies ===")
rows = cur.fetchall()
if not rows:
    print("  (none)")
else:
    for r in rows:
        print(f"  Table: {r[0]}")
        print(f"    Policy: {r[1]}, Permissive: {r[2]}, Roles: {r[3]}, CMD: {r[4]}")
        print(f"    USING: {r[5]}")
        print(f"    WITH CHECK: {r[6]}")
        print()

conn.close()
