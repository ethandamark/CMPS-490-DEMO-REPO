-- ================================================================
-- Migration 006: Remove Firebase device_token Column
-- ================================================================
-- This migration drops the device_token column from the device table.
--
-- Rationale: Firebase Cloud Messaging (FCM) is being fully deprecated.
-- All notifications are now handled locally on the Android device using
-- Android's NotificationManager instead of FCM push notifications.
-- The device_token column (used to store FCM registration tokens) is
-- no longer needed.
--
-- Safety: This is a destructive migration. Ensure all dependent code
-- that references device_token has been updated before applying.
-- ================================================================

ALTER TABLE device
    DROP COLUMN IF EXISTS device_token;
