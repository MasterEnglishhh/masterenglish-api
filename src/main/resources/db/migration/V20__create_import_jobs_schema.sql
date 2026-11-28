-- V17: Create import jobs and import job rows schema for asynchronous content importing and DryRun ledger

CREATE TABLE import_jobs (
    id UUID PRIMARY KEY,
    created_by UUID NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    import_type VARCHAR(24) NOT NULL,
    import_namespace VARCHAR(120) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    input_bucket VARCHAR(100) NOT NULL,
    input_object_key VARCHAR(500) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    input_sha256 VARCHAR(64),
    upload_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'UPLOADING',
    total_rows INTEGER NOT NULL DEFAULT 0,
    success_rows INTEGER NOT NULL DEFAULT 0,
    failed_rows INTEGER NOT NULL DEFAULT 0,
    skipped_rows INTEGER NOT NULL DEFAULT 0,
    confirmed_at TIMESTAMPTZ,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_import_jobs_creator_request UNIQUE (created_by, request_id),
    CONSTRAINT uk_import_jobs_input_object UNIQUE (input_bucket, input_object_key),
    CONSTRAINT import_jobs_c1 CHECK (import_type IN ('VOCABULARY_CSV', 'QUESTIONS_JSON', 'GRAMMAR_JSON')),
    CONSTRAINT import_jobs_c2 CHECK (status IN ('UPLOADING', 'DRY_RUN', 'READY', 'PROCESSING', 'COMPLETED', 'PARTIAL_SUCCESS', 'FAILED', 'EXPIRED')),
    CONSTRAINT import_jobs_c3 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT import_jobs_c4 CHECK (input_sha256 IS NULL OR input_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT import_jobs_c5 CHECK (declared_size_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT import_jobs_c6 CHECK (total_rows BETWEEN 0 AND 5000 AND success_rows >= 0 AND failed_rows >= 0 AND skipped_rows >= 0 AND success_rows + failed_rows + skipped_rows <= total_rows),
    CONSTRAINT import_jobs_c7 CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT import_jobs_c8 CHECK ((status IN ('COMPLETED', 'PARTIAL_SUCCESS', 'FAILED', 'EXPIRED')) = (finished_at IS NOT NULL)),
    CONSTRAINT import_jobs_c9 CHECK (status NOT IN ('PROCESSING', 'COMPLETED', 'PARTIAL_SUCCESS') OR confirmed_at IS NOT NULL),
    CONSTRAINT import_jobs_c10 CHECK (upload_expires_at > created_at),
    CONSTRAINT fk_import_jobs_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE INDEX ix_import_jobs_user ON import_jobs(created_by, created_at DESC);

CREATE TABLE import_job_rows (
    job_id UUID NOT NULL,
    row_number INTEGER NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    external_key VARCHAR(200),
    payload JSONB NOT NULL,
    status VARCHAR(12) NOT NULL,
    result_content_item_id UUID,
    result_snapshot JSONB,
    errors JSONB NOT NULL DEFAULT '[]',
    processed_at TIMESTAMPTZ,
    CONSTRAINT pk_import_job_rows PRIMARY KEY (job_id, row_number),
    CONSTRAINT import_job_rows_c1 CHECK (row_number BETWEEN 1 AND 5000),
    CONSTRAINT import_job_rows_c2 CHECK (status IN ('VALID', 'INVALID', 'PENDING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT import_job_rows_c3 CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT import_job_rows_c4 CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 1048576),
    CONSTRAINT import_job_rows_c5 CHECK (result_snapshot IS NULL OR (jsonb_typeof(result_snapshot) = 'object' AND octet_length(result_snapshot::text) <= 1048576)),
    CONSTRAINT import_job_rows_c6 CHECK (jsonb_typeof(errors) = 'array'),
    CONSTRAINT import_job_rows_c7 CHECK (status <> 'SUCCESS' OR (result_content_item_id IS NOT NULL AND processed_at IS NOT NULL)),
    CONSTRAINT fk_import_job_rows_job_id FOREIGN KEY (job_id) REFERENCES import_jobs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_import_job_rows_result_item FOREIGN KEY (result_content_item_id) REFERENCES content_items(id) ON DELETE RESTRICT
);

-- Connect storage_allocations.import_job_id to import_jobs(id)
ALTER TABLE storage_allocations
    ADD CONSTRAINT fk_storage_allocations_import_job_id
    FOREIGN KEY (import_job_id) REFERENCES import_jobs(id) ON DELETE SET NULL;
