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

### Enum Types

| Enum Name | Values |
|-----------|--------|
| `status_enum` | 'active', 'inactive' |
| `platform_enum` | 'android', 'ios' |
| `weather_condition_enum` | 'rain', 'clean' |
| `alert_type_enum` | 'storm', 'flood' |
| `delivery_status_enum` | 'pending', 'sent', 'failed' |

### Tables

#### 1. anonymous_user

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `anon_user_id` | UUID | PRIMARY KEY |
| `created_at` | TIMESTAMP | NOT NULL |
| `last_active_at` | TIMESTAMP | |
| `status` | status_enum | DEFAULT 'active' |

#### 2. device

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `device_id` | UUID | PRIMARY KEY |
| `anon_user_id` | UUID | UNIQUE, FK → anonymous_user |
| `alert_token` | TEXT | UNIQUE |
| `platform` | platform_enum | |
| `app_version` | VARCHAR(50) | |
| `location_permission_status` | BOOLEAN | |
| `notifications_enabled` | BOOLEAN | DEFAULT false |

#### 3. device_location

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `location_id` | UUID | PRIMARY KEY |
| `device_id` | UUID | FK → device |
| `latitude` | DECIMAL(9,6) | |
| `longitude` | DECIMAL(9,6) | |
| `captured_at` | TIMESTAMP | |

#### 4. weather_cache

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `cache_id` | UUID | PRIMARY KEY |
| `temp` | DECIMAL(5,2) | |
| `humidity` | DECIMAL(5,2) | |
| `wind_speed` | DECIMAL(6,2) | |
| `wind_direction` | DECIMAL(5,2) | |
| `precipitation_amount` | DECIMAL(6,2) | |
| `pressure` | DECIMAL(7,2) | |
| `weather_condition` | weather_condition_enum | |
| `recorded_at` | TIMESTAMP | |
| `latitude` | DECIMAL(9,6) | |
| `longitude` | DECIMAL(9,6) | |
| `result_level` | INT | CHECK 0-5 |
| `result_type` | TEXT | CHECK IN ('storm', 'clear') |

#### 5. model_instance

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `instance_id` | UUID | PRIMARY KEY |
| `version` | VARCHAR(50) | |
| `latitude` | DECIMAL(9,6) | |
| `longitude` | DECIMAL(9,6) | |
| `result_level` | INT | CHECK 0-5 |
| `result_type` | TEXT | CHECK IN ('storm', 'clear') |
| `confidence_score` | DECIMAL(5,4) | |
| `created_at` | TIMESTAMP | |

#### 6. alert_event

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `alert_id` | UUID | PRIMARY KEY |
| `instance_id` | UUID | FK → model_instance |
| `latitude` | DECIMAL(9,6) | |
| `longitude` | DECIMAL(9,6) | |
| `alert_type` | alert_type_enum | |
| `severity_level` | INT | CHECK 0-5 |
| `created_at` | TIMESTAMP | |
| `expires_at` | TIMESTAMP | |

#### 7. device_alert

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `device_alert_id` | UUID | PRIMARY KEY |
| `device_id` | UUID | FK → device |
| `alert_id` | UUID | FK → alert_event |
| `delivery_status` | delivery_status_enum | |
| `sent_at` | TIMESTAMP | |

#### 8. offline_weather_snapshot

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `weather_id` | UUID | PRIMARY KEY |
| `device_id` | UUID | FK → device |
| `cache_id` | UUID | FK → weather_cache |
| `synced_at` | TIMESTAMP | |
| `is_current` | BOOLEAN | |

#### 9. offline_alert_snapshot

| Attribute | Type | Constraints |
|-----------|------|-------------|
| `offline_alert_id` | UUID | PRIMARY KEY |
| `device_id` | UUID | FK → device |
| `alert_id` | UUID | FK → alert_event |
| `synced_at` | TIMESTAMP | |
| `is_current` | BOOLEAN | |