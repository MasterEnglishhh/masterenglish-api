-- M01 baseline revised on 2026-10-25: a user may have multiple roles.
-- UUID aggregate keys; small, migration-owned role catalogue.
CREATE TABLE roles (
    id SMALLINT PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT ck_roles_code CHECK (code IN ('LEARNER', 'CONTENT_ADMIN', 'SYSTEM_ADMIN'))
);

INSERT INTO roles (id, code, name) VALUES
    (1, 'LEARNER', 'Learner'),
    (2, 'CONTENT_ADMIN', 'Content administrator'),
    (3, 'SYSTEM_ADMIN', 'System administrator');

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    phone VARCHAR(30),
    -- The FK is added with the M08 media migration; profile uploads depend on M08.
    avatar_media_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    auth_version INTEGER NOT NULL DEFAULT 0,
    password_changed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_users_email_trimmed CHECK (email = btrim(email) AND email <> ''),
    CONSTRAINT ck_users_display_name CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_users_phone CHECK (phone IS NULL OR (phone = btrim(phone) AND phone <> '')),
    CONSTRAINT ck_users_password_hash CHECK (password_hash <> ''),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_users_auth_version CHECK (auth_version >= 0),
    CONSTRAINT ck_users_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE INDEX idx_users_status_created ON users (status, created_at DESC);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES roles(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role_user ON user_roles (role_id, user_id);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    device_label VARCHAR(120),
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(40),
    CONSTRAINT ck_auth_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_sessions_revoke_reason CHECK (
        revoke_reason IS NULL OR revoke_reason IN
        ('LOGOUT', 'PASSWORD_RESET', 'PASSWORD_CHANGE', 'SECURITY', 'ROLE_CHANGE', 'ACCOUNT_LOCKED', 'EXPIRED')
    ),
    CONSTRAINT ck_auth_sessions_revocation CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    )
);

CREATE INDEX idx_auth_sessions_user_revoked ON auth_sessions (user_id, revoked_at);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions (expires_at);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_id UUID UNIQUE,
    last_used_at TIMESTAMPTZ,
    CONSTRAINT uq_refresh_tokens_session_id UNIQUE (session_id, id),
    CONSTRAINT ck_refresh_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_tokens_replacement CHECK (
        replaced_by_id IS NULL OR (replaced_by_id <> id AND revoked_at IS NOT NULL)
    ),
    -- Rotation revokes the old row before inserting the new row. Check the
    -- replacement reference at transaction commit, and keep it in this session.
    CONSTRAINT fk_refresh_tokens_replacement
        FOREIGN KEY (session_id, replaced_by_id)
        REFERENCES refresh_tokens (session_id, id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX uq_refresh_tokens_one_unrevoked
    ON refresh_tokens (session_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_session_issued ON refresh_tokens (session_id, issued_at DESC);
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens (expires_at);

CREATE TABLE password_reset_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    otp_hmac VARCHAR(64) NOT NULL,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    reset_token_hash VARCHAR(64) UNIQUE,
    reset_token_expires_at TIMESTAMPTZ,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_reset_challenges_hmac CHECK (otp_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reset_challenges_hash CHECK (
        reset_token_hash IS NULL OR reset_token_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_reset_challenges_attempts CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_reset_challenges_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_reset_challenges_grant CHECK (
        (verified_at IS NULL AND reset_token_hash IS NULL AND reset_token_expires_at IS NULL)
        OR (verified_at IS NOT NULL AND reset_token_hash IS NOT NULL AND reset_token_expires_at IS NOT NULL
            AND reset_token_expires_at > verified_at AND attempt_count < 5)
    ),
    CONSTRAINT ck_reset_challenges_consumed CHECK (
        consumed_at IS NULL OR (verified_at IS NOT NULL AND consumed_at >= verified_at)
    )
);

CREATE UNIQUE INDEX uq_reset_challenges_one_open
    ON password_reset_challenges (user_id)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX idx_reset_challenges_user_created
    ON password_reset_challenges (user_id, created_at DESC);
CREATE INDEX idx_reset_challenges_expiry ON password_reset_challenges (expires_at);

CREATE TABLE learning_goals (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    target_score SMALLINT NOT NULL,
    daily_minutes INTEGER NOT NULL,
    target_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_learning_goals_score CHECK (target_score BETWEEN 10 AND 990 AND target_score % 5 = 0),
    CONSTRAINT ck_learning_goals_minutes CHECK (daily_minutes BETWEEN 5 AND 240)
);
