-- V6: Content foundation, media assets, vocabulary catalog, and learning SRS tables

-- 1. Content sources: xuất xứ và bản quyền
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

-- 2. Media assets: quản lý file lâu dài (ảnh, audio)
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

-- 3. Content items: định danh ổn định và pointer bản công bố
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

-- 4. Content revisions: bản thảo và bản công bố bất biến
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

-- 5. Content source links: nguồn trích dẫn của từng revision
CREATE TABLE content_source_links (
    revision_id UUID NOT NULL,
    source_id UUID NOT NULL,
    usage_note TEXT,
    source_snapshot JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT content_source_links_c1 PRIMARY KEY (revision_id, source_id),
    CONSTRAINT content_source_links_c2 CHECK (source_snapshot IS NULL OR (jsonb_typeof(source_snapshot) = 'object' AND octet_length(source_snapshot::text) <= 1048576))
);

-- 6. Revision dependencies: liên kết ghim bản revision con
CREATE TABLE revision_dependencies (
    parent_revision_id UUID NOT NULL,
    relation_key VARCHAR(200) NOT NULL,
    child_revision_id UUID NOT NULL,
    CONSTRAINT revision_dependencies_c1 PRIMARY KEY (parent_revision_id, relation_key),
    CONSTRAINT revision_dependencies_c2 CHECK (parent_revision_id <> child_revision_id)
);

-- 7. Revision media refs: tra cứu file media trong revision
CREATE TABLE revision_media_refs (
    revision_id UUID NOT NULL,
    media_id UUID NOT NULL,
    usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    CONSTRAINT revision_media_refs_c1 PRIMARY KEY (revision_id, media_id, usage_role),
    CONSTRAINT revision_media_refs_c2 CHECK (usage_role IN ('STIMULUS','EXPLANATION','TRANSCRIPT'))
);

-- 8. Words: projection từ content item WORD
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

-- 9. Word senses: nghĩa cụ thể (đơn vị theo dõi SRS)
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

-- 10. Word sense examples: ví dụ minh họa
CREATE TABLE word_sense_examples (
    id UUID PRIMARY KEY,
    word_sense_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    example_en TEXT NOT NULL,
    example_vi TEXT,
    CONSTRAINT word_sense_examples_c1 UNIQUE (word_sense_id, position),
    CONSTRAINT word_sense_examples_c2 CHECK (position > 0 AND btrim(example_en) <> '')
);

-- 11. Decks: projection bộ thẻ từ vựng
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

-- 12. Deck words: thành viên nghĩa từ thuộc deck
CREATE TABLE deck_words (
    deck_id UUID NOT NULL,
    word_sense_id UUID NOT NULL,
    position INTEGER NOT NULL,
    CONSTRAINT deck_words_c1 PRIMARY KEY (deck_id, word_sense_id),
    CONSTRAINT deck_words_c2 UNIQUE (deck_id, position),
    CONSTRAINT deck_words_c3 CHECK (position > 0)
);

-- 13. User saved words: nghĩa từ học viên đánh dấu lưu
CREATE TABLE user_saved_words (
    user_id UUID NOT NULL,
    word_sense_id UUID NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_saved_words_c1 PRIMARY KEY (user_id, word_sense_id)
);

-- 14. User word progress: tiến độ SRS hiện tại theo nghĩa
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

-- 15. Word reviews: lịch sử review bất biến và idempotent
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

-- 16. Audit logs: nhật ký kiểm toán cho tác vụ quản trị
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

-- 17. Foreign Key constraints
ALTER TABLE content_sources
    ADD CONSTRAINT fk_content_sources_created_by_users FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT;

