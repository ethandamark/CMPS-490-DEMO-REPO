# Database Documentation

## Setup

### Docker Setup

1. Download Docker Desktop from the [Docker website](https://www.docker.com/products/docker-desktop) and install it.
2. Once installed, go to settings and under **General** ensure that **"Expose daemon on tcp://localhost:2375 without TLS"** is enabled.

### Supabase Setup

1. Download supabase globally on your personal dev machine.
   - **Windows:** `npm install -g supabase` or `choco install supabase` or `scoop install supabase`
   - **Linux:** `npm install -g supabase` or `brew install supabase`
   - **MacOS:** `brew install supabase` or `npm install -g supabase`

2. Set the download path as an environment variable on your machine (if not using npm or brew).
   - **Windows:**
     - Find the file path of your supabase download
     - Go to system settings and click **"Advanced System Settings"**
     - Click **"Environment Variables"**
     - Add a new user variable
     - Add the supabase download path to the PATH variable and save (press OK)
   - **Linux:**
     - Open terminal and edit your shell profile (`~/.bashrc`, `~/.bash_profile`, or `~/.zshrc`)
     - Add the line: `export PATH="$PATH:/path/to/supabase"`
     - Run `source ~/.bashrc` (or appropriate profile file) to apply changes
   - **MacOS:**
     - Open terminal and edit your shell profile (`~/.zshrc` or `~/.bash_profile`)
     - Add the line: `export PATH="$PATH:/path/to/supabase"`
     - Run `source ~/.zshrc` (or appropriate profile file) to apply changes

3. Return to the project, open a terminal, and run: `supabase init`
4. After initialization is complete, run: `supabase start`

## Database Schema

The app uses two storage layers: **Supabase** (cloud, permanent record) and **Room DB** (Android local, 24 h rolling window pruned on each location update).

### Enum Types

| Enum Name | Values |
|-----------|--------|
| `status_enum` | 'active', 'inactive' |
| `snapshot_type` | enum — identifies the type of an offline weather snapshot |

---

## Supabase Tables (Cloud)

Tables are related left → right: `anonymous_user` → `device` → `device_location` / `offline_weather_snapshot` → `model_instance`

#### 1. anonymous_user

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `id` | UUID | PRIMARY KEY |
| `created_at` | TIMESTAMPTZ | NOT NULL |
| `last_active_at` | TIMESTAMPTZ | |
| `status` | status_enum | DEFAULT 'active' |

> Marked inactive by `pg_cron` job `deactivate_stale_accounts()` daily at 3 AM UTC for accounts inactive > 30 days.

#### 2. device

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `device_id` | TEXT | PRIMARY KEY |
| `user_id` | UUID | FK → anonymous_user |
| `platform` | TEXT | |
| `app_version` | VARCHAR(50) | |
| `notifications_enabled` | BOOLEAN | DEFAULT false |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

#### 3. device_location

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `id` | BIGSERIAL | PRIMARY KEY |
| `device_id` | TEXT | FK → device, **UNIQUE** |
| `latitude` | DECIMAL(9,6) | |
| `longitude` | DECIMAL(9,6) | |
| `updated_at` | TIMESTAMPTZ | |

> Enforces a 1:1 relationship with `device` via `UNIQUE(device_id)` (migration 020). The backend uses UPSERT — at most one row per device at all times.

#### 4. offline_weather_snapshot

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `id` | BIGSERIAL | PRIMARY KEY |
| `device_id` | TEXT | FK → device |
| `snapshot_time` | TIMESTAMPTZ | |
| `latitude` | DECIMAL(9,6) | |
| `longitude` | DECIMAL(9,6) | |
| `weather_data` | JSONB | |
| `snapshot_type` | snapshot_type | |
| `is_simulation_sentinel` | BOOLEAN | |

#### 5. model_instance

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `id` | BIGSERIAL | PRIMARY KEY |
| `snapshot_id` | BIGINT | FK → offline_weather_snapshot |
| `device_id` | TEXT | FK → device |
| `model_version` | VARCHAR(50) | |
| `prediction_score` | DECIMAL(5,4) | |
| `feature_vector` | JSONB | |
| `predicted_at` | TIMESTAMPTZ | |

---

## Room DB Tables (Android Local)

All four tables are pruned on every location update — rows older than 24 h are deleted. Backfill queries are capped at 24 records.

#### 1. weather_cache

| Attribute | Notes |
|-----------|-------|
| `id` | PRIMARY KEY |
| `device_id` | |
| `timestamp_ms` | |
| `latitude`, `longitude` | |
| `temp_c` | Surface observation |
| `pressure_hPa` | Surface observation |
| `humidity_pct` | Surface observation |
| `wind_speed_kmh` | Surface observation |
| `precip_mm` | Surface observation |
| `cape`, `cin`, `pwat`, `srh03`, `li`, `lcl` | NWP features from Open-Meteo |
| `mrms_max_dbz_75km` | Reserved — null |

#### 2. offline_weather_snapshot

| Attribute | Notes |
|-----------|-------|
| `id` | PRIMARY KEY |
| `device_id` | |
| `snapshot_time_ms` | |
| `latitude`, `longitude` | |
| `weather_data` | JSON string |
| `is_synced` | Set to `true` after successful POST to backend |

#### 3. model_instance

| Attribute | Notes |
|-----------|-------|
| `id` | PRIMARY KEY |
| `snapshot_id` | FK → offline_weather_snapshot |
| `device_id` | |
| `model_version` | |
| `prediction_score` | |
| `feature_vector` | JSON string |
| `predicted_at_ms` | |
| `is_synced` | Set to `true` after successful POST to backend |

#### 4. hourly_prediction

Local-only timeline used to render the storm risk chart in the UI. Not synced to Supabase. Pruned > 24 h.