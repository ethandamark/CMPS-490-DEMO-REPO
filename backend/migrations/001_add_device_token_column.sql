-- Migration: Add device_token column to device table
-- Purpose: Split alert_token and device_token for cleaner architecture
--   - alert_token: Server-generated UUID for internal identification
--   - device_token: Firebase Cloud Messaging token for push notifications

-- Step 1: Add device_token column (nullable for backward compatibility)
ALTER TABLE device ADD COLUMN IF NOT EXISTS device_token TEXT;

-- Step 2: Migrate existing FCM tokens from alert_token to device_token
-- If alert_token contains ":" (FCM token format), move it to device_token
UPDATE device 
SET device_token = alert_token,
    alert_token = NULL
WHERE alert_token IS NOT NULL 
  AND alert_token LIKE '%:%';  -- FCM tokens contain colons

-- Step 3: For remaining alert_tokens that are alphanumeric, keep them as is
-- (these will eventually be converted to UUIDs in a separate step)

-- Create index on device_token for faster FCM lookups
CREATE INDEX IF NOT EXISTS idx_device_token ON device(device_token);

-- Create index on alert_token for faster device lookups  
CREATE INDEX IF NOT EXISTS idx_alert_token ON device(alert_token);
