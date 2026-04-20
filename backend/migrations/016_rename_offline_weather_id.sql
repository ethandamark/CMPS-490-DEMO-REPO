-- 016: Rename offline_weather_id → weather_id in both tables
ALTER TABLE offline_weather_snapshot
    RENAME COLUMN offline_weather_id TO weather_id;

ALTER TABLE model_instance
    RENAME COLUMN offline_weather_id TO weather_id;
