-- Migration: 202611150002_add_username_to_profiles.sql
-- Add unique username support for university-wide messaging and mentions

ALTER TABLE classmate.profiles ADD COLUMN IF NOT EXISTS username text;
CREATE UNIQUE INDEX IF NOT EXISTS idx_classmate_profiles_username_lower ON classmate.profiles (lower(username)) WHERE username IS NOT NULL;
