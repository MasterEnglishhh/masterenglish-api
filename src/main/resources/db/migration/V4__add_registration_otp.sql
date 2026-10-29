-- Registration credentials belong to a request until its own OTP is verified.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE otp_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    purpose VARCHAR(24) NOT NULL,
    otp_hmac VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    blocked_until TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_otp_context UNIQUE (user_id, purpose, id),
    CONSTRAINT ck_otp_purpose CHECK (purpose IN ('EMAIL_VERIFICATION','PASSWORD_CHANGE','PASSWORD_RESET')),
    CONSTRAINT ck_otp_hash CHECK (otp_hmac IS NULL OR otp_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_otp_attempts CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_otp_expiry CHECK (expires_at > created_at AND expires_at <= last_sent_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_otp_closed CHECK (NOT (consumed_at IS NOT NULL AND invalidated_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_otp_open ON otp_challenges(user_id, purpose)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_otp_expiry ON otp_challenges(expires_at);

CREATE TABLE registration_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    otp_challenge_id UUID NOT NULL UNIQUE,
    otp_purpose VARCHAR(24) GENERATED ALWAYS AS ('EMAIL_VERIFICATION'::VARCHAR) STORED,
    pending_first_name VARCHAR(30) NOT NULL,
    pending_last_name VARCHAR(30) NOT NULL,
    pending_password_hash VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_registration_otp FOREIGN KEY (user_id, otp_purpose, otp_challenge_id)
        REFERENCES otp_challenges(user_id, purpose, id),
    CONSTRAINT ck_registration_hash CHECK (pending_password_hash IS NULL OR
        pending_password_hash ~ '^(\{bcrypt\})?\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$'),
    CONSTRAINT ck_registration_secret CHECK (completed_at IS NOT NULL OR invalidated_at IS NOT NULL OR pending_password_hash IS NOT NULL),
    CONSTRAINT ck_registration_closed CHECK (NOT (completed_at IS NOT NULL AND invalidated_at IS NOT NULL)),
    CONSTRAINT ck_registration_expiry CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days'),
    CONSTRAINT ck_registration_first_name CHECK (pending_first_name = btrim(pending_first_name) AND pending_first_name <> ''),
    CONSTRAINT ck_registration_last_name CHECK (pending_last_name = btrim(pending_last_name) AND pending_last_name <> '')
);
CREATE UNIQUE INDEX uq_registration_requests_open ON registration_requests(user_id)
    WHERE completed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_registration_requests_expiry ON registration_requests(expires_at);
