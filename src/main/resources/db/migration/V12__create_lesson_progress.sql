-- V12: Learning lesson progress (M04.03).
-- Mirrors approved-baseline lesson_progress. Completion pins the revision the learner finished;
-- a later publication of the same lesson does not change that record.
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
    CONSTRAINT lesson_progress_c2 CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT lesson_progress_c3 CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT lesson_progress_c4 CHECK (state <> 'COMPLETED' OR last_completed_revision_id IS NOT NULL),
    CONSTRAINT lesson_progress_c5 CHECK (version >= 0)
);

ALTER TABLE lesson_progress
    ADD CONSTRAINT fk_lesson_progress_user_id_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_lesson_progress_lesson_id_grammar_lessons
        FOREIGN KEY (lesson_id) REFERENCES grammar_lessons (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_lesson_progress_lesson_revision_content_revisions
        FOREIGN KEY (lesson_id, last_completed_revision_id)
        REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;
