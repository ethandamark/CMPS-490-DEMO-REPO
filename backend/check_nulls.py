"""Check which columns are null in weather_cache rows."""
import psycopg2
import os
from dotenv import load_dotenv

load_dotenv()
conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
cur = conn.cursor()
cur.execute("SELECT * FROM weather_cache LIMIT 1")
colnames = [desc[0] for desc in cur.description]
row = cur.fetchone()
if row:
    print("=== weather_cache row ===")
    for col, val in zip(colnames, row):
        status = "NULL" if val is None else f"{val}"
        print(f"  {col:30s}  {status}")
else:
    print("No rows in weather_cache")

print()
cur.execute("SELECT * FROM offline_weather_snapshot LIMIT 1")
colnames = [desc[0] for desc in cur.description]
row = cur.fetchone()
if row:
    print("=== offline_weather_snapshot row ===")
    for col, val in zip(colnames, row):
        status = "NULL" if val is None else f"{val}"
        print(f"  {col:30s}  {status}")
conn.close()