ALTER TABLE media_assets
    ADD CONSTRAINT fk_media_assets_source_id_content_sources FOREIGN KEY (source_id) REFERENCES content_sources (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_media_assets_uploaded_by_users FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE RESTRICT;

ALTER TABLE content_items
    ADD CONSTRAINT fk_content_items_created_by_users FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_content_items_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE content_revisions
    ADD CONSTRAINT fk_content_revisions_content_item_id_content_items FOREIGN KEY (content_item_id) REFERENCES content_items (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_content_revisions_created_by_users FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_content_revisions_reviewed_by_users FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT;

ALTER TABLE content_source_links
    ADD CONSTRAINT fk_content_source_links_revision_id_content_revisions FOREIGN KEY (revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_content_source_links_source_id_content_sources FOREIGN KEY (source_id) REFERENCES content_sources (id) ON DELETE RESTRICT;

ALTER TABLE revision_dependencies
    ADD CONSTRAINT fk_revision_dependencies_parent_revision_id_content_revisions FOREIGN KEY (parent_revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_revision_dependencies_child_revision_id_content_revisions FOREIGN KEY (child_revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT;

ALTER TABLE revision_media_refs
    ADD CONSTRAINT fk_revision_media_refs_revision_id_content_revisions FOREIGN KEY (revision_id) REFERENCES content_revisions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_revision_media_refs_media_id_media_assets FOREIGN KEY (media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;

ALTER TABLE words
    ADD CONSTRAINT fk_words_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_words_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_words_audio_media_id_media_assets FOREIGN KEY (audio_media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;

ALTER TABLE word_senses
    ADD CONSTRAINT fk_word_senses_word_id_words FOREIGN KEY (word_id) REFERENCES words (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_word_senses_word_id_word_revision_id_content_revisions FOREIGN KEY (word_id, word_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_word_senses_audio_media_id_media_assets FOREIGN KEY (audio_media_id) REFERENCES media_assets (id) ON DELETE RESTRICT;

ALTER TABLE word_sense_examples
    ADD CONSTRAINT fk_word_sense_examples_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;

ALTER TABLE decks
    ADD CONSTRAINT fk_decks_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_decks_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE deck_words
    ADD CONSTRAINT fk_deck_words_deck_id_decks FOREIGN KEY (deck_id) REFERENCES decks (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_deck_words_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;

ALTER TABLE user_saved_words
    ADD CONSTRAINT fk_user_saved_words_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_user_saved_words_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;

ALTER TABLE user_word_progress
    ADD CONSTRAINT fk_user_word_progress_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_user_word_progress_word_sense_id_word_senses FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT;

ALTER TABLE word_reviews
    ADD CONSTRAINT fk_word_reviews_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_word_reviews_source_word_id_word_sense_id_word_senses FOREIGN KEY (source_word_id, word_sense_id) REFERENCES word_senses (word_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_word_reviews_source_word_id_source_revision_id_content_revisions FOREIGN KEY (source_word_id, source_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;

ALTER TABLE audit_logs
    ADD CONSTRAINT fk_audit_logs_actor_user_id_users FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT;

-- 18. Indexes
CREATE INDEX ix_media_owner_purpose ON media_assets(uploaded_by, purpose, status);
CREATE INDEX ix_media_cleanup ON media_assets(status, archived_at);
CREATE UNIQUE INDEX uq_content_external_key ON content_items(content_type, import_namespace, external_key) WHERE external_key IS NOT NULL;
CREATE INDEX ix_content_catalog ON content_items(content_type, is_archived);
CREATE UNIQUE INDEX uq_revision_one_open ON content_revisions(content_item_id) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_revisions_item_version ON content_revisions(content_item_id, version_no DESC);
CREATE INDEX ix_source_links_source ON content_source_links(source_id);
CREATE INDEX ix_revision_dependencies_child ON revision_dependencies(child_revision_id);
CREATE INDEX ix_revision_media_asset ON revision_media_refs(media_id);
CREATE INDEX ix_words_search ON words(lower(lemma) varchar_pattern_ops);
CREATE INDEX ix_senses_filters ON word_senses(cefr_level, part_of_speech) WHERE is_archived = FALSE;
CREATE UNIQUE INDEX uq_senses_active_position ON word_senses(word_id, sense_no) WHERE is_archived = FALSE;
CREATE INDEX ix_deck_words_sense ON deck_words(word_sense_id);
CREATE INDEX ix_progress_due ON user_word_progress(user_id, due_at);
CREATE INDEX ix_reviews_user_day ON word_reviews(user_id, local_day);
CREATE INDEX ix_reviews_sense_time ON word_reviews(user_id, word_sense_id, reviewed_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs(actor_user_id, created_at DESC);
CREATE INDEX ix_audit_entity ON audit_logs(entity_type, entity_id, created_at DESC);
