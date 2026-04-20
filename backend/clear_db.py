"""Truncate all public tables in the Supabase database."""
import psycopg2
import os
from dotenv import load_dotenv

load_dotenv()
conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
conn.autocommit = True
cur = conn.cursor()

cur.execute("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
tables = [r[0] for r in cur.fetchall()]
print(f"Found {len(tables)} tables\n")

for t in tables:
    cur.execute(f"TRUNCATE {t} CASCADE")
    print(f"  Truncated {t}")

conn.close()
print("\nAll tables cleared.")
