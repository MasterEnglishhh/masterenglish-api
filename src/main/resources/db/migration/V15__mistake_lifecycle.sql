-- V15: mistake lifecycle and practice from mistakes (M04.09).
-- Tables and constraints follow docs/database/DATA_DICTIONARY.md (Approved Baseline v3.0).
-- V1..V14 are not edited.

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
    CONSTRAINT mistake_records_c3 CHECK (state IN ('OPEN', 'IMPROVING', 'RESOLVED')),
    CONSTRAINT mistake_records_c4 CHECK (wrong_count > 0 AND consecutive_correct >= 0),
    CONSTRAINT mistake_records_c5 CHECK (
        (state = 'OPEN' AND consecutive_correct = 0 AND resolved_at IS NULL)
        OR (state = 'IMPROVING' AND consecutive_correct = 1 AND resolved_at IS NULL)
        OR (state = 'RESOLVED' AND consecutive_correct >= 2 AND resolved_at IS NOT NULL))
);

ALTER TABLE mistake_records
    ADD CONSTRAINT fk_mistake_records_user_id_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_mistake_records_question_id_questions
        FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_mistake_records_user_id_source_attempt_id_attempts
        FOREIGN KEY (user_id, source_attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_mistake_records_source_attempt_id_source_snapshot_id
        FOREIGN KEY (source_attempt_id, source_snapshot_id)
        REFERENCES attempt_question_snapshots (attempt_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_mistake_records_source_snapshot_id_question_id
        FOREIGN KEY (source_snapshot_id, question_id)
        REFERENCES attempt_question_snapshots (id, question_id) ON DELETE RESTRICT;

CREATE INDEX ix_mistakes_user_state ON mistake_records (user_id, state, last_wrong_at DESC);

CREATE TABLE attempt_source_mistakes (
    attempt_id UUID NOT NULL,
    user_id UUID NOT NULL,
    mistake_id UUID NOT NULL,
    CONSTRAINT attempt_source_mistakes_c1 PRIMARY KEY (attempt_id, mistake_id),
    CONSTRAINT fk_attempt_source_mistakes_user_id_attempt_id_attempts
        FOREIGN KEY (user_id, attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_attempt_source_mistakes_user_id_mistake_id_mistake_records
        FOREIGN KEY (user_id, mistake_id) REFERENCES mistake_records (user_id, id) ON DELETE RESTRICT
);
