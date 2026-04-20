-- ================================================================
-- Migration 009: Clean up anonymous_user and device columns
-- ================================================================
-- 1. Remove notification_opt_in from anonymous_user (notifications_enabled
--    on device table handles this).
-- 2. Remove last_seen_at and created_at from device (anonymous_user's
--    last_active_at and created_at carry this instead).
-- 3. Add a scheduled deactivation function: accounts with last_active_at
--    older than 30 days are set to 'inactive'.
-- ================================================================

-- 1. Drop notification_opt_in from anonymous_user
ALTER TABLE anonymous_user DROP COLUMN IF EXISTS notification_opt_in;

-- 2. Drop last_seen_at and created_at from device
ALTER TABLE device DROP COLUMN IF EXISTS last_seen_at;
ALTER TABLE device DROP COLUMN IF EXISTS created_at;

-- 3. Set default for status on anonymous_user
ALTER TABLE anonymous_user ALTER COLUMN status SET DEFAULT 'active';

-- 4. Create function to auto-deactivate stale accounts (>30 days)
CREATE OR REPLACE FUNCTION deactivate_stale_accounts()
RETURNS void AS $$
BEGIN
    UPDATE anonymous_user
    SET status = 'inactive'
    WHERE last_active_at < NOW() - INTERVAL '30 days'
      AND status = 'active';
END;
$$ LANGUAGE plpgsql;

-- 5. Schedule via pg_cron if available, otherwise run manually.
--    On local Supabase pg_cron may not be enabled, so wrap in a DO block.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        PERFORM cron.schedule(
            'deactivate-stale-accounts',
            '0 3 * * *',  -- daily at 3 AM UTC
            'SELECT deactivate_stale_accounts()'
        );
    END IF;
END $$;
