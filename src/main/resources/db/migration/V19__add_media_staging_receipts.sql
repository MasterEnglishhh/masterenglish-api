-- V16: Media upload staging claim and receipt columns

ALTER TABLE media_upload_requests
    ADD COLUMN put_claim_token UUID,
    ADD COLUMN put_claimed_at TIMESTAMPTZ,
    ADD COLUMN staged_size_bytes BIGINT,
    ADD COLUMN staged_sha256 VARCHAR(64),
    ADD COLUMN staged_at TIMESTAMPTZ;

ALTER TABLE media_upload_requests
    ADD CONSTRAINT media_upload_requests_c13
        CHECK ((staged_size_bytes IS NULL) = (staged_sha256 IS NULL) AND (staged_size_bytes IS NULL) = (staged_at IS NULL)),
    ADD CONSTRAINT media_upload_requests_c14
        CHECK (staged_size_bytes IS NULL OR staged_size_bytes = declared_size_bytes),
    ADD CONSTRAINT media_upload_requests_c15
        CHECK (staged_sha256 IS NULL OR staged_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT media_upload_requests_c16
        CHECK ((put_claim_token IS NULL) = (put_claimed_at IS NULL)),
    ADD CONSTRAINT media_upload_requests_c17
        CHECK (staged_at IS NULL OR put_claim_token IS NOT NULL);
