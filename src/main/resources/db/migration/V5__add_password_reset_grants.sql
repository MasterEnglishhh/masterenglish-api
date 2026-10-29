CREATE TABLE password_reset_grants (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    otp_challenge_id UUID NOT NULL UNIQUE,
    otp_purpose VARCHAR(24) GENERATED ALWAYS AS ('PASSWORD_RESET'::VARCHAR) STORED,
    grant_hash VARCHAR(64) UNIQUE,
    auth_version_at_issue INTEGER NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT fk_reset_grant_otp FOREIGN KEY (user_id, otp_purpose, otp_challenge_id)
        REFERENCES otp_challenges(user_id, purpose, id),
    CONSTRAINT ck_reset_grant_hash CHECK (grant_hash IS NULL OR grant_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reset_grant_secret CHECK (used_at IS NOT NULL OR invalidated_at IS NOT NULL OR grant_hash IS NOT NULL),
    CONSTRAINT ck_reset_grant_expiry CHECK (expires_at > issued_at AND expires_at <= issued_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_reset_grant_version CHECK (auth_version_at_issue >= 0),
    CONSTRAINT ck_reset_grant_closed CHECK (NOT (used_at IS NOT NULL AND invalidated_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_reset_grant_open ON password_reset_grants(user_id)
    WHERE used_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_reset_grant_expiry ON password_reset_grants(expires_at);

CREATE FUNCTION guard_password_reset_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM otp_challenges c
        WHERE c.id=NEW.otp_challenge_id AND c.user_id=NEW.user_id
          AND c.purpose='PASSWORD_RESET' AND c.consumed_at IS NOT NULL AND c.invalidated_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Reset grant requires a consumed PASSWORD_RESET challenge' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_password_reset_grant BEFORE INSERT OR UPDATE ON password_reset_grants
    FOR EACH ROW EXECUTE FUNCTION guard_password_reset_grant();
