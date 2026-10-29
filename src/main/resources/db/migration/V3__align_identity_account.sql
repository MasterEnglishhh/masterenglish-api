-- V3: Align identity account schema with approved baseline

-- 1. Update users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

-- Backfill first_name and last_name from display_name
UPDATE users
SET first_name = COALESCE(
        first_name,
        SUBSTRING(
            CASE
                WHEN position(' ' in btrim(display_name)) > 0
                THEN substring(btrim(display_name) from position(' ' in btrim(display_name)) + 1)
                ELSE btrim(display_name)
            END FROM 1 FOR 30
        )
    ),
    last_name = COALESCE(
        last_name,
        SUBSTRING(
            CASE
                WHEN position(' ' in btrim(display_name)) > 0
                THEN substring(btrim(display_name) from 1 for position(' ' in btrim(display_name)) - 1)
                ELSE btrim(display_name)
            END FROM 1 FOR 30
        )
    )
WHERE first_name IS NULL OR last_name IS NULL;

-- Backfill email_verified_at for ACTIVE accounts
UPDATE users
SET email_verified_at = created_at
WHERE status = 'ACTIVE' AND email_verified_at IS NULL;

-- Make display_name nullable
ALTER TABLE users ALTER COLUMN display_name DROP NOT NULL;
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_display_name;
ALTER TABLE users ADD CONSTRAINT ck_users_display_name CHECK (display_name IS NULL OR btrim(display_name) <> '');

-- Update status check constraint and default
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_status;
ALTER TABLE users ADD CONSTRAINT ck_users_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'DISABLED'));
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'PENDING_VERIFICATION';

-- Add constraints on users
ALTER TABLE users ADD CONSTRAINT ck_users_first_name
    CHECK (first_name IS NULL OR (first_name = btrim(first_name) AND first_name <> ''));

ALTER TABLE users ADD CONSTRAINT ck_users_last_name
    CHECK (last_name IS NULL OR (last_name = btrim(last_name) AND last_name <> ''));

ALTER TABLE users ADD CONSTRAINT ck_users_active_verified
    CHECK (status <> 'ACTIVE' OR email_verified_at IS NOT NULL);

ALTER TABLE users ADD CONSTRAINT ck_users_pending_unverified
    CHECK (status <> 'PENDING_VERIFICATION' OR email_verified_at IS NULL);

ALTER TABLE users ADD CONSTRAINT ck_users_locked
    CHECK ((status = 'LOCKED') = (locked_until IS NOT NULL));

ALTER TABLE users ADD CONSTRAINT ck_users_verified_profile
    CHECK (email_verified_at IS NULL OR (first_name IS NOT NULL AND last_name IS NOT NULL AND password_hash IS NOT NULL));

-- 2. Update auth_sessions table
ALTER TABLE auth_sessions ADD COLUMN IF NOT EXISTS ip_address INET;
ALTER TABLE auth_sessions DROP COLUMN IF EXISTS device_label;

ALTER TABLE auth_sessions DROP CONSTRAINT IF EXISTS ck_auth_sessions_revoke_reason;
ALTER TABLE auth_sessions ADD CONSTRAINT ck_auth_sessions_revoke_reason CHECK (
    revoke_reason IS NULL OR revoke_reason IN (
        'LOGOUT', 'PASSWORD_RESET', 'PASSWORD_CHANGE', 'SECURITY',
        'ROLE_CHANGE', 'ACCOUNT_LOCKED', 'ACCOUNT_DISABLED', 'SESSION_LIMIT', 'EXPIRED'
    )
);

ALTER TABLE auth_sessions DROP CONSTRAINT IF EXISTS ck_auth_sessions_expiry;
ALTER TABLE auth_sessions ADD CONSTRAINT ck_auth_sessions_expiry
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days');

-- 3. Create rate limiting tables
CREATE TABLE auth_rate_limits (
    id UUID PRIMARY KEY,
    scope VARCHAR(64) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    blocked_until TIMESTAMPTZ,
    reset_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_auth_rate_limits_scope_subject UNIQUE (scope, subject_hash),
    CONSTRAINT ck_auth_rate_limits_subject_hash CHECK (subject_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_rate_limits_scope CHECK (scope IN (
        'OTP_SEND_EMAIL', 'LOGIN_FAILURE_EMAIL', 'OTP_VERIFY_EMAIL_VERIFICATION',
        'OTP_VERIFY_PASSWORD_CHANGE', 'OTP_VERIFY_PASSWORD_RESET',
        'OTP_SEND_IP', 'OTP_VERIFY_IP', 'LOGIN_IP'
    ))
);

CREATE TABLE auth_rate_limit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rate_limit_id UUID NOT NULL REFERENCES auth_rate_limits(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_auth_rate_limit_events_window ON auth_rate_limit_events (rate_limit_id, occurred_at DESC);
