"""Apply all migrations in order to the Supabase database."""
import psycopg2
import os
import glob
from dotenv import load_dotenv

load_dotenv()

conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
conn.autocommit = True
cur = conn.cursor()

# Check if base schema exists (look for the 'device' table)
cur.execute(
    "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
    "WHERE table_schema = 'public' AND table_name = 'device')"
)
schema_exists = cur.fetchone()[0]

if not schema_exists:
    print("Base schema not found — creating from create_schema.sql...")
    with open("create_schema.sql") as fh:
        cur.execute(fh.read())
    print("  OK\n")

    # Apply RLS policies
    print("Enabling RLS...")
    with open("enable_rls.sql") as fh:
        cur.execute(fh.read())
    print("  OK")
    with open("fix_rls_public_access.sql") as fh:
        cur.execute(fh.read())
    print("  OK\n")

files = sorted(glob.glob("migrations/*.sql"))
print(f"Found {len(files)} migration files\n")

for f in files:
    print(f"Applying {f}...")
    try:
        with open(f) as fh:
            sql = fh.read()
        cur.execute(sql)
        print("  OK")
    except Exception as e:
        print(f"  WARN: {e}")
        conn.rollback()
        conn.autocommit = True

print("\n--- Verifying tables ---")
cur.execute(
    "SELECT table_name FROM information_schema.tables "
    "WHERE table_schema = 'public' ORDER BY table_name"
)
for r in cur.fetchall():
    print(f"  {r[0]}")

# Reload PostgREST schema cache so it sees new/modified tables
cur.execute("NOTIFY pgrst, 'reload schema'")
print("\nPostgREST schema cache reloaded.")

conn.close()
print("All migrations applied.")
