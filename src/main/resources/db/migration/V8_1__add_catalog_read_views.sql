CREATE VIEW catalog_visible_items AS
SELECT id, published_revision_id
FROM content_items
WHERE NOT is_archived AND access_revoked_at IS NULL;

CREATE VIEW catalog_visible_senses AS
SELECT s.id AS word_sense_id, s.word_id, s.cefr_level
FROM word_senses s
JOIN words w ON w.id = s.word_id AND w.published_revision_id = s.word_revision_id
JOIN catalog_visible_items i ON i.id = w.id AND i.published_revision_id = w.published_revision_id
JOIN content_revisions r ON r.id = w.published_revision_id
    AND r.content_item_id = w.id AND r.status = 'PUBLISHED'
WHERE NOT s.is_archived;

CREATE VIEW catalog_visible_deck_senses AS
SELECT dw.deck_id, dw.word_sense_id
FROM deck_words dw
JOIN decks d ON d.id = dw.deck_id
JOIN catalog_visible_items i ON i.id = d.id AND i.published_revision_id = d.published_revision_id
JOIN content_revisions r ON r.id = d.published_revision_id AND r.status = 'PUBLISHED';
