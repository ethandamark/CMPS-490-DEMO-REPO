-- Add unique constraint to device_alert to prevent duplicate alert-device pairings
ALTER TABLE device_alert ADD CONSTRAINT unique_device_alert UNIQUE (device_id, alert_id);
