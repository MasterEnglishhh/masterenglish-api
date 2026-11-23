-- V13: Shared practice engine for grammar quizzes (M04.04-M04.11).
-- Subset of the approved-baseline practice tables. Only the GRAMMAR category is enabled here.
-- TOEIC groups, media references and mistake records belong to later milestones.
-- Attempts store a sealed snapshot of questions and options, so later publications never change
-- a learner's attempt. Grading uses only the snapshot.

CREATE TABLE questions (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('QUESTION'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    category VARCHAR(16) NOT NULL,
    question_type_code VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE',
    practice_mode VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    difficulty VARCHAR(8) NOT NULL DEFAULT 'MEDIUM',
    prompt TEXT NOT NULL,
    explanation TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT questions_c1 UNIQUE (id, category),
    CONSTRAINT questions_c2 CHECK (category IN ('GRAMMAR')),
    CONSTRAINT questions_c3 CHECK (question_type_code IN ('SINGLE_CHOICE')),
    CONSTRAINT questions_c4 CHECK (practice_mode IN ('STANDARD', 'FILL_OPTION')),
    CONSTRAINT questions_c5 CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT questions_c6 CHECK (
        btrim(prompt) <> '' AND btrim(explanation) <> ''
        AND char_length(prompt) <= 10000 AND char_length(explanation) <= 20000)
);

ALTER TABLE questions
    ADD CONSTRAINT fk_questions_id_content_type_content_items
        FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_questions_id_published_revision_id_content_revisions
        FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE question_options (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES questions (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    body TEXT NOT NULL,
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT question_options_c1 UNIQUE (question_id, position),
    CONSTRAINT question_options_c2 CHECK (position BETWEEN 1 AND 6),
    CONSTRAINT question_options_c3 CHECK (btrim(body) <> '')
);

CREATE UNIQUE INDEX ux_question_options_one_correct
    ON question_options (question_id) WHERE is_correct;

CREATE TABLE question_topics (
    question_id UUID NOT NULL REFERENCES questions (id) ON DELETE RESTRICT,
    topic_id UUID NOT NULL REFERENCES grammar_topics (id) ON DELETE RESTRICT,
    CONSTRAINT question_topics_c1 PRIMARY KEY (question_id, topic_id)
);

CREATE INDEX ix_question_topics_topic ON question_topics (topic_id, question_id);

CREATE TABLE attempts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    category VARCHAR(16) NOT NULL DEFAULT 'GRAMMAR',
    source_type VARCHAR(24) NOT NULL,
    source_topic_id UUID REFERENCES grammar_topics (id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    total_count INTEGER NOT NULL,
    correct_count INTEGER,
    score INTEGER,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attempts_c1 UNIQUE (user_id, id),
    CONSTRAINT attempts_c2 UNIQUE (user_id, request_id),
    CONSTRAINT attempts_c3 CHECK (category = 'GRAMMAR'),
    CONSTRAINT attempts_c4 CHECK (source_type IN ('GRAMMAR_TOPIC', 'MIXED_GRAMMAR')),
    CONSTRAINT attempts_c5 CHECK ((source_type = 'GRAMMAR_TOPIC') = (source_topic_id IS NOT NULL)),
    CONSTRAINT attempts_c6 CHECK (status IN ('IN_PROGRESS', 'SUBMITTED')),
    CONSTRAINT attempts_c7 CHECK (total_count BETWEEN 1 AND 50),
    CONSTRAINT attempts_c8 CHECK (
        (status = 'SUBMITTED') = (submitted_at IS NOT NULL AND correct_count IS NOT NULL AND score IS NOT NULL)),
    CONSTRAINT attempts_c9 CHECK (correct_count IS NULL OR correct_count BETWEEN 0 AND total_count),
    CONSTRAINT attempts_c10 CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT attempts_c11 CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT attempts_c12 CHECK (version >= 0)
);

CREATE INDEX ix_attempts_user_status ON attempts (user_id, status, submitted_at);

CREATE TABLE attempt_source_topics (
    attempt_id UUID NOT NULL REFERENCES attempts (id) ON DELETE RESTRICT,
    topic_id UUID NOT NULL REFERENCES grammar_topics (id) ON DELETE RESTRICT,
    CONSTRAINT attempt_source_topics_c1 PRIMARY KEY (attempt_id, topic_id)
);

CREATE TABLE attempt_question_snapshots (
    attempt_id UUID NOT NULL REFERENCES attempts (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    question_id UUID NOT NULL REFERENCES questions (id) ON DELETE RESTRICT,
    topic_id UUID NOT NULL REFERENCES grammar_topics (id) ON DELETE RESTRICT,
    question_type_code VARCHAR(32) NOT NULL,
    practice_mode VARCHAR(32) NOT NULL,
    prompt TEXT NOT NULL,
    explanation TEXT NOT NULL,
    CONSTRAINT attempt_question_snapshots_c1 PRIMARY KEY (attempt_id, position),
    CONSTRAINT attempt_question_snapshots_c2 UNIQUE (attempt_id, question_id),
    CONSTRAINT attempt_question_snapshots_c3 CHECK (position BETWEEN 1 AND 50)
);

CREATE INDEX ix_attempt_question_snapshots_topic ON attempt_question_snapshots (topic_id);

CREATE TABLE attempt_snapshot_options (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    question_position INTEGER NOT NULL,
    position INTEGER NOT NULL,
    body TEXT NOT NULL,
    is_correct BOOLEAN NOT NULL,
    CONSTRAINT attempt_snapshot_options_c1 UNIQUE (attempt_id, question_position, position),
    CONSTRAINT attempt_snapshot_options_c2 UNIQUE (attempt_id, question_position, id),
    CONSTRAINT attempt_snapshot_options_c3 FOREIGN KEY (attempt_id, question_position)
        REFERENCES attempt_question_snapshots (attempt_id, position) ON DELETE RESTRICT,
    CONSTRAINT attempt_snapshot_options_c4 CHECK (position BETWEEN 1 AND 6)
);

CREATE TABLE attempt_answers (
    attempt_id UUID NOT NULL,
    question_position INTEGER NOT NULL,
    selected_option_id UUID NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attempt_answers_c1 PRIMARY KEY (attempt_id, question_position),
    CONSTRAINT attempt_answers_c2 FOREIGN KEY (attempt_id, question_position)
        REFERENCES attempt_question_snapshots (attempt_id, position) ON DELETE RESTRICT,
    CONSTRAINT attempt_answers_c3 FOREIGN KEY (attempt_id, question_position, selected_option_id)
        REFERENCES attempt_snapshot_options (attempt_id, question_position, id) ON DELETE RESTRICT
);
