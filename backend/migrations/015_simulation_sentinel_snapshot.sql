-- Migration 015: Insert a sentinel snapshot row for simulation data
-- Model instances from storm simulations FK to this instead of real snapshots.

INSERT INTO offline_weather_snapshot (
    offline_weather_id,
    device_id,
    synced_at,
    is_current,
    weather_data,
    snapshot_type
) VALUES (
    '00000000-0000-0000-0000-000000000000',
    NULL,
    NULL,
    FALSE,
    '[]'::jsonb,
    'seed'
) ON CONFLICT (offline_weather_id) DO NOTHING;
