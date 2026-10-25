-- APPROVED REFERENCE DESIGN, 2026-10-25. PostgreSQL 17.
-- Fresh isolated database ONLY. Not a Flyway migration. Never run over the current application DB.
-- Includes native V1 event_publication; do not apply V1/V2 on top of this reference schema.
-- Source: D01-D29 and approved B01-B37. Regenerate with tools/build_approved_schema.py.
BEGIN;
SET LOCAL TIME ZONE 'UTC';

-- Identity: Ba role độc lập, danh mục do migration quản lý.
CREATE TABLE roles (
    id SMALLINT PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT roles_c1 CHECK (code IN ('LEARNER','CONTENT_ADMIN','SYSTEM_ADMIN'))
);

-- Identity: Tài khoản, profile và trạng thái bảo mật; đăng ký chờ xác minh được thiếu credential chính thức.
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    first_name VARCHAR(30),
    last_name VARCHAR(30),
    password_hash VARCHAR(255),
    phone VARCHAR(30),
    avatar_media_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ,
    auth_version INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_c1 CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','LOCKED','DISABLED')),
    CONSTRAINT users_c2 CHECK (email = btrim(email) AND email <> ''),
    CONSTRAINT users_c3 CHECK (first_name IS NULL OR (first_name = btrim(first_name) AND first_name <> '')),
    CONSTRAINT users_c4 CHECK (last_name IS NULL OR (last_name = btrim(last_name) AND last_name <> '')),
    CONSTRAINT users_c5 CHECK (phone IS NULL OR (phone = btrim(phone) AND phone <> '')),
    CONSTRAINT users_c6 CHECK (password_hash IS NULL OR password_hash ~ '^(\{bcrypt\})?\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$'),
    CONSTRAINT users_c7 CHECK (email_verified_at IS NULL OR (first_name IS NOT NULL AND last_name IS NOT NULL AND password_hash IS NOT NULL)),
    CONSTRAINT users_c8 CHECK (status <> 'ACTIVE' OR email_verified_at IS NOT NULL),
    CONSTRAINT users_c9 CHECK (status <> 'PENDING_VERIFICATION' OR email_verified_at IS NULL),
    CONSTRAINT users_c10 CHECK ((status = 'LOCKED') = (locked_until IS NOT NULL)),
    CONSTRAINT users_c11 CHECK (auth_version >= 0 AND version >= 0),
    CONSTRAINT users_c12 CHECK (updated_at >= created_at)
);

-- Identity: Role hiện tại; lịch sử thay đổi ở audit_logs.
CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id SMALLINT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by UUID,
    CONSTRAINT user_roles_c1 PRIMARY KEY (user_id, role_id)
);

-- Identity: Phiên độc lập, hạn cố định tối đa bảy ngày; không lưu device_label.
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    user_agent TEXT,
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(32),
    CONSTRAINT auth_sessions_c1 UNIQUE (user_id, id),
    CONSTRAINT auth_sessions_c2 CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days'),
    CONSTRAINT auth_sessions_c3 CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CONSTRAINT auth_sessions_c4 CHECK (last_active_at IS NULL OR last_active_at >= created_at),
    CONSTRAINT auth_sessions_c5 CHECK (revoke_reason IN ('LOGOUT','PASSWORD_CHANGE','PASSWORD_RESET','ROLE_CHANGE','ACCOUNT_LOCKED','ACCOUNT_DISABLED','SECURITY','SESSION_LIMIT','EXPIRED'))
);

-- Identity: Hash token ngẫu nhiên và chuỗi rotation trong một phiên.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_id UUID UNIQUE,
    last_used_at TIMESTAMPTZ,
    CONSTRAINT refresh_tokens_c1 UNIQUE (session_id, id),
    CONSTRAINT refresh_tokens_c2 CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refresh_tokens_c3 CHECK (expires_at > issued_at),
    CONSTRAINT refresh_tokens_c4 CHECK (replaced_by_id IS NULL OR (replaced_by_id <> id AND revoked_at IS NOT NULL))
);

-- Identity: OTP dùng một lần; bộ đếm liên yêu cầu ở auth_rate_limits.
CREATE TABLE otp_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    otp_hmac VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    blocked_until TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT otp_challenges_c1 UNIQUE (user_id, purpose, id),
    CONSTRAINT otp_challenges_c2 CHECK (purpose IN ('EMAIL_VERIFICATION','PASSWORD_CHANGE','PASSWORD_RESET')),
    CONSTRAINT otp_challenges_c3 CHECK (otp_hmac IS NULL OR otp_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT otp_challenges_c4 CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT otp_challenges_c5 CHECK (expires_at > created_at AND expires_at <= last_sent_at + INTERVAL '5 minutes'),
    CONSTRAINT otp_challenges_c6 CHECK (NOT (consumed_at IS NOT NULL AND invalidated_at IS NOT NULL))
);

-- Identity: Ngữ cảnh chờ OTP, không lưu mật khẩu rõ.
CREATE TABLE registration_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
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
    CONSTRAINT registration_requests_c1 CHECK (pending_password_hash IS NULL OR pending_password_hash ~ '^(\{bcrypt\})?\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$'),
    CONSTRAINT registration_requests_c2 CHECK (completed_at IS NOT NULL OR invalidated_at IS NOT NULL OR pending_password_hash IS NOT NULL),
    CONSTRAINT registration_requests_c3 CHECK (NOT (completed_at IS NOT NULL AND invalidated_at IS NOT NULL)),
    CONSTRAINT registration_requests_c4 CHECK (expires_at > created_at),
    CONSTRAINT registration_requests_c5 CHECK (pending_first_name = btrim(pending_first_name) AND pending_first_name <> ''),
    CONSTRAINT registration_requests_c6 CHECK (pending_last_name = btrim(pending_last_name) AND pending_last_name <> ''),
    CONSTRAINT registration_requests_c7 CHECK (expires_at <= created_at + INTERVAL '7 days')
);

-- Identity: Ngữ cảnh chờ OTP, không lưu mật khẩu rõ.
CREATE TABLE password_change_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    otp_challenge_id UUID NOT NULL UNIQUE,
    otp_purpose VARCHAR(24) GENERATED ALWAYS AS ('PASSWORD_CHANGE'::VARCHAR) STORED,
    session_id UUID NOT NULL,
    auth_version_at_request INTEGER NOT NULL,
    pending_password_hash VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT password_change_requests_c1 CHECK (pending_password_hash IS NULL OR pending_password_hash ~ '^(\{bcrypt\})?\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$'),
    CONSTRAINT password_change_requests_c2 CHECK (completed_at IS NOT NULL OR invalidated_at IS NOT NULL OR pending_password_hash IS NOT NULL),
    CONSTRAINT password_change_requests_c3 CHECK (NOT (completed_at IS NOT NULL AND invalidated_at IS NOT NULL)),
    CONSTRAINT password_change_requests_c4 CHECK (expires_at > created_at),
    CONSTRAINT password_change_requests_c5 CHECK (auth_version_at_request >= 0),
    CONSTRAINT password_change_requests_c6 CHECK (expires_at <= created_at + INTERVAL '5 minutes')
);

-- Identity: Quyền reset giới hạn, không phải role hay phiên đăng nhập.
CREATE TABLE password_reset_grants (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    otp_challenge_id UUID NOT NULL UNIQUE,
    otp_purpose VARCHAR(24) GENERATED ALWAYS AS ('PASSWORD_RESET'::VARCHAR) STORED,
    grant_hash VARCHAR(64) UNIQUE,
    auth_version_at_issue INTEGER NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT password_reset_grants_c1 CHECK (grant_hash IS NULL OR grant_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT password_reset_grants_c2 CHECK (used_at IS NOT NULL OR invalidated_at IS NOT NULL OR grant_hash IS NOT NULL),
    CONSTRAINT password_reset_grants_c3 CHECK (expires_at > issued_at AND expires_at <= issued_at + INTERVAL '5 minutes'),
    CONSTRAINT password_reset_grants_c4 CHECK (auth_version_at_issue >= 0),
    CONSTRAINT password_reset_grants_c5 CHECK (NOT (used_at IS NOT NULL AND invalidated_at IS NOT NULL))
);

-- Identity: Bộ giới hạn độc lập user/challenge ID, có cả email chưa đăng ký.
CREATE TABLE auth_rate_limits (
    id UUID PRIMARY KEY,
    scope VARCHAR(64) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    blocked_until TIMESTAMPTZ,
    reset_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_rate_limits_c1 UNIQUE (scope, subject_hash),
    CONSTRAINT auth_rate_limits_c2 CHECK (subject_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT auth_rate_limits_c3 CHECK (scope IN ('OTP_SEND_EMAIL','LOGIN_FAILURE_EMAIL','OTP_VERIFY_EMAIL_VERIFICATION','OTP_VERIFY_PASSWORD_CHANGE','OTP_VERIFY_PASSWORD_RESET','OTP_SEND_IP','OTP_VERIFY_IP','LOGIN_IP'))
);

-- Identity: Sự kiện đếm trong cửa sổ thời gian trượt, không phải audit đầy đủ.
CREATE TABLE auth_rate_limit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rate_limit_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Content: Xuất xứ và khai báo quyền sử dụng; ACTIVE không phải kết luận pháp lý.
CREATE TABLE content_sources (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_url TEXT,
    author VARCHAR(255),
    license_code VARCHAR(100),
    license_url TEXT,
    attribution TEXT,
    permission_note TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT content_sources_c1 CHECK (source_type IN ('SELF_AUTHORED','EXTERNAL')),
    CONSTRAINT content_sources_c2 CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT content_sources_c3 CHECK (btrim(title) <> '')
);

-- Media: File lâu dài; object bất biến, xóa chỉ khi hết mọi tham chiếu.
CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    media_type VARCHAR(16) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    bucket VARCHAR(100),
    object_key VARCHAR(500),
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100),
    file_size_bytes BIGINT,
    duration_ms INTEGER,
    width INTEGER,
    height INTEGER,
    checksum_sha256 VARCHAR(64),
    source_id UUID,
    uploaded_by UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    failure_code VARCHAR(64),
    archived_at TIMESTAMPTZ,
    deletion_started_at TIMESTAMPTZ,
    object_deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT media_assets_c1 CHECK (media_type IN ('IMAGE','AUDIO')),
    CONSTRAINT media_assets_c2 CHECK (purpose IN ('CONTENT','AVATAR')),
    CONSTRAINT media_assets_c3 CHECK (status IN ('PENDING','READY','FAILED','ARCHIVED')),
    CONSTRAINT media_assets_c4 UNIQUE (bucket, object_key),
    CONSTRAINT media_assets_c5 CHECK (purpose <> 'AVATAR' OR media_type = 'IMAGE'),
    CONSTRAINT media_assets_c6 CHECK (file_size_bytes IS NULL OR file_size_bytes > 0),
    CONSTRAINT media_assets_c7 CHECK (checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT media_assets_c8 CHECK (status NOT IN ('READY','ARCHIVED') OR (bucket IS NOT NULL AND object_key IS NOT NULL AND mime_type IS NOT NULL AND file_size_bytes IS NOT NULL AND checksum_sha256 IS NOT NULL)),
    CONSTRAINT media_assets_c9 CHECK (status NOT IN ('READY','ARCHIVED') OR (media_type = 'IMAGE' AND width > 0 AND height > 0 AND width IS NOT NULL AND height IS NOT NULL AND duration_ms IS NULL) OR (media_type = 'AUDIO' AND duration_ms > 0 AND duration_ms IS NOT NULL AND width IS NULL AND height IS NULL)),
    CONSTRAINT media_assets_c10 CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    CONSTRAINT media_assets_c11 CHECK (deletion_started_at IS NULL OR status IN ('ARCHIVED','FAILED')),
    CONSTRAINT media_assets_c12 CHECK (status <> 'FAILED' OR failure_code IS NOT NULL),
    CONSTRAINT media_assets_c13 CHECK (file_size_bytes IS NULL OR file_size_bytes <= CASE WHEN purpose='AVATAR' THEN 2097152 WHEN media_type='IMAGE' THEN 5242880 ELSE 20971520 END),
    CONSTRAINT media_assets_c14 CHECK (mime_type IS NULL OR (media_type='IMAGE' AND mime_type IN ('image/jpeg','image/png','image/webp')) OR (media_type='AUDIO' AND mime_type IN ('audio/mpeg','audio/mp4'))),
    CONSTRAINT media_assets_c15 CHECK (original_filename <> '' AND original_filename !~ '[[:cntrl:]]')
);

-- Media: Ngữ cảnh upload tạm; finished_at không đổi khi dọn file.
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
    CONSTRAINT media_upload_requests_c12 CHECK (status <> 'VERIFYING' OR verification_started_at IS NOT NULL)
);

-- Content: Định danh ổn định và pointer bản công bố; domain projection dùng cùng UUID.
CREATE TABLE content_items (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) NOT NULL,
    created_by UUID NOT NULL,
    published_revision_id UUID,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMPTZ,
    access_revoked_at TIMESTAMPTZ,
    access_revoke_reason TEXT,
    import_namespace VARCHAR(120),
    external_key VARCHAR(200),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT content_items_c1 UNIQUE (id, content_type),
    CONSTRAINT content_items_c2 CHECK (content_type IN ('WORD','DECK','GRAMMAR_TOPIC','GRAMMAR_LESSON','QUESTION','QUESTION_GROUP','PRACTICE_SET')),
    CONSTRAINT content_items_c3 CHECK ((is_archived) = (archived_at IS NOT NULL)),
    CONSTRAINT content_items_c4 CHECK ((access_revoked_at IS NULL) = (access_revoke_reason IS NULL)),
    CONSTRAINT content_items_c5 CHECK ((import_namespace IS NULL) = (external_key IS NULL)),
    CONSTRAINT content_items_c6 CHECK (version >= 0)
);

