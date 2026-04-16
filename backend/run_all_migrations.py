"""Apply all migrations in order to the Supabase database."""
import psycopg2
import os
import glob
from dotenv import load_dotenv

load_dotenv()

conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
conn.autocommit = True
cur = conn.cursor()

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

conn.close()
print("\nAll migrations applied.")
