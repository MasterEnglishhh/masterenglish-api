-- V15: Media upload request lifecycle, storage pools quota, and storage allocation ledger

-- 1. Storage pools: Ngân sách CONTENT/TEMP toàn hệ thống và AVATAR từng user; hạ trần không xóa dữ liệu
CREATE TABLE storage_pools (
    id UUID PRIMARY KEY,
    scope VARCHAR(12) NOT NULL,
    owner_user_id UUID,
    quota_bytes BIGINT NOT NULL,
    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT storage_pools_c1 UNIQUE NULLS NOT DISTINCT (scope, owner_user_id),
    CONSTRAINT storage_pools_c2 CHECK (scope IN ('CONTENT','TEMP','AVATAR')),
    CONSTRAINT storage_pools_c3 CHECK ((scope='AVATAR')=(owner_user_id IS NOT NULL)),
    CONSTRAINT storage_pools_c4 CHECK (quota_bytes > 0 AND reserved_bytes >= 0 AND used_bytes >= 0 AND version >= 0),
    CONSTRAINT fk_storage_pools_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE RESTRICT
);

-- Seed singleton system pools with deterministic UUIDs: CONTENT (10 GiB) and TEMP (1 GiB)
INSERT INTO storage_pools (id, scope, quota_bytes, reserved_bytes, used_bytes)
VALUES
    ('20000000-0000-0000-0000-000000000001', 'CONTENT', 10737418240, 0, 0),
    ('20000000-0000-0000-0000-000000000002', 'TEMP', 1073741824, 0, 0);

