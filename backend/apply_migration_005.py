"""Apply migration 005 to add ML columns to weather_cache."""
import psycopg2
import os
from dotenv import load_dotenv

load_dotenv()
conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
conn.autocommit = True
cur = conn.cursor()

with open("migrations/005_add_ml_columns_to_weather_cache.sql") as f:
    sql = f.read()

cur.execute(sql)
print("Migration 005 applied successfully")

# Verify
cur.execute(
    "SELECT column_name FROM information_schema.columns "
    "WHERE table_name = 'weather_cache' ORDER BY ordinal_position"
)
cols = [r[0] for r in cur.fetchall()]
print(f"weather_cache now has {len(cols)} columns:")
for c in cols:
    print(f"  - {c}")
conn.close()
