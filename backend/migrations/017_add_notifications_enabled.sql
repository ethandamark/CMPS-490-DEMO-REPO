-- 017: Add notifications_enabled column to device table
ALTER TABLE device
    ADD COLUMN IF NOT EXISTS notifications_enabled BOOLEAN DEFAULT FALSE;
