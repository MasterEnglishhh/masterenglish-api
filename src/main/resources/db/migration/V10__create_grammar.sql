-- V10: Grammar catalogue projections (topics and lessons).
-- Mirrors DATA_DICTIONARY grammar_topics and grammar_lessons. content_items already allows
-- GRAMMAR_TOPIC/GRAMMAR_LESSON (V6). lesson_progress belongs to learning and comes in a later migration.

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
    CONSTRAINT grammar_topics_c1 CHECK (difficulty IN ('BASIC', 'INTERMEDIATE', 'ADVANCED')),
    CONSTRAINT grammar_topics_c2 CHECK (sort_order >= 0 AND btrim(name) <> '')
);

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
    CONSTRAINT grammar_lessons_c4 CHECK (estimated_minutes IS NULL OR estimated_minutes > 0),
    CONSTRAINT grammar_lessons_c5 CHECK (btrim(title) <> '')
);

ALTER TABLE grammar_topics
    ADD CONSTRAINT fk_grammar_topics_id_content_type_content_items
        FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_grammar_topics_id_published_revision_id_content_revisions
        FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE grammar_lessons
    ADD CONSTRAINT fk_grammar_lessons_id_content_type_content_items
        FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_grammar_lessons_id_published_revision_id_content_revisions
        FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_grammar_lessons_topic_id_grammar_topics
        FOREIGN KEY (topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;

CREATE INDEX ix_grammar_topics_order ON grammar_topics (sort_order, name);
CREATE INDEX ix_grammar_lessons_topic ON grammar_lessons (topic_id, sort_order);
