"""
Apply database migrations to local Supabase instance
"""
import psycopg2
import os

# Connect to local Supabase PostgreSQL
try:
    conn = psycopg2.connect(
        host="127.0.0.1",
        port=54322,
        database="postgres",
        user="postgres",
        password="postgres"
    )
    cur = conn.cursor()
    
    # Read migration file
    migration_path = os.path.join(os.path.dirname(__file__), "migrations", "001_add_device_token_column.sql")
    with open(migration_path, "r") as f:
        migration_sql = f.read()
    
    # Execute migration
    try:
        cur.execute(migration_sql)
        conn.commit()
        print("✓ Migration applied successfully!")
        print("✓ device_token column added to device table")
    except psycopg2.errors.DuplicateColumn as e:
        print(f"⚠ Column already exists (OK): {e}")
        conn.rollback()
    except Exception as e:
        print(f"✗ Migration error: {e}")
        conn.rollback()
    finally:
        cur.close()
        conn.close()
except Exception as e:
    print(f"✗ Database connection error: {e}")
