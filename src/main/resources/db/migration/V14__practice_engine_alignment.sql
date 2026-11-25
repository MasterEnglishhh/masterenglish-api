-- V14: Grammar practice engine aligned with Approved Baseline v3.0 (M04 learner scope).
--
-- V1..V13 are not edited. The attempt tables created by V13 are rebuilt here, so local attempt
-- rows from V13 are discarded (approved for the local environment). Shell tables for TOEIC /
-- practice sets (M05) are created empty so the nullable foreign-key columns exist.
--
-- Not copied from the baseline for now: its triggers (guard_*, *_complete) and the me_* JSON
-- helpers behind ck_*_public_safe. The application enforces those rules until M05 adds them.

-- Rebuild attempt tables from V13 (children first)
DROP TABLE attempt_answers;
DROP TABLE attempt_snapshot_options;
DROP TABLE attempt_question_snapshots;
DROP TABLE attempt_source_topics;
DROP TABLE attempts;

-- Question types (SINGLE_CHOICE is the only type used by M04)
CREATE TABLE question_types (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT question_types_c1 CHECK (schema_version > 0)
);
INSERT INTO question_types (code, name, schema_version, enabled)
VALUES ('SINGLE_CHOICE', 'Single choice', 1, TRUE);

-- Shell tables for M05 (no rows, no business rules yet)
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

-- Questions: practice modes, question types and optional group columns
ALTER TABLE questions DROP CONSTRAINT questions_c3;
ALTER TABLE questions DROP CONSTRAINT questions_c4;
ALTER TABLE questions
    ADD COLUMN part_number SMALLINT,
    ADD COLUMN group_id UUID,
    ADD COLUMN group_position SMALLINT,
    ADD COLUMN in_current_group BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN blank_key VARCHAR(80),
    ADD CONSTRAINT questions_c7 CHECK (practice_mode IN ('STANDARD', 'TRANSLATE_EN_VI', 'TRANSLATE_VI_EN', 'LISTEN_AND_CHOOSE', 'FILL_OPTION')),
    ADD CONSTRAINT questions_c8 CHECK (part_number IS NULL),
    ADD CONSTRAINT questions_c9 CHECK ((group_id IS NULL AND group_position IS NULL) OR (group_id IS NOT NULL AND group_position > 0)),
    ADD CONSTRAINT questions_c10 CHECK (blank_key IS NULL);
ALTER TABLE questions ADD CONSTRAINT fk_questions_question_type_code_question_types
    FOREIGN KEY (question_type_code) REFERENCES question_types (code) ON DELETE RESTRICT;
ALTER TABLE questions ADD CONSTRAINT fk_questions_group_id_question_groups
    FOREIGN KEY (group_id) REFERENCES question_groups (id) ON DELETE RESTRICT;
ALTER TABLE questions ADD CONSTRAINT fk_questions_group_id_category_question_groups
    FOREIGN KEY (group_id, category) REFERENCES question_groups (id, category) ON DELETE RESTRICT;
CREATE INDEX ix_questions_filters ON questions(category, part_number, question_type_code, difficulty);
CREATE UNIQUE INDEX uq_group_current_position ON questions(group_id, group_position) WHERE in_current_group=TRUE;
CREATE UNIQUE INDEX uq_group_current_blank ON questions(group_id, blank_key) WHERE in_current_group=TRUE;

-- Attempts and snapshots (approved baseline shape, grammar scope)
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
CREATE TABLE attempt_source_topics (
    attempt_id UUID NOT NULL,
    topic_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    CONSTRAINT attempt_source_topics_c1 PRIMARY KEY (attempt_id, topic_id)
);
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
CREATE TABLE attempt_snapshot_options (
    snapshot_id UUID NOT NULL,
    option_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    public_body TEXT,
    CONSTRAINT attempt_snapshot_options_c1 PRIMARY KEY (snapshot_id, option_id),
    CONSTRAINT attempt_snapshot_options_c2 UNIQUE (snapshot_id, position),
    CONSTRAINT attempt_snapshot_options_c3 CHECK (position BETWEEN 1 AND 6)
);
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

-- Foreign keys
ALTER TABLE question_groups ADD CONSTRAINT fk_question_groups_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE question_groups ADD CONSTRAINT fk_question_groups_id_published_revision_id_cont_454aaec9c9 FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_id_content_type_content_items FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_id_published_revision_id_content_revisions FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_deck_id_decks FOREIGN KEY (deck_id) REFERENCES decks (id) ON DELETE RESTRICT;
ALTER TABLE practice_sets ADD CONSTRAINT fk_practice_sets_topic_id_grammar_topics FOREIGN KEY (topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;
ALTER TABLE set_items ADD CONSTRAINT fk_set_items_set_id_practice_sets FOREIGN KEY (set_id) REFERENCES practice_sets (id) ON DELETE RESTRICT;
ALTER TABLE set_items ADD CONSTRAINT fk_set_items_question_id_questions FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT;
ALTER TABLE set_items ADD CONSTRAINT fk_set_items_group_id_question_groups FOREIGN KEY (group_id) REFERENCES question_groups (id) ON DELETE RESTRICT;
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

-- Indexes
CREATE INDEX ix_sets_filters ON practice_sets(category, part_number);
CREATE UNIQUE INDEX uq_set_question ON set_items(set_id, question_id) WHERE question_id IS NOT NULL;
CREATE UNIQUE INDEX uq_set_group ON set_items(set_id, group_id) WHERE group_id IS NOT NULL;
CREATE INDEX ix_attempts_user_history ON attempts(user_id, started_at DESC);
CREATE INDEX ix_attempts_deadline ON attempts(deadline_at) WHERE status='IN_PROGRESS';
CREATE INDEX ix_attempts_user_open ON attempts(user_id, last_active_at) WHERE status='IN_PROGRESS';
CREATE INDEX ix_snapshot_question ON attempt_question_snapshots(question_id, attempt_id);
CREATE INDEX ix_answers_attempt ON attempt_answers(attempt_id);
