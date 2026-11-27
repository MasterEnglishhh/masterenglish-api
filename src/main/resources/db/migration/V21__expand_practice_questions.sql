-- V18: Expand questions table for VOCABULARY and TOEIC, and create question_words table

ALTER TABLE questions DROP CONSTRAINT questions_c2;
ALTER TABLE questions ADD CONSTRAINT questions_c2 CHECK (category IN ('GRAMMAR', 'VOCABULARY', 'TOEIC'));

ALTER TABLE questions ADD COLUMN part_number INTEGER;
ALTER TABLE questions ADD CONSTRAINT questions_c7 CHECK (part_number IS NULL OR part_number BETWEEN 1 AND 7);
ALTER TABLE questions ADD CONSTRAINT questions_c8 CHECK ((category = 'TOEIC') = (part_number IS NOT NULL));

CREATE TABLE question_words (
    question_id UUID NOT NULL REFERENCES questions (id) ON DELETE RESTRICT,
    word_sense_id UUID NOT NULL REFERENCES word_senses (id) ON DELETE RESTRICT,
    CONSTRAINT question_words_c1 PRIMARY KEY (question_id, word_sense_id)
);

CREATE INDEX ix_question_words_target ON question_words(word_sense_id);
