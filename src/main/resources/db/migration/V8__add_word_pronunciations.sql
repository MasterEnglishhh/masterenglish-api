-- Pronunciation supplements an exact published word revision without changing its snapshot.
CREATE TABLE word_pronunciations (
    word_id UUID NOT NULL,
    word_revision_id UUID NOT NULL,
    locale VARCHAR(10) NOT NULL,
    voice_id VARCHAR(100) NOT NULL,
    media_asset_id UUID NOT NULL REFERENCES media_assets(id),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (word_revision_id, locale),
    FOREIGN KEY (word_id, word_revision_id) REFERENCES content_revisions(content_item_id, id),
    CHECK (locale ~ '^[a-z]{2}-[A-Z]{2}$'),
    CHECK (btrim(voice_id) <> '')
);
CREATE INDEX idx_word_pronunciations_asset ON word_pronunciations(media_asset_id);
