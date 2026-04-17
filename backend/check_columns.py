import psycopg2, os
from dotenv import load_dotenv
load_dotenv()
conn = psycopg2.connect(os.getenv("SUPABASE_DB_URL"))
cur = conn.cursor()
cur.execute("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'weather_cache' ORDER BY ordinal_position")
for r in cur.fetchall():
    print(f"  {r[0]:30s}  {r[1]}")
print()
cur.execute("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'offline_weather_snapshot' ORDER BY ordinal_position")
for r in cur.fetchall():
    print(f"  {r[0]:30s}  {r[1]}")
conn.close()