-- Content: Draft có schema; Published bất biến; một nhánh đang biên soạn mỗi item.
CREATE TABLE content_revisions (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1,
    snapshot JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    change_summary TEXT,
    created_by UUID NOT NULL,
    reviewed_by UUID,
    review_note TEXT,
    reviewed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT content_revisions_c1 UNIQUE (content_item_id, id),
    CONSTRAINT content_revisions_c2 UNIQUE (content_item_id, version_no),
    CONSTRAINT content_revisions_c3 CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED')),
    CONSTRAINT content_revisions_c4 CHECK (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 1048576),
    CONSTRAINT content_revisions_c5 CHECK (version_no > 0 AND schema_version > 0 AND version >= 0),
    CONSTRAINT content_revisions_c6 CHECK (status NOT IN ('APPROVED','REJECTED','PUBLISHED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT content_revisions_c7 CHECK (status <> 'REJECTED' OR (review_note IS NOT NULL AND btrim(review_note) <> '')),
    CONSTRAINT content_revisions_c8 CHECK ((status='PUBLISHED') = (published_at IS NOT NULL))
);

-- Content: Nguồn theo từng revision; bản chụp nguồn lúc công bố.
CREATE TABLE content_source_links (
    revision_id UUID NOT NULL,
    source_id UUID NOT NULL,
    usage_note TEXT,
    source_snapshot JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT content_source_links_c1 PRIMARY KEY (revision_id, source_id),
    CONSTRAINT content_source_links_c2 CHECK (source_snapshot IS NULL OR (jsonb_typeof(source_snapshot) = 'object' AND octet_length(source_snapshot::text) <= 1048576))
);

-- Content: Ghim revision con; formal edge chỉ tới bản đã công bố.
CREATE TABLE revision_dependencies (
    parent_revision_id UUID NOT NULL,
    relation_key VARCHAR(200) NOT NULL,
    child_revision_id UUID NOT NULL,
    CONSTRAINT revision_dependencies_c1 PRIMARY KEY (parent_revision_id, relation_key),
    CONSTRAINT revision_dependencies_c2 CHECK (parent_revision_id <> child_revision_id)
);

-- Content: FK tra cứu tất cả file trong Draft/Published, kể cả ở JSON.
CREATE TABLE revision_media_refs (
    revision_id UUID NOT NULL,
    media_id UUID NOT NULL,
    usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    CONSTRAINT revision_media_refs_c1 PRIMARY KEY (revision_id, media_id, usage_role),
    CONSTRAINT revision_media_refs_c2 CHECK (usage_role IN ('STIMULUS','EXPLANATION','TRANSCRIPT'))
);

-- Vocabulary: Projection từ hiện hành; nghĩa là đơn vị theo dõi học.
CREATE TABLE words (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('WORD'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    lemma VARCHAR(150) NOT NULL,
    language_code VARCHAR(10) NOT NULL DEFAULT 'en',
    ipa VARCHAR(150),
    audio_media_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT words_c1 CHECK (btrim(lemma) <> '')
);

-- Vocabulary: Nghĩa ổn định; đổi hẳn nghĩa tạo ID mới, không chuyển SRS.
CREATE TABLE word_senses (
    id UUID PRIMARY KEY,
    word_id UUID NOT NULL,
    word_revision_id UUID NOT NULL,
    sense_no SMALLINT NOT NULL,
    part_of_speech VARCHAR(24) NOT NULL,
    cefr_level VARCHAR(2) NOT NULL,
    meaning_vi TEXT NOT NULL,
    definition_en TEXT,
    audio_media_id UUID,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT word_senses_c1 UNIQUE (word_id, id),
    CONSTRAINT word_senses_c2 CHECK (part_of_speech IN ('NOUN','VERB','ADJECTIVE','ADVERB','PRONOUN','PREPOSITION','CONJUNCTION','DETERMINER','INTERJECTION','NUMERAL','PHRASE')),
    CONSTRAINT word_senses_c3 CHECK (cefr_level IN ('A1','A2','B1','B2','C1','C2')),
    CONSTRAINT word_senses_c4 CHECK (sense_no > 0 AND btrim(meaning_vi) <> '')
);

-- Vocabulary: Nhiều ví dụ của một nghĩa, cặp Anh/Việt có thứ tự.
CREATE TABLE word_sense_examples (
    id UUID PRIMARY KEY,
    word_sense_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    example_en TEXT NOT NULL,
    example_vi TEXT,
    CONSTRAINT word_sense_examples_c1 UNIQUE (word_sense_id, position),
    CONSTRAINT word_sense_examples_c2 CHECK (position > 0 AND btrim(example_en) <> '')
);

-- Vocabulary: Deck công bố, thành viên theo nghĩa và revision đã ghim.
CREATE TABLE decks (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('DECK'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    level VARCHAR(2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT decks_c1 CHECK (level IN ('A1','A2','B1','B2','C1','C2')),
    CONSTRAINT decks_c2 CHECK (btrim(title) <> '')
);

-- Vocabulary: Thành viên projection của deck; runtime dùng bản ghim khi giao bài.
CREATE TABLE deck_words (
    deck_id UUID NOT NULL,
    word_sense_id UUID NOT NULL,
    position INTEGER NOT NULL,
    CONSTRAINT deck_words_c1 PRIMARY KEY (deck_id, word_sense_id),
    CONSTRAINT deck_words_c2 UNIQUE (deck_id, position),
    CONSTRAINT deck_words_c3 CHECK (position > 0)
);

-- Learning: Bỏ lưu chỉ xóa liên kết này, không xóa SRS.
CREATE TABLE user_saved_words (
    user_id UUID NOT NULL,
    word_sense_id UUID NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_saved_words_c1 PRIMARY KEY (user_id, word_sense_id)
);

-- Learning: Trạng thái SRS hiện tại, khóa theo user và nghĩa.
CREATE TABLE user_word_progress (
    user_id UUID NOT NULL,
    word_sense_id UUID NOT NULL,
    repetitions INTEGER NOT NULL DEFAULT 0,
    interval_days INTEGER NOT NULL DEFAULT 0,
    ease_factor NUMERIC(6,3) NOT NULL DEFAULT 2.5,
    due_at TIMESTAMPTZ NOT NULL,
    last_reviewed_at TIMESTAMPTZ,
    policy_version VARCHAR(32) NOT NULL DEFAULT 'SM2_3BTN_V1',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_word_progress_c1 PRIMARY KEY (user_id, word_sense_id),
    CONSTRAINT user_word_progress_c2 CHECK (repetitions >= 0 AND interval_days >= 0 AND ease_factor >= 1.3 AND version >= 0)
);

-- Learning: Sự kiện review bất biến và idempotent, có chính sách/trạng thái trước-sau.
CREATE TABLE word_reviews (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    word_sense_id UUID NOT NULL,
    source_word_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    rating VARCHAR(8) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    before_state JSONB NOT NULL,
    after_state JSONB NOT NULL,
    previous_due_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    timezone VARCHAR(60) NOT NULL,
    local_day DATE NOT NULL,
    CONSTRAINT word_reviews_c1 UNIQUE (user_id, request_id),
    CONSTRAINT word_reviews_c2 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT word_reviews_c3 CHECK (rating IN ('AGAIN','HARD','GOOD')),
    CONSTRAINT word_reviews_c4 CHECK (jsonb_typeof(before_state) = 'object' AND octet_length(before_state::text) <= 1048576),
    CONSTRAINT word_reviews_c5 CHECK (jsonb_typeof(after_state) = 'object' AND octet_length(after_state::text) <= 1048576)
);

-- Grammar: Chủ điểm, không thêm cây curriculum nhiều cấp ngoài phạm vi.
CREATE TABLE grammar_topics (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('GRAMMAR_TOPIC'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    difficulty VARCHAR(16),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT grammar_topics_c1 CHECK (difficulty IN ('BASIC','INTERMEDIATE','ADVANCED')),
    CONSTRAINT grammar_topics_c2 CHECK (sort_order >= 0 AND btrim(name) <> '')
);

-- Grammar: Bài học khối có schema, thuộc một topic.
CREATE TABLE grammar_lessons (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('GRAMMAR_LESSON'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    topic_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    body_schema_version SMALLINT NOT NULL DEFAULT 1,
    body JSONB NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    estimated_minutes SMALLINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT grammar_lessons_c1 CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 1048576),
    CONSTRAINT grammar_lessons_c2 CHECK (char_length(body::text) <= 100000),
    CONSTRAINT grammar_lessons_c3 CHECK (sort_order >= 0 AND body_schema_version > 0),
    CONSTRAINT grammar_lessons_c4 CHECK (estimated_minutes IS NULL OR estimated_minutes > 0)
);

-- Learning: Hoàn thành bằng thao tác người học; giữ bản đã hoàn thành khi có bản mới.
CREATE TABLE lesson_progress (
    user_id UUID NOT NULL,
    lesson_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    last_completed_revision_id UUID,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT lesson_progress_c1 PRIMARY KEY (user_id, lesson_id),
    CONSTRAINT lesson_progress_c2 CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    CONSTRAINT lesson_progress_c3 CHECK ((state='COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT lesson_progress_c4 CHECK (state <> 'COMPLETED' OR last_completed_revision_id IS NOT NULL),
    CONSTRAINT lesson_progress_c5 CHECK (version >= 0)
);

-- Practice: Catalogue cách trả lời; chỉ bật code có renderer/grader đã triển khai.
CREATE TABLE question_types (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT question_types_c1 CHECK (schema_version > 0)
);

-- Practice: Ngữ cảnh cha; group type không thay question response type.
CREATE TABLE question_groups (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('QUESTION_GROUP'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    category VARCHAR(16) NOT NULL,
    part_number SMALLINT,
    title VARCHAR(200) NOT NULL,
    group_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT question_groups_c1 UNIQUE (id, category),
    CONSTRAINT question_groups_c2 UNIQUE (id, category, part_number),
    CONSTRAINT question_groups_c3 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    CONSTRAINT question_groups_c4 CHECK (group_type IN ('GENERAL','CONVERSATION','TALK','TEXT_COMPLETION','PASSAGE')),
    CONSTRAINT question_groups_c5 CHECK ((category='TOEIC' AND part_number IS NOT NULL AND ((part_number=3 AND group_type='CONVERSATION') OR (part_number=4 AND group_type='TALK') OR (part_number=6 AND group_type='TEXT_COMPLETION') OR (part_number=7 AND group_type='PASSAGE'))) OR (category<>'TOEIC' AND part_number IS NULL AND group_type='GENERAL'))
);

-- Practice: Câu chấm được; mỗi câu tối đa một nhóm; không dùng group_questions N:N.
CREATE TABLE questions (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('QUESTION'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    category VARCHAR(16) NOT NULL,
    question_type_code VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE',
    practice_mode VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    part_number SMALLINT,
    prompt TEXT,
    explanation TEXT NOT NULL,
    difficulty VARCHAR(8) NOT NULL DEFAULT 'MEDIUM',
    group_id UUID,
    group_position SMALLINT,
    in_current_group BOOLEAN NOT NULL DEFAULT TRUE,
    blank_key VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT questions_c1 UNIQUE (id, group_id),
    CONSTRAINT questions_c2 UNIQUE (id, category),
    CONSTRAINT questions_c3 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    CONSTRAINT questions_c4 CHECK (difficulty IN ('EASY','MEDIUM','HARD')),
    CONSTRAINT questions_c5 CHECK (practice_mode IN ('STANDARD','TRANSLATE_EN_VI','TRANSLATE_VI_EN','LISTEN_AND_CHOOSE','FILL_OPTION')),
    CONSTRAINT questions_c6 CHECK (char_length(prompt) <= 10000 AND char_length(explanation) <= 20000),
    CONSTRAINT questions_c7 CHECK ((category='TOEIC' AND part_number BETWEEN 1 AND 7 AND part_number IS NOT NULL) OR (category<>'TOEIC' AND part_number IS NULL)),
    CONSTRAINT questions_c8 CHECK ((group_id IS NULL AND group_position IS NULL AND blank_key IS NULL) OR (group_id IS NOT NULL AND group_position > 0 AND group_position IS NOT NULL)),
    CONSTRAINT questions_c9 CHECK (category<>'TOEIC' OR (part_number IN (3,4,6,7) AND group_id IS NOT NULL) OR (part_number IN (1,2,5) AND group_id IS NULL)),
    CONSTRAINT questions_c10 CHECK (blank_key IS NULL OR (category='TOEIC' AND part_number=6)),
    CONSTRAINT questions_c11 CHECK (category<>'TOEIC' OR part_number<>6 OR blank_key IS NOT NULL)
);

-- Practice: Lựa chọn của projection; đúng một đáp án khi commit.
CREATE TABLE question_options (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    body TEXT,
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT question_options_c1 UNIQUE (question_id, id),
    CONSTRAINT question_options_c2 UNIQUE (question_id, position),
    CONSTRAINT question_options_c3 CHECK (position BETWEEN 1 AND 6)
);

-- Practice: Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus.
CREATE TABLE group_materials (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    document_key VARCHAR(80),
    material_type VARCHAR(8) NOT NULL,
    usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    media_id UUID,
    body TEXT,
    CONSTRAINT group_materials_c1 UNIQUE (group_id, position),
    CONSTRAINT group_materials_c2 CHECK (material_type IN ('TEXT','IMAGE','AUDIO')),
    CONSTRAINT group_materials_c3 CHECK (usage_role IN ('STIMULUS','TRANSCRIPT','EXPLANATION')),
    CONSTRAINT group_materials_c4 CHECK (position > 0),
    CONSTRAINT group_materials_c5 CHECK ((material_type='TEXT' AND body IS NOT NULL AND media_id IS NULL) OR (material_type IN ('IMAGE','AUDIO') AND media_id IS NOT NULL AND body IS NULL)),
    CONSTRAINT group_materials_c6 CHECK (char_length(body) <= 50000)
);

-- Practice: Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus.
CREATE TABLE question_materials (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    document_key VARCHAR(80),
    material_type VARCHAR(8) NOT NULL,
    usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    media_id UUID,
    body TEXT,
    CONSTRAINT question_materials_c1 UNIQUE (question_id, position),
    CONSTRAINT question_materials_c2 CHECK (material_type IN ('TEXT','IMAGE','AUDIO')),
    CONSTRAINT question_materials_c3 CHECK (usage_role IN ('STIMULUS','TRANSCRIPT','EXPLANATION')),
    CONSTRAINT question_materials_c4 CHECK (position > 0),
    CONSTRAINT question_materials_c5 CHECK ((material_type='TEXT' AND body IS NOT NULL AND media_id IS NULL) OR (material_type IN ('IMAGE','AUDIO') AND media_id IS NOT NULL AND body IS NULL)),
    CONSTRAINT question_materials_c6 CHECK (char_length(body) <= 50000)
);

-- Practice: Bộ luyện hiện hành; FIXED có set_items, DYNAMIC có selection_config.
CREATE TABLE practice_sets (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('PRACTICE_SET'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    category VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    selection_mode VARCHAR(8) NOT NULL,
    selection_config JSONB NOT NULL DEFAULT '{}',
    deck_id UUID,
    topic_id UUID,
    part_number SMALLINT,
    time_limit_seconds INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT practice_sets_c1 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    CONSTRAINT practice_sets_c2 CHECK (selection_mode IN ('FIXED','DYNAMIC')),
    CONSTRAINT practice_sets_c3 CHECK (jsonb_typeof(selection_config) = 'object' AND octet_length(selection_config::text) <= 1048576),
    CONSTRAINT practice_sets_c4 CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0),
    CONSTRAINT practice_sets_c5 CHECK ((category='TOEIC' AND part_number BETWEEN 1 AND 7 AND part_number IS NOT NULL) OR (category<>'TOEIC' AND part_number IS NULL)),
    CONSTRAINT practice_sets_c6 CHECK (deck_id IS NULL OR category='VOCABULARY'),
    CONSTRAINT practice_sets_c7 CHECK (topic_id IS NULL OR category='GRAMMAR')
);

-- Practice: Một vị trí chọn câu độc lập XOR cả nhóm.
CREATE TABLE set_items (
    id UUID PRIMARY KEY,
    set_id UUID NOT NULL,
    position INTEGER NOT NULL,
    question_id UUID,
    group_id UUID,
    CONSTRAINT set_items_c1 UNIQUE (set_id, position),
    CONSTRAINT set_items_c2 CHECK (position > 0),
    CONSTRAINT set_items_c3 CHECK (num_nonnulls(question_id, group_id)=1)
);

-- Practice: Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic.
CREATE TABLE question_words (
    question_id UUID NOT NULL,
    word_sense_id UUID NOT NULL,
    CONSTRAINT question_words_c1 PRIMARY KEY (question_id, word_sense_id)
);

-- Practice: Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic.
CREATE TABLE question_topics (
    question_id UUID NOT NULL,
    topic_id UUID NOT NULL,
    CONSTRAINT question_topics_c1 PRIMARY KEY (question_id, topic_id)
);

-- Results: Nguồn và kết quả lượt làm; tạo idempotent, snapshot được seal trước khi giao.
CREATE TABLE attempts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    category VARCHAR(16) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_set_id UUID,
    source_deck_id UUID,
    source_topic_id UUID,
    source_revision_id UUID,
    source_config JSONB NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    total_count SMALLINT NOT NULL,
    correct_count SMALLINT,
    answered_count SMALLINT,
    score SMALLINT,
    snapshot_sealed_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deadline_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    effective_finished_at TIMESTAMPTZ,
    submit_reason VARCHAR(16),
    abandoned_at TIMESTAMPTZ,
    duration_seconds INTEGER,
    timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    local_day DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attempts_c1 UNIQUE (user_id, id),
    CONSTRAINT attempts_c2 UNIQUE (user_id, request_id),
    CONSTRAINT attempts_c3 UNIQUE (id, category),
    CONSTRAINT attempts_c4 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    CONSTRAINT attempts_c5 CHECK (source_type IN ('PRACTICE_SET','DECK','GRAMMAR_TOPIC','MIXED_GRAMMAR','MISTAKES')),
    CONSTRAINT attempts_c6 CHECK (status IN ('IN_PROGRESS','SUBMITTED','ABANDONED')),
    CONSTRAINT attempts_c7 CHECK (submit_reason IN ('MANUAL','TIME_LIMIT')),
    CONSTRAINT attempts_c8 CHECK (jsonb_typeof(source_config) = 'object' AND octet_length(source_config::text) <= 1048576),
    CONSTRAINT attempts_c9 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT attempts_c10 CHECK (total_count BETWEEN 1 AND 100 AND version >= 0),
    CONSTRAINT attempts_c11 CHECK (correct_count IS NULL OR correct_count BETWEEN 0 AND total_count),
    CONSTRAINT attempts_c12 CHECK (answered_count IS NULL OR answered_count BETWEEN 0 AND total_count),
    CONSTRAINT attempts_c13 CHECK (score IS NULL OR score = correct_count),
    CONSTRAINT attempts_c14 CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    CONSTRAINT attempts_c15 CHECK (deadline_at IS NULL OR deadline_at > started_at),
    CONSTRAINT attempts_c16 CHECK ((source_type='PRACTICE_SET' AND source_set_id IS NOT NULL AND source_deck_id IS NULL AND source_topic_id IS NULL) OR (source_type='DECK' AND source_deck_id IS NOT NULL AND category='VOCABULARY' AND source_set_id IS NULL AND source_topic_id IS NULL) OR (source_type='GRAMMAR_TOPIC' AND source_topic_id IS NOT NULL AND category='GRAMMAR' AND source_set_id IS NULL AND source_deck_id IS NULL) OR (source_type='MIXED_GRAMMAR' AND category='GRAMMAR' AND num_nonnulls(source_set_id,source_deck_id,source_topic_id)=0) OR (source_type='MISTAKES' AND num_nonnulls(source_set_id,source_deck_id,source_topic_id)=0)),
    CONSTRAINT attempts_c17 CHECK ((status='SUBMITTED' AND submitted_at IS NOT NULL AND effective_finished_at IS NOT NULL AND submit_reason IS NOT NULL AND correct_count IS NOT NULL AND answered_count IS NOT NULL AND score IS NOT NULL AND duration_seconds IS NOT NULL AND local_day IS NOT NULL AND abandoned_at IS NULL) OR (status='IN_PROGRESS' AND num_nonnulls(submitted_at,effective_finished_at,submit_reason,correct_count,answered_count,score,duration_seconds,abandoned_at,local_day)=0) OR (status='ABANDONED' AND abandoned_at IS NOT NULL AND num_nonnulls(submitted_at,effective_finished_at,submit_reason,correct_count,answered_count,score,duration_seconds,local_day)=0)),
    CONSTRAINT attempts_c18 CHECK (effective_finished_at IS NULL OR (effective_finished_at >= started_at AND effective_finished_at <= submitted_at)),
    CONSTRAINT attempts_c19 CHECK (submit_reason IS DISTINCT FROM 'TIME_LIMIT' OR (deadline_at IS NOT NULL AND effective_finished_at=deadline_at))
);

-- Results: Nguồn nhiều topic có FK, không chỉ giấu ID trong JSON.
CREATE TABLE attempt_source_topics (
    attempt_id UUID NOT NULL,
    topic_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    CONSTRAINT attempt_source_topics_c1 PRIMARY KEY (attempt_id, topic_id)
);

-- Results: Ngữ cảnh nhóm chung lưu một lần trong mỗi lượt làm.
CREATE TABLE attempt_group_snapshots (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    group_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    ordinal SMALLINT NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1,
    category VARCHAR(16) NOT NULL,
    part_number SMALLINT,
    expected_question_count SMALLINT NOT NULL,
    public_snapshot JSONB NOT NULL,
    private_snapshot JSONB NOT NULL DEFAULT '{}',
    CONSTRAINT attempt_group_snapshots_c1 UNIQUE (attempt_id, id),
    CONSTRAINT attempt_group_snapshots_c2 UNIQUE (attempt_id, group_id),
    CONSTRAINT attempt_group_snapshots_c3 UNIQUE (attempt_id, ordinal),
    CONSTRAINT attempt_group_snapshots_c4 CHECK (ordinal > 0 AND schema_version > 0 AND expected_question_count BETWEEN 1 AND 100),
    CONSTRAINT attempt_group_snapshots_c5 CHECK ((category='TOEIC' AND part_number IN (3,4,6,7) AND part_number IS NOT NULL) OR (category IN ('VOCABULARY','GRAMMAR') AND part_number IS NULL)),
    CONSTRAINT attempt_group_snapshots_c6 CHECK (category<>'TOEIC' OR (part_number NOT IN (3,4) OR expected_question_count=3)),
    CONSTRAINT attempt_group_snapshots_c7 CHECK (category<>'TOEIC' OR part_number<>6 OR expected_question_count=4),
    CONSTRAINT attempt_group_snapshots_c8 CHECK (jsonb_typeof(public_snapshot) = 'object' AND octet_length(public_snapshot::text) <= 1048576),
    CONSTRAINT attempt_group_snapshots_c9 CHECK (jsonb_typeof(private_snapshot) = 'object' AND octet_length(private_snapshot::text) <= 1048576)
);

-- Results: Đề giao bất biến, đáp án chuẩn/lời giải tách khỏi dữ liệu trước nộp.
CREATE TABLE attempt_question_snapshots (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    question_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    group_snapshot_id UUID,
    category VARCHAR(16) NOT NULL,
    ordinal SMALLINT NOT NULL,
    question_type_code VARCHAR(32) NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1,
    grading_policy_version VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE_V1',
    question_snapshot JSONB NOT NULL,
    answer_key_snapshot JSONB NOT NULL,
    explanation_snapshot JSONB,
    CONSTRAINT attempt_question_snapshots_c1 UNIQUE (attempt_id, id),
    CONSTRAINT attempt_question_snapshots_c2 UNIQUE (id, question_id),
    CONSTRAINT attempt_question_snapshots_c3 UNIQUE (attempt_id, ordinal),
    CONSTRAINT attempt_question_snapshots_c4 UNIQUE (attempt_id, question_id),
    CONSTRAINT attempt_question_snapshots_c5 CHECK (ordinal BETWEEN 1 AND 100 AND schema_version > 0),
    CONSTRAINT attempt_question_snapshots_c6 CHECK (jsonb_typeof(question_snapshot) = 'object' AND octet_length(question_snapshot::text) <= 1048576),
    CONSTRAINT attempt_question_snapshots_c7 CHECK (jsonb_typeof(answer_key_snapshot) = 'object' AND octet_length(answer_key_snapshot::text) <= 1048576),
    CONSTRAINT attempt_question_snapshots_c8 CHECK (explanation_snapshot IS NULL OR (jsonb_typeof(explanation_snapshot) = 'object' AND octet_length(explanation_snapshot::text) <= 1048576))
);

-- Results: Option thuộc đúng snapshot, không FK tới lựa chọn hiện hành có thể sửa.
CREATE TABLE attempt_snapshot_options (
    snapshot_id UUID NOT NULL,
    option_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    public_body TEXT,
    CONSTRAINT attempt_snapshot_options_c1 PRIMARY KEY (snapshot_id, option_id),
    CONSTRAINT attempt_snapshot_options_c2 UNIQUE (snapshot_id, position),
    CONSTRAINT attempt_snapshot_options_c3 CHECK (position BETWEEN 1 AND 6)
);

-- Results: Một đáp án hiện tại mỗi câu; version cả khi bỏ chọn.
CREATE TABLE attempt_answers (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    snapshot_id UUID NOT NULL UNIQUE,
    selected_option_id UUID,
    response_payload JSONB,
    version BIGINT NOT NULL DEFAULT 0,
    answered_at TIMESTAMPTZ,
    is_correct BOOLEAN,
    score SMALLINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attempt_answers_c1 CHECK (version >= 0),
    CONSTRAINT attempt_answers_c2 CHECK ((selected_option_id IS NULL) = (answered_at IS NULL)),
    CONSTRAINT attempt_answers_c3 CHECK (score IS NULL OR score IN (0,1)),
    CONSTRAINT attempt_answers_c4 CHECK ((is_correct IS NULL) = (score IS NULL)),
    CONSTRAINT attempt_answers_c5 CHECK (is_correct IS NULL OR score = CASE WHEN is_correct THEN 1 ELSE 0 END),
    CONSTRAINT attempt_answers_c6 CHECK (num_nonnulls(selected_option_id,response_payload) <= 1),
    CONSTRAINT attempt_answers_c7 CHECK (response_payload IS NULL OR (jsonb_typeof(response_payload) = 'object' AND octet_length(response_payload::text) <= 1048576))
);

-- Results: File trong đề/lời giải snapshot; bảo toàn độc lập projection hiện hành.
CREATE TABLE attempt_media_refs (
    attempt_id UUID NOT NULL,
    media_id UUID NOT NULL,
    usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    CONSTRAINT attempt_media_refs_c1 PRIMARY KEY (attempt_id, media_id, usage_role),
    CONSTRAINT attempt_media_refs_c2 CHECK (usage_role IN ('STIMULUS','EXPLANATION','TRANSCRIPT'))
);

-- Results: Lỗi theo user/câu; lịch sử sự kiện gốc ở các kết quả bất biến.
CREATE TABLE mistake_records (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    question_id UUID NOT NULL,
    source_attempt_id UUID NOT NULL,
    source_snapshot_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    wrong_count INTEGER NOT NULL,
    consecutive_correct INTEGER NOT NULL DEFAULT 0,
    last_wrong_at TIMESTAMPTZ NOT NULL,
    last_evaluated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT mistake_records_c1 UNIQUE (user_id, question_id),
    CONSTRAINT mistake_records_c2 UNIQUE (user_id, id),
    CONSTRAINT mistake_records_c3 CHECK (state IN ('OPEN','IMPROVING','RESOLVED')),
    CONSTRAINT mistake_records_c4 CHECK (wrong_count > 0 AND consecutive_correct >= 0),
    CONSTRAINT mistake_records_c5 CHECK ((state='OPEN' AND consecutive_correct=0 AND resolved_at IS NULL) OR (state='IMPROVING' AND consecutive_correct=1 AND resolved_at IS NULL) OR (state='RESOLVED' AND consecutive_correct>=2 AND resolved_at IS NOT NULL))
);

-- Results: Lỗi chọn để luyện lại phải thuộc đúng chủ lượt làm.
CREATE TABLE attempt_source_mistakes (
    attempt_id UUID NOT NULL,
    user_id UUID NOT NULL,
    mistake_id UUID NOT NULL,
    CONSTRAINT attempt_source_mistakes_c1 PRIMARY KEY (attempt_id, mistake_id)
);

-- Learning: Một mục tiêu hiện tại, không diễn giải thành trình độ được dự đoán.
CREATE TABLE learning_goals (
    user_id UUID PRIMARY KEY,
    target_score SMALLINT NOT NULL,
    daily_minutes SMALLINT NOT NULL,
    target_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT learning_goals_c1 CHECK (target_score BETWEEN 10 AND 990 AND target_score % 5=0),
    CONSTRAINT learning_goals_c2 CHECK (daily_minutes BETWEEN 5 AND 240)
);

-- Learning: Đoạn thời gian hoạt động ước tính, không cộng trùng nhiều tab/thiết bị.
CREATE TABLE study_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    word_sense_id UUID,
    lesson_id UUID,
    attempt_id UUID,
    timezone VARCHAR(60) NOT NULL,
    local_day DATE NOT NULL,
    credit_token UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    last_credited_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    active_seconds NUMERIC(12,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT study_sessions_c1 CHECK (source_type IN ('FLASHCARD','LESSON','PRACTICE')),
    CONSTRAINT study_sessions_c2 CHECK ((source_type='FLASHCARD' AND word_sense_id IS NOT NULL AND num_nonnulls(lesson_id,attempt_id)=0) OR (source_type='LESSON' AND lesson_id IS NOT NULL AND num_nonnulls(word_sense_id,attempt_id)=0) OR (source_type='PRACTICE' AND attempt_id IS NOT NULL AND num_nonnulls(word_sense_id,lesson_id)=0)),
    CONSTRAINT study_sessions_c3 CHECK (last_credited_at >= started_at AND (closed_at IS NULL OR closed_at >= last_credited_at)),
    CONSTRAINT study_sessions_c4 CHECK (active_seconds >= 0 AND active_seconds <= EXTRACT(EPOCH FROM (last_credited_at-started_at)) AND version >= 0)
);

-- Notifications: Nhắc học opt-in theo giờ; email bảo mật không phụ thuộc email nhắc học.
CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    daily_reminder_time TIME,
    timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notification_preferences_c1 CHECK ((quiet_hours_start IS NULL)=(quiet_hours_end IS NULL)),
    CONSTRAINT notification_preferences_c2 CHECK (quiet_hours_start IS NULL OR quiet_hours_start<>quiet_hours_end)
);

-- Notifications: Danh mục quy tắc nhắc, không có chiến dịch quảng bá/browser push.
CREATE TABLE reminder_rules (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    minimum_gap_minutes INTEGER NOT NULL DEFAULT 1440,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT reminder_rules_c1 CHECK (minimum_gap_minutes >= 0)
);

-- Notifications: Thông báo/dedupe; bản ghi INTERNAL dùng cho mail bảo mật, không vào feed learner.
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    reminder_rule_id UUID,
    type VARCHAR(24) NOT NULL,
    visibility VARCHAR(12) NOT NULL DEFAULT 'USER',
    title VARCHAR(180) NOT NULL,
    body TEXT NOT NULL,
    target_url VARCHAR(500),
    dedupe_key VARCHAR(200) NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notifications_c1 UNIQUE (user_id, dedupe_key),
    CONSTRAINT notifications_c2 CHECK (type IN ('REVIEW_DUE','DAILY_GOAL','CONTENT_NEW','SYSTEM','SECURITY')),
    CONSTRAINT notifications_c3 CHECK (visibility IN ('USER','INTERNAL')),
    CONSTRAINT notifications_c4 CHECK (target_url IS NULL OR (target_url LIKE '/%' AND target_url NOT LIKE '//%'))
);

-- Notifications: Queue gửi có claim/lease; payload OTP chỉ lưu mã hóa, không rõ trong event/log.
CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    channel VARCHAR(8) NOT NULL,
    recipient_address VARCHAR(254),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    provider_message_id VARCHAR(180),
    last_failure_code VARCHAR(64),
    sent_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    otp_challenge_id UUID,
    encrypted_payload BYTEA,
    encryption_key_id VARCHAR(64),
    sensitive_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notification_deliveries_c1 UNIQUE (notification_id, channel),
    CONSTRAINT notification_deliveries_c2 CHECK (channel IN ('IN_APP','EMAIL')),
    CONSTRAINT notification_deliveries_c3 CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED')),
    CONSTRAINT notification_deliveries_c4 CHECK (attempt_count BETWEEN 0 AND 4),
    CONSTRAINT notification_deliveries_c5 CHECK (channel<>'EMAIL' OR recipient_address IS NOT NULL),
    CONSTRAINT notification_deliveries_c6 CHECK ((status='PROCESSING')=(claim_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT notification_deliveries_c7 CHECK ((claim_token IS NULL)=(lease_until IS NULL)),
    CONSTRAINT notification_deliveries_c8 CHECK ((status='SENT')=(sent_at IS NOT NULL)),
    CONSTRAINT notification_deliveries_c9 CHECK ((status IN ('SENT','FAILED'))=(finished_at IS NOT NULL)),
    CONSTRAINT notification_deliveries_c10 CHECK (status NOT IN ('SENT','FAILED') OR next_retry_at IS NULL),
    CONSTRAINT notification_deliveries_c11 CHECK ((encrypted_payload IS NULL)=(encryption_key_id IS NULL)),
    CONSTRAINT notification_deliveries_c12 CHECK (channel='EMAIL' OR (encrypted_payload IS NULL AND otp_challenge_id IS NULL))
);

-- Content: Ngữ cảnh upload/import riêng, DryRun trước khi tạo Draft.
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
    CONSTRAINT import_jobs_c1 UNIQUE (created_by, request_id),
    CONSTRAINT import_jobs_c2 UNIQUE (input_bucket, input_object_key),
    CONSTRAINT import_jobs_c3 CHECK (import_type IN ('VOCABULARY_CSV','QUESTIONS_JSON','GRAMMAR_JSON')),
    CONSTRAINT import_jobs_c4 CHECK (status IN ('UPLOADING','DRY_RUN','READY','PROCESSING','COMPLETED','PARTIAL_SUCCESS','FAILED','EXPIRED')),
    CONSTRAINT import_jobs_c5 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT import_jobs_c6 CHECK (input_sha256 IS NULL OR input_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT import_jobs_c7 CHECK (declared_size_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT import_jobs_c8 CHECK (total_rows BETWEEN 0 AND 5000 AND success_rows>=0 AND failed_rows>=0 AND skipped_rows>=0 AND success_rows+failed_rows+skipped_rows<=total_rows),
    CONSTRAINT import_jobs_c9 CHECK ((claim_token IS NULL)=(lease_until IS NULL)),
    CONSTRAINT import_jobs_c10 CHECK ((status IN ('COMPLETED','PARTIAL_SUCCESS','FAILED','EXPIRED'))=(finished_at IS NOT NULL)),
    CONSTRAINT import_jobs_c11 CHECK (status NOT IN ('PROCESSING','COMPLETED','PARTIAL_SUCCESS') OR confirmed_at IS NOT NULL),
    CONSTRAINT import_jobs_c12 CHECK (upload_expires_at > created_at)
);

-- Content: Ledger từng dòng thay bảng import_job_errors đơn thuần; retry không tạo entity trùng.
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
    CONSTRAINT import_job_rows_c1 PRIMARY KEY (job_id, row_number),
    CONSTRAINT import_job_rows_c2 CHECK (row_number BETWEEN 1 AND 5000),
    CONSTRAINT import_job_rows_c3 CHECK (status IN ('VALID','INVALID','PENDING','SUCCESS','FAILED','SKIPPED')),
    CONSTRAINT import_job_rows_c4 CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT import_job_rows_c5 CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 1048576),
    CONSTRAINT import_job_rows_c6 CHECK (result_snapshot IS NULL OR (jsonb_typeof(result_snapshot) = 'object' AND octet_length(result_snapshot::text) <= 1048576)),
    CONSTRAINT import_job_rows_c7 CHECK (jsonb_typeof(errors)='array'),
    CONSTRAINT import_job_rows_c8 CHECK (status<>'SUCCESS' OR (result_content_item_id IS NOT NULL AND processed_at IS NOT NULL))
);

-- Media: Ngân sách CONTENT/TEMP toàn hệ thống và AVATAR từng user; hạ trần không xóa dữ liệu.
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
    CONSTRAINT storage_pools_c4 CHECK (quota_bytes > 0 AND reserved_bytes >= 0 AND used_bytes >= 0 AND version >= 0)
);

-- Media: Ledger giữ chỗ/file, chỉ giải phóng sau đối chiếu, không reset quota bằng archive.
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
    CONSTRAINT storage_allocations_c6 CHECK (state='RELEASED' OR num_nonnulls(media_id,upload_request_id,import_job_id)>0)
);

-- Administration: Allowlist typed; không dùng bảng này để chứa secret.
CREATE TABLE system_settings (
    key VARCHAR(80) PRIMARY KEY,
    value JSONB NOT NULL,
    value_type VARCHAR(12) NOT NULL,
    unit VARCHAR(20),
    description TEXT,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT system_settings_c1 CHECK (value_type IN ('INTEGER','BOOLEAN','STRING')),
    CONSTRAINT system_settings_c2 CHECK (key IN ('auth.bcrypt_cost','auth.max_sessions','auth.session_seconds','auth.access_seconds','auth.otp_seconds','auth.otp_resend_seconds','auth.otp_send_hourly_email','auth.otp_send_hourly_ip','auth.otp_verify_15min_ip','auth.login_15min_ip','media.upload_seconds','media.verify_seconds','media.lease_seconds','media.verify_parallelism','media.avatar_hourly','media.content_hourly','media.avatar_open','media.content_open','storage.avatar_bytes','storage.content_bytes','storage.temp_bytes','practice.max_questions','practice.max_open','practice.abandon_days','stats.weak_min_sample','stats.weak_window_days','stats.weak_below_percent','import.max_bytes','import.max_rows','notification.max_retries','notification.lease_seconds','notification.learning_email_default','notification.default_timezone','retention.notification_days','retention.job_days','retention.auth_days','retention.audit_days')),
    CONSTRAINT system_settings_c3 CHECK (version >= 0),
    CONSTRAINT system_settings_c4 CHECK ((value_type='INTEGER' AND jsonb_typeof(value)='number' AND value::text ~ '^[0-9]+$') OR (value_type='BOOLEAN' AND jsonb_typeof(value)='boolean') OR (value_type='STRING' AND jsonb_typeof(value)='string')),
    CONSTRAINT system_settings_c5 CHECK (key<>'auth.bcrypt_cost' OR (value_type='INTEGER' AND (value::text)::integer BETWEEN 10 AND 16))
);

-- Administration: Audit append-only cho tác vụ quản trị; không sao chép secret/URL có chữ ký.
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_type VARCHAR(8) NOT NULL,
    actor_user_id UUID,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID,
    metadata JSONB,
    reason TEXT,
    request_id VARCHAR(100),
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT audit_logs_c1 CHECK (actor_type IN ('USER','SYSTEM')),
    CONSTRAINT audit_logs_c2 CHECK ((actor_type='USER')=(actor_user_id IS NOT NULL)),
    CONSTRAINT audit_logs_c3 CHECK (metadata IS NULL OR (jsonb_typeof(metadata) = 'object' AND octet_length(metadata::text) <= 1048576))
);

-- Infrastructure: Schema native Modulith 2.1, giữ đúng V1; không tạo registry khác.
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    listener_id VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    completion_date TIMESTAMP(6) WITH TIME ZONE,
    last_resubmission_date TIMESTAMP(6) WITH TIME ZONE,
    completion_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(255)
);

-- All FK targets now exist; circular publication/avatar relations are explicit.
ALTER TABLE users ADD CONSTRAINT fk_users_avatar_media_id_media_assets FOREIGN KEY (avatar_media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_role_id_roles FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT;
ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_assigned_by_users FOREIGN KEY (assigned_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE auth_sessions ADD CONSTRAINT fk_auth_sessions_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_session_id_auth_sessions FOREIGN KEY (session_id) REFERENCES auth_sessions (id) ON DELETE RESTRICT;
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_session_id_replaced_by_id_refresh_tokens FOREIGN KEY (session_id, replaced_by_id) REFERENCES refresh_tokens (session_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE otp_challenges ADD CONSTRAINT fk_otp_challenges_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE registration_requests ADD CONSTRAINT fk_registration_requests_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE registration_requests ADD CONSTRAINT fk_registration_requests_user_id_otp_purpose_otp_a71b234223 FOREIGN KEY (user_id, otp_purpose, otp_challenge_id) REFERENCES otp_challenges (user_id, purpose, id) ON DELETE RESTRICT;
ALTER TABLE password_change_requests ADD CONSTRAINT fk_password_change_requests_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE password_change_requests ADD CONSTRAINT fk_password_change_requests_user_id_otp_purpose__b832787d51 FOREIGN KEY (user_id, otp_purpose, otp_challenge_id) REFERENCES otp_challenges (user_id, purpose, id) ON DELETE RESTRICT;
ALTER TABLE password_change_requests ADD CONSTRAINT fk_password_change_requests_user_id_session_id_auth_sessions FOREIGN KEY (user_id, session_id) REFERENCES auth_sessions (user_id, id) ON DELETE RESTRICT;
ALTER TABLE password_reset_grants ADD CONSTRAINT fk_password_reset_grants_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE password_reset_grants ADD CONSTRAINT fk_password_reset_grants_user_id_otp_purpose_otp_a968a160cd FOREIGN KEY (user_id, otp_purpose, otp_challenge_id) REFERENCES otp_challenges (user_id, purpose, id) ON DELETE RESTRICT;
ALTER TABLE auth_rate_limit_events ADD CONSTRAINT fk_auth_rate_limit_events_rate_limit_id_auth_rate_limits FOREIGN KEY (rate_limit_id) REFERENCES auth_rate_limits (id) ON DELETE RESTRICT;
ALTER TABLE content_sources ADD CONSTRAINT fk_content_sources_created_by_users FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE media_assets ADD CONSTRAINT fk_media_assets_source_id_content_sources FOREIGN KEY (source_id) REFERENCES content_sources (id) ON DELETE RESTRICT;
ALTER TABLE media_assets ADD CONSTRAINT fk_media_assets_uploaded_by_users FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE media_upload_requests ADD CONSTRAINT fk_media_upload_requests_media_id_media_assets FOREIGN KEY (media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE content_items ADD CONSTRAINT fk_content_items_created_by_users FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE content_items ADD CONSTRAINT fk_content_items_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE content_revisions ADD CONSTRAINT fk_content_revisions_content_item_id_content_items FOREIGN KEY (content_item_id) REFERENCES content_items (id) ON DELETE RESTRICT;
ALTER TABLE content_revisions ADD CONSTRAINT fk_content_revisions_created_by_users FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE content_revisions ADD CONSTRAINT fk_content_revisions_reviewed_by_users FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE content_source_links ADD CONSTRAINT fk_content_source_links_revision_id_content_revisions FOREIGN KEY (revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT;
ALTER TABLE content_source_links ADD CONSTRAINT fk_content_source_links_source_id_content_sources FOREIGN KEY (source_id) REFERENCES content_sources (id) ON DELETE RESTRICT;
ALTER TABLE revision_dependencies ADD CONSTRAINT fk_revision_dependencies_parent_revision_id_cont_479a99008a FOREIGN KEY (parent_revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT;
ALTER TABLE revision_dependencies ADD CONSTRAINT fk_revision_dependencies_child_revision_id_content_revisions FOREIGN KEY (child_revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT;
ALTER TABLE revision_media_refs ADD CONSTRAINT fk_revision_media_refs_revision_id_content_revisions FOREIGN KEY (revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT;
ALTER TABLE revision_media_refs ADD CONSTRAINT fk_revision_media_refs_media_id_media_assets FOREIGN KEY (media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE words ADD CONSTRAINT fk_words_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE words ADD CONSTRAINT fk_words_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE words ADD CONSTRAINT fk_words_audio_media_id_media_assets FOREIGN KEY (audio_media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE word_senses ADD CONSTRAINT fk_word_senses_word_id_words FOREIGN KEY (word_id) REFERENCES words (id) ON DELETE RESTRICT;
ALTER TABLE word_senses ADD CONSTRAINT fk_word_senses_word_id_word_revision_id_content_revisions FOREIGN KEY (word_id, word_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;
ALTER TABLE word_senses ADD CONSTRAINT fk_word_senses_audio_media_id_media_assets FOREIGN KEY (audio_media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE word_sense_examples ADD CONSTRAINT fk_word_sense_examples_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;
ALTER TABLE decks ADD CONSTRAINT fk_decks_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE decks ADD CONSTRAINT fk_decks_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE deck_words ADD CONSTRAINT fk_deck_words_deck_id_decks FOREIGN KEY (deck_id) REFERENCES decks (id) ON DELETE RESTRICT;
ALTER TABLE deck_words ADD CONSTRAINT fk_deck_words_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;
ALTER TABLE user_saved_words ADD CONSTRAINT fk_user_saved_words_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE user_saved_words ADD CONSTRAINT fk_user_saved_words_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;
ALTER TABLE user_word_progress ADD CONSTRAINT fk_user_word_progress_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE user_word_progress ADD CONSTRAINT fk_user_word_progress_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;
ALTER TABLE word_reviews ADD CONSTRAINT fk_word_reviews_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE word_reviews ADD CONSTRAINT fk_word_reviews_source_word_id_word_sense_id_word_senses FOREIGN KEY (source_word_id, word_sense_id) REFERENCES word_senses (word_id, id) ON DELETE RESTRICT;
ALTER TABLE word_reviews ADD CONSTRAINT fk_word_reviews_source_word_id_source_revision_i_d6a00d2712 FOREIGN KEY (source_word_id, source_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;
ALTER TABLE grammar_topics ADD CONSTRAINT fk_grammar_topics_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE grammar_topics ADD CONSTRAINT fk_grammar_topics_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE grammar_lessons ADD CONSTRAINT fk_grammar_lessons_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE grammar_lessons ADD CONSTRAINT fk_grammar_lessons_id_published_revision_id_cont_6509002e20 FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE grammar_lessons ADD CONSTRAINT fk_grammar_lessons_topic_id_grammar_topics FOREIGN KEY (topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;
ALTER TABLE lesson_progress ADD CONSTRAINT fk_lesson_progress_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE lesson_progress ADD CONSTRAINT fk_lesson_progress_lesson_id_grammar_lessons FOREIGN KEY (lesson_id) REFERENCES grammar_lessons (id) ON DELETE RESTRICT;
ALTER TABLE lesson_progress ADD CONSTRAINT fk_lesson_progress_lesson_id_last_completed_revi_cd3bd102b1 FOREIGN KEY (lesson_id, last_completed_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;
ALTER TABLE question_groups ADD CONSTRAINT fk_question_groups_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE question_groups ADD CONSTRAINT fk_question_groups_id_published_revision_id_cont_454aaec9c9 FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE questions ADD CONSTRAINT fk_questions_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE questions ADD CONSTRAINT fk_questions_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE questions ADD CONSTRAINT fk_questions_question_type_code_question_types FOREIGN KEY (question_type_code) REFERENCES question_types (code) ON DELETE RESTRICT;
ALTER TABLE questions ADD CONSTRAINT fk_questions_group_id_question_groups FOREIGN KEY (group_id) REFERENCES question_groups (id) ON DELETE RESTRICT;
ALTER TABLE questions ADD CONSTRAINT fk_questions_group_id_category_question_groups FOREIGN KEY (group_id, category) REFERENCES question_groups (id, category) ON DELETE RESTRICT;
ALTER TABLE questions ADD CONSTRAINT fk_questions_group_id_category_part_number_question_groups FOREIGN KEY (group_id, category, part_number) REFERENCES question_groups (id, category, part_number) ON DELETE RESTRICT;
ALTER TABLE question_options ADD CONSTRAINT fk_question_options_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE group_materials ADD CONSTRAINT fk_group_materials_group_id_question_groups FOREIGN KEY (group_id) REFERENCES question_groups (id) ON DELETE RESTRICT;
ALTER TABLE group_materials ADD CONSTRAINT fk_group_materials_media_id_media_assets FOREIGN KEY (media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE question_materials ADD CONSTRAINT fk_question_materials_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE question_materials ADD CONSTRAINT fk_question_materials_media_id_media_assets FOREIGN KEY (media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_deck_id_decks FOREIGN KEY (deck_id) REFERENCES decks (id) ON DELETE RESTRICT;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_topic_id_grammar_topics FOREIGN KEY (topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;
ALTER TABLE set_items ADD CONSTRAINT fk_set_items_set_id_practice_sets FOREIGN KEY (set_id) REFERENCES practice_sets (id) ON DELETE RESTRICT;
ALTER TABLE set_items ADD CONSTRAINT fk_set_items_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE set_items ADD CONSTRAINT fk_set_items_group_id_question_groups FOREIGN KEY (group_id) REFERENCES question_groups (id) ON DELETE RESTRICT;
ALTER TABLE question_words ADD CONSTRAINT fk_question_words_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE question_words ADD CONSTRAINT fk_question_words_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;
ALTER TABLE question_topics ADD CONSTRAINT fk_question_topics_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE question_topics ADD CONSTRAINT fk_question_topics_topic_id_grammar_topics FOREIGN KEY (topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;
ALTER TABLE attempts ADD CONSTRAINT fk_attempts_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE attempts ADD CONSTRAINT fk_attempts_source_set_id_practice_sets FOREIGN KEY (source_set_id) REFERENCES practice_sets (id) ON DELETE RESTRICT;
ALTER TABLE attempts ADD CONSTRAINT fk_attempts_source_deck_id_decks FOREIGN KEY (source_deck_id) REFERENCES decks (id) ON DELETE RESTRICT;
ALTER TABLE attempts ADD CONSTRAINT fk_attempts_source_topic_id_grammar_topics FOREIGN KEY (source_topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;
ALTER TABLE attempts ADD CONSTRAINT fk_attempts_source_revision_id_content_revisions FOREIGN KEY (source_revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT;
ALTER TABLE attempt_source_topics ADD CONSTRAINT fk_attempt_source_topics_attempt_id_attempts FOREIGN KEY (attempt_id) REFERENCES attempts (id) ON DELETE RESTRICT;
ALTER TABLE attempt_source_topics ADD CONSTRAINT fk_attempt_source_topics_topic_id_grammar_topics FOREIGN KEY (topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;
ALTER TABLE attempt_source_topics ADD CONSTRAINT fk_attempt_source_topics_topic_id_source_revisio_828a6de95c FOREIGN KEY (topic_id, source_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;
ALTER TABLE attempt_group_snapshots ADD CONSTRAINT fk_attempt_group_snapshots_attempt_id_attempts FOREIGN KEY (attempt_id) REFERENCES attempts (id) ON DELETE RESTRICT;
ALTER TABLE attempt_group_snapshots ADD CONSTRAINT fk_attempt_group_snapshots_group_id_question_groups FOREIGN KEY (group_id) REFERENCES question_groups (id) ON DELETE RESTRICT;
ALTER TABLE attempt_group_snapshots ADD CONSTRAINT fk_attempt_group_snapshots_attempt_id_category_attempts FOREIGN KEY (attempt_id, category) REFERENCES attempts (id, category) ON DELETE RESTRICT;
ALTER TABLE attempt_group_snapshots ADD CONSTRAINT fk_attempt_group_snapshots_group_id_category_question_groups FOREIGN KEY (group_id, category) REFERENCES question_groups (id, category) ON DELETE RESTRICT;
ALTER TABLE attempt_group_snapshots ADD CONSTRAINT fk_attempt_group_snapshots_group_id_source_revis_591e490853 FOREIGN KEY (group_id, source_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;
ALTER TABLE attempt_question_snapshots ADD CONSTRAINT fk_attempt_question_snapshots_attempt_id_attempts FOREIGN KEY (attempt_id) REFERENCES attempts (id) ON DELETE RESTRICT;
ALTER TABLE attempt_question_snapshots ADD CONSTRAINT fk_attempt_question_snapshots_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE attempt_question_snapshots ADD CONSTRAINT fk_attempt_question_snapshots_attempt_id_category_attempts FOREIGN KEY (attempt_id, category) REFERENCES attempts (id, category) ON DELETE RESTRICT;
ALTER TABLE attempt_question_snapshots ADD CONSTRAINT fk_attempt_question_snapshots_question_id_category_questions FOREIGN KEY (question_id, category) REFERENCES questions (id, category) ON DELETE RESTRICT;
ALTER TABLE attempt_question_snapshots ADD CONSTRAINT fk_attempt_question_snapshots_question_id_source_07fbe7547b FOREIGN KEY (question_id, source_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;
ALTER TABLE attempt_question_snapshots ADD CONSTRAINT fk_attempt_question_snapshots_attempt_id_group_s_7cd08436e7 FOREIGN KEY (attempt_id, group_snapshot_id) REFERENCES attempt_group_snapshots (attempt_id, id) ON DELETE RESTRICT;
ALTER TABLE attempt_question_snapshots ADD CONSTRAINT fk_attempt_question_snapshots_question_type_code_117357f079 FOREIGN KEY (question_type_code) REFERENCES question_types (code) ON DELETE RESTRICT;
ALTER TABLE attempt_snapshot_options ADD CONSTRAINT fk_attempt_snapshot_options_snapshot_id_attempt__b2a91a2ba7 FOREIGN KEY (snapshot_id) REFERENCES attempt_question_snapshots (id) ON DELETE RESTRICT;
ALTER TABLE attempt_answers ADD CONSTRAINT fk_attempt_answers_attempt_id_snapshot_id_attemp_200a4d97c4 FOREIGN KEY (attempt_id, snapshot_id) REFERENCES attempt_question_snapshots (attempt_id, id) ON DELETE RESTRICT;
ALTER TABLE attempt_answers ADD CONSTRAINT fk_attempt_answers_snapshot_id_selected_option_i_38d3d26fd0 FOREIGN KEY (snapshot_id, selected_option_id) REFERENCES attempt_snapshot_options (snapshot_id, option_id) ON DELETE RESTRICT;
ALTER TABLE attempt_media_refs ADD CONSTRAINT fk_attempt_media_refs_attempt_id_attempts FOREIGN KEY (attempt_id) REFERENCES attempts (id) ON DELETE RESTRICT;
ALTER TABLE attempt_media_refs ADD CONSTRAINT fk_attempt_media_refs_media_id_media_assets FOREIGN KEY (media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;
ALTER TABLE mistake_records ADD CONSTRAINT fk_mistake_records_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE mistake_records ADD CONSTRAINT fk_mistake_records_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE mistake_records ADD CONSTRAINT fk_mistake_records_user_id_source_attempt_id_attempts FOREIGN KEY (user_id, source_attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT;
ALTER TABLE mistake_records ADD CONSTRAINT fk_mistake_records_source_attempt_id_source_snap_1e5b0a3f8a FOREIGN KEY (source_attempt_id, source_snapshot_id) REFERENCES attempt_question_snapshots (attempt_id, id) ON DELETE RESTRICT;
ALTER TABLE mistake_records ADD CONSTRAINT fk_mistake_records_source_snapshot_id_question_i_67b7b27b2d FOREIGN KEY (source_snapshot_id, question_id) REFERENCES attempt_question_snapshots (id, question_id) ON DELETE RESTRICT;
ALTER TABLE attempt_source_mistakes ADD CONSTRAINT fk_attempt_source_mistakes_user_id_attempt_id_attempts FOREIGN KEY (user_id, attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT;
ALTER TABLE attempt_source_mistakes ADD CONSTRAINT fk_attempt_source_mistakes_user_id_mistake_id_mi_12442d0e13 FOREIGN KEY (user_id, mistake_id) REFERENCES mistake_records (user_id, id) ON DELETE RESTRICT;
ALTER TABLE learning_goals ADD CONSTRAINT fk_learning_goals_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE study_sessions ADD CONSTRAINT fk_study_sessions_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE study_sessions ADD CONSTRAINT fk_study_sessions_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;
ALTER TABLE study_sessions ADD CONSTRAINT fk_study_sessions_lesson_id_grammar_lessons FOREIGN KEY (lesson_id) REFERENCES grammar_lessons (id) ON DELETE RESTRICT;
ALTER TABLE study_sessions ADD CONSTRAINT fk_study_sessions_user_id_attempt_id_attempts FOREIGN KEY (user_id, attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT;
ALTER TABLE notification_preferences ADD CONSTRAINT fk_notification_preferences_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_reminder_rule_id_reminder_rules FOREIGN KEY (reminder_rule_id) REFERENCES reminder_rules (id) ON DELETE RESTRICT;
ALTER TABLE notification_deliveries ADD CONSTRAINT fk_notification_deliveries_notification_id_notifications FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE RESTRICT;
ALTER TABLE notification_deliveries ADD CONSTRAINT fk_notification_deliveries_otp_challenge_id_otp_challenges FOREIGN KEY (otp_challenge_id) REFERENCES otp_challenges (id) ON DELETE RESTRICT;
ALTER TABLE import_jobs ADD CONSTRAINT fk_import_jobs_created_by_users FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE import_job_rows ADD CONSTRAINT fk_import_job_rows_job_id_import_jobs FOREIGN KEY (job_id) REFERENCES import_jobs (id) ON DELETE RESTRICT;
ALTER TABLE import_job_rows ADD CONSTRAINT fk_import_job_rows_result_content_item_id_content_items FOREIGN KEY (result_content_item_id) REFERENCES content_items (id) ON DELETE RESTRICT;
ALTER TABLE storage_pools ADD CONSTRAINT fk_storage_pools_owner_user_id_users FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE storage_allocations ADD CONSTRAINT fk_storage_allocations_pool_id_storage_pools FOREIGN KEY (pool_id) REFERENCES storage_pools (id) ON DELETE RESTRICT;
ALTER TABLE storage_allocations ADD CONSTRAINT fk_storage_allocations_media_id_media_assets FOREIGN KEY (media_id) REFERENCES media_assets (id) ON DELETE SET NULL;
ALTER TABLE storage_allocations ADD CONSTRAINT fk_storage_allocations_upload_request_id_media_u_763b2685df FOREIGN KEY (upload_request_id) REFERENCES media_upload_requests (id) ON DELETE SET NULL;
ALTER TABLE storage_allocations ADD CONSTRAINT fk_storage_allocations_import_job_id_import_jobs FOREIGN KEY (import_job_id) REFERENCES import_jobs (id) ON DELETE SET NULL;
ALTER TABLE system_settings ADD CONSTRAINT fk_system_settings_updated_by_users FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE audit_logs ADD CONSTRAINT fk_audit_logs_actor_user_id_users FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT;
COMMENT ON TABLE roles IS 'Ba role độc lập, danh mục do migration quản lý.';
COMMENT ON COLUMN roles.id IS 'Mã số nhỏ, cố định';
COMMENT ON COLUMN roles.code IS 'Mã quyền dùng trong API/JWT';
COMMENT ON COLUMN roles.name IS 'Nhãn hiển thị';
CREATE UNIQUE INDEX uq_users_email ON users(lower(email));
CREATE INDEX ix_users_status_created ON users(status, created_at DESC);
COMMENT ON TABLE users IS 'Tài khoản, profile và trạng thái bảo mật; đăng ký chờ xác minh được thiếu credential chính thức.';
COMMENT ON COLUMN users.id IS 'Định danh tài khoản';
COMMENT ON COLUMN users.email IS 'Email cố định, khóa so sánh lower(email)';
COMMENT ON COLUMN users.first_name IS 'Phần tên, có thể gồm tên đệm';
COMMENT ON COLUMN users.last_name IS 'Phần họ';
COMMENT ON COLUMN users.password_hash IS 'BCrypt, không phải mật khẩu rõ';
COMMENT ON COLUMN users.phone IS 'Tùy chọn, dạng chuỗi, không unique';
COMMENT ON COLUMN users.avatar_media_id IS 'Ảnh READY/AVATAR của chính user';
COMMENT ON COLUMN users.status IS 'Vòng đời tài khoản';
COMMENT ON COLUMN users.email_verified_at IS 'Thời điểm xác minh';
COMMENT ON COLUMN users.locked_until IS 'Hạn khóa tạm';
COMMENT ON COLUMN users.password_changed_at IS 'Thời điểm gán/đổi credential';
COMMENT ON COLUMN users.auth_version IS 'Thu hồi quyền xác thực cũ';
COMMENT ON COLUMN users.version IS 'Optimistic lock';
COMMENT ON COLUMN users.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN users.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_user_roles_role ON user_roles(role_id, user_id);
COMMENT ON TABLE user_roles IS 'Role hiện tại; lịch sử thay đổi ở audit_logs.';
COMMENT ON COLUMN user_roles.user_id IS 'Tài khoản được gán';
COMMENT ON COLUMN user_roles.role_id IS 'Role';
COMMENT ON COLUMN user_roles.assigned_at IS 'Mốc gán';
COMMENT ON COLUMN user_roles.assigned_by IS 'Người gán; NULL cho signup/bootstrap';
CREATE INDEX ix_sessions_user_live ON auth_sessions(user_id, created_at) WHERE revoked_at IS NULL;
CREATE INDEX ix_sessions_expiry ON auth_sessions(expires_at);
COMMENT ON TABLE auth_sessions IS 'Phiên độc lập, hạn cố định tối đa bảy ngày; không lưu device_label.';
COMMENT ON COLUMN auth_sessions.id IS 'Định danh phiên/JWT sid';
COMMENT ON COLUMN auth_sessions.user_id IS 'Chủ phiên';
COMMENT ON COLUMN auth_sessions.user_agent IS 'Metadata trình duyệt, không phải danh tính thiết bị';
COMMENT ON COLUMN auth_sessions.ip_address IS 'IP qua chuỗi proxy tin cậy';
COMMENT ON COLUMN auth_sessions.created_at IS 'Lần đăng nhập';
COMMENT ON COLUMN auth_sessions.last_active_at IS 'Không dùng làm phút học';
COMMENT ON COLUMN auth_sessions.expires_at IS 'Không được kéo dài bởi refresh';
COMMENT ON COLUMN auth_sessions.revoked_at IS 'Mốc thu hồi';
COMMENT ON COLUMN auth_sessions.revoke_reason IS 'Lý do thu hồi';
CREATE UNIQUE INDEX uq_refresh_one_unrevoked ON refresh_tokens(session_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_expiry ON refresh_tokens(expires_at);
COMMENT ON TABLE refresh_tokens IS 'Hash token ngẫu nhiên và chuỗi rotation trong một phiên.';
COMMENT ON COLUMN refresh_tokens.id IS 'Token ID nội bộ';
COMMENT ON COLUMN refresh_tokens.session_id IS 'Phiên sở hữu';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 của opaque token';
COMMENT ON COLUMN refresh_tokens.issued_at IS 'Thời điểm cấp';
COMMENT ON COLUMN refresh_tokens.expires_at IS 'Không vượt hạn phiên';
COMMENT ON COLUMN refresh_tokens.revoked_at IS 'Mốc thu hồi hoặc rotate';
COMMENT ON COLUMN refresh_tokens.replaced_by_id IS 'Token kế tiếp trong cùng phiên';
COMMENT ON COLUMN refresh_tokens.last_used_at IS 'Lần refresh thành công';
CREATE UNIQUE INDEX uq_otp_open ON otp_challenges(user_id, purpose) WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_otp_expiry ON otp_challenges(expires_at);
COMMENT ON TABLE otp_challenges IS 'OTP dùng một lần; bộ đếm liên yêu cầu ở auth_rate_limits.';
COMMENT ON COLUMN otp_challenges.id IS 'Challenge ID';
COMMENT ON COLUMN otp_challenges.user_id IS 'Chủ challenge';
COMMENT ON COLUMN otp_challenges.purpose IS 'Không dùng chéo mục đích';
COMMENT ON COLUMN otp_challenges.otp_hmac IS 'HMAC, được xóa khi kết thúc';
COMMENT ON COLUMN otp_challenges.expires_at IS 'Hạn mã hiện tại, 5 phút';
COMMENT ON COLUMN otp_challenges.last_sent_at IS 'Mốc gửi gần nhất';
COMMENT ON COLUMN otp_challenges.attempt_count IS 'Lỗi trên challenge cụ thể';
COMMENT ON COLUMN otp_challenges.blocked_until IS 'Không thay bộ đếm chung';
COMMENT ON COLUMN otp_challenges.consumed_at IS 'OTP đã dùng';
COMMENT ON COLUMN otp_challenges.invalidated_at IS 'OTP bị thay/hủy';
COMMENT ON COLUMN otp_challenges.created_at IS 'Mốc tạo';
CREATE UNIQUE INDEX uq_registration_requests_open ON registration_requests(user_id) WHERE completed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_registration_requests_expiry ON registration_requests(expires_at);
COMMENT ON TABLE registration_requests IS 'Ngữ cảnh chờ OTP, không lưu mật khẩu rõ.';
COMMENT ON COLUMN registration_requests.id IS 'Định danh yêu cầu';
COMMENT ON COLUMN registration_requests.user_id IS 'Chủ yêu cầu';
COMMENT ON COLUMN registration_requests.otp_challenge_id IS 'Challenge đúng ngữ cảnh';
COMMENT ON COLUMN registration_requests.otp_purpose IS 'Discriminator FK do DB sinh';
COMMENT ON COLUMN registration_requests.pending_first_name IS 'Phần tên của đúng lượt đăng ký';
COMMENT ON COLUMN registration_requests.pending_last_name IS 'Phần họ của đúng lượt đăng ký';
COMMENT ON COLUMN registration_requests.pending_password_hash IS 'BCrypt chờ xác nhận, được dọn khi kết thúc';
COMMENT ON COLUMN registration_requests.expires_at IS 'Hạn chu kỳ 7 ngày hoặc yêu cầu đổi 5 phút';
COMMENT ON COLUMN registration_requests.completed_at IS 'Hoàn tất';
COMMENT ON COLUMN registration_requests.invalidated_at IS 'Bị hủy/thay thế';
COMMENT ON COLUMN registration_requests.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN registration_requests.updated_at IS 'Thời điểm cập nhật';
CREATE UNIQUE INDEX uq_password_change_requests_open ON password_change_requests(user_id) WHERE completed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX ix_password_change_requests_expiry ON password_change_requests(expires_at);
COMMENT ON TABLE password_change_requests IS 'Ngữ cảnh chờ OTP, không lưu mật khẩu rõ.';
COMMENT ON COLUMN password_change_requests.id IS 'Định danh yêu cầu';
COMMENT ON COLUMN password_change_requests.user_id IS 'Chủ yêu cầu';
COMMENT ON COLUMN password_change_requests.otp_challenge_id IS 'Challenge đúng ngữ cảnh';
COMMENT ON COLUMN password_change_requests.otp_purpose IS 'Discriminator FK do DB sinh';
COMMENT ON COLUMN password_change_requests.session_id IS 'Chỉ phiên khởi tạo được hoàn tất';
COMMENT ON COLUMN password_change_requests.auth_version_at_request IS 'Phiên bản xác thực lúc kiểm tra mật khẩu cũ';
COMMENT ON COLUMN password_change_requests.pending_password_hash IS 'BCrypt chờ xác nhận, được dọn khi kết thúc';
COMMENT ON COLUMN password_change_requests.expires_at IS 'Hạn chu kỳ 7 ngày hoặc yêu cầu đổi 5 phút';
COMMENT ON COLUMN password_change_requests.completed_at IS 'Hoàn tất';
COMMENT ON COLUMN password_change_requests.invalidated_at IS 'Bị hủy/thay thế';
COMMENT ON COLUMN password_change_requests.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN password_change_requests.updated_at IS 'Thời điểm cập nhật';
CREATE UNIQUE INDEX uq_reset_grant_open ON password_reset_grants(user_id) WHERE used_at IS NULL AND invalidated_at IS NULL;
COMMENT ON TABLE password_reset_grants IS 'Quyền reset giới hạn, không phải role hay phiên đăng nhập.';
COMMENT ON COLUMN password_reset_grants.id IS 'ID grant';
COMMENT ON COLUMN password_reset_grants.user_id IS 'Chủ grant';
COMMENT ON COLUMN password_reset_grants.otp_challenge_id IS 'Một OTP chỉ cấp một grant';
COMMENT ON COLUMN password_reset_grants.otp_purpose IS 'Đúng purpose của OTP';
COMMENT ON COLUMN password_reset_grants.grant_hash IS 'Hash opaque grant, được dọn khi hết hiệu lực';
COMMENT ON COLUMN password_reset_grants.auth_version_at_issue IS 'Chống dùng grant cũ sau thay bảo mật';
COMMENT ON COLUMN password_reset_grants.issued_at IS 'Mốc cấp';
COMMENT ON COLUMN password_reset_grants.expires_at IS 'Tối đa 5 phút';
COMMENT ON COLUMN password_reset_grants.used_at IS 'Mốc sử dụng';
COMMENT ON COLUMN password_reset_grants.invalidated_at IS 'Mốc hủy/thay';
COMMENT ON TABLE auth_rate_limits IS 'Bộ giới hạn độc lập user/challenge ID, có cả email chưa đăng ký.';
COMMENT ON COLUMN auth_rate_limits.id IS 'Bucket ID';
COMMENT ON COLUMN auth_rate_limits.scope IS 'Quy tắc và mục đích, tách email/IP';
COMMENT ON COLUMN auth_rate_limits.subject_hash IS 'HMAC định danh chuẩn hóa';
COMMENT ON COLUMN auth_rate_limits.blocked_until IS 'Hạn chờ, không kéo dài vì request bị chặn';
COMMENT ON COLUMN auth_rate_limits.reset_at IS 'Mốc đặt lại lỗi, không xóa quota gửi';
COMMENT ON COLUMN auth_rate_limits.updated_at IS 'Mốc cập nhật';
CREATE INDEX ix_rate_events_window ON auth_rate_limit_events(rate_limit_id, occurred_at DESC);
COMMENT ON TABLE auth_rate_limit_events IS 'Sự kiện đếm trong cửa sổ thời gian trượt, không phải audit đầy đủ.';
COMMENT ON COLUMN auth_rate_limit_events.id IS 'ID sự kiện';
COMMENT ON COLUMN auth_rate_limit_events.rate_limit_id IS 'Bucket';
COMMENT ON COLUMN auth_rate_limit_events.occurred_at IS 'Mốc được tính';
COMMENT ON TABLE content_sources IS 'Xuất xứ và khai báo quyền sử dụng; ACTIVE không phải kết luận pháp lý.';
COMMENT ON COLUMN content_sources.id IS 'Source ID';
COMMENT ON COLUMN content_sources.title IS 'Tên nguồn';
COMMENT ON COLUMN content_sources.source_type IS 'Tự soạn hoặc bên ngoài';
COMMENT ON COLUMN content_sources.source_url IS 'URL nguồn gốc, không phải signed URL';
COMMENT ON COLUMN content_sources.author IS 'Tác giả/đơn vị, không tự đồng nhất người nhập';
COMMENT ON COLUMN content_sources.license_code IS 'Khai báo giấy phép, có thể thiếu ở Draft';
COMMENT ON COLUMN content_sources.license_url IS 'URL thông tin giấy phép';
COMMENT ON COLUMN content_sources.attribution IS 'Nội dung ghi nguồn';
COMMENT ON COLUMN content_sources.permission_note IS 'Thông tin khai báo nội bộ, không tự trả learner';
COMMENT ON COLUMN content_sources.status IS 'Chọn mới hay archive';
COMMENT ON COLUMN content_sources.created_by IS 'Người khai báo';
COMMENT ON COLUMN content_sources.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN content_sources.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_media_owner_purpose ON media_assets(uploaded_by, purpose, status);
CREATE INDEX ix_media_cleanup ON media_assets(status, archived_at);
COMMENT ON TABLE media_assets IS 'File lâu dài; object bất biến, xóa chỉ khi hết mọi tham chiếu.';
COMMENT ON COLUMN media_assets.id IS 'Media ID ổn định';
COMMENT ON COLUMN media_assets.media_type IS 'IMAGE/AUDIO hiện tại';
COMMENT ON COLUMN media_assets.purpose IS 'CONTENT/AVATAR';
COMMENT ON COLUMN media_assets.bucket IS 'Kho file cuối';
COMMENT ON COLUMN media_assets.object_key IS 'Key cuối, không phải URL';
COMMENT ON COLUMN media_assets.original_filename IS 'Tên để hiển thị';
COMMENT ON COLUMN media_assets.mime_type IS 'MIME thực tế sau xác minh';
COMMENT ON COLUMN media_assets.file_size_bytes IS 'Byte thực tế';
COMMENT ON COLUMN media_assets.duration_ms IS 'Thời lượng audio';
COMMENT ON COLUMN media_assets.width IS 'Chiều rộng ảnh';
COMMENT ON COLUMN media_assets.height IS 'Chiều cao ảnh';
COMMENT ON COLUMN media_assets.checksum_sha256 IS 'Hash file thực tế';
COMMENT ON COLUMN media_assets.source_id IS 'Nguồn chính của file, avatar tùy chọn';
COMMENT ON COLUMN media_assets.uploaded_by IS 'Chủ lượt upload';
COMMENT ON COLUMN media_assets.status IS 'Vòng đời file';
COMMENT ON COLUMN media_assets.failure_code IS 'Lỗi có kiểm soát';
COMMENT ON COLUMN media_assets.archived_at IS 'Mốc archive ổn định';
COMMENT ON COLUMN media_assets.deletion_started_at IS 'Claim ngăn cấp/gắn mới khi đang dọn';
COMMENT ON COLUMN media_assets.object_deleted_at IS 'Backend xác nhận đối tượng cuối đã xóa, khác việc archive';
COMMENT ON COLUMN media_assets.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN media_assets.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_uploads_open ON media_upload_requests(status, expires_at);
CREATE INDEX ix_uploads_claim ON media_upload_requests(lease_until) WHERE status='VERIFYING';
COMMENT ON TABLE media_upload_requests IS 'Ngữ cảnh upload tạm; finished_at không đổi khi dọn file.';
COMMENT ON COLUMN media_upload_requests.id IS 'Upload request ID';
COMMENT ON COLUMN media_upload_requests.media_id IS 'Một media cho một yêu cầu';
COMMENT ON COLUMN media_upload_requests.temp_bucket IS 'Kho tạm';
COMMENT ON COLUMN media_upload_requests.temp_object_key IS 'Key tạm duy nhất';
COMMENT ON COLUMN media_upload_requests.declared_size_bytes IS 'Byte khai báo, phải khớp file thật';
COMMENT ON COLUMN media_upload_requests.declared_mime_type IS 'MIME khai báo';
COMMENT ON COLUMN media_upload_requests.max_size_bytes IS 'Trần được backend cấp';
COMMENT ON COLUMN media_upload_requests.status IS 'Trạng thái yêu cầu';
COMMENT ON COLUMN media_upload_requests.expires_at IS 'Hạn nhận complete';
COMMENT ON COLUMN media_upload_requests.verification_started_at IS 'Mốc nhận complete hợp lệ';
COMMENT ON COLUMN media_upload_requests.verification_deadline_at IS 'Hạn toàn quá trình, không kéo dài retry';
COMMENT ON COLUMN media_upload_requests.claim_token IS 'Lượt xử lý đang giữ quyền';
COMMENT ON COLUMN media_upload_requests.lease_until IS 'Hạn quyền xử lý';
COMMENT ON COLUMN media_upload_requests.completed_at IS 'Mốc thành công';
COMMENT ON COLUMN media_upload_requests.finished_at IS 'Mốc kết thúc mọi trạng thái, cho retention ổn định';
COMMENT ON COLUMN media_upload_requests.failure_code IS 'Mã lỗi';
COMMENT ON COLUMN media_upload_requests.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN media_upload_requests.updated_at IS 'Thời điểm cập nhật';
CREATE UNIQUE INDEX uq_content_external_key ON content_items(content_type, import_namespace, external_key) WHERE external_key IS NOT NULL;
CREATE INDEX ix_content_catalog ON content_items(content_type, is_archived);
COMMENT ON TABLE content_items IS 'Định danh ổn định và pointer bản công bố; domain projection dùng cùng UUID.';
COMMENT ON COLUMN content_items.id IS 'Định danh dùng xuyên phiên bản';
COMMENT ON COLUMN content_items.content_type IS 'Loại nội dung, bất biến';
COMMENT ON COLUMN content_items.created_by IS 'Người khởi tạo';
COMMENT ON COLUMN content_items.published_revision_id IS 'Bản hiện hành, NULL khi chưa công bố';
COMMENT ON COLUMN content_items.is_archived IS 'Ngừng cấp mới';
COMMENT ON COLUMN content_items.archived_at IS 'Mốc archive';
COMMENT ON COLUMN content_items.access_revoked_at IS 'Thu hồi truy cập riêng với archive';
COMMENT ON COLUMN content_items.access_revoke_reason IS 'Lý do thu hồi';
COMMENT ON COLUMN content_items.import_namespace IS 'Namespace khóa ngoài nếu nhập có key';
COMMENT ON COLUMN content_items.external_key IS 'Nhận diện xuyên job, không tự suy theo lemma';
COMMENT ON COLUMN content_items.version IS 'Optimistic lock của item';
COMMENT ON COLUMN content_items.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN content_items.updated_at IS 'Thời điểm cập nhật';
CREATE UNIQUE INDEX uq_revision_one_open ON content_revisions(content_item_id) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_revisions_item_version ON content_revisions(content_item_id, version_no DESC);
COMMENT ON TABLE content_revisions IS 'Draft có schema; Published bất biến; một nhánh đang biên soạn mỗi item.';
COMMENT ON COLUMN content_revisions.id IS 'Revision ID';
COMMENT ON COLUMN content_revisions.content_item_id IS 'Nội dung sở hữu';
COMMENT ON COLUMN content_revisions.version_no IS 'Số thứ tự bản, khác optimistic version';
COMMENT ON COLUMN content_revisions.schema_version IS 'Schema payload theo content_type';
COMMENT ON COLUMN content_revisions.snapshot IS 'Nguồn chân lý của bản, Draft có thể thiếu';
COMMENT ON COLUMN content_revisions.status IS 'Vòng đời biên soạn';
COMMENT ON COLUMN content_revisions.change_summary IS 'Tóm tắt thay đổi';
COMMENT ON COLUMN content_revisions.created_by IS 'Người tạo bản';
COMMENT ON COLUMN content_revisions.reviewed_by IS 'Người duyệt/từ chối gần nhất';
COMMENT ON COLUMN content_revisions.review_note IS 'Lý do khi từ chối';
COMMENT ON COLUMN content_revisions.reviewed_at IS 'Mốc quyết định duyệt';
COMMENT ON COLUMN content_revisions.published_at IS 'Mốc công bố, không phải pointer hiện hành';
COMMENT ON COLUMN content_revisions.version IS 'Chống ghi đè Draft';
COMMENT ON COLUMN content_revisions.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN content_revisions.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_source_links_source ON content_source_links(source_id);
COMMENT ON TABLE content_source_links IS 'Nguồn theo từng revision; bản chụp nguồn lúc công bố.';
COMMENT ON COLUMN content_source_links.revision_id IS 'Bản nội dung';
COMMENT ON COLUMN content_source_links.source_id IS 'Nguồn';
COMMENT ON COLUMN content_source_links.usage_note IS 'Đoạn/ảnh/audio dùng nguồn này';
COMMENT ON COLUMN content_source_links.source_snapshot IS 'Bản chụp metadata; có trường nội bộ';
COMMENT ON COLUMN content_source_links.created_at IS 'Mốc gắn';
CREATE INDEX ix_revision_dependencies_child ON revision_dependencies(child_revision_id);
COMMENT ON TABLE revision_dependencies IS 'Ghim revision con; formal edge chỉ tới bản đã công bố.';
COMMENT ON COLUMN revision_dependencies.parent_revision_id IS 'Bản cha';
COMMENT ON COLUMN revision_dependencies.relation_key IS 'Khóa thành viên ổn định trong schema, ví dụ sense:UUID';
COMMENT ON COLUMN revision_dependencies.child_revision_id IS 'Bản con được ghim';
CREATE INDEX ix_revision_media_asset ON revision_media_refs(media_id);
COMMENT ON TABLE revision_media_refs IS 'FK tra cứu tất cả file trong Draft/Published, kể cả ở JSON.';
COMMENT ON COLUMN revision_media_refs.revision_id IS 'Revision dùng file';
COMMENT ON COLUMN revision_media_refs.media_id IS 'File cần bảo toàn';
COMMENT ON COLUMN revision_media_refs.usage_role IS 'Phân biệt học liệu và lời giải/transcript';
CREATE INDEX ix_words_search ON words(lower(lemma) varchar_pattern_ops);
COMMENT ON TABLE words IS 'Projection từ hiện hành; nghĩa là đơn vị theo dõi học.';
COMMENT ON COLUMN words.id IS 'Cùng UUID với content_items, không tạo định danh thứ hai';
COMMENT ON COLUMN words.content_type IS 'Loại cố định, không nhận từ client';
COMMENT ON COLUMN words.published_revision_id IS 'Bản nguồn của projection hiện hành';
COMMENT ON COLUMN words.lemma IS 'Từ/biểu thức, không unique tuyệt đối';
COMMENT ON COLUMN words.language_code IS 'Ngôn ngữ';
COMMENT ON COLUMN words.ipa IS 'Phiên âm';
COMMENT ON COLUMN words.audio_media_id IS 'Audio chung';
COMMENT ON COLUMN words.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN words.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_senses_filters ON word_senses(cefr_level, part_of_speech) WHERE is_archived=FALSE;
CREATE UNIQUE INDEX uq_senses_active_position ON word_senses(word_id, sense_no) WHERE is_archived=FALSE;
COMMENT ON TABLE word_senses IS 'Nghĩa ổn định; đổi hẳn nghĩa tạo ID mới, không chuyển SRS.';
COMMENT ON COLUMN word_senses.id IS 'Định danh nghĩa';
COMMENT ON COLUMN word_senses.word_id IS 'Từ sở hữu';
COMMENT ON COLUMN word_senses.word_revision_id IS 'Bản từ sinh projection hiện tại của nghĩa';
COMMENT ON COLUMN word_senses.sense_no IS 'Thứ tự nghĩa';
COMMENT ON COLUMN word_senses.part_of_speech IS 'Từ loại';
COMMENT ON COLUMN word_senses.cefr_level IS 'Cấp độ của nghĩa';
COMMENT ON COLUMN word_senses.meaning_vi IS 'Nghĩa Việt';
COMMENT ON COLUMN word_senses.definition_en IS 'Định nghĩa Anh';
COMMENT ON COLUMN word_senses.audio_media_id IS 'Ghi đè audio chung nếu có';
COMMENT ON COLUMN word_senses.is_archived IS 'Giữ progress cũ khi bỏ nghĩa';
COMMENT ON COLUMN word_senses.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN word_senses.updated_at IS 'Thời điểm cập nhật';
COMMENT ON TABLE word_sense_examples IS 'Nhiều ví dụ của một nghĩa, cặp Anh/Việt có thứ tự.';
COMMENT ON COLUMN word_sense_examples.id IS 'Example ID';
COMMENT ON COLUMN word_sense_examples.word_sense_id IS 'Nghĩa';
COMMENT ON COLUMN word_sense_examples.position IS 'Thứ tự ví dụ';
COMMENT ON COLUMN word_sense_examples.example_en IS 'Câu tiếng Anh';
COMMENT ON COLUMN word_sense_examples.example_vi IS 'Bản dịch tùy chọn';
COMMENT ON TABLE decks IS 'Deck công bố, thành viên theo nghĩa và revision đã ghim.';
COMMENT ON COLUMN decks.id IS 'Cùng UUID với content_items, không tạo định danh thứ hai';
COMMENT ON COLUMN decks.content_type IS 'Loại cố định, không nhận từ client';
COMMENT ON COLUMN decks.published_revision_id IS 'Bản nguồn của projection hiện hành';
COMMENT ON COLUMN decks.title IS 'Tên deck';
COMMENT ON COLUMN decks.description IS 'Mô tả';
COMMENT ON COLUMN decks.level IS 'Mức gợi ý, không thay CEFR ở nghĩa';
COMMENT ON COLUMN decks.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN decks.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_deck_words_sense ON deck_words(word_sense_id);
COMMENT ON TABLE deck_words IS 'Thành viên projection của deck; runtime dùng bản ghim khi giao bài.';
COMMENT ON COLUMN deck_words.deck_id IS 'Deck';
COMMENT ON COLUMN deck_words.word_sense_id IS 'Nghĩa';
COMMENT ON COLUMN deck_words.position IS 'Thứ tự';
COMMENT ON TABLE user_saved_words IS 'Bỏ lưu chỉ xóa liên kết này, không xóa SRS.';
COMMENT ON COLUMN user_saved_words.user_id IS 'Người lưu';
COMMENT ON COLUMN user_saved_words.word_sense_id IS 'Nghĩa được lưu';
COMMENT ON COLUMN user_saved_words.saved_at IS 'Mốc lưu';
CREATE INDEX ix_progress_due ON user_word_progress(user_id, due_at);
COMMENT ON TABLE user_word_progress IS 'Trạng thái SRS hiện tại, khóa theo user và nghĩa.';
COMMENT ON COLUMN user_word_progress.user_id IS 'Người học';
COMMENT ON COLUMN user_word_progress.word_sense_id IS 'Nghĩa';
COMMENT ON COLUMN user_word_progress.repetitions IS 'Chuỗi nhớ đạt';
COMMENT ON COLUMN user_word_progress.interval_days IS 'Khoảng ngày hiện tại; học lại ngắn có thể bằng 0';
COMMENT ON COLUMN user_word_progress.ease_factor IS 'Hệ số, không dưới 1.3';
COMMENT ON COLUMN user_word_progress.due_at IS 'Hạn ôn do server tính';
COMMENT ON COLUMN user_word_progress.last_reviewed_at IS 'Lần review gần nhất';
COMMENT ON COLUMN user_word_progress.policy_version IS 'Thuật toán đã dùng';
COMMENT ON COLUMN user_word_progress.version IS 'Chống ghi đè';
COMMENT ON COLUMN user_word_progress.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN user_word_progress.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_reviews_user_day ON word_reviews(user_id, local_day);
CREATE INDEX ix_reviews_sense_time ON word_reviews(user_id, word_sense_id, reviewed_at DESC);
COMMENT ON TABLE word_reviews IS 'Sự kiện review bất biến và idempotent, có chính sách/trạng thái trước-sau.';
COMMENT ON COLUMN word_reviews.id IS 'Review ID';
COMMENT ON COLUMN word_reviews.user_id IS 'Người học';
COMMENT ON COLUMN word_reviews.word_sense_id IS 'Nghĩa';
COMMENT ON COLUMN word_reviews.source_word_id IS 'Từ chứa nghĩa đã xem';
COMMENT ON COLUMN word_reviews.source_revision_id IS 'Bản từ đã xem';
COMMENT ON COLUMN word_reviews.request_id IS 'Retry dùng lại cùng key';
COMMENT ON COLUMN word_reviews.request_hash IS 'Phát hiện cùng key nhưng payload khác';
COMMENT ON COLUMN word_reviews.rating IS 'AGAIN/HARD/GOOD sau khi lật';
COMMENT ON COLUMN word_reviews.policy_version IS 'Bản thuật toán';
COMMENT ON COLUMN word_reviews.before_state IS 'Trạng thái SRS trước review';
COMMENT ON COLUMN word_reviews.after_state IS 'Trạng thái SRS sau review';
COMMENT ON COLUMN word_reviews.previous_due_at IS 'Hạn cũ';
COMMENT ON COLUMN word_reviews.next_due_at IS 'Hạn mới';
COMMENT ON COLUMN word_reviews.reviewed_at IS 'Server time';
COMMENT ON COLUMN word_reviews.timezone IS 'Múi giờ tại thời điểm ghi nhận';
COMMENT ON COLUMN word_reviews.local_day IS 'Ngày thống kê ổn định';
COMMENT ON TABLE grammar_topics IS 'Chủ điểm, không thêm cây curriculum nhiều cấp ngoài phạm vi.';
COMMENT ON COLUMN grammar_topics.id IS 'Cùng UUID với content_items, không tạo định danh thứ hai';
COMMENT ON COLUMN grammar_topics.content_type IS 'Loại cố định, không nhận từ client';
COMMENT ON COLUMN grammar_topics.published_revision_id IS 'Bản nguồn của projection hiện hành';
COMMENT ON COLUMN grammar_topics.slug IS 'Đường dẫn ổn định';
COMMENT ON COLUMN grammar_topics.name IS 'Tên chủ điểm';
COMMENT ON COLUMN grammar_topics.description IS 'Mô tả';
COMMENT ON COLUMN grammar_topics.difficulty IS 'Mức gợi ý';
COMMENT ON COLUMN grammar_topics.sort_order IS 'Thứ tự';
COMMENT ON COLUMN grammar_topics.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN grammar_topics.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_lessons_topic_order ON grammar_lessons(topic_id, sort_order);
COMMENT ON TABLE grammar_lessons IS 'Bài học khối có schema, thuộc một topic.';
COMMENT ON COLUMN grammar_lessons.id IS 'Cùng UUID với content_items, không tạo định danh thứ hai';
COMMENT ON COLUMN grammar_lessons.content_type IS 'Loại cố định, không nhận từ client';
COMMENT ON COLUMN grammar_lessons.published_revision_id IS 'Bản nguồn của projection hiện hành';
COMMENT ON COLUMN grammar_lessons.topic_id IS 'Chủ điểm';
COMMENT ON COLUMN grammar_lessons.title IS 'Tiêu đề';
COMMENT ON COLUMN grammar_lessons.body_schema_version IS 'Schema khối';
COMMENT ON COLUMN grammar_lessons.body IS 'Các block được kiểm soát';
COMMENT ON COLUMN grammar_lessons.sort_order IS 'Thứ tự trong chủ điểm';
COMMENT ON COLUMN grammar_lessons.estimated_minutes IS 'Thời gian gợi ý, không phải phút học thực tế';
COMMENT ON COLUMN grammar_lessons.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN grammar_lessons.updated_at IS 'Thời điểm cập nhật';
COMMENT ON TABLE lesson_progress IS 'Hoàn thành bằng thao tác người học; giữ bản đã hoàn thành khi có bản mới.';
COMMENT ON COLUMN lesson_progress.user_id IS 'Người học';
COMMENT ON COLUMN lesson_progress.lesson_id IS 'Bài học';
COMMENT ON COLUMN lesson_progress.state IS 'Không có row là NOT_STARTED';
COMMENT ON COLUMN lesson_progress.last_completed_revision_id IS 'Bản đã hoàn thành';
COMMENT ON COLUMN lesson_progress.started_at IS 'Mở học';
COMMENT ON COLUMN lesson_progress.completed_at IS 'Lần hoàn thành';
COMMENT ON COLUMN lesson_progress.version IS 'Chống ghi đè';
COMMENT ON COLUMN lesson_progress.updated_at IS 'Mốc cập nhật';
COMMENT ON TABLE question_types IS 'Catalogue cách trả lời; chỉ bật code có renderer/grader đã triển khai.';
COMMENT ON COLUMN question_types.code IS 'SINGLE_CHOICE ban đầu';
COMMENT ON COLUMN question_types.name IS 'Nhãn';
COMMENT ON COLUMN question_types.schema_version IS 'Phiên bản response';
COMMENT ON COLUMN question_types.enabled IS 'Không tự hỗ trợ type bằng thêm row';
COMMENT ON TABLE question_groups IS 'Ngữ cảnh cha; group type không thay question response type.';
COMMENT ON COLUMN question_groups.id IS 'Cùng UUID với content_items, không tạo định danh thứ hai';
COMMENT ON COLUMN question_groups.content_type IS 'Loại cố định, không nhận từ client';
COMMENT ON COLUMN question_groups.published_revision_id IS 'Bản nguồn của projection hiện hành';
COMMENT ON COLUMN question_groups.category IS 'Mảng kiến thức';
COMMENT ON COLUMN question_groups.part_number IS 'Part nếu TOEIC';
COMMENT ON COLUMN question_groups.title IS 'Tên nhóm';
COMMENT ON COLUMN question_groups.group_type IS 'Loại ngữ cảnh';
COMMENT ON COLUMN question_groups.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN question_groups.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_questions_filters ON questions(category, part_number, question_type_code, difficulty);
CREATE UNIQUE INDEX uq_group_current_position ON questions(group_id, group_position) WHERE in_current_group=TRUE;
CREATE UNIQUE INDEX uq_group_current_blank ON questions(group_id, blank_key) WHERE in_current_group=TRUE;
COMMENT ON TABLE questions IS 'Câu chấm được; mỗi câu tối đa một nhóm; không dùng group_questions N:N.';
COMMENT ON COLUMN questions.id IS 'Cùng UUID với content_items, không tạo định danh thứ hai';
COMMENT ON COLUMN questions.content_type IS 'Loại cố định, không nhận từ client';
COMMENT ON COLUMN questions.published_revision_id IS 'Bản nguồn của projection hiện hành';
COMMENT ON COLUMN questions.category IS 'Vocabulary/Grammar/TOEIC';
COMMENT ON COLUMN questions.question_type_code IS 'Cơ chế trả lời';
COMMENT ON COLUMN questions.practice_mode IS 'Kiểu luyện, không phải grader';
COMMENT ON COLUMN questions.part_number IS 'TOEIC Part';
COMMENT ON COLUMN questions.prompt IS 'Có thể trống cho câu nghe';
COMMENT ON COLUMN questions.explanation IS 'Lời giải, chỉ cấp sau nộp';
COMMENT ON COLUMN questions.difficulty IS 'Độ khó';
COMMENT ON COLUMN questions.group_id IS 'Context cha nếu có';
COMMENT ON COLUMN questions.group_position IS 'Thứ tự câu con';
COMMENT ON COLUMN questions.in_current_group IS 'Membership projection hiện hành; giữ context cũ khi thay câu';
COMMENT ON COLUMN questions.blank_key IS 'Neo vị trí trống Part 6';
COMMENT ON COLUMN questions.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN questions.updated_at IS 'Thời điểm cập nhật';
CREATE UNIQUE INDEX uq_options_one_correct ON question_options(question_id) WHERE is_correct=TRUE;
COMMENT ON TABLE question_options IS 'Lựa chọn của projection; đúng một đáp án khi commit.';
COMMENT ON COLUMN question_options.id IS 'ID lựa chọn ổn định trong revision/snapshot';
COMMENT ON COLUMN question_options.question_id IS 'Câu sở hữu';
COMMENT ON COLUMN question_options.position IS 'Vị trí/nhãn hiển thị';
COMMENT ON COLUMN question_options.body IS 'Có thể trống với phương án chỉ nằm trong audio TOEIC';
COMMENT ON COLUMN question_options.is_correct IS 'Chỉ backend/biên soạn, không DTO trước nộp';
COMMENT ON TABLE group_materials IS 'Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus.';
COMMENT ON COLUMN group_materials.id IS 'Material ID/anchor ổn định';
COMMENT ON COLUMN group_materials.group_id IS 'Nội dung sở hữu';
COMMENT ON COLUMN group_materials.position IS 'Thứ tự trình bày';
COMMENT ON COLUMN group_materials.document_key IS 'Ghép các block cùng tài liệu; Part 7 có 1–3 tài liệu';
COMMENT ON COLUMN group_materials.material_type IS 'TEXT/IMAGE/AUDIO';
COMMENT ON COLUMN group_materials.usage_role IS 'Quyền hiển thị theo thời điểm';
COMMENT ON COLUMN group_materials.media_id IS 'File nếu ảnh/audio';
COMMENT ON COLUMN group_materials.body IS 'Text/Markdown, chứa blank marker có schema nếu cần';
COMMENT ON TABLE question_materials IS 'Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus.';
COMMENT ON COLUMN question_materials.id IS 'Material ID/anchor ổn định';
COMMENT ON COLUMN question_materials.question_id IS 'Nội dung sở hữu';
COMMENT ON COLUMN question_materials.position IS 'Thứ tự trình bày';
COMMENT ON COLUMN question_materials.document_key IS 'Ghép các block cùng tài liệu; Part 7 có 1–3 tài liệu';
COMMENT ON COLUMN question_materials.material_type IS 'TEXT/IMAGE/AUDIO';
COMMENT ON COLUMN question_materials.usage_role IS 'Quyền hiển thị theo thời điểm';
COMMENT ON COLUMN question_materials.media_id IS 'File nếu ảnh/audio';
COMMENT ON COLUMN question_materials.body IS 'Text/Markdown, chứa blank marker có schema nếu cần';
CREATE INDEX ix_sets_filters ON practice_sets(category, part_number);
COMMENT ON TABLE practice_sets IS 'Bộ luyện hiện hành; FIXED có set_items, DYNAMIC có selection_config.';
COMMENT ON COLUMN practice_sets.id IS 'Cùng UUID với content_items, không tạo định danh thứ hai';
COMMENT ON COLUMN practice_sets.content_type IS 'Loại cố định, không nhận từ client';
COMMENT ON COLUMN practice_sets.published_revision_id IS 'Bản nguồn của projection hiện hành';
COMMENT ON COLUMN practice_sets.category IS 'Một category mỗi bộ/lượt';
COMMENT ON COLUMN practice_sets.title IS 'Tên bộ';
COMMENT ON COLUMN practice_sets.selection_mode IS 'FIXED/DYNAMIC';
COMMENT ON COLUMN practice_sets.selection_config IS 'Filter có schema, không bỏ qua kiểm tra IDs';
COMMENT ON COLUMN practice_sets.deck_id IS 'Nguồn Vocabulary nếu có';
COMMENT ON COLUMN practice_sets.topic_id IS 'Chủ điểm đơn nếu có';
COMMENT ON COLUMN practice_sets.part_number IS 'Part TOEIC';
COMMENT ON COLUMN practice_sets.time_limit_seconds IS 'NULL là không đếm ngược';
COMMENT ON COLUMN practice_sets.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN practice_sets.updated_at IS 'Thời điểm cập nhật';
CREATE UNIQUE INDEX uq_set_question ON set_items(set_id, question_id) WHERE question_id IS NOT NULL;
CREATE UNIQUE INDEX uq_set_group ON set_items(set_id, group_id) WHERE group_id IS NOT NULL;
COMMENT ON TABLE set_items IS 'Một vị trí chọn câu độc lập XOR cả nhóm.';
COMMENT ON COLUMN set_items.id IS 'Set member ID';
COMMENT ON COLUMN set_items.set_id IS 'Bộ';
COMMENT ON COLUMN set_items.position IS 'Thứ tự đơn vị';
COMMENT ON COLUMN set_items.question_id IS 'Chỉ câu độc lập';
COMMENT ON COLUMN set_items.group_id IS 'Hoặc cả nhóm';
CREATE INDEX ix_question_words_target ON question_words(word_sense_id);
COMMENT ON TABLE question_words IS 'Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic.';
COMMENT ON COLUMN question_words.question_id IS 'Câu hỏi';
COMMENT ON COLUMN question_words.word_sense_id IS 'Kiến thức liên quan';
CREATE INDEX ix_question_topics_target ON question_topics(topic_id);
COMMENT ON TABLE question_topics IS 'Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic.';
COMMENT ON COLUMN question_topics.question_id IS 'Câu hỏi';
COMMENT ON COLUMN question_topics.topic_id IS 'Kiến thức liên quan';
CREATE INDEX ix_attempts_user_history ON attempts(user_id, started_at DESC);
CREATE INDEX ix_attempts_deadline ON attempts(deadline_at) WHERE status='IN_PROGRESS';
CREATE INDEX ix_attempts_user_open ON attempts(user_id, last_active_at) WHERE status='IN_PROGRESS';
COMMENT ON TABLE attempts IS 'Nguồn và kết quả lượt làm; tạo idempotent, snapshot được seal trước khi giao.';
COMMENT ON COLUMN attempts.id IS 'Attempt ID';
COMMENT ON COLUMN attempts.user_id IS 'Chủ bài, chỉ chủ được làm/xem theo quyền';
COMMENT ON COLUMN attempts.category IS 'Một category';
COMMENT ON COLUMN attempts.source_type IS 'Nguồn tạo bài';
COMMENT ON COLUMN attempts.source_set_id IS 'Bộ luyện nếu có';
COMMENT ON COLUMN attempts.source_deck_id IS 'Deck nếu có';
COMMENT ON COLUMN attempts.source_topic_id IS 'Topic đơn nếu có';
COMMENT ON COLUMN attempts.source_revision_id IS 'Bản nguồn chính nếu có';
COMMENT ON COLUMN attempts.source_config IS 'Filter/kiểu/số lượng/revision có schema';
COMMENT ON COLUMN attempts.request_id IS 'Idempotency-Key khi bắt đầu';
COMMENT ON COLUMN attempts.request_hash IS 'Fingerprint payload gốc';
COMMENT ON COLUMN attempts.status IS 'Trạng thái bài';
COMMENT ON COLUMN attempts.total_count IS 'Số câu con, không phải số nhóm';
COMMENT ON COLUMN attempts.correct_count IS 'Số đúng sau nộp';
COMMENT ON COLUMN attempts.answered_count IS 'Số câu có chọn đáp án';
COMMENT ON COLUMN attempts.score IS 'Một điểm/câu con đúng';
COMMENT ON COLUMN attempts.snapshot_sealed_at IS 'Không được sửa đề sau seal';
COMMENT ON COLUMN attempts.started_at IS 'Mốc bắt đầu';
COMMENT ON COLUMN attempts.last_active_at IS 'Phục vụ bỏ dở';
COMMENT ON COLUMN attempts.deadline_at IS 'NULL là không giới hạn giờ';
COMMENT ON COLUMN attempts.submitted_at IS 'Mốc backend ghi kết quả';
COMMENT ON COLUMN attempts.effective_finished_at IS 'Deadline nếu tự nộp muộn, không cộng độ trễ';
COMMENT ON COLUMN attempts.submit_reason IS 'MANUAL/TIME_LIMIT';
COMMENT ON COLUMN attempts.abandoned_at IS 'Chủ động bỏ hoặc hết hạn hoạt động';
COMMENT ON COLUMN attempts.duration_seconds IS 'Thời gian bài, không phải phút học hoạt động';
COMMENT ON COLUMN attempts.timezone IS 'Múi giờ ghi nhận ngày';
COMMENT ON COLUMN attempts.local_day IS 'Ngày kết quả theo múi giờ lúc nộp';
COMMENT ON COLUMN attempts.version IS 'Optimistic lock của trạng thái';
COMMENT ON COLUMN attempts.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN attempts.updated_at IS 'Thời điểm cập nhật';
COMMENT ON TABLE attempt_source_topics IS 'Nguồn nhiều topic có FK, không chỉ giấu ID trong JSON.';
COMMENT ON COLUMN attempt_source_topics.attempt_id IS 'Lượt làm';
COMMENT ON COLUMN attempt_source_topics.topic_id IS 'Chủ điểm';
COMMENT ON COLUMN attempt_source_topics.source_revision_id IS 'Bản topic ghim';
COMMENT ON TABLE attempt_group_snapshots IS 'Ngữ cảnh nhóm chung lưu một lần trong mỗi lượt làm.';
COMMENT ON COLUMN attempt_group_snapshots.id IS 'Group snapshot ID';
COMMENT ON COLUMN attempt_group_snapshots.attempt_id IS 'Lượt sở hữu';
COMMENT ON COLUMN attempt_group_snapshots.group_id IS 'Nhóm nguồn';
COMMENT ON COLUMN attempt_group_snapshots.source_revision_id IS 'Bản nhóm đã ghim';
COMMENT ON COLUMN attempt_group_snapshots.ordinal IS 'Thứ tự đơn vị trong bài';
COMMENT ON COLUMN attempt_group_snapshots.schema_version IS 'Schema render nhóm';
COMMENT ON COLUMN attempt_group_snapshots.category IS 'Category của bộ câu trong nhóm';
COMMENT ON COLUMN attempt_group_snapshots.part_number IS 'Part đã ghim';
COMMENT ON COLUMN attempt_group_snapshots.expected_question_count IS 'Đủ nguyên nhóm khi seal';
COMMENT ON COLUMN attempt_group_snapshots.public_snapshot IS 'Stimulus công khai, không transcript/key';
COMMENT ON COLUMN attempt_group_snapshots.private_snapshot IS 'Transcript/lời giải riêng';
CREATE INDEX ix_snapshot_question ON attempt_question_snapshots(question_id, attempt_id);
COMMENT ON TABLE attempt_question_snapshots IS 'Đề giao bất biến, đáp án chuẩn/lời giải tách khỏi dữ liệu trước nộp.';
COMMENT ON COLUMN attempt_question_snapshots.id IS 'Snapshot ID dùng để lưu đáp án';
COMMENT ON COLUMN attempt_question_snapshots.attempt_id IS 'Lượt sở hữu';
COMMENT ON COLUMN attempt_question_snapshots.question_id IS 'Câu nguồn ổn định';
COMMENT ON COLUMN attempt_question_snapshots.source_revision_id IS 'Bản câu đã ghim';
COMMENT ON COLUMN attempt_question_snapshots.group_snapshot_id IS 'Ngữ cảnh nhóm trong đúng lượt';
COMMENT ON COLUMN attempt_question_snapshots.category IS 'Discriminator FK: không trộn category trong bài';
COMMENT ON COLUMN attempt_question_snapshots.ordinal IS 'Thứ tự toàn bài';
COMMENT ON COLUMN attempt_question_snapshots.question_type_code IS 'Renderer/grader';
COMMENT ON COLUMN attempt_question_snapshots.schema_version IS 'Phiên bản snapshot';
COMMENT ON COLUMN attempt_question_snapshots.grading_policy_version IS 'Chính sách chấm đã đóng băng';
COMMENT ON COLUMN attempt_question_snapshots.question_snapshot IS 'Payload trước nộp được kiểm soát';
COMMENT ON COLUMN attempt_question_snapshots.answer_key_snapshot IS 'Key riêng tư';
COMMENT ON COLUMN attempt_question_snapshots.explanation_snapshot IS 'Lời giải riêng tư';
COMMENT ON TABLE attempt_snapshot_options IS 'Option thuộc đúng snapshot, không FK tới lựa chọn hiện hành có thể sửa.';
COMMENT ON COLUMN attempt_snapshot_options.snapshot_id IS 'Câu snapshot';
COMMENT ON COLUMN attempt_snapshot_options.option_id IS 'ID option trong bản đã giao';
COMMENT ON COLUMN attempt_snapshot_options.position IS 'Nhãn/vị trí không đổi';
COMMENT ON COLUMN attempt_snapshot_options.public_body IS 'NULL khi phương án chỉ nằm trong audio TOEIC';
CREATE INDEX ix_answers_attempt ON attempt_answers(attempt_id);
COMMENT ON TABLE attempt_answers IS 'Một đáp án hiện tại mỗi câu; version cả khi bỏ chọn.';
COMMENT ON COLUMN attempt_answers.id IS 'Answer ID';
COMMENT ON COLUMN attempt_answers.attempt_id IS 'Lượt sở hữu';
COMMENT ON COLUMN attempt_answers.snapshot_id IS 'Câu snapshot';
COMMENT ON COLUMN attempt_answers.selected_option_id IS 'NULL nghĩa là bỏ trống';
COMMENT ON COLUMN attempt_answers.response_payload IS 'Dành cho schema type mở rộng, SINGLE_CHOICE dùng selected_option_id';
COMMENT ON COLUMN attempt_answers.version IS 'Optimistic version khi autosave';
COMMENT ON COLUMN attempt_answers.answered_at IS 'NULL nếu đang bỏ trống';
COMMENT ON COLUMN attempt_answers.is_correct IS 'Chỉ server ghi khi chấm';
COMMENT ON COLUMN attempt_answers.score IS '0/1 theo chính sách ban đầu';
COMMENT ON COLUMN attempt_answers.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN attempt_answers.updated_at IS 'Thời điểm cập nhật';
CREATE INDEX ix_attempt_media_asset ON attempt_media_refs(media_id);
COMMENT ON TABLE attempt_media_refs IS 'File trong đề/lời giải snapshot; bảo toàn độc lập projection hiện hành.';
COMMENT ON COLUMN attempt_media_refs.attempt_id IS 'Lượt làm';
COMMENT ON COLUMN attempt_media_refs.media_id IS 'File phải giữ';
COMMENT ON COLUMN attempt_media_refs.usage_role IS 'Quyền đọc trước/sau nộp';
CREATE INDEX ix_mistakes_user_state ON mistake_records(user_id, state, last_wrong_at DESC);
COMMENT ON TABLE mistake_records IS 'Lỗi theo user/câu; lịch sử sự kiện gốc ở các kết quả bất biến.';
COMMENT ON COLUMN mistake_records.id IS 'Mistake ID';
COMMENT ON COLUMN mistake_records.user_id IS 'Chủ lỗi';
COMMENT ON COLUMN mistake_records.question_id IS 'Câu ổn định';
COMMENT ON COLUMN mistake_records.source_attempt_id IS 'Bài sai gần nhất';
COMMENT ON COLUMN mistake_records.source_snapshot_id IS 'Snapshot sai tương ứng';
COMMENT ON COLUMN mistake_records.state IS 'OPEN/IMPROVING/RESOLVED';
COMMENT ON COLUMN mistake_records.wrong_count IS 'Tổng lần sai/bỏ trống ở bài đã nộp';
COMMENT ON COLUMN mistake_records.consecutive_correct IS 'Chuỗi đúng sau lỗi gần nhất';
COMMENT ON COLUMN mistake_records.last_wrong_at IS 'Theo thời gian kết thúc hiệu lực';
COMMENT ON COLUMN mistake_records.last_evaluated_at IS 'Kết quả mới nhất theo thời gian hiệu lực';
COMMENT ON COLUMN mistake_records.resolved_at IS 'Khi đạt ít nhất hai lần đúng liên tiếp';
COMMENT ON COLUMN mistake_records.updated_at IS 'Mốc projection cập nhật';
COMMENT ON TABLE attempt_source_mistakes IS 'Lỗi chọn để luyện lại phải thuộc đúng chủ lượt làm.';
COMMENT ON COLUMN attempt_source_mistakes.attempt_id IS 'Lượt luyện lại';
COMMENT ON COLUMN attempt_source_mistakes.user_id IS 'Discriminator bảo đảm ownership bằng FK';
COMMENT ON COLUMN attempt_source_mistakes.mistake_id IS 'Lỗi đã chọn';
COMMENT ON TABLE learning_goals IS 'Một mục tiêu hiện tại, không diễn giải thành trình độ được dự đoán.';
COMMENT ON COLUMN learning_goals.user_id IS 'Người học có LEARNER';
COMMENT ON COLUMN learning_goals.target_score IS 'Điểm đích TOEIC';
COMMENT ON COLUMN learning_goals.daily_minutes IS 'Phút/ngày';
COMMENT ON COLUMN learning_goals.target_date IS 'Ngày đích tùy chọn, API kiểm tra theo timezone';
COMMENT ON COLUMN learning_goals.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN learning_goals.updated_at IS 'Thời điểm cập nhật';
CREATE UNIQUE INDEX uq_study_one_open ON study_sessions(user_id) WHERE closed_at IS NULL;
CREATE INDEX ix_study_day ON study_sessions(user_id, local_day);
COMMENT ON TABLE study_sessions IS 'Đoạn thời gian hoạt động ước tính, không cộng trùng nhiều tab/thiết bị.';
COMMENT ON COLUMN study_sessions.id IS 'Đoạn hoạt động';
COMMENT ON COLUMN study_sessions.user_id IS 'Chủ đoạn';
COMMENT ON COLUMN study_sessions.source_type IS 'FLASHCARD/LESSON/PRACTICE';
COMMENT ON COLUMN study_sessions.word_sense_id IS 'Ngữ cảnh thẻ nếu có';
COMMENT ON COLUMN study_sessions.lesson_id IS 'Ngữ cảnh bài học nếu có';
COMMENT ON COLUMN study_sessions.attempt_id IS 'Ngữ cảnh luyện nếu có';
COMMENT ON COLUMN study_sessions.timezone IS 'Timezone ở thời điểm ghi nhận';
COMMENT ON COLUMN study_sessions.local_day IS 'Chia đoạn khi qua ngày/đổi timezone';
COMMENT ON COLUMN study_sessions.credit_token IS 'Chủ nhịp ghi nhận hiện tại, không thay đăng nhập';
COMMENT ON COLUMN study_sessions.started_at IS 'Mốc bắt đầu';
COMMENT ON COLUMN study_sessions.last_credited_at IS 'Server cập nhật, không tin client clock';
COMMENT ON COLUMN study_sessions.closed_at IS 'Đóng khi ngừng/đổi ngữ cảnh';
COMMENT ON COLUMN study_sessions.active_seconds IS 'Mỗi heartbeat cộng tối đa 30 giây';
COMMENT ON COLUMN study_sessions.version IS 'Chống cộng lại';
COMMENT ON TABLE notification_preferences IS 'Nhắc học opt-in theo giờ; email bảo mật không phụ thuộc email nhắc học.';
COMMENT ON COLUMN notification_preferences.user_id IS 'Người nhận';
COMMENT ON COLUMN notification_preferences.in_app_enabled IS 'Kênh in-app';
COMMENT ON COLUMN notification_preferences.email_enabled IS 'Email nhắc học, không phải OTP';
COMMENT ON COLUMN notification_preferences.daily_reminder_time IS 'NULL là chưa đặt lịch';
COMMENT ON COLUMN notification_preferences.timezone IS 'IANA timezone';
COMMENT ON COLUMN notification_preferences.quiet_hours_start IS 'NULL cùng quiet_hours_end nếu tắt';
COMMENT ON COLUMN notification_preferences.quiet_hours_end IS 'Cho phép qua nửa đêm';
COMMENT ON COLUMN notification_preferences.updated_at IS 'Mốc sửa';
COMMENT ON TABLE reminder_rules IS 'Danh mục quy tắc nhắc, không có chiến dịch quảng bá/browser push.';
COMMENT ON COLUMN reminder_rules.id IS 'Rule ID';
COMMENT ON COLUMN reminder_rules.code IS 'Mã do backend hỗ trợ';
COMMENT ON COLUMN reminder_rules.enabled IS 'Cờ vận hành';
COMMENT ON COLUMN reminder_rules.minimum_gap_minutes IS 'Khoảng tối thiểu giữa các nhắc cùng loại';
COMMENT ON COLUMN reminder_rules.created_at IS 'Mốc tạo';
CREATE INDEX ix_notifications_feed ON notifications(user_id, created_at DESC) WHERE visibility='USER';
COMMENT ON TABLE notifications IS 'Thông báo/dedupe; bản ghi INTERNAL dùng cho mail bảo mật, không vào feed learner.';
COMMENT ON COLUMN notifications.id IS 'Notification ID';
COMMENT ON COLUMN notifications.user_id IS 'Người nhận';
COMMENT ON COLUMN notifications.reminder_rule_id IS 'Quy tắc nhắc nếu có';
COMMENT ON COLUMN notifications.type IS 'Loại sự kiện';
COMMENT ON COLUMN notifications.visibility IS 'USER hoặc INTERNAL';
COMMENT ON COLUMN notifications.title IS 'Không chứa secret';
COMMENT ON COLUMN notifications.body IS 'Nội dung công khai, không chứa OTP/token';
COMMENT ON COLUMN notifications.target_url IS 'Chỉ đường dẫn nội bộ';
COMMENT ON COLUMN notifications.dedupe_key IS 'User/sự kiện/ngày/ngữ cảnh';
COMMENT ON COLUMN notifications.read_at IS 'Mốc đọc';
COMMENT ON COLUMN notifications.created_at IS 'Mốc tạo';
CREATE INDEX ix_delivery_pending ON notification_deliveries(next_retry_at, created_at) WHERE status='PENDING';
CREATE INDEX ix_delivery_lease ON notification_deliveries(lease_until) WHERE status='PROCESSING';
COMMENT ON TABLE notification_deliveries IS 'Queue gửi có claim/lease; payload OTP chỉ lưu mã hóa, không rõ trong event/log.';
COMMENT ON COLUMN notification_deliveries.id IS 'Delivery ID';
COMMENT ON COLUMN notification_deliveries.notification_id IS 'Thông báo sở hữu';
COMMENT ON COLUMN notification_deliveries.channel IS 'IN_APP/EMAIL';
COMMENT ON COLUMN notification_deliveries.recipient_address IS 'Địa chỉ email đã chốt cho lần gửi';
COMMENT ON COLUMN notification_deliveries.status IS 'Trạng thái gửi';
COMMENT ON COLUMN notification_deliveries.attempt_count IS 'Tối đa 1 lần đầu + 3 retry';
COMMENT ON COLUMN notification_deliveries.next_retry_at IS 'Lần được nhận xử lý tiếp';
COMMENT ON COLUMN notification_deliveries.claim_token IS 'Claim hiện tại';
COMMENT ON COLUMN notification_deliveries.lease_until IS 'Lease 60 giây có gia hạn';
COMMENT ON COLUMN notification_deliveries.provider_message_id IS 'Receipt nếu nhà cung cấp có';
COMMENT ON COLUMN notification_deliveries.last_failure_code IS 'Lỗi đã làm sạch';
COMMENT ON COLUMN notification_deliveries.sent_at IS 'Mốc gửi thành công';
COMMENT ON COLUMN notification_deliveries.finished_at IS 'Mốc kết thúc, dùng retention';
COMMENT ON COLUMN notification_deliveries.otp_challenge_id IS 'Mail OTP chỉ gửi khi challenge còn hợp lệ';
COMMENT ON COLUMN notification_deliveries.encrypted_payload IS 'Ciphertext cho dữ liệu gửi nhạy cảm, không chứa khóa giải mã';
COMMENT ON COLUMN notification_deliveries.encryption_key_id IS 'ID khóa ở môi trường/kho secret';
COMMENT ON COLUMN notification_deliveries.sensitive_expires_at IS 'Không gửi OTP đã hết hạn';
COMMENT ON COLUMN notification_deliveries.created_at IS 'Mốc tạo';
COMMENT ON COLUMN notification_deliveries.updated_at IS 'Mốc cập nhật';
CREATE INDEX ix_import_jobs_user ON import_jobs(created_by, created_at DESC);
COMMENT ON TABLE import_jobs IS 'Ngữ cảnh upload/import riêng, DryRun trước khi tạo Draft.';
COMMENT ON COLUMN import_jobs.id IS 'Job ID';
COMMENT ON COLUMN import_jobs.created_by IS 'CONTENT_ADMIN thực hiện';
COMMENT ON COLUMN import_jobs.request_id IS 'Retry cùng yêu cầu tạo job';
COMMENT ON COLUMN import_jobs.request_hash IS 'Payload fingerprint';
COMMENT ON COLUMN import_jobs.import_type IS 'Loại importer được hỗ trợ';
COMMENT ON COLUMN import_jobs.import_namespace IS 'Namespace khóa ngoài';
COMMENT ON COLUMN import_jobs.original_filename IS 'Tên hiển thị';
COMMENT ON COLUMN import_jobs.input_bucket IS 'Kho riêng tư';
COMMENT ON COLUMN import_jobs.input_object_key IS 'Key input riêng cho job';
COMMENT ON COLUMN import_jobs.declared_size_bytes IS 'Không vượt 5 MiB';
COMMENT ON COLUMN import_jobs.input_sha256 IS 'Hash file thực tế sau xác minh';
COMMENT ON COLUMN import_jobs.upload_expires_at IS 'Hạn nhận file/complete';
COMMENT ON COLUMN import_jobs.status IS 'Không coi upload CSV là IMAGE/AUDIO';
COMMENT ON COLUMN import_jobs.total_rows IS 'Đơn vị dòng/item, không phải số entity con';
COMMENT ON COLUMN import_jobs.success_rows IS 'Đã tạo Draft và ghi ledger';
COMMENT ON COLUMN import_jobs.failed_rows IS 'Dòng lỗi';
COMMENT ON COLUMN import_jobs.skipped_rows IS 'Trùng/được bỏ qua';
COMMENT ON COLUMN import_jobs.confirmed_at IS 'Người dùng xác nhận sau DryRun';
COMMENT ON COLUMN import_jobs.claim_token IS 'Chủ xử lý';
COMMENT ON COLUMN import_jobs.lease_until IS 'Hạn claim, phục hồi sau crash';
COMMENT ON COLUMN import_jobs.finished_at IS 'Kết thúc job';
COMMENT ON COLUMN import_jobs.failure_code IS 'Mã lỗi job';
COMMENT ON COLUMN import_jobs.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN import_jobs.updated_at IS 'Thời điểm cập nhật';
COMMENT ON TABLE import_job_rows IS 'Ledger từng dòng thay bảng import_job_errors đơn thuần; retry không tạo entity trùng.';
COMMENT ON COLUMN import_job_rows.job_id IS 'Job';
COMMENT ON COLUMN import_job_rows.row_number IS 'Dòng/item trong file';
COMMENT ON COLUMN import_job_rows.input_hash IS 'Fingerprint dòng';
COMMENT ON COLUMN import_job_rows.external_key IS 'Khóa ngoài nếu file cung cấp';
COMMENT ON COLUMN import_job_rows.payload IS 'Dữ liệu đã parse có schema';
COMMENT ON COLUMN import_job_rows.status IS 'VALID/INVALID trước xác nhận, kết quả sau xử lý';
COMMENT ON COLUMN import_job_rows.result_content_item_id IS 'Entity gốc được tạo thành công';
COMMENT ON COLUMN import_job_rows.result_snapshot IS 'IDs kết quả con của dòng nhóm nếu có';
COMMENT ON COLUMN import_job_rows.errors IS 'Lỗi theo trường, không stack trace/secret';
COMMENT ON COLUMN import_job_rows.processed_at IS 'Mốc xử lý xong';
COMMENT ON TABLE storage_pools IS 'Ngân sách CONTENT/TEMP toàn hệ thống và AVATAR từng user; hạ trần không xóa dữ liệu.';
COMMENT ON COLUMN storage_pools.id IS 'Pool ID';
COMMENT ON COLUMN storage_pools.scope IS 'CONTENT/TEMP/AVATAR';
COMMENT ON COLUMN storage_pools.owner_user_id IS 'Chỉ AVATAR có owner';
COMMENT ON COLUMN storage_pools.quota_bytes IS 'Trần cấu hình';
COMMENT ON COLUMN storage_pools.reserved_bytes IS 'Chỗ đã giữ, do trigger ledger duy trì';
COMMENT ON COLUMN storage_pools.used_bytes IS 'Byte đã commit, gồm file chờ dọn';
COMMENT ON COLUMN storage_pools.version IS 'Chống cập nhật đè nhau';
COMMENT ON COLUMN storage_pools.updated_at IS 'Mốc cập nhật';
CREATE INDEX ix_allocations_pool ON storage_allocations(pool_id, state);
CREATE INDEX ix_allocations_media ON storage_allocations(media_id);
COMMENT ON TABLE storage_allocations IS 'Ledger giữ chỗ/file, chỉ giải phóng sau đối chiếu, không reset quota bằng archive.';
COMMENT ON COLUMN storage_allocations.id IS 'Allocation ID';
COMMENT ON COLUMN storage_allocations.pool_id IS 'Ngân sách chịu byte này';
COMMENT ON COLUMN storage_allocations.allocation_key IS 'Khóa idempotent: mục đích/request/claim';
COMMENT ON COLUMN storage_allocations.media_id IS 'Asset file cuối nếu có';
COMMENT ON COLUMN storage_allocations.upload_request_id IS 'Nguồn upload media nếu có';
COMMENT ON COLUMN storage_allocations.import_job_id IS 'Hoặc nguồn input import';
COMMENT ON COLUMN storage_allocations.bytes IS 'Byte được giữ/commit';
COMMENT ON COLUMN storage_allocations.state IS 'RESERVED/COMMITTED/RELEASED';
COMMENT ON COLUMN storage_allocations.bucket IS 'Đối tượng đã được gắn nếu có';
COMMENT ON COLUMN storage_allocations.object_key IS 'Key không phải URL';
COMMENT ON COLUMN storage_allocations.committed_at IS 'File được xác nhận tồn tại';
COMMENT ON COLUMN storage_allocations.released_at IS 'Đã xác nhận không còn tiêu thụ byte';
COMMENT ON COLUMN storage_allocations.version IS 'CAS của ledger';
COMMENT ON COLUMN storage_allocations.created_at IS 'Thời điểm tạo, UTC';
COMMENT ON COLUMN storage_allocations.updated_at IS 'Thời điểm cập nhật';
COMMENT ON TABLE system_settings IS 'Allowlist typed; không dùng bảng này để chứa secret.';
COMMENT ON COLUMN system_settings.key IS 'Khóa được backend hỗ trợ';
COMMENT ON COLUMN system_settings.value IS 'Giá trị có kiểu';
COMMENT ON COLUMN system_settings.value_type IS 'INTEGER/BOOLEAN/STRING';
COMMENT ON COLUMN system_settings.unit IS 'Đơn vị';
COMMENT ON COLUMN system_settings.description IS 'Ý nghĩa/tác động';
COMMENT ON COLUMN system_settings.updated_by IS 'Quản trị thay, NULL cho seed';
COMMENT ON COLUMN system_settings.version IS 'Chống ghi đè';
COMMENT ON COLUMN system_settings.updated_at IS 'Mốc đổi';
CREATE INDEX ix_audit_actor ON audit_logs(actor_user_id, created_at DESC);
CREATE INDEX ix_audit_entity ON audit_logs(entity_type, entity_id, created_at DESC);
COMMENT ON TABLE audit_logs IS 'Audit append-only cho tác vụ quản trị; không sao chép secret/URL có chữ ký.';
COMMENT ON COLUMN audit_logs.id IS 'Audit ID';
COMMENT ON COLUMN audit_logs.actor_type IS 'USER/SYSTEM';
COMMENT ON COLUMN audit_logs.actor_user_id IS 'Người thực hiện nếu USER';
COMMENT ON COLUMN audit_logs.action IS 'Mã thao tác backend kiểm soát';
COMMENT ON COLUMN audit_logs.entity_type IS 'Loại đối tượng, không phải FK đa hình';
COMMENT ON COLUMN audit_logs.entity_id IS 'Định danh đích';
COMMENT ON COLUMN audit_logs.metadata IS 'Before/after đã lọc';
COMMENT ON COLUMN audit_logs.reason IS 'Lý do phù hợp thao tác';
COMMENT ON COLUMN audit_logs.request_id IS 'Trace ID, không phải access token';
COMMENT ON COLUMN audit_logs.ip_address IS 'IP đáng tin nếu có';
COMMENT ON COLUMN audit_logs.created_at IS 'Mốc ghi';
CREATE INDEX idx_event_publication_completion_date ON event_publication(completion_date);
CREATE INDEX idx_event_publication_status_publication_date ON event_publication(status, publication_date);
COMMENT ON TABLE event_publication IS 'Schema native Modulith 2.1, giữ đúng V1; không tạo registry khác.';
COMMENT ON COLUMN event_publication.id IS 'Publication ID';
COMMENT ON COLUMN event_publication.publication_date IS 'Mốc xuất bản event';
COMMENT ON COLUMN event_publication.listener_id IS 'Listener';
COMMENT ON COLUMN event_publication.serialized_event IS 'Chỉ payload an toàn/ID, không OTP rõ';
COMMENT ON COLUMN event_publication.event_type IS 'Event class';
COMMENT ON COLUMN event_publication.completion_date IS 'Mốc hoàn tất';
COMMENT ON COLUMN event_publication.last_resubmission_date IS 'Mốc retry';
COMMENT ON COLUMN event_publication.completion_attempts IS 'Bộ đếm native';
COMMENT ON COLUMN event_publication.status IS 'Trạng thái native';
-- Cross-table invariants. Application authorization, schema validation and storage
-- IO still belong to the service contracts; these functions do not replace them.

CREATE FUNCTION me_email_key(p_email TEXT) RETURNS TEXT LANGUAGE SQL IMMUTABLE
AS $$ SELECT lower(btrim(p_email)) $$;

CREATE FUNCTION me_public_json_safe(p_data JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE v_key TEXT; v_value JSONB;
BEGIN
  IF p_data IS NULL THEN RETURN TRUE; END IF;
  IF jsonb_typeof(p_data)='object' THEN
    FOR v_key,v_value IN SELECT key,value FROM jsonb_each(p_data) LOOP
      IF v_key = ANY(ARRAY['correctOptionId','correct_option_id','isCorrect','is_correct',
          'answerKey','answer_key','explanation','transcript','permission_note','password_hash',
          'otp_hmac','grant_hash','encrypted_payload']) OR NOT me_public_json_safe(v_value)
      THEN RETURN FALSE; END IF;
    END LOOP;
  ELSIF jsonb_typeof(p_data)='array' THEN
    FOR v_value IN SELECT value FROM jsonb_array_elements(p_data) LOOP
      IF NOT me_public_json_safe(v_value) THEN RETURN FALSE; END IF;
    END LOOP;
  END IF;
  RETURN TRUE;
END $$;

ALTER TABLE attempt_question_snapshots ADD CONSTRAINT ck_snapshot_public_safe
  CHECK (me_public_json_safe(question_snapshot));
ALTER TABLE attempt_group_snapshots ADD CONSTRAINT ck_group_snapshot_public_safe
  CHECK (me_public_json_safe(public_snapshot));

CREATE FUNCTION me_has_role(p_user UUID,p_code TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE
AS $$ SELECT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id
  JOIN roles r ON r.id=ur.role_id WHERE u.id=p_user AND u.status='ACTIVE'
  AND u.email_verified_at IS NOT NULL AND r.code=p_code) $$;

CREATE FUNCTION me_revoke_sessions(p_user UUID,p_reason TEXT) RETURNS VOID
LANGUAGE plpgsql AS $$ BEGIN
  UPDATE auth_sessions SET revoked_at=CURRENT_TIMESTAMP,revoke_reason=p_reason
    WHERE user_id=p_user AND revoked_at IS NULL;
END $$;

CREATE FUNCTION me_session_revoke_tokens() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
    UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,NEW.revoked_at)
      WHERE session_id=NEW.id AND revoked_at IS NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER session_revoke_tokens AFTER UPDATE ON auth_sessions
  FOR EACH ROW EXECUTE FUNCTION me_session_revoke_tokens();

CREATE FUNCTION me_guard_user() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_media media_assets; v_last_admin BOOLEAN;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF NEW.id<>OLD.id OR NEW.email IS DISTINCT FROM OLD.email THEN
      RAISE EXCEPTION 'user id/email are immutable' USING ERRCODE='23514'; END IF;
    IF OLD.status='ACTIVE' AND NEW.status<>'ACTIVE' AND EXISTS(
      SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
      WHERE ur.user_id=OLD.id AND r.code='SYSTEM_ADMIN') THEN
      PERFORM pg_advisory_xact_lock(721001);
      SELECT NOT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id
        JOIN roles r ON r.id=ur.role_id WHERE u.id<>OLD.id AND u.status='ACTIVE'
        AND u.email_verified_at IS NOT NULL AND r.code='SYSTEM_ADMIN') INTO v_last_admin;
      IF v_last_admin THEN RAISE EXCEPTION 'last active system administrator'
        USING ERRCODE='23514'; END IF;
    END IF;
    IF NEW.password_hash IS DISTINCT FROM OLD.password_hash OR NEW.status IS DISTINCT FROM OLD.status THEN
      NEW.auth_version:=OLD.auth_version+1;
      IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
        NEW.password_changed_at:=CURRENT_TIMESTAMP; END IF;
    END IF;
  END IF;
  IF NEW.avatar_media_id IS NOT NULL THEN
    SELECT * INTO v_media FROM media_assets WHERE id=NEW.avatar_media_id FOR UPDATE;
    IF NOT FOUND OR v_media.uploaded_by<>NEW.id OR v_media.purpose<>'AVATAR'
      OR v_media.media_type<>'IMAGE' OR v_media.status<>'READY'
      OR v_media.deletion_started_at IS NOT NULL THEN
      RAISE EXCEPTION 'avatar must be an owned ready image' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_user BEFORE INSERT OR UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION me_guard_user();

CREATE FUNCTION me_user_security_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_reason TEXT;
BEGIN
  IF NEW.auth_version<>OLD.auth_version THEN
    v_reason:=CASE WHEN NEW.status='LOCKED' THEN 'ACCOUNT_LOCKED'
      WHEN NEW.status='DISABLED' THEN 'ACCOUNT_DISABLED'
      WHEN NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN 'PASSWORD_CHANGE'
      ELSE 'SECURITY' END;
    PERFORM me_revoke_sessions(NEW.id,v_reason);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER user_security_changed AFTER UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION me_user_security_changed();

CREATE FUNCTION me_guard_role_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_code TEXT;
BEGIN
  IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'replace role grant rather than update'
    USING ERRCODE='23514'; END IF;
  IF TG_OP='DELETE' THEN
    SELECT code INTO v_code FROM roles WHERE id=OLD.role_id;
    IF v_code='SYSTEM_ADMIN' THEN
      PERFORM pg_advisory_xact_lock(721001);
      IF EXISTS(SELECT 1 FROM users WHERE id=OLD.user_id AND status='ACTIVE') AND NOT EXISTS(
        SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
        WHERE u.id<>OLD.user_id AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
        AND r.code='SYSTEM_ADMIN') THEN
        RAISE EXCEPTION 'cannot remove last active system administrator' USING ERRCODE='23514';
      END IF;
    END IF;
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_role_change BEFORE UPDATE OR DELETE ON user_roles
  FOR EACH ROW EXECUTE FUNCTION me_guard_role_change();

CREATE FUNCTION me_role_change_security() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user UUID;
BEGIN
  v_user:=CASE WHEN TG_OP='DELETE' THEN OLD.user_id ELSE NEW.user_id END;
  PERFORM 1 FROM users WHERE id=v_user FOR UPDATE;
  PERFORM me_revoke_sessions(v_user,'ROLE_CHANGE');
  UPDATE users SET auth_version=auth_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=v_user;
  RETURN NULL;
END $$;
CREATE TRIGGER role_change_security AFTER INSERT OR DELETE ON user_roles
  FOR EACH ROW EXECUTE FUNCTION me_role_change_security();

CREATE FUNCTION me_minimum_role() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user UUID;
BEGIN
  IF TG_TABLE_NAME='users' THEN v_user:=NEW.id;
  ELSE v_user:=CASE WHEN TG_OP='DELETE' THEN OLD.user_id ELSE NEW.user_id END; END IF;
  PERFORM 1 FROM users WHERE id=v_user FOR UPDATE;
  IF FOUND AND NOT EXISTS(SELECT 1 FROM user_roles WHERE user_id=v_user) THEN
    RAISE EXCEPTION 'user must have at least one role at commit' USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER user_minimum_role AFTER INSERT ON users DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION me_minimum_role();
CREATE CONSTRAINT TRIGGER grant_minimum_role AFTER INSERT OR DELETE ON user_roles DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION me_minimum_role();

CREATE FUNCTION me_guard_session() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user users; v_limit INTEGER; v_count INTEGER; v_old UUID;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF (NEW.user_id,NEW.created_at,NEW.expires_at) IS DISTINCT FROM (OLD.user_id,OLD.created_at,OLD.expires_at)
      OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
      RAISE EXCEPTION 'session identity/absolute expiry/revocation is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO v_user FROM users WHERE id=NEW.user_id FOR UPDATE;
  IF NOT FOUND OR v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL THEN
    RAISE EXCEPTION 'only verified active users can log in' USING ERRCODE='23514'; END IF;
  v_limit:=COALESCE((SELECT (value::text)::INTEGER FROM system_settings WHERE key='auth.max_sessions'),5);
  LOOP
    SELECT count(*) INTO v_count FROM auth_sessions WHERE user_id=NEW.user_id
      AND revoked_at IS NULL AND expires_at>NEW.created_at;
    EXIT WHEN v_count<v_limit;
    SELECT id INTO v_old FROM auth_sessions WHERE user_id=NEW.user_id AND revoked_at IS NULL
      AND expires_at>NEW.created_at ORDER BY created_at,id LIMIT 1 FOR UPDATE;
    UPDATE auth_sessions SET revoked_at=NEW.created_at,revoke_reason='SESSION_LIMIT' WHERE id=v_old;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_session BEFORE INSERT OR UPDATE ON auth_sessions
  FOR EACH ROW EXECUTE FUNCTION me_guard_session();

CREATE FUNCTION me_guard_refresh() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_session auth_sessions;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.session_id,NEW.token_hash,NEW.issued_at,NEW.expires_at)
      IS DISTINCT FROM (OLD.id,OLD.session_id,OLD.token_hash,OLD.issued_at,OLD.expires_at)
      OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
      RAISE EXCEPTION 'token identity/lifetime/revocation is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO v_session FROM auth_sessions WHERE id=NEW.session_id;
  IF NOT FOUND OR NEW.issued_at<v_session.created_at OR NEW.expires_at>v_session.expires_at
    OR v_session.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'token must fit its live session' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_refresh BEFORE INSERT OR UPDATE ON refresh_tokens
  FOR EACH ROW EXECUTE FUNCTION me_guard_refresh();

CREATE FUNCTION me_consume_otp(p_id UUID,p_user UUID,p_purpose TEXT,p_expected_hmac TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_user users; v_id UUID;
BEGIN
  SELECT * INTO v_user FROM users WHERE id=p_user FOR UPDATE;
  IF NOT FOUND OR v_user.status IN ('LOCKED','DISABLED') OR
    (p_purpose<>'EMAIL_VERIFICATION' AND (v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL))
    THEN RETURN FALSE; END IF;
  UPDATE otp_challenges SET consumed_at=CURRENT_TIMESTAMP,otp_hmac=NULL
    WHERE id=p_id AND user_id=p_user AND purpose=p_purpose AND otp_hmac=p_expected_hmac
    AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at>CURRENT_TIMESTAMP
    AND (blocked_until IS NULL OR blocked_until<=CURRENT_TIMESTAMP) AND attempt_count<5 RETURNING id INTO v_id;
  RETURN v_id IS NOT NULL;
END $$;

CREATE FUNCTION me_rotate_refresh(p_session UUID,p_old_hash TEXT,p_new_id UUID,p_new_hash TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_session auth_sessions; v_user users; v_old refresh_tokens;
BEGIN
  SELECT * INTO v_session FROM auth_sessions WHERE id=p_session;
  IF NOT FOUND THEN RETURN FALSE; END IF;
  SELECT * INTO v_user FROM users WHERE id=v_session.user_id FOR UPDATE;
  SELECT * INTO v_session FROM auth_sessions WHERE id=p_session FOR UPDATE;
  IF v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL OR
    v_session.revoked_at IS NOT NULL OR v_session.expires_at<=CURRENT_TIMESTAMP THEN RETURN FALSE; END IF;
  SELECT * INTO v_old FROM refresh_tokens WHERE session_id=p_session AND token_hash=p_old_hash FOR UPDATE;
  IF NOT FOUND OR v_old.revoked_at IS NOT NULL OR v_old.expires_at<=CURRENT_TIMESTAMP THEN RETURN FALSE; END IF;
  UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP,last_used_at=CURRENT_TIMESTAMP,replaced_by_id=p_new_id
    WHERE id=v_old.id;
  INSERT INTO refresh_tokens(id,session_id,token_hash,issued_at,expires_at)
    VALUES(p_new_id,p_session,p_new_hash,CURRENT_TIMESTAMP,v_session.expires_at);
  RETURN TRUE;
END $$;

CREATE FUNCTION me_guard_change_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_session auth_sessions; v_user users;
BEGIN
  IF NEW.completed_at IS NOT NULL OR NEW.invalidated_at IS NOT NULL THEN RETURN NEW; END IF;
  SELECT * INTO v_session FROM auth_sessions WHERE id=NEW.session_id;
  SELECT * INTO v_user FROM users WHERE id=NEW.user_id;
  IF v_session.id IS NULL OR v_session.user_id<>NEW.user_id OR NEW.expires_at>v_session.expires_at
    OR v_session.revoked_at IS NOT NULL OR v_user.status<>'ACTIVE'
    OR NEW.auth_version_at_request<>v_user.auth_version THEN
    RAISE EXCEPTION 'password change request must fit its authenticated context' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_change_request BEFORE INSERT OR UPDATE ON password_change_requests
  FOR EACH ROW EXECUTE FUNCTION me_guard_change_request();

CREATE FUNCTION me_guard_reset_grant() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user users; v_otp otp_challenges;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF (NEW.user_id,NEW.otp_challenge_id,NEW.auth_version_at_issue,NEW.issued_at,NEW.expires_at)
      IS DISTINCT FROM (OLD.user_id,OLD.otp_challenge_id,OLD.auth_version_at_issue,OLD.issued_at,OLD.expires_at) THEN
      RAISE EXCEPTION 'grant context is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO v_user FROM users WHERE id=NEW.user_id;
  SELECT * INTO v_otp FROM otp_challenges WHERE id=NEW.otp_challenge_id;
  IF v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL OR
    NEW.auth_version_at_issue<>v_user.auth_version OR v_otp.consumed_at IS NULL
    OR v_otp.invalidated_at IS NOT NULL OR v_otp.purpose<>'PASSWORD_RESET'
    OR v_otp.user_id<>NEW.user_id THEN
    RAISE EXCEPTION 'reset grant requires consumed reset OTP and current active user' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reset_grant BEFORE INSERT OR UPDATE ON password_reset_grants
  FOR EACH ROW EXECUTE FUNCTION me_guard_reset_grant();

CREATE FUNCTION me_guard_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.status<>'DRAFT' THEN RAISE EXCEPTION 'revision starts as Draft' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='DELETE' THEN
    IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'published revision retained' USING ERRCODE='55000'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status='PUBLISHED' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'published revision is immutable' USING ERRCODE='55000'; END IF;
  IF (NEW.id,NEW.content_item_id,NEW.version_no) IS DISTINCT FROM (OLD.id,OLD.content_item_id,OLD.version_no) THEN
    RAISE EXCEPTION 'revision identity is immutable' USING ERRCODE='23514'; END IF;
  IF NEW.status<>OLD.status AND NOT (
    (OLD.status='DRAFT' AND NEW.status='IN_REVIEW') OR
    (OLD.status='IN_REVIEW' AND NEW.status IN ('APPROVED','REJECTED','DRAFT')) OR
    (OLD.status='REJECTED' AND NEW.status='DRAFT') OR
    (OLD.status='APPROVED' AND NEW.status IN ('PUBLISHED','DRAFT'))) THEN
    RAISE EXCEPTION 'invalid editorial transition' USING ERRCODE='23514'; END IF;
  IF NEW.snapshot IS DISTINCT FROM OLD.snapshot AND NEW.status<>'DRAFT' THEN
    RAISE EXCEPTION 'edit payload only in Draft' USING ERRCODE='23514'; END IF;
  IF NEW.status='PUBLISHED' THEN
    IF NOT (NEW.snapshot ? 'data') OR jsonb_typeof(NEW.snapshot->'data')<>'object'
      OR NOT (NEW.snapshot ? 'schema_version') OR (NEW.snapshot->>'schema_version')::INTEGER<>NEW.schema_version THEN
      RAISE EXCEPTION 'published snapshot requires typed envelope' USING ERRCODE='23514'; END IF;
    IF NOT EXISTS(SELECT 1 FROM content_source_links WHERE revision_id=NEW.id) OR EXISTS(
      SELECT 1 FROM content_source_links l JOIN content_sources s ON s.id=l.source_id
      WHERE l.revision_id=NEW.id AND (l.source_snapshot IS NULL OR s.status<>'ACTIVE'
      OR s.license_code IS NULL OR btrim(s.license_code)='')) THEN
      RAISE EXCEPTION 'publication requires active declared sources and source snapshots' USING ERRCODE='23514'; END IF;
  END IF;
  NEW.version:=OLD.version+1; NEW.updated_at:=CURRENT_TIMESTAMP;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_revision BEFORE INSERT OR UPDATE OR DELETE ON content_revisions
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision();

CREATE FUNCTION me_check_content_pointer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_item content_items; v_revision content_revisions; v_projection TEXT; v_matches BOOLEAN;
BEGIN
  IF TG_TABLE_NAME='content_items' THEN SELECT * INTO v_item FROM content_items WHERE id=NEW.id;
  ELSE SELECT * INTO v_item FROM content_items WHERE id=NEW.content_item_id; END IF;
  IF v_item.id IS NULL OR v_item.published_revision_id IS NULL THEN RETURN NULL; END IF;
  SELECT * INTO v_revision FROM content_revisions WHERE id=v_item.published_revision_id;
  IF v_revision.content_item_id<>v_item.id OR v_revision.status<>'PUBLISHED' THEN
    RAISE EXCEPTION 'published pointer must target own Published revision' USING ERRCODE='23514'; END IF;
  v_projection:=CASE v_item.content_type WHEN 'WORD' THEN 'words' WHEN 'DECK' THEN 'decks'
    WHEN 'GRAMMAR_TOPIC' THEN 'grammar_topics' WHEN 'GRAMMAR_LESSON' THEN 'grammar_lessons'
    WHEN 'QUESTION' THEN 'questions' WHEN 'QUESTION_GROUP' THEN 'question_groups'
    WHEN 'PRACTICE_SET' THEN 'practice_sets' END;
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I WHERE id=$1 AND published_revision_id=$2)',v_projection)
    INTO v_matches USING v_item.id,v_item.published_revision_id;
  IF NOT v_matches THEN RAISE EXCEPTION 'published projection must match pointer'
    USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER content_pointer_valid AFTER INSERT OR UPDATE ON content_items
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_content_pointer();
CREATE CONSTRAINT TRIGGER published_projection_valid AFTER INSERT OR UPDATE ON content_revisions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_content_pointer();

CREATE FUNCTION me_guard_content_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.id<>OLD.id OR NEW.content_type<>OLD.content_type THEN
    RAISE EXCEPTION 'content identity/type is immutable' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_content_item BEFORE UPDATE ON content_items FOR EACH ROW EXECUTE FUNCTION me_guard_content_item();

CREATE FUNCTION me_guard_revision_link() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_old UUID; v_new UUID; v_child UUID;
BEGIN
  IF TG_OP<>'INSERT' THEN v_old:=(to_jsonb(OLD)->>TG_ARGV[0])::UUID; END IF;
  IF TG_OP<>'DELETE' THEN v_new:=(to_jsonb(NEW)->>TG_ARGV[0])::UUID; END IF;
  IF EXISTS(SELECT 1 FROM content_revisions WHERE id IN (v_old,v_new) AND status='PUBLISHED') THEN
    RAISE EXCEPTION 'published source/media/dependency links are immutable' USING ERRCODE='55000'; END IF;
  IF TG_OP<>'DELETE' AND TG_TABLE_NAME='revision_dependencies' THEN
    v_child:=NEW.child_revision_id;
    IF NOT EXISTS(SELECT 1 FROM content_revisions WHERE id=v_child AND status='PUBLISHED') THEN
      RAISE EXCEPTION 'formal dependency must pin Published revision' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP<>'DELETE' AND TG_TABLE_NAME='content_source_links' THEN
    IF NOT EXISTS(SELECT 1 FROM content_sources WHERE id=NEW.source_id AND status='ACTIVE') THEN
      RAISE EXCEPTION 'new link requires active source' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER guard_source_link BEFORE INSERT OR UPDATE OR DELETE ON content_source_links
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision_link('revision_id');
CREATE TRIGGER guard_revision_media BEFORE INSERT OR UPDATE OR DELETE ON revision_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision_link('revision_id');
CREATE TRIGGER guard_dependency BEFORE INSERT OR UPDATE OR DELETE ON revision_dependencies
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision_link('parent_revision_id');

CREATE FUNCTION me_media_has_refs(p_media UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM users WHERE avatar_media_id=p_media)
 OR EXISTS(SELECT 1 FROM revision_media_refs WHERE media_id=p_media)
 OR EXISTS(SELECT 1 FROM attempt_media_refs WHERE media_id=p_media)
 OR EXISTS(SELECT 1 FROM words WHERE audio_media_id=p_media)
 OR EXISTS(SELECT 1 FROM word_senses WHERE audio_media_id=p_media)
 OR EXISTS(SELECT 1 FROM group_materials WHERE media_id=p_media)
 OR EXISTS(SELECT 1 FROM question_materials WHERE media_id=p_media)
$$;

CREATE FUNCTION me_guard_media() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='DELETE' THEN
    IF me_media_has_refs(OLD.id) OR OLD.deletion_started_at IS NULL OR
      OLD.status NOT IN ('ARCHIVED','FAILED') OR
      (OLD.bucket IS NOT NULL AND OLD.object_deleted_at IS NULL) OR
      (OLD.status='ARCHIVED' AND OLD.archived_at>CURRENT_TIMESTAMP-INTERVAL '30 days') THEN
      RAISE EXCEPTION 'media is not eligible for physical metadata deletion' USING ERRCODE='23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status IN ('READY','ARCHIVED') AND
    (NEW.id,NEW.media_type,NEW.purpose,NEW.bucket,NEW.object_key,NEW.mime_type,NEW.file_size_bytes,
     NEW.duration_ms,NEW.width,NEW.height,NEW.checksum_sha256,NEW.uploaded_by)
    IS DISTINCT FROM
    (OLD.id,OLD.media_type,OLD.purpose,OLD.bucket,OLD.object_key,OLD.mime_type,OLD.file_size_bytes,
     OLD.duration_ms,OLD.width,OLD.height,OLD.checksum_sha256,OLD.uploaded_by) THEN
    RAISE EXCEPTION 'confirmed media bytes and identity are immutable' USING ERRCODE='55000'; END IF;
  IF NEW.status<>OLD.status AND NOT ((OLD.status='PENDING' AND NEW.status IN ('READY','FAILED')) OR
      (OLD.status='READY' AND NEW.status='ARCHIVED')) THEN
    RAISE EXCEPTION 'invalid media lifecycle transition' USING ERRCODE='23514'; END IF;
  IF NEW.status='ARCHIVED' AND OLD.status<>'ARCHIVED' AND EXISTS(SELECT 1 FROM users WHERE avatar_media_id=OLD.id) THEN
    RAISE EXCEPTION 'cannot archive current avatar' USING ERRCODE='23514'; END IF;
  IF NEW.deletion_started_at IS NOT NULL AND OLD.deletion_started_at IS NULL AND me_media_has_refs(OLD.id) THEN
    RAISE EXCEPTION 'cannot claim referenced media for deletion' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_media BEFORE UPDATE OR DELETE ON media_assets FOR EACH ROW EXECUTE FUNCTION me_guard_media();

CREATE FUNCTION me_guard_media_ref() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_id UUID; v_media media_assets;
BEGIN
  v_id:=(to_jsonb(NEW)->>TG_ARGV[0])::UUID;
  IF v_id IS NULL THEN RETURN NEW; END IF;
  SELECT * INTO v_media FROM media_assets WHERE id=v_id FOR UPDATE;
  IF NOT FOUND OR v_media.deletion_started_at IS NOT NULL OR
      (v_media.status<>'READY' AND NOT (TG_TABLE_NAME='attempt_media_refs' AND v_media.status='ARCHIVED')) THEN
    RAISE EXCEPTION 'new media reference must target ready unclaimed file' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='attempt_media_refs' THEN
    -- Archived media remains valid only through the exact Published source graph.
    IF NOT EXISTS(
      WITH RECURSIVE roots(id) AS (
        SELECT source_revision_id FROM attempts WHERE id=NEW.attempt_id
        UNION SELECT source_revision_id FROM attempt_question_snapshots WHERE attempt_id=NEW.attempt_id
        UNION SELECT source_revision_id FROM attempt_group_snapshots WHERE attempt_id=NEW.attempt_id
        UNION SELECT source_revision_id FROM attempt_source_topics WHERE attempt_id=NEW.attempt_id
      ), deps(id) AS (
        SELECT id FROM roots UNION SELECT d.child_revision_id FROM revision_dependencies d JOIN deps p ON p.id=d.parent_revision_id
      ) SELECT 1 FROM deps JOIN revision_media_refs r ON r.revision_id=deps.id
        JOIN content_revisions cr ON cr.id=r.revision_id WHERE r.media_id=v_id AND cr.status='PUBLISHED'
    ) THEN RAISE EXCEPTION 'attempt media must derive from pinned Published revisions' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_TABLE_NAME IN ('group_materials','question_materials') THEN
    IF NEW.material_type<>v_media.media_type THEN
      RAISE EXCEPTION 'material and file types differ' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_TABLE_NAME NOT IN ('users') AND v_media.purpose<>'CONTENT' THEN
    RAISE EXCEPTION 'avatar cannot be attached as learning content' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_new_revision_media BEFORE INSERT OR UPDATE ON revision_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_new_attempt_media BEFORE INSERT OR UPDATE ON attempt_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_group_material_media BEFORE INSERT OR UPDATE ON group_materials
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_question_material_media BEFORE INSERT OR UPDATE ON question_materials
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_word_audio BEFORE INSERT OR UPDATE ON words
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('audio_media_id');
CREATE TRIGGER guard_sense_audio BEFORE INSERT OR UPDATE ON word_senses
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('audio_media_id');

CREATE FUNCTION me_check_question() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_id UUID; v_q questions; v_count INTEGER; v_correct INTEGER; v_expected INTEGER;
BEGIN
  IF TG_TABLE_NAME='questions' THEN v_id:=NEW.id;
  ELSIF TG_OP='DELETE' THEN v_id:=OLD.question_id; ELSE v_id:=NEW.question_id; END IF;
  SELECT * INTO v_q FROM questions WHERE id=v_id;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM content_items WHERE id=v_id AND is_archived) THEN RETURN NULL; END IF;
  SELECT count(*),count(*) FILTER(WHERE is_correct) INTO v_count,v_correct FROM question_options WHERE question_id=v_id;
  v_expected:=CASE WHEN v_q.category='TOEIC' THEN CASE WHEN v_q.part_number=2 THEN 3 ELSE 4 END ELSE NULL END;
  IF v_correct<>1 OR (v_expected IS NOT NULL AND v_count<>v_expected) OR
    (v_expected IS NULL AND (v_count<2 OR v_count>6)) THEN
    RAISE EXCEPTION 'published question requires valid choice count and exactly one key' USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER question_options_complete AFTER INSERT OR UPDATE ON questions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_question();
CREATE CONSTRAINT TRIGGER options_complete AFTER INSERT OR UPDATE OR DELETE ON question_options
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_question();

CREATE FUNCTION me_guard_question_context() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF (NEW.group_id,NEW.category,NEW.part_number,NEW.question_type_code)
    IS DISTINCT FROM (OLD.group_id,OLD.category,OLD.part_number,OLD.question_type_code) THEN
    RAISE EXCEPTION 'new semantic context requires new question id'
    USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_question_context BEFORE UPDATE ON questions FOR EACH ROW EXECUTE FUNCTION me_guard_question_context();

CREATE FUNCTION me_check_group() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_id UUID; v_group question_groups; v_count INTEGER; v_documents INTEGER;
BEGIN
  IF TG_TABLE_NAME='question_groups' THEN v_id:=NEW.id;
  ELSIF TG_TABLE_NAME='group_materials' THEN v_id:=CASE WHEN TG_OP='DELETE' THEN OLD.group_id ELSE NEW.group_id END;
  ELSE v_id:=CASE WHEN TG_OP='DELETE' THEN OLD.group_id ELSE NEW.group_id END; END IF;
  IF v_id IS NULL THEN RETURN NULL; END IF;
  SELECT * INTO v_group FROM question_groups WHERE id=v_id;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM content_items WHERE id=v_id AND is_archived) THEN RETURN NULL; END IF;
  SELECT count(*) INTO v_count FROM questions WHERE group_id=v_id AND in_current_group;
  IF v_count=0 OR (v_group.category='TOEIC' AND ((v_group.part_number IN (3,4) AND v_count<>3) OR
      (v_group.part_number=6 AND v_count<>4))) THEN
    RAISE EXCEPTION 'group question count does not fit its context' USING ERRCODE='23514'; END IF;
  IF v_group.category='TOEIC' AND v_group.part_number IN (3,4) AND NOT EXISTS(
    SELECT 1 FROM group_materials WHERE group_id=v_id AND material_type='AUDIO' AND usage_role='STIMULUS') THEN
    RAISE EXCEPTION 'listening group needs common audio' USING ERRCODE='23514'; END IF;
  IF v_group.category='TOEIC' AND v_group.part_number=6 AND NOT EXISTS(
    SELECT 1 FROM group_materials WHERE group_id=v_id AND material_type='TEXT' AND usage_role='STIMULUS') THEN
    RAISE EXCEPTION 'Part 6 needs common text' USING ERRCODE='23514'; END IF;
  IF v_group.category='TOEIC' AND v_group.part_number=7 THEN
    SELECT count(DISTINCT document_key) INTO v_documents FROM group_materials
      WHERE group_id=v_id AND usage_role='STIMULUS';
    IF v_documents NOT BETWEEN 1 AND 3 OR EXISTS(SELECT 1 FROM group_materials WHERE group_id=v_id
        AND usage_role='STIMULUS' AND document_key IS NULL) THEN
      RAISE EXCEPTION 'Part 7 requires one to three identified documents' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER group_complete AFTER INSERT OR UPDATE ON question_groups
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_group();
CREATE CONSTRAINT TRIGGER group_children_complete AFTER INSERT OR UPDATE OR DELETE ON questions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_group();
CREATE CONSTRAINT TRIGGER group_material_complete AFTER INSERT OR UPDATE OR DELETE ON group_materials
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_group();

CREATE FUNCTION me_guard_set_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.question_id IS NOT NULL AND EXISTS(SELECT 1 FROM questions WHERE id=NEW.question_id AND group_id IS NOT NULL) THEN
    RAISE EXCEPTION 'select a whole group rather than a grouped child' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_set_item BEFORE INSERT OR UPDATE ON set_items FOR EACH ROW EXECUTE FUNCTION me_guard_set_item();

CREATE FUNCTION me_storage_account() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_reserved_old BIGINT:=0; v_used_old BIGINT:=0; v_reserved_new BIGINT:=0; v_used_new BIGINT:=0;
  v_pool storage_pools; v_increase BIGINT;
BEGIN
  IF TG_OP='DELETE' THEN
    IF OLD.state<>'RELEASED' THEN RAISE EXCEPTION 'release allocation after storage reconciliation before deletion'
      USING ERRCODE='23514'; END IF;
    RETURN OLD;
  END IF;
  IF TG_OP='UPDATE' THEN
    IF NEW.pool_id<>OLD.pool_id OR NEW.bytes<>OLD.bytes OR NEW.allocation_key<>OLD.allocation_key THEN
      RAISE EXCEPTION 'allocation identity/byte reservation is immutable' USING ERRCODE='23514'; END IF;
    IF OLD.state='RELEASED' AND NEW IS DISTINCT FROM OLD AND
      (NEW.state,NEW.pool_id,NEW.bytes,NEW.bucket,NEW.object_key) IS DISTINCT FROM
      (OLD.state,OLD.pool_id,OLD.bytes,OLD.bucket,OLD.object_key) THEN
      RAISE EXCEPTION 'released allocation cannot consume again' USING ERRCODE='23514'; END IF;
    IF NEW.state<>OLD.state AND NOT ((OLD.state='RESERVED' AND NEW.state IN ('COMMITTED','RELEASED')) OR
        (OLD.state='COMMITTED' AND NEW.state='RELEASED')) THEN
      RAISE EXCEPTION 'invalid allocation transition' USING ERRCODE='23514'; END IF;
    v_reserved_old:=CASE WHEN OLD.state='RESERVED' THEN OLD.bytes ELSE 0 END;
    v_used_old:=CASE WHEN OLD.state='COMMITTED' THEN OLD.bytes ELSE 0 END;
  END IF;
  v_reserved_new:=CASE WHEN NEW.state='RESERVED' THEN NEW.bytes ELSE 0 END;
  v_used_new:=CASE WHEN NEW.state='COMMITTED' THEN NEW.bytes ELSE 0 END;
  SELECT * INTO v_pool FROM storage_pools WHERE id=NEW.pool_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'storage pool not found' USING ERRCODE='23503'; END IF;
  v_increase:=(v_reserved_new+v_used_new)-(v_reserved_old+v_used_old);
  IF v_increase>0 AND v_pool.reserved_bytes+v_pool.used_bytes+v_increase>v_pool.quota_bytes THEN
    RAISE EXCEPTION 'storage quota exceeded' USING ERRCODE='23514'; END IF;
  UPDATE storage_pools SET reserved_bytes=reserved_bytes-v_reserved_old+v_reserved_new,
    used_bytes=used_bytes-v_used_old+v_used_new,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=NEW.pool_id;
  RETURN NEW;
END $$;
CREATE TRIGGER storage_account BEFORE INSERT OR UPDATE OR DELETE ON storage_allocations
  FOR EACH ROW EXECUTE FUNCTION me_storage_account();

CREATE FUNCTION me_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF TG_TABLE_NAME='audit_logs' THEN
    IF TG_OP='DELETE' AND OLD.created_at<=CURRENT_TIMESTAMP-INTERVAL '365 days' THEN RETURN OLD; END IF;
  END IF;
  RAISE EXCEPTION '% is append only (subject to documented retention)',TG_TABLE_NAME USING ERRCODE='55000';
END $$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION me_append_only();
CREATE TRIGGER review_append_only BEFORE UPDATE OR DELETE ON word_reviews FOR EACH ROW EXECUTE FUNCTION me_append_only();

CREATE FUNCTION me_guard_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_count INTEGER; v_old UUID;
BEGIN
  IF TG_OP='INSERT' THEN
    PERFORM 1 FROM users WHERE id=NEW.user_id FOR UPDATE;
    IF NOT me_has_role(NEW.user_id,'LEARNER') THEN
      RAISE EXCEPTION 'attempt requires active learner' USING ERRCODE='23514'; END IF;
    FOR v_old IN SELECT id FROM attempts WHERE user_id=NEW.user_id AND status='IN_PROGRESS'
      AND deadline_at<=CURRENT_TIMESTAMP ORDER BY id LOOP
      PERFORM me_submit_attempt(v_old,NEW.user_id,'TIME_LIMIT');
    END LOOP;
    UPDATE attempts SET status='ABANDONED',abandoned_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
      WHERE user_id=NEW.user_id AND status='IN_PROGRESS' AND deadline_at IS NULL
      AND last_active_at<=CURRENT_TIMESTAMP-INTERVAL '7 days';
    SELECT count(*) INTO v_count FROM attempts WHERE user_id=NEW.user_id AND status='IN_PROGRESS';
    IF v_count>=COALESCE((SELECT (value::text)::INTEGER FROM system_settings WHERE key='practice.max_open'),5) THEN
      RAISE EXCEPTION 'open attempt limit reached' USING ERRCODE='23514'; END IF;
    IF NEW.status<>'IN_PROGRESS' OR NEW.snapshot_sealed_at IS NOT NULL THEN
      RAISE EXCEPTION 'attempt starts unsealed and in progress' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='DELETE' THEN
    IF OLD.status<>'ABANDONED' OR OLD.abandoned_at>CURRENT_TIMESTAMP-INTERVAL '30 days' THEN
      RAISE EXCEPTION 'attempt retained by history policy' USING ERRCODE='55000'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status='SUBMITTED' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'submitted result is immutable' USING ERRCODE='55000'; END IF;
  IF OLD.status='ABANDONED' AND NEW.status<>'ABANDONED' THEN
    RAISE EXCEPTION 'abandoned attempt cannot resume' USING ERRCODE='23514'; END IF;
  IF OLD.snapshot_sealed_at IS NOT NULL AND
    (NEW.user_id,NEW.category,NEW.source_type,NEW.source_set_id,NEW.source_deck_id,NEW.source_topic_id,
     NEW.source_revision_id,NEW.source_config,NEW.request_id,NEW.request_hash,NEW.total_count,
     NEW.snapshot_sealed_at,NEW.started_at,NEW.deadline_at)
    IS DISTINCT FROM
    (OLD.user_id,OLD.category,OLD.source_type,OLD.source_set_id,OLD.source_deck_id,OLD.source_topic_id,
     OLD.source_revision_id,OLD.source_config,OLD.request_id,OLD.request_hash,OLD.total_count,
     OLD.snapshot_sealed_at,OLD.started_at,OLD.deadline_at) THEN
    RAISE EXCEPTION 'sealed attempt source/deadline/identity is immutable' USING ERRCODE='55000'; END IF;
  NEW.version:=OLD.version+1; NEW.updated_at:=CURRENT_TIMESTAMP;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_attempt BEFORE INSERT OR UPDATE OR DELETE ON attempts
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt();

CREATE FUNCTION me_guard_attempt_child() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_attempt UUID; v_parent attempts; v_old_attempt UUID; v_snapshot UUID;
BEGIN
  IF TG_TABLE_NAME='attempt_snapshot_options' THEN
    v_snapshot:=CASE WHEN TG_OP='DELETE' THEN OLD.snapshot_id ELSE NEW.snapshot_id END;
    SELECT attempt_id INTO v_attempt FROM attempt_question_snapshots WHERE id=v_snapshot;
  ELSE
    v_attempt:=CASE WHEN TG_OP='DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END;
    IF TG_OP='UPDATE' AND NEW.attempt_id<>OLD.attempt_id THEN
      RAISE EXCEPTION 'cannot move attempt child' USING ERRCODE='23514'; END IF;
  END IF;
  SELECT * INTO v_parent FROM attempts WHERE id=v_attempt FOR UPDATE;
  IF v_parent.id IS NULL THEN RAISE EXCEPTION 'attempt not found' USING ERRCODE='23503'; END IF;
  IF v_parent.status='ABANDONED' AND TG_OP='DELETE' AND v_parent.abandoned_at<=CURRENT_TIMESTAMP-INTERVAL '30 days' THEN RETURN OLD; END IF;
  IF v_parent.snapshot_sealed_at IS NOT NULL THEN
    RAISE EXCEPTION 'attempt snapshot and source refs are sealed' USING ERRCODE='55000'; END IF;
  IF TG_OP<>'DELETE' AND TG_TABLE_NAME IN ('attempt_question_snapshots','attempt_group_snapshots') THEN
    IF NOT me_revision_available(NEW.source_revision_id) THEN
      RAISE EXCEPTION 'attempt source must be available Published content' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER guard_group_snapshot BEFORE INSERT OR UPDATE OR DELETE ON attempt_group_snapshots
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_question_snapshot BEFORE INSERT OR UPDATE OR DELETE ON attempt_question_snapshots
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_snapshot_option BEFORE INSERT OR UPDATE OR DELETE ON attempt_snapshot_options
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_attempt_media BEFORE INSERT OR UPDATE OR DELETE ON attempt_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_attempt_topics BEFORE INSERT OR UPDATE OR DELETE ON attempt_source_topics
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_attempt_mistakes BEFORE INSERT OR UPDATE OR DELETE ON attempt_source_mistakes
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();

CREATE FUNCTION me_revision_available(p_revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  WITH RECURSIVE reachable(id) AS (
    SELECT id FROM content_revisions WHERE id=p_revision
    UNION SELECT d.child_revision_id FROM revision_dependencies d JOIN reachable r ON d.parent_revision_id=r.id
  )
  SELECT EXISTS(SELECT 1 FROM reachable) AND NOT EXISTS(
    SELECT 1 FROM reachable r JOIN content_revisions v ON v.id=r.id JOIN content_items i ON i.id=v.content_item_id
    WHERE v.status<>'PUBLISHED' OR i.is_archived OR i.access_revoked_at IS NOT NULL)
$$;

CREATE FUNCTION me_guard_answer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_attempt UUID; v_parent attempts;
BEGIN
  v_attempt:=CASE WHEN TG_OP='DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END;
  SELECT * INTO v_parent FROM attempts WHERE id=v_attempt FOR UPDATE;
  IF v_parent.id IS NULL THEN RAISE EXCEPTION 'attempt not found' USING ERRCODE='23503'; END IF;
  IF TG_OP='DELETE' AND v_parent.status='ABANDONED' AND v_parent.abandoned_at<=CURRENT_TIMESTAMP-INTERVAL '30 days' THEN RETURN OLD; END IF;
  IF v_parent.status<>'IN_PROGRESS' THEN
    RAISE EXCEPTION 'answers cannot change after submit/abandon' USING ERRCODE='55000'; END IF;
  IF TG_OP='INSERT' AND v_parent.snapshot_sealed_at IS NOT NULL THEN
    RAISE EXCEPTION 'pre-create empty answer slots before sealing' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.attempt_id,NEW.snapshot_id) IS DISTINCT FROM (OLD.id,OLD.attempt_id,OLD.snapshot_id) THEN
      RAISE EXCEPTION 'answer identity is immutable' USING ERRCODE='23514'; END IF;
    NEW.version:=OLD.version+1; NEW.updated_at:=CURRENT_TIMESTAMP;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER guard_answer BEFORE INSERT OR UPDATE OR DELETE ON attempt_answers
  FOR EACH ROW EXECUTE FUNCTION me_guard_answer();

CREATE FUNCTION me_validate_attempt(p_id UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts; v_count INTEGER; v_min INTEGER; v_max INTEGER;
BEGIN
  SELECT * INTO v_attempt FROM attempts WHERE id=p_id;
  IF NOT FOUND OR v_attempt.status='ABANDONED' THEN RETURN; END IF;
  IF v_attempt.snapshot_sealed_at IS NULL THEN
    RAISE EXCEPTION 'attempt must be fully sealed before commit' USING ERRCODE='23514'; END IF;
  SELECT count(*),min(ordinal),max(ordinal) INTO v_count,v_min,v_max FROM attempt_question_snapshots WHERE attempt_id=p_id;
  IF v_count<>v_attempt.total_count OR v_min<>1 OR v_max<>v_count OR
     (SELECT count(*) FROM attempt_answers WHERE attempt_id=p_id)<>v_count THEN
    RAISE EXCEPTION 'attempt requires complete ordered snapshots and answer slots' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM attempt_question_snapshots s WHERE s.attempt_id=p_id AND
    (s.question_type_code<>'SINGLE_CHOICE' OR NOT (s.answer_key_snapshot ? 'correctOptionId') OR NOT EXISTS(
      SELECT 1 FROM attempt_snapshot_options o WHERE o.snapshot_id=s.id
      AND o.option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID))) THEN
    RAISE EXCEPTION 'snapshot key must belong to its own supported choice snapshot' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM attempt_question_snapshots s JOIN questions q ON q.id=s.question_id
    WHERE s.attempt_id=p_id AND ((q.group_id IS NULL AND s.group_snapshot_id IS NOT NULL) OR
      (q.group_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM attempt_group_snapshots g
       WHERE g.id=s.group_snapshot_id AND g.attempt_id=p_id AND g.group_id=q.group_id)))) THEN
    RAISE EXCEPTION 'question must carry its own group context' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM attempt_group_snapshots g LEFT JOIN attempt_question_snapshots s ON s.group_snapshot_id=g.id
    WHERE g.attempt_id=p_id GROUP BY g.id HAVING count(s.id)<>g.expected_question_count
    OR max(s.ordinal)-min(s.ordinal)+1<>count(s.id)) THEN
    RAISE EXCEPTION 'whole group must be contiguous and complete' USING ERRCODE='23514'; END IF;
  IF v_attempt.source_type='MIXED_GRAMMAR' AND (SELECT count(*) FROM attempt_source_topics WHERE attempt_id=p_id)<2 THEN
    RAISE EXCEPTION 'mixed Grammar needs multiple source topics' USING ERRCODE='23514'; END IF;
  IF v_attempt.source_type='MISTAKES' AND NOT EXISTS(SELECT 1 FROM attempt_source_mistakes WHERE attempt_id=p_id) THEN
    RAISE EXCEPTION 'mistakes source needs owned mistake refs' USING ERRCODE='23514'; END IF;
  IF v_attempt.status='SUBMITTED' THEN
    IF EXISTS(SELECT 1 FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id
      WHERE a.attempt_id=p_id AND (a.is_correct IS DISTINCT FROM
        COALESCE(a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID,FALSE)
        OR a.score IS DISTINCT FROM CASE WHEN a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID THEN 1 ELSE 0 END))
      OR v_attempt.correct_count<>(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_id AND is_correct)
      OR v_attempt.answered_count<>(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_id AND selected_option_id IS NOT NULL)
      THEN RAISE EXCEPTION 'result must match server snapshot grading' USING ERRCODE='23514'; END IF;
  END IF;
END $$;

CREATE FUNCTION me_attempt_complete_at_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  PERFORM me_validate_attempt(NEW.id); RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER attempt_complete_at_commit AFTER INSERT OR UPDATE ON attempts
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_attempt_complete_at_commit();

CREATE FUNCTION me_seal_attempt(p_id UUID,p_user UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts;
BEGIN
  PERFORM 1 FROM users WHERE id=p_user FOR UPDATE;
  SELECT * INTO v_attempt FROM attempts WHERE id=p_id AND user_id=p_user FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'attempt is not owned' USING ERRCODE='42501'; END IF;
  IF v_attempt.snapshot_sealed_at IS NULL THEN
    UPDATE attempts SET snapshot_sealed_at=CURRENT_TIMESTAMP WHERE id=p_id;
  END IF;
  PERFORM me_validate_attempt(p_id);
END $$;

CREATE FUNCTION me_save_answer(p_attempt UUID,p_user UUID,p_snapshot UUID,p_option UUID,p_expected_version BIGINT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts; v_id UUID;
BEGIN
  PERFORM 1 FROM users WHERE id=p_user FOR UPDATE;
  SELECT * INTO v_attempt FROM attempts WHERE id=p_attempt AND user_id=p_user FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'attempt is not owned' USING ERRCODE='42501'; END IF;
  IF v_attempt.status<>'IN_PROGRESS' THEN RETURN FALSE; END IF;
  IF NOT me_has_role(p_user,'LEARNER') THEN RAISE EXCEPTION 'active learner required' USING ERRCODE='42501'; END IF;
  IF v_attempt.deadline_at<=CURRENT_TIMESTAMP THEN
    PERFORM me_submit_attempt(p_attempt,p_user,'TIME_LIMIT'); RETURN FALSE; END IF;
  UPDATE attempt_answers SET selected_option_id=p_option,response_payload=NULL,
    answered_at=CASE WHEN p_option IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,
    is_correct=NULL,score=NULL WHERE attempt_id=p_attempt AND snapshot_id=p_snapshot
    AND version=p_expected_version RETURNING id INTO v_id;
  IF v_id IS NOT NULL THEN UPDATE attempts SET last_active_at=CURRENT_TIMESTAMP WHERE id=p_attempt; END IF;
  RETURN v_id IS NOT NULL;
END $$;

CREATE FUNCTION me_refresh_mistakes(p_user UUID,p_question UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_wrong INTEGER; v_streak INTEGER; v_last_wrong TIMESTAMPTZ; v_last TIMESTAMPTZ;
  v_attempt UUID; v_snapshot UUID; v_state TEXT;
BEGIN
  SELECT count(*) INTO v_wrong FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id
    JOIN attempts t ON t.id=a.attempt_id WHERE t.user_id=p_user AND t.status='SUBMITTED'
    AND s.question_id=p_question AND a.is_correct=FALSE;
  IF v_wrong=0 THEN RETURN; END IF;
  SELECT t.id,s.id,t.effective_finished_at INTO v_attempt,v_snapshot,v_last_wrong
    FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id JOIN attempts t ON t.id=a.attempt_id
    WHERE t.user_id=p_user AND t.status='SUBMITTED' AND s.question_id=p_question AND a.is_correct=FALSE
    ORDER BY t.effective_finished_at DESC,t.id DESC LIMIT 1;
  SELECT count(*),max(t.effective_finished_at) INTO v_streak,v_last
    FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id JOIN attempts t ON t.id=a.attempt_id
    WHERE t.user_id=p_user AND t.status='SUBMITTED' AND s.question_id=p_question AND a.is_correct=TRUE
    AND (t.effective_finished_at,t.id)>(v_last_wrong,v_attempt);
  v_last:=COALESCE(v_last,v_last_wrong);
  v_state:=CASE WHEN v_streak=0 THEN 'OPEN' WHEN v_streak=1 THEN 'IMPROVING' ELSE 'RESOLVED' END;
  INSERT INTO mistake_records(id,user_id,question_id,source_attempt_id,source_snapshot_id,state,
    wrong_count,consecutive_correct,last_wrong_at,last_evaluated_at,resolved_at)
    VALUES(gen_random_uuid(),p_user,p_question,v_attempt,v_snapshot,v_state,v_wrong,v_streak,v_last_wrong,v_last,
      CASE WHEN v_streak>=2 THEN v_last ELSE NULL END)
    ON CONFLICT(user_id,question_id) DO UPDATE SET source_attempt_id=EXCLUDED.source_attempt_id,
      source_snapshot_id=EXCLUDED.source_snapshot_id,state=EXCLUDED.state,wrong_count=EXCLUDED.wrong_count,
      consecutive_correct=EXCLUDED.consecutive_correct,last_wrong_at=EXCLUDED.last_wrong_at,
      last_evaluated_at=EXCLUDED.last_evaluated_at,resolved_at=EXCLUDED.resolved_at,updated_at=CURRENT_TIMESTAMP;
END $$;

CREATE FUNCTION me_submit_attempt(p_attempt UUID,p_user UUID,p_reason TEXT DEFAULT 'MANUAL')
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts; v_finish TIMESTAMPTZ; v_reason TEXT; v_question UUID;
BEGIN
  PERFORM 1 FROM users WHERE id=p_user FOR UPDATE;
  SELECT * INTO v_attempt FROM attempts WHERE id=p_attempt AND user_id=p_user FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'attempt is not owned' USING ERRCODE='42501'; END IF;
  IF v_attempt.status='SUBMITTED' THEN RETURN TRUE; END IF;
  IF v_attempt.status<>'IN_PROGRESS' THEN RETURN FALSE; END IF;
  IF v_attempt.snapshot_sealed_at IS NULL THEN RAISE EXCEPTION 'attempt not sealed' USING ERRCODE='23514'; END IF;
  IF v_attempt.deadline_at<=CURRENT_TIMESTAMP THEN v_finish:=v_attempt.deadline_at;v_reason:='TIME_LIMIT';
  ELSE
    IF p_reason<>'MANUAL' OR NOT me_has_role(p_user,'LEARNER') THEN
      RAISE EXCEPTION 'manual submit requires active learner' USING ERRCODE='42501'; END IF;
    v_finish:=CURRENT_TIMESTAMP;v_reason:='MANUAL';
  END IF;
  UPDATE attempt_answers a SET is_correct=COALESCE(a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID,FALSE),
    score=CASE WHEN a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID THEN 1 ELSE 0 END
    FROM attempt_question_snapshots s WHERE a.snapshot_id=s.id AND a.attempt_id=p_attempt;
  UPDATE attempts SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP,effective_finished_at=v_finish,
    submit_reason=v_reason,correct_count=(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_attempt AND is_correct),
    answered_count=(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_attempt AND selected_option_id IS NOT NULL),
    score=(SELECT COALESCE(sum(score),0) FROM attempt_answers WHERE attempt_id=p_attempt),
    duration_seconds=GREATEST(0,floor(EXTRACT(EPOCH FROM(v_finish-started_at)))::INTEGER),
    local_day=(v_finish AT TIME ZONE timezone)::DATE WHERE id=p_attempt;
  FOR v_question IN SELECT question_id FROM attempt_question_snapshots WHERE attempt_id=p_attempt LOOP
    PERFORM me_refresh_mistakes(p_user,v_question);
  END LOOP;
  RETURN TRUE;
END $$;

CREATE FUNCTION me_guard_upload_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_media media_assets; v_previous UUID;
BEGIN
  SELECT * INTO v_media FROM media_assets WHERE id=NEW.media_id;
  IF NEW.declared_size_bytes>(CASE WHEN v_media.purpose='AVATAR' THEN 2097152
     WHEN v_media.media_type='IMAGE' THEN 5242880 ELSE 20971520 END) THEN
    RAISE EXCEPTION 'upload size exceeds type/purpose policy' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.media_id,NEW.temp_bucket,NEW.temp_object_key,NEW.declared_size_bytes,
        NEW.declared_mime_type,NEW.max_size_bytes,NEW.expires_at)
      IS DISTINCT FROM (OLD.id,OLD.media_id,OLD.temp_bucket,OLD.temp_object_key,OLD.declared_size_bytes,
        OLD.declared_mime_type,OLD.max_size_bytes,OLD.expires_at) THEN
      RAISE EXCEPTION 'upload grant context is immutable' USING ERRCODE='23514'; END IF;
    IF OLD.finished_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'terminal upload metadata is immutable' USING ERRCODE='55000'; END IF;
    IF OLD.verification_deadline_at IS NOT NULL AND
      (NEW.verification_started_at,NEW.verification_deadline_at) IS DISTINCT FROM
      (OLD.verification_started_at,OLD.verification_deadline_at) THEN
      RAISE EXCEPTION 'verification retries cannot extend deadline' USING ERRCODE='23514'; END IF;
    IF NEW.status<>OLD.status AND NOT ((OLD.status='PENDING' AND NEW.status IN ('VERIFYING','FAILED','EXPIRED'))
       OR (OLD.status='VERIFYING' AND NEW.status IN ('COMPLETED','FAILED'))) THEN
      RAISE EXCEPTION 'invalid upload transition' USING ERRCODE='23514'; END IF;
    IF NEW.lease_until IS NOT NULL AND NEW.lease_until>CURRENT_TIMESTAMP+INTERVAL '60 seconds' THEN
      RAISE EXCEPTION 'verification lease exceeds 60 seconds' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_upload_request BEFORE INSERT OR UPDATE ON media_upload_requests
  FOR EACH ROW EXECUTE FUNCTION me_guard_upload_request();

CREATE FUNCTION me_check_upload_complete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_request media_upload_requests; v_media media_assets;
BEGIN
  SELECT * INTO v_request FROM media_upload_requests WHERE id=NEW.id;
  SELECT * INTO v_media FROM media_assets WHERE id=v_request.media_id;
  IF v_request.status='COMPLETED' AND (v_media.status NOT IN ('READY','ARCHIVED')
    OR v_media.file_size_bytes<>v_request.declared_size_bytes) THEN
    RAISE EXCEPTION 'completed upload must match confirmed media' USING ERRCODE='23514'; END IF;
  IF v_request.status IN ('FAILED','EXPIRED') AND v_media.status<>'FAILED' THEN
    RAISE EXCEPTION 'failed upload must match failed media' USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER upload_result_consistent AFTER INSERT OR UPDATE ON media_upload_requests
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_upload_complete();

-- Explicit read models: application authorization still scopes queries by user.
CREATE VIEW learning_daily_activity AS
SELECT user_id,local_day,sum(active_seconds) AS active_seconds
FROM study_sessions GROUP BY user_id,local_day;

CREATE VIEW learning_review_summary AS
SELECT user_id,local_day,count(*) AS review_count,count(DISTINCT word_sense_id) AS unique_senses_reviewed
FROM word_reviews GROUP BY user_id,local_day;

CREATE VIEW learning_attempt_summary AS
SELECT user_id,local_day,category,count(*) AS submitted_attempts,
  sum(total_count) AS question_count,sum(correct_count) AS correct_count,
  sum(total_count-answered_count) AS blank_count
FROM attempts WHERE status='SUBMITTED' GROUP BY user_id,local_day,category;

CREATE VIEW media_quota_usage AS
SELECT id,scope,owner_user_id,quota_bytes,reserved_bytes,used_bytes,
  GREATEST(0,quota_bytes-reserved_bytes-used_bytes) AS available_bytes
FROM storage_pools;


-- Safe catalog/config seeds. No users, passwords, API keys or production credentials.
INSERT INTO roles(id,code,name) VALUES (1,'LEARNER','Learner'),(2,'CONTENT_ADMIN','Content administrator'),(3,'SYSTEM_ADMIN','System administrator');
INSERT INTO question_types(code,name,schema_version,enabled) VALUES ('SINGLE_CHOICE','Chọn một đáp án',1,TRUE);
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.bcrypt_cost','12'::jsonb,'INTEGER','rounds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.max_sessions','5'::jsonb,'INTEGER','sessions');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.session_seconds','604800'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.access_seconds','900'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.otp_seconds','300'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.otp_resend_seconds','60'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.otp_send_hourly_email','5'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.otp_send_hourly_ip','300'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.otp_verify_15min_ip','300'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('auth.login_15min_ip','300'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.upload_seconds','600'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.verify_seconds','300'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.lease_seconds','60'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.verify_parallelism','2'::jsonb,'INTEGER','workers');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.avatar_hourly','10'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.content_hourly','100'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.avatar_open','2'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('media.content_open','3'::jsonb,'INTEGER','requests');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('storage.avatar_bytes','52428800'::jsonb,'INTEGER','bytes');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('storage.content_bytes','10737418240'::jsonb,'INTEGER','bytes');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('storage.temp_bytes','1073741824'::jsonb,'INTEGER','bytes');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('practice.max_questions','100'::jsonb,'INTEGER','questions');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('practice.max_open','5'::jsonb,'INTEGER','attempts');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('practice.abandon_days','7'::jsonb,'INTEGER','days');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('stats.weak_min_sample','20'::jsonb,'INTEGER','questions');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('stats.weak_window_days','30'::jsonb,'INTEGER','days');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('stats.weak_below_percent','70'::jsonb,'INTEGER','percent');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('import.max_bytes','5242880'::jsonb,'INTEGER','bytes');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('import.max_rows','5000'::jsonb,'INTEGER','rows');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('notification.max_retries','3'::jsonb,'INTEGER','retries');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('notification.lease_seconds','60'::jsonb,'INTEGER','seconds');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('notification.learning_email_default','false'::jsonb,'BOOLEAN',NULL);
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('notification.default_timezone','"Asia/Ho_Chi_Minh"'::jsonb,'STRING',NULL);
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('retention.notification_days','90'::jsonb,'INTEGER','days');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('retention.job_days','30'::jsonb,'INTEGER','days');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('retention.auth_days','30'::jsonb,'INTEGER','days');
INSERT INTO system_settings(key,value,value_type,unit) VALUES ('retention.audit_days','365'::jsonb,'INTEGER','days');
INSERT INTO storage_pools(id,scope,quota_bytes) VALUES ('20000000-0000-0000-0000-000000000001','CONTENT',10737418240),('20000000-0000-0000-0000-000000000002','TEMP',1073741824);
COMMIT;
