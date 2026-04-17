"""Clear test data and verify clean state."""
import psycopg2
import os
from dotenv import load_dotenv

load_dotenv()
conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
conn.autocommit = True
cur = conn.cursor()
cur.execute("DELETE FROM offline_weather_snapshot")
print(f"Deleted offline_weather_snapshot rows")
cur.execute("DELETE FROM weather_cache")
print(f"Deleted weather_cache rows")
cur.execute("SELECT count(*) FROM weather_cache")
print(f"weather_cache: {cur.fetchone()[0]} rows")
cur.execute("SELECT count(*) FROM offline_weather_snapshot")
print(f"offline_weather_snapshot: {cur.fetchone()[0]} rows")
conn.close()
