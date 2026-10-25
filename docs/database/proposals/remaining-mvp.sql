-- SUPERSEDED 2026-10-25 by approved-baseline.sql and ../APPROVED_BASELINE.md.
-- Historical review proposal; do not use as the current implementation design.
-- REVIEW PROPOSAL ONLY. Not loaded by normal Flyway startup.
-- Apply after V1 + V2 in an isolated PostgreSQL 17 database.
-- The 36 tables below complete the proposed 44-table non-AI baseline.

-- Content, revisions, media and import (M08): 7 tables.
CREATE TABLE content_items (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) NOT NULL CHECK (content_type IN
        ('WORD','DECK','GRAMMAR_TOPIC','GRAMMAR_LESSON','QUESTION','QUESTION_GROUP','PRACTICE_SET')),
    created_by UUID NOT NULL REFERENCES users(id),
    published_revision_id UUID,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_content_items_type_archived ON content_items(content_type, is_archived);

CREATE TABLE content_revisions (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL REFERENCES content_items(id),
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED')),
    change_summary TEXT,
    created_by UUID NOT NULL REFERENCES users(id),
    reviewed_by UUID REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_content_revisions_number UNIQUE(content_item_id, version_no),
    CONSTRAINT uq_content_revisions_item_id UNIQUE(content_item_id, id)
);
ALTER TABLE content_items ADD CONSTRAINT fk_content_items_published_revision
    FOREIGN KEY(id, published_revision_id)
    REFERENCES content_revisions(content_item_id, id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX idx_content_revisions_item_status ON content_revisions(content_item_id, status);

CREATE TABLE content_sources (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source_url TEXT,
    source_type VARCHAR(30) NOT NULL CHECK (source_type IN ('SELF_AUTHORED','OPEN_DATA','LICENSED','OTHER')),
    author VARCHAR(255),
    license_code VARCHAR(60) NOT NULL,
    attribution TEXT,
    license_url TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE content_source_links (
    revision_id UUID NOT NULL REFERENCES content_revisions(id),
    source_id UUID NOT NULL REFERENCES content_sources(id),
    usage_note TEXT,
    PRIMARY KEY(revision_id, source_id)
);
CREATE INDEX idx_content_source_links_source ON content_source_links(source_id);

CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    bucket VARCHAR(100) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    purpose VARCHAR(20) NOT NULL DEFAULT 'CONTENT' CHECK (purpose IN ('CONTENT','AVATAR')),
    file_size_bytes BIGINT NOT NULL CHECK (file_size_bytes > 0),
    duration_ms INTEGER CHECK (duration_ms >= 0),
    bitrate_kbps INTEGER CHECK (bitrate_kbps > 0),
    checksum_sha256 VARCHAR(64) CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    access_level VARCHAR(20) NOT NULL CHECK (access_level IN ('PUBLIC','PRIVATE')),
    source_id UUID REFERENCES content_sources(id),
    uploaded_by UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','READY','FAILED','ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(bucket, object_key)
);
CREATE INDEX idx_media_assets_status_created ON media_assets(status, created_at);
CREATE INDEX idx_media_assets_source ON media_assets(source_id);
ALTER TABLE users ADD CONSTRAINT fk_users_avatar_media
    FOREIGN KEY(avatar_media_id) REFERENCES media_assets(id);

CREATE TABLE import_jobs (
    id UUID PRIMARY KEY,
    created_by UUID NOT NULL REFERENCES users(id),
    import_type VARCHAR(30) NOT NULL CHECK (import_type IN ('VOCABULARY_CSV','QUESTIONS_JSON','GRAMMAR_JSON')),
    file_object_key VARCHAR(500) NOT NULL,
    status VARCHAR(25) NOT NULL CHECK (status IN ('PENDING','PROCESSING','COMPLETED','PARTIAL_SUCCESS','FAILED')),
    total_rows INTEGER NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    imported_rows INTEGER NOT NULL DEFAULT 0 CHECK (imported_rows >= 0),
    failed_rows INTEGER NOT NULL DEFAULT 0 CHECK (failed_rows >= 0),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (imported_rows + failed_rows <= total_rows)
);
CREATE INDEX idx_import_jobs_creator_created ON import_jobs(created_by, created_at DESC);

CREATE TABLE import_job_errors (
    id UUID PRIMARY KEY,
    import_job_id UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    row_number INTEGER CHECK (row_number > 0),
    field_name VARCHAR(100),
    error_code VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_import_job_errors_job_row ON import_job_errors(import_job_id, row_number);

-- Vocabulary and flashcards (M03): 7 tables.
-- These relational rows are published projections; drafts live in revisions.
CREATE TABLE words (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL UNIQUE REFERENCES content_items(id),
    lemma VARCHAR(150) NOT NULL,
    language_code VARCHAR(10) NOT NULL DEFAULT 'en',
    ipa VARCHAR(150),
    audio_media_id UUID REFERENCES media_assets(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE word_senses (
    id UUID PRIMARY KEY,
    word_id UUID NOT NULL REFERENCES words(id),
    sense_no SMALLINT NOT NULL CHECK (sense_no > 0),
    part_of_speech VARCHAR(30) NOT NULL CHECK (part_of_speech IN
        ('NOUN','VERB','ADJECTIVE','ADVERB','PRONOUN','PREPOSITION','CONJUNCTION',
         'DETERMINER','INTERJECTION','NUMERAL','PHRASE')),
    cefr_level VARCHAR(2) CHECK (cefr_level IN ('A1','A2','B1','B2','C1','C2')),
    meaning_vi TEXT NOT NULL,
    definition_en TEXT,
    example_en TEXT,
    example_vi TEXT,
    audio_media_id UUID REFERENCES media_assets(id),
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(word_id, sense_no)
);
CREATE INDEX idx_word_senses_cefr_pos ON word_senses(cefr_level, part_of_speech)
    WHERE is_archived = FALSE;

CREATE TABLE decks (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL UNIQUE REFERENCES content_items(id),
    title VARCHAR(200) NOT NULL,
    description TEXT,
    level VARCHAR(10) CHECK (level IN ('A1','A2','B1','B2','C1','C2')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE deck_words (
    deck_id UUID NOT NULL REFERENCES decks(id),
    word_sense_id UUID NOT NULL REFERENCES word_senses(id),
    position INTEGER NOT NULL CHECK (position > 0),
    PRIMARY KEY(deck_id, word_sense_id),
    UNIQUE(deck_id, position)
);
CREATE INDEX idx_deck_words_sense ON deck_words(word_sense_id);

CREATE TABLE user_saved_words (
    user_id UUID NOT NULL REFERENCES users(id),
    word_sense_id UUID NOT NULL REFERENCES word_senses(id),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id, word_sense_id)
);

CREATE TABLE user_word_progress (
    user_id UUID NOT NULL REFERENCES users(id),
    word_sense_id UUID NOT NULL REFERENCES word_senses(id),
    repetitions INTEGER NOT NULL DEFAULT 0 CHECK (repetitions >= 0),
    interval_days INTEGER NOT NULL DEFAULT 0 CHECK (interval_days >= 0),
    ease_factor NUMERIC(4,2) NOT NULL DEFAULT 2.50 CHECK (ease_factor > 0),
    due_at TIMESTAMPTZ NOT NULL,
    last_reviewed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id, word_sense_id)
);
CREATE INDEX idx_user_word_progress_due ON user_word_progress(user_id, due_at);

CREATE TABLE word_reviews (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    word_sense_id UUID NOT NULL REFERENCES word_senses(id),
    request_id UUID NOT NULL,
    rating VARCHAR(10) NOT NULL CHECK (rating IN ('AGAIN','HARD','GOOD')),
    previous_due_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, request_id)
);
CREATE INDEX idx_word_reviews_user_reviewed ON word_reviews(user_id, reviewed_at DESC);

-- Grammar (M04): 3 tables.
CREATE TABLE grammar_topics (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL UNIQUE REFERENCES content_items(id),
    slug VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(180) NOT NULL,
    description TEXT,
    difficulty VARCHAR(15) CHECK (difficulty IN ('BASIC','INTERMEDIATE','ADVANCED')),
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE grammar_lessons (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL UNIQUE REFERENCES content_items(id),
    topic_id UUID NOT NULL REFERENCES grammar_topics(id),
    title VARCHAR(200) NOT NULL,
    body JSONB NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    estimated_minutes SMALLINT CHECK (estimated_minutes > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_grammar_lessons_topic_order ON grammar_lessons(topic_id, sort_order);

CREATE TABLE lesson_progress (
    user_id UUID NOT NULL REFERENCES users(id),
    lesson_id UUID NOT NULL REFERENCES grammar_lessons(id),
    state VARCHAR(20) NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id, lesson_id),
    CHECK ((state = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (state = 'IN_PROGRESS' AND completed_at IS NULL))
);
CREATE INDEX idx_lesson_progress_user_state ON lesson_progress(user_id, state);

-- Shared question bank / practice (M05): 9 tables.
CREATE TABLE questions (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL UNIQUE REFERENCES content_items(id),
    category VARCHAR(20) NOT NULL CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    question_type VARCHAR(32) NOT NULL CHECK (question_type IN ('MULTIPLE_CHOICE','FILL_OPTION')),
    part_number SMALLINT,
    prompt TEXT,
    explanation TEXT NOT NULL,
    difficulty VARCHAR(20) NOT NULL CHECK (difficulty IN ('EASY','MEDIUM','HARD')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((category = 'TOEIC' AND part_number IS NOT NULL AND part_number BETWEEN 1 AND 7)
        OR (category <> 'TOEIC' AND part_number IS NULL))
);
CREATE INDEX idx_questions_category_part ON questions(category, part_number);

CREATE TABLE question_options (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES questions(id),
    position SMALLINT NOT NULL CHECK (position > 0),
    body TEXT NOT NULL,
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(question_id, position)
);
CREATE UNIQUE INDEX uq_question_options_correct ON question_options(question_id) WHERE is_correct = TRUE;

CREATE TABLE question_groups (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL UNIQUE REFERENCES content_items(id),
    part_number SMALLINT CHECK (part_number BETWEEN 1 AND 7),
    title VARCHAR(200),
    group_type VARCHAR(30) NOT NULL CHECK (group_type IN
        ('PHOTO','QUESTION_RESPONSE','CONVERSATION','TALK','TEXT_COMPLETION','PASSAGE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE group_questions (
    group_id UUID NOT NULL REFERENCES question_groups(id),
    question_id UUID NOT NULL REFERENCES questions(id),
    position SMALLINT NOT NULL CHECK (position > 0),
    PRIMARY KEY(group_id, question_id),
    UNIQUE(group_id, position)
);
CREATE INDEX idx_group_questions_question ON group_questions(question_id);

CREATE TABLE group_materials (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES question_groups(id),
    position SMALLINT NOT NULL CHECK (position > 0),
    material_type VARCHAR(20) NOT NULL CHECK (material_type IN ('AUDIO','IMAGE','TEXT')),
    media_id UUID REFERENCES media_assets(id),
    body TEXT,
    UNIQUE(group_id, position),
    CHECK ((material_type = 'TEXT' AND body IS NOT NULL AND media_id IS NULL)
        OR (material_type IN ('AUDIO','IMAGE') AND media_id IS NOT NULL))
);

CREATE TABLE practice_sets (
    id UUID PRIMARY KEY,
    content_item_id UUID NOT NULL UNIQUE REFERENCES content_items(id),
    category VARCHAR(20) NOT NULL CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    title VARCHAR(200) NOT NULL,
    deck_id UUID REFERENCES decks(id),
    topic_id UUID REFERENCES grammar_topics(id),
    part_number SMALLINT,
    selection_mode VARCHAR(20) NOT NULL CHECK (selection_mode IN ('FIXED','DYNAMIC')),
    time_limit_seconds INTEGER CHECK (time_limit_seconds > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((category = 'TOEIC' AND part_number IS NOT NULL AND part_number BETWEEN 1 AND 7)
        OR (category <> 'TOEIC' AND part_number IS NULL)),
    CHECK (deck_id IS NULL OR category = 'VOCABULARY'),
    CHECK (topic_id IS NULL OR category = 'GRAMMAR')
);
CREATE INDEX idx_practice_sets_category_part ON practice_sets(category, part_number);

CREATE TABLE set_items (
    id UUID PRIMARY KEY,
    set_id UUID NOT NULL REFERENCES practice_sets(id),
    position INTEGER NOT NULL CHECK (position > 0),
    question_id UUID REFERENCES questions(id),
    group_id UUID REFERENCES question_groups(id),
    UNIQUE(set_id, position),
    CHECK (num_nonnulls(question_id, group_id) = 1)
);

CREATE TABLE question_words (
    question_id UUID NOT NULL REFERENCES questions(id),
    word_sense_id UUID NOT NULL REFERENCES word_senses(id),
    PRIMARY KEY(question_id, word_sense_id)
);
CREATE INDEX idx_question_words_sense ON question_words(word_sense_id);

CREATE TABLE question_topics (
    question_id UUID NOT NULL REFERENCES questions(id),
    topic_id UUID NOT NULL REFERENCES grammar_topics(id),
    PRIMARY KEY(question_id, topic_id)
);
CREATE INDEX idx_question_topics_topic ON question_topics(topic_id);

-- Attempts, immutable snapshots and mistakes (M06): 4 tables.
CREATE TABLE attempts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    practice_set_id UUID REFERENCES practice_sets(id),
    category VARCHAR(20) NOT NULL CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','SUBMITTED','ABANDONED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    correct_count INTEGER CHECK (correct_count >= 0),
    total_count INTEGER NOT NULL CHECK (total_count > 0),
    duration_seconds INTEGER CHECK (duration_seconds >= 0),
    CONSTRAINT uq_attempts_user_id UNIQUE(user_id, id),
    CHECK (correct_count IS NULL OR correct_count <= total_count),
    CHECK ((status = 'SUBMITTED' AND submitted_at IS NOT NULL
            AND correct_count IS NOT NULL AND duration_seconds IS NOT NULL)
        OR (status IN ('IN_PROGRESS','ABANDONED') AND submitted_at IS NULL AND correct_count IS NULL))
);
CREATE INDEX idx_attempts_user_started ON attempts(user_id, started_at DESC);

CREATE TABLE attempt_question_snapshots (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES attempts(id),
    question_id UUID REFERENCES questions(id),
    source_revision_id UUID REFERENCES content_revisions(id),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    question_snapshot JSONB NOT NULL CHECK (jsonb_typeof(question_snapshot) = 'object'),
    answer_key_snapshot JSONB NOT NULL CHECK (jsonb_typeof(answer_key_snapshot) = 'object'),
    explanation_snapshot JSONB,
    UNIQUE(attempt_id, ordinal),
    CONSTRAINT uq_attempt_snapshots_attempt_id UNIQUE(attempt_id, id)
);

CREATE TABLE attempt_answers (
    id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL UNIQUE REFERENCES attempt_question_snapshots(id),
    selected_option_id UUID,
    answer_text TEXT,
    is_correct BOOLEAN,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (num_nonnulls(selected_option_id, answer_text) <= 1)
);
-- selected_option_id intentionally references an option inside the immutable
-- JSON snapshot, not a mutable live question_options row. Validate in service.

CREATE TABLE mistake_records (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    question_id UUID REFERENCES questions(id),
    source_attempt_id UUID NOT NULL,
    source_snapshot_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','IMPROVING','RESOLVED')),
    mistake_count INTEGER NOT NULL DEFAULT 1 CHECK (mistake_count >= 1),
    last_mistake_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    FOREIGN KEY(user_id, source_attempt_id) REFERENCES attempts(user_id, id),
    FOREIGN KEY(source_attempt_id, source_snapshot_id)
        REFERENCES attempt_question_snapshots(attempt_id, id),
    CHECK ((status = 'RESOLVED' AND resolved_at IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_at IS NULL))
);
CREATE UNIQUE INDEX uq_mistakes_user_question
    ON mistake_records(user_id, question_id) WHERE question_id IS NOT NULL;
CREATE UNIQUE INDEX uq_mistakes_user_snapshot_without_question
    ON mistake_records(user_id, source_snapshot_id) WHERE question_id IS NULL;
CREATE INDEX idx_mistakes_user_state_time ON mistake_records(user_id, status, last_mistake_at DESC);

-- Notifications (M10): 4 tables.
CREATE TABLE reminder_rules (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('IN_APP','EMAIL','BOTH')),
    minimum_gap_minutes INTEGER NOT NULL CHECK (minimum_gap_minutes >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    reminder_rule_id UUID REFERENCES reminder_rules(id),
    type VARCHAR(35) NOT NULL CHECK (type IN ('REVIEW_DUE','DAILY_GOAL','CONTENT_NEW','SYSTEM')),
    title VARCHAR(180) NOT NULL,
    body TEXT NOT NULL,
    target_url VARCHAR(500),
    dedupe_key VARCHAR(160),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (target_url IS NULL OR (target_url LIKE '/%' AND target_url NOT LIKE '//%'))
);
CREATE UNIQUE INDEX uq_notifications_dedupe
    ON notifications(user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);

CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    daily_reminder_time TIME,
    timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((quiet_hours_start IS NULL) = (quiet_hours_end IS NULL))
);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(id),
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('IN_APP','EMAIL')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SENT','FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_retry_at TIMESTAMPTZ,
    provider_message_id VARCHAR(180),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(notification_id, channel),
    CHECK ((status = 'SENT' AND sent_at IS NOT NULL)
        OR (status <> 'SENT' AND sent_at IS NULL))
);
CREATE INDEX idx_deliveries_retry ON notification_deliveries(status, next_retry_at)
    WHERE status IN ('PENDING','FAILED');

-- System administration (M09): 2 tables.
CREATE TABLE system_settings (
    key VARCHAR(100) PRIMARY KEY,
    value_json JSONB NOT NULL,
    description TEXT,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID,
    metadata JSONB,
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_logs_actor_created ON audit_logs(actor_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
