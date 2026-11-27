-- V19: Create question_groups, group_materials, practice_sets, and set_items schema

CREATE TABLE question_groups (
    id UUID PRIMARY KEY,
    content_type VARCHAR(30) GENERATED ALWAYS AS ('QUESTION_GROUP'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    category VARCHAR(16) NOT NULL,
    part_number INTEGER,
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

ALTER TABLE question_groups
    ADD CONSTRAINT fk_question_groups_id_content_type_content_items
        FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;

ALTER TABLE question_groups
    ADD CONSTRAINT fk_question_groups_id_published_revision_id_content_revisions
        FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE questions ADD COLUMN group_id UUID;
ALTER TABLE questions ADD COLUMN group_position INTEGER;
ALTER TABLE questions ADD COLUMN in_current_group BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE questions ADD COLUMN blank_key VARCHAR(80);

ALTER TABLE questions ADD CONSTRAINT fk_questions_group_id_question_groups
    FOREIGN KEY (group_id) REFERENCES question_groups (id) ON DELETE RESTRICT;

ALTER TABLE questions ADD CONSTRAINT questions_c9
    CHECK ((group_id IS NULL AND group_position IS NULL AND blank_key IS NULL)
        OR (group_id IS NOT NULL AND group_position > 0 AND group_position IS NOT NULL));

CREATE TABLE group_materials (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES question_groups (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    document_key VARCHAR(80),
    material_type VARCHAR(8) NOT NULL,
    usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    media_id UUID REFERENCES media_assets (id) ON DELETE RESTRICT,
    body TEXT,
    CONSTRAINT group_materials_c1 UNIQUE (group_id, position),
    CONSTRAINT group_materials_c2 CHECK (material_type IN ('TEXT','IMAGE','AUDIO')),
    CONSTRAINT group_materials_c3 CHECK (usage_role IN ('STIMULUS','TRANSCRIPT','EXPLANATION')),
    CONSTRAINT group_materials_c4 CHECK (position > 0),
    CONSTRAINT group_materials_c5 CHECK ((material_type='TEXT' AND body IS NOT NULL AND media_id IS NULL) OR (material_type IN ('IMAGE','AUDIO') AND media_id IS NOT NULL AND body IS NULL)),
    CONSTRAINT group_materials_c6 CHECK (char_length(body) <= 50000)
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
    part_number INTEGER,
    time_limit_seconds INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT practice_sets_c1 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    CONSTRAINT practice_sets_c2 CHECK (selection_mode IN ('FIXED','DYNAMIC')),
    CONSTRAINT practice_sets_c3 CHECK (jsonb_typeof(selection_config) = 'object' AND octet_length(selection_config::text) <= 1048576),
    CONSTRAINT practice_sets_c4 CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0),
    CONSTRAINT practice_sets_c5 CHECK ((category='TOEIC' AND (part_number IS NULL OR part_number BETWEEN 1 AND 7)) OR (category<>'TOEIC' AND part_number IS NULL)),
    CONSTRAINT practice_sets_c6 CHECK (deck_id IS NULL OR category='VOCABULARY'),
    CONSTRAINT practice_sets_c7 CHECK (topic_id IS NULL OR category='GRAMMAR')
);

ALTER TABLE practice_sets
    ADD CONSTRAINT fk_practice_sets_id_content_type_content_items
        FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT;

ALTER TABLE practice_sets
    ADD CONSTRAINT fk_practice_sets_id_published_revision_id_content_revisions
        FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE practice_sets
    ADD CONSTRAINT fk_practice_sets_deck_id_decks
        FOREIGN KEY (deck_id) REFERENCES decks (id) ON DELETE RESTRICT;

ALTER TABLE practice_sets
    ADD CONSTRAINT fk_practice_sets_topic_id_grammar_topics
        FOREIGN KEY (topic_id) REFERENCES grammar_topics (id) ON DELETE RESTRICT;

CREATE TABLE set_items (
    id UUID PRIMARY KEY,
    set_id UUID NOT NULL REFERENCES practice_sets (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    question_id UUID REFERENCES questions (id) ON DELETE RESTRICT,
    group_id UUID REFERENCES question_groups (id) ON DELETE RESTRICT,
    CONSTRAINT set_items_c1 UNIQUE (set_id, position),
    CONSTRAINT set_items_c2 CHECK (position > 0),
    CONSTRAINT set_items_c3 CHECK (num_nonnulls(question_id, group_id)=1)
);

CREATE INDEX ix_questions_group ON questions (group_id, group_position);
CREATE INDEX ix_set_items_set ON set_items (set_id, position);