-- 2. Media upload requests: Ngữ cảnh upload tạm; finished_at không đổi khi dọn file
CREATE TABLE media_upload_requests (
    id UUID PRIMARY KEY,
    media_id UUID NOT NULL UNIQUE,
    temp_bucket VARCHAR(100) NOT NULL,
    temp_object_key VARCHAR(500) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    declared_mime_type VARCHAR(100) NOT NULL,
    max_size_bytes BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    verification_started_at TIMESTAMPTZ,
    verification_deadline_at TIMESTAMPTZ,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT media_upload_requests_c1 CHECK (status IN ('PENDING','VERIFYING','COMPLETED','FAILED','EXPIRED')),
    CONSTRAINT media_upload_requests_c2 UNIQUE (temp_bucket, temp_object_key),
    CONSTRAINT media_upload_requests_c3 CHECK (declared_size_bytes > 0 AND declared_size_bytes <= max_size_bytes AND max_size_bytes <= 20971520),
    CONSTRAINT media_upload_requests_c4 CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '10 minutes'),
    CONSTRAINT media_upload_requests_c5 CHECK ((verification_started_at IS NULL) = (verification_deadline_at IS NULL)),
    CONSTRAINT media_upload_requests_c6 CHECK (verification_started_at IS NULL OR (verification_started_at <= expires_at AND verification_deadline_at > verification_started_at AND verification_deadline_at <= verification_started_at + INTERVAL '5 minutes')),
    CONSTRAINT media_upload_requests_c7 CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT media_upload_requests_c8 CHECK (lease_until IS NULL OR (status='VERIFYING' AND lease_until <= verification_deadline_at)),
    CONSTRAINT media_upload_requests_c9 CHECK ((status='COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT media_upload_requests_c10 CHECK ((status IN ('COMPLETED','FAILED','EXPIRED')) = (finished_at IS NOT NULL)),
    CONSTRAINT media_upload_requests_c11 CHECK (status NOT IN ('FAILED','EXPIRED') OR failure_code IS NOT NULL),
    CONSTRAINT media_upload_requests_c12 CHECK (status <> 'VERIFYING' OR verification_started_at IS NOT NULL),
    CONSTRAINT fk_media_upload_requests_media_id FOREIGN KEY (media_id) REFERENCES media_assets(id) ON DELETE RESTRICT
);

CREATE INDEX ix_uploads_open ON media_upload_requests(status, expires_at);
CREATE INDEX ix_uploads_claim ON media_upload_requests(lease_until) WHERE status='VERIFYING';

-- 3. Storage allocations: Ledger giữ chỗ/file, chỉ giải phóng sau đối chiếu, không reset quota bằng archive
CREATE TABLE storage_allocations (
    id UUID PRIMARY KEY,
    pool_id UUID NOT NULL,
    allocation_key VARCHAR(200) NOT NULL UNIQUE,
    media_id UUID,
    upload_request_id UUID,
    import_job_id UUID,
    bytes BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL DEFAULT 'RESERVED',
    bucket VARCHAR(100),
    object_key VARCHAR(500),
    committed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT storage_allocations_c1 CHECK (state IN ('RESERVED','COMMITTED','RELEASED')),
    CONSTRAINT storage_allocations_c2 CHECK (bytes > 0 AND version >= 0),
    CONSTRAINT storage_allocations_c3 CHECK ((bucket IS NULL)=(object_key IS NULL)),
    CONSTRAINT storage_allocations_c4 CHECK (state<>'COMMITTED' OR (committed_at IS NOT NULL AND bucket IS NOT NULL)),
    CONSTRAINT storage_allocations_c5 CHECK ((state='RELEASED')=(released_at IS NOT NULL)),
    CONSTRAINT storage_allocations_c6 CHECK (state='RELEASED' OR num_nonnulls(media_id,upload_request_id,import_job_id)>0),
    CONSTRAINT fk_storage_allocations_pool_id FOREIGN KEY (pool_id) REFERENCES storage_pools(id) ON DELETE RESTRICT,
    CONSTRAINT fk_storage_allocations_media_id FOREIGN KEY (media_id) REFERENCES media_assets(id) ON DELETE SET NULL,
    CONSTRAINT fk_storage_allocations_upload_request_id FOREIGN KEY (upload_request_id) REFERENCES media_upload_requests(id) ON DELETE SET NULL
);

CREATE INDEX ix_allocations_pool ON storage_allocations(pool_id, state);
CREATE INDEX ix_allocations_media ON storage_allocations(media_id);

-- 4. Backfill existing CONTENT media assets with physical files into storage_allocations
-- Schema policy note:
-- Legacy media rows with confirmed physical objects (READY, ARCHIVED, or FAILED without deletion)
-- are backfilled as COMMITTED to conservatively hold their byte consumption in storage accounting.
-- Any legacy PENDING rows that already have finite bucket/object_key/file_size_bytes are also backfilled
-- as COMMITTED to guard quota conservatively until verified or cleaned.
-- Legacy rows that have bucket/object_key but no known size (NULL or 0 bytes) cannot have size deduced
-- without remote storage probes (prohibited in migrations); these anomaly rows must be reconciled
-- by an out-of-band maintenance worker and are not assumed cleaned up.
INSERT INTO storage_allocations (
    id, pool_id, allocation_key, media_id, bytes, state, bucket, object_key, committed_at, released_at
)
SELECT
    gen_random_uuid(),
    '20000000-0000-0000-0000-000000000001'::uuid,
    'legacy-media-' || id::text,
    id,
    file_size_bytes,
    CASE WHEN object_deleted_at IS NOT NULL THEN 'RELEASED' ELSE 'COMMITTED' END,
    bucket,
    object_key,
    COALESCE(updated_at, created_at),
    object_deleted_at
FROM media_assets
WHERE purpose = 'CONTENT'
  AND file_size_bytes IS NOT NULL
  AND file_size_bytes > 0
  AND bucket IS NOT NULL
  AND object_key IS NOT NULL
  AND status IN ('READY', 'ARCHIVED', 'FAILED', 'PENDING');

-- Update used_bytes in storage_pools for CONTENT pool with backfilled committed bytes
UPDATE storage_pools
SET used_bytes = COALESCE(
    (SELECT SUM(bytes) FROM storage_allocations WHERE pool_id = '20000000-0000-0000-0000-000000000001' AND state = 'COMMITTED'),
    0
)
WHERE id = '20000000-0000-0000-0000-000000000001';

-- 5. Trigger functions and constraints

-- Guard upload request invariants and lifecycle transitions
CREATE FUNCTION me_guard_upload_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  v_media media_assets;
BEGIN
  SELECT * INTO v_media FROM media_assets WHERE id = NEW.media_id;
  IF v_media.id IS NULL THEN
    RAISE EXCEPTION 'media asset not found' USING ERRCODE = '23503';
  END IF;

  IF NEW.declared_size_bytes > (CASE WHEN v_media.purpose = 'AVATAR' THEN 2097152
     WHEN v_media.media_type = 'IMAGE' THEN 5242880 ELSE 20971520 END) THEN
    RAISE EXCEPTION 'upload size exceeds type/purpose policy' USING ERRCODE = '23514';
  END IF;

  IF NEW.max_size_bytes > (CASE WHEN v_media.purpose = 'AVATAR' THEN 2097152
     WHEN v_media.media_type = 'IMAGE' THEN 5242880 ELSE 20971520 END) THEN
    RAISE EXCEPTION 'upload grant max size exceeds type/purpose policy' USING ERRCODE = '23514';
  END IF;

  IF (v_media.media_type = 'IMAGE' AND NEW.declared_mime_type NOT IN ('image/jpeg', 'image/png', 'image/webp')) OR
     (v_media.media_type = 'AUDIO' AND NEW.declared_mime_type NOT IN ('audio/mpeg', 'audio/mp4')) THEN
    RAISE EXCEPTION 'upload MIME type not allowed for media type' USING ERRCODE = '23514';
  END IF;

  IF TG_OP = 'UPDATE' THEN
    IF (NEW.id, NEW.media_id, NEW.temp_bucket, NEW.temp_object_key, NEW.declared_size_bytes,
        NEW.declared_mime_type, NEW.max_size_bytes, NEW.expires_at)
      IS DISTINCT FROM (OLD.id, OLD.media_id, OLD.temp_bucket, OLD.temp_object_key, OLD.declared_size_bytes,
        OLD.declared_mime_type, OLD.max_size_bytes, OLD.expires_at) THEN
      RAISE EXCEPTION 'upload grant context is immutable' USING ERRCODE = '23514';
    END IF;

    IF OLD.finished_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'terminal upload metadata is immutable' USING ERRCODE = '55000';
    END IF;

    IF OLD.verification_deadline_at IS NOT NULL AND
      (NEW.verification_started_at, NEW.verification_deadline_at) IS DISTINCT FROM
      (OLD.verification_started_at, OLD.verification_deadline_at) THEN
      RAISE EXCEPTION 'verification retries cannot extend deadline' USING ERRCODE = '23514';
    END IF;

    IF NEW.status <> OLD.status AND NOT (
      (OLD.status = 'PENDING' AND NEW.status IN ('VERIFYING', 'FAILED', 'EXPIRED')) OR
      (OLD.status = 'VERIFYING' AND NEW.status IN ('COMPLETED', 'FAILED'))
    ) THEN
      RAISE EXCEPTION 'invalid upload transition' USING ERRCODE = '23514';
    END IF;

    IF NEW.lease_until IS NOT NULL AND NEW.lease_until > CURRENT_TIMESTAMP + INTERVAL '60 seconds' THEN
      RAISE EXCEPTION 'verification lease exceeds 60 seconds' USING ERRCODE = '23514';
    END IF;
  END IF;

  RETURN NEW;
END $$;

CREATE TRIGGER guard_upload_request
BEFORE INSERT OR UPDATE ON media_upload_requests
FOR EACH ROW EXECUTE FUNCTION me_guard_upload_request();

-- Deferred constraint trigger ensuring upload completion matches confirmed media asset
CREATE FUNCTION me_check_upload_complete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  v_request media_upload_requests;
  v_media media_assets;
BEGIN
  SELECT * INTO v_request FROM media_upload_requests WHERE id = NEW.id;
  SELECT * INTO v_media FROM media_assets WHERE id = v_request.media_id;

  IF v_request.status = 'COMPLETED' AND (v_media.status NOT IN ('READY', 'ARCHIVED')
    OR v_media.file_size_bytes <> v_request.declared_size_bytes) THEN
    RAISE EXCEPTION 'completed upload must match confirmed media' USING ERRCODE = '23514';
  END IF;

  IF v_request.status IN ('FAILED', 'EXPIRED') AND v_media.status <> 'FAILED' THEN
    RAISE EXCEPTION 'failed upload must match failed media' USING ERRCODE = '23514';
  END IF;

  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER upload_result_consistent
AFTER INSERT OR UPDATE ON media_upload_requests
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION me_check_upload_complete();

-- Serializes concurrent allocations per pool to prevent lock-upgrade deadlocks
CREATE FUNCTION me_storage_lock_pool() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM storage_pools WHERE id = NEW.pool_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'storage pool not found' USING ERRCODE = '23503';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER storage_lock_pool
BEFORE INSERT OR UPDATE ON storage_allocations
FOR EACH ROW EXECUTE FUNCTION me_storage_lock_pool();

-- Storage ledger account trigger maintaining reserved_bytes and used_bytes with row lock
CREATE FUNCTION me_storage_account() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  v_reserved_old BIGINT := 0;
  v_used_old BIGINT := 0;
  v_reserved_new BIGINT := 0;
  v_used_new BIGINT := 0;
  v_pool storage_pools;
  v_increase BIGINT;
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state <> 'RELEASED' THEN
      RAISE EXCEPTION 'release allocation after storage reconciliation before deletion' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
  END IF;

  IF TG_OP = 'UPDATE' THEN
    IF NEW.pool_id <> OLD.pool_id OR NEW.bytes <> OLD.bytes OR NEW.allocation_key <> OLD.allocation_key THEN
      RAISE EXCEPTION 'allocation identity/byte reservation is immutable' USING ERRCODE = '23514';
    END IF;

    IF OLD.state = 'RELEASED' AND NEW IS DISTINCT FROM OLD AND
      (NEW.state, NEW.pool_id, NEW.bytes, NEW.bucket, NEW.object_key) IS DISTINCT FROM
      (OLD.state, OLD.pool_id, OLD.bytes, OLD.bucket, OLD.object_key) THEN
      RAISE EXCEPTION 'released allocation cannot consume again' USING ERRCODE = '23514';
    END IF;

    IF NEW.state <> OLD.state AND NOT (
      (OLD.state = 'RESERVED' AND NEW.state IN ('COMMITTED', 'RELEASED')) OR
      (OLD.state = 'COMMITTED' AND NEW.state = 'RELEASED')
    ) THEN
      RAISE EXCEPTION 'invalid allocation transition' USING ERRCODE = '23514';
    END IF;

    v_reserved_old := CASE WHEN OLD.state = 'RESERVED' THEN OLD.bytes ELSE 0 END;
    v_used_old := CASE WHEN OLD.state = 'COMMITTED' THEN OLD.bytes ELSE 0 END;
  END IF;

  v_reserved_new := CASE WHEN NEW.state = 'RESERVED' THEN NEW.bytes ELSE 0 END;
  v_used_new := CASE WHEN NEW.state = 'COMMITTED' THEN NEW.bytes ELSE 0 END;

  SELECT * INTO v_pool FROM storage_pools WHERE id = NEW.pool_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'storage pool not found' USING ERRCODE = '23503';
  END IF;

  v_increase := (v_reserved_new + v_used_new) - (v_reserved_old + v_used_old);
  IF v_increase > 0 AND v_pool.reserved_bytes + v_pool.used_bytes + v_increase > v_pool.quota_bytes THEN
    RAISE EXCEPTION 'storage quota exceeded' USING ERRCODE = '23514', CONSTRAINT = 'storage_pool_quota_exceeded';
  END IF;

  UPDATE storage_pools
  SET reserved_bytes = reserved_bytes - v_reserved_old + v_reserved_new,
      used_bytes = used_bytes - v_used_old + v_used_new,
      version = version + 1,
      updated_at = CURRENT_TIMESTAMP
  WHERE id = NEW.pool_id;

  RETURN NEW;
END $$;

CREATE TRIGGER storage_account
AFTER INSERT OR UPDATE OR DELETE ON storage_allocations
FOR EACH ROW EXECUTE FUNCTION me_storage_account();

-- 6. Read-only view for quota usage
CREATE VIEW media_quota_usage AS
SELECT id, scope, owner_user_id, quota_bytes, reserved_bytes, used_bytes,
  GREATEST(0, quota_bytes - reserved_bytes - used_bytes) AS available_bytes
FROM storage_pools;
