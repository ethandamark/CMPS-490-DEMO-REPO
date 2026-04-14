-- PostgreSQL enum types based on the ER diagram
CREATE TYPE status_enum AS ENUM ('active', 'inactive');
CREATE TYPE platform_enum AS ENUM ('android', 'ios');
CREATE TYPE weather_condition_enum AS ENUM ('rain', 'clean');
CREATE TYPE result_type_enum AS ENUM ('storm', 'flood');
CREATE TYPE alert_type_enum AS ENUM ('storm', 'flood');
CREATE TYPE delivery_status_enum AS ENUM ('pending', 'sent', 'failed');

--------------------------------------------------
-- 1. Anonymous User Class
--------------------------------------------------
CREATE TABLE anonymous_user (
    anon_user_id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    last_active_at TIMESTAMP,
    notification_opt_in BOOLEAN,
    status status_enum
);

--------------------------------------------------
-- 2. Device Class
-- 1 to 1 with anonymous_user via UNIQUE anon_user_id
--------------------------------------------------
CREATE TABLE device (
    device_id UUID PRIMARY KEY,
    anon_user_id UUID UNIQUE REFERENCES anonymous_user(anon_user_id),
    alert_token UUID UNIQUE DEFAULT gen_random_uuid(),
    device_token TEXT,
    platform platform_enum,
    app_version VARCHAR(50),
    location_permission_status BOOLEAN,
    last_seen_at TIMESTAMP,
    created_at TIMESTAMP
);

--------------------------------------------------
-- 3. Device Location Class
--------------------------------------------------
CREATE TABLE device_location (
    location_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    captured_at TIMESTAMP
);

--------------------------------------------------
-- 4. Weather Cache Class
--------------------------------------------------
CREATE TABLE weather_cache (
    cache_id UUID PRIMARY KEY,
    temp DECIMAL(5,2),
    humidity DECIMAL(5,2),
    wind_speed DECIMAL(6,2),
    wind_direction DECIMAL(5,2),
    precipitation_amount DECIMAL(6,2),
    pressure DECIMAL(7,2),
    weather_condition weather_condition_enum,
    recorded_at TIMESTAMP,
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    result_level INT CHECK (result_level BETWEEN 0 AND 5),
    result_type result_type_enum
);

--------------------------------------------------
-- 5. Model Instance Class
--------------------------------------------------
CREATE TABLE model_instance (
    instance_id UUID PRIMARY KEY,
    version VARCHAR(50),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    result_level INT CHECK (result_level BETWEEN 0 AND 5),
    result_type result_type_enum,
    confidence_score DECIMAL(5,4),
    created_at TIMESTAMP
);

--------------------------------------------------
-- 6. Alert Event Class
-- The diagram labels alert_id and instance_id as STRING.
-- For PostgreSQL + FK compatibility with model_instance(instance_id),
-- UUID is used here.
--------------------------------------------------
CREATE TABLE alert_event (
    alert_id UUID PRIMARY KEY,
    instance_id UUID REFERENCES model_instance(instance_id),
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    alert_type alert_type_enum,
    severity_level INT CHECK (severity_level BETWEEN 0 AND 5),
    created_at TIMESTAMP,
    expires_at TIMESTAMP
);

--------------------------------------------------
-- 7. Device Alert Class
--------------------------------------------------
CREATE TABLE device_alert (
    device_alert_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    alert_id UUID REFERENCES alert_event(alert_id),
    delivery_status delivery_status_enum,
    sent_at TIMESTAMP,
    UNIQUE (device_id, alert_id)
);

--------------------------------------------------
-- 8. Offline Weather Snapshot Class
--------------------------------------------------
CREATE TABLE offline_weather_snapshot (
    offline_weather_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    cache_id UUID REFERENCES weather_cache(cache_id),
    synced_at TIMESTAMP,
    is_current BOOLEAN
);

--------------------------------------------------
-- 9. Offline Alert Snapshot Class
--------------------------------------------------
CREATE TABLE offline_alert_snapshot (
    offline_alert_id UUID PRIMARY KEY,
    device_id UUID REFERENCES device(device_id),
    alert_id UUID REFERENCES alert_event(alert_id),
    synced_at TIMESTAMP,
    is_current BOOLEAN
);
