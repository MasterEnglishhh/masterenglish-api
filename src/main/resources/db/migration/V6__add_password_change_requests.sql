ALTER TABLE auth_sessions ADD CONSTRAINT uq_auth_session_owner UNIQUE (user_id, id);

CREATE TABLE password_change_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    session_id UUID NOT NULL,
    otp_challenge_id UUID NOT NULL UNIQUE,
    otp_purpose VARCHAR(24) GENERATED ALWAYS AS ('PASSWORD_CHANGE'::VARCHAR) STORED,
    pending_password_hash VARCHAR(255),
    auth_version_at_request INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT fk_password_change_session FOREIGN KEY (user_id, session_id)
        REFERENCES auth_sessions(user_id, id),
    CONSTRAINT fk_password_change_otp FOREIGN KEY (user_id, otp_purpose, otp_challenge_id)
        REFERENCES otp_challenges(user_id, purpose, id),
    CONSTRAINT ck_password_change_version CHECK (auth_version_at_request >= 0),
    CONSTRAINT ck_password_change_expiry CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_password_change_closed CHECK (NOT (completed_at IS NOT NULL AND invalidated_at IS NOT NULL)),
    CONSTRAINT ck_password_change_secret CHECK ((pending_password_hash IS NOT NULL) = (completed_at IS NULL AND invalidated_at IS NULL))
);

CREATE UNIQUE INDEX uq_password_change_open ON password_change_requests(user_id)
    WHERE completed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_password_change_expiry ON password_change_requests(expires_at);
