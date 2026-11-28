-- V25: Nâng cấp engine luyện tập M04 lên dạng Approved Baseline v3.0 (tiếp nối V21..V24 của M08).
--
-- V1..V24 không sửa. Bảng attempt do V13 tạo có thể đã áp dụng trên môi trường dùng chung,
-- nên dữ liệu được GIỮ: sao lưu sang legacy_v13_*, tạo bảng theo baseline, rồi chuyển dữ liệu
-- (cuối file). legacy_v13_* được giữ để đối chiếu, bỏ ở migration riêng sau khi kiểm chứng.
-- question_groups, practice_sets, set_items và các cột group/part_number trên questions đã có từ V21..V23.
--
-- Xấp xỉ khi chuyển dữ liệu: V13 không lưu revision tại thời điểm làm bài, nên snapshot câu hỏi
-- dùng published_revision_id hiện tại và topicIds lấy từ question_topics hiện tại.
--
-- Not copied from the baseline for now: its triggers (guard_*, *_complete) and the me_* JSON
-- helpers behind ck_*_public_safe. The application enforces those rules until M05 adds them.

-- Sao lưu dữ liệu V13. CREATE TABLE AS không chép ràng buộc hay index, nên tên của chúng
-- được giải phóng để tạo lại bảng theo baseline.
CREATE TABLE legacy_v13_attempts AS SELECT * FROM attempts;
CREATE TABLE legacy_v13_attempt_source_topics AS SELECT * FROM attempt_source_topics;
CREATE TABLE legacy_v13_attempt_question_snapshots AS SELECT * FROM attempt_question_snapshots;
CREATE TABLE legacy_v13_attempt_snapshot_options AS SELECT * FROM attempt_snapshot_options;
CREATE TABLE legacy_v13_attempt_answers AS SELECT * FROM attempt_answers;

-- study_sessions (V17) trỏ tới attempts; FK được tạo lại sau khi chuyển dữ liệu.
ALTER TABLE study_sessions DROP CONSTRAINT fk_study_sessions_user_id_attempt_id_attempts;

-- Bỏ bảng V13 (con trước).
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

-- Questions: practice modes and question type FK (part_number & group columns already added by V21/V22)
ALTER TABLE questions DROP CONSTRAINT questions_c3;
ALTER TABLE questions DROP CONSTRAINT questions_c4;
ALTER TABLE questions
    ADD CONSTRAINT questions_c10 CHECK (practice_mode IN ('STANDARD', 'TRANSLATE_EN_VI', 'TRANSLATE_VI_EN', 'LISTEN_AND_CHOOSE', 'FILL_OPTION'));
ALTER TABLE questions ADD CONSTRAINT fk_questions_question_type_code_question_types
    FOREIGN KEY (question_type_code) REFERENCES question_types (code) ON DELETE RESTRICT;
ALTER TABLE questions ADD CONSTRAINT fk_questions_group_id_category_question_groups
    FOREIGN KEY (group_id, category) REFERENCES question_groups (id, category) ON DELETE RESTRICT;
CREATE INDEX ix_questions_filters ON questions(category, part_number, question_type_code, difficulty);

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

-- Foreign keys (question_groups / practice_sets / set_items FKs already created by V22)
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

-- Indexes (uq_set_question / uq_set_group already created by V23)
CREATE INDEX ix_sets_filters ON practice_sets(category, part_number);
CREATE INDEX ix_attempts_user_history ON attempts(user_id, started_at DESC);
CREATE INDEX ix_attempts_deadline ON attempts(deadline_at) WHERE status='IN_PROGRESS';
CREATE INDEX ix_attempts_user_open ON attempts(user_id, last_active_at) WHERE status='IN_PROGRESS';
CREATE INDEX ix_snapshot_question ON attempt_question_snapshots(question_id, attempt_id);
CREATE INDEX ix_answers_attempt ON attempt_answers(attempt_id);

-- =====================================================================================
-- Chuyển dữ liệu V13 sang dạng baseline. Mỗi câu có đúng một dòng attempt_answers
-- (giống lúc seal của engine); câu chưa trả lời có score 0 khi nộp bài.
-- =====================================================================================

INSERT INTO attempts (
    id, user_id, category, source_type, source_set_id, source_deck_id, source_topic_id,
    source_revision_id, source_config, request_id, request_hash, status, total_count,
    correct_count, answered_count, score, snapshot_sealed_at, started_at, last_active_at,
    deadline_at, submitted_at, effective_finished_at, submit_reason, abandoned_at,
    duration_seconds, timezone, local_day, version, created_at, updated_at)
SELECT a.id,
       a.user_id,
       'GRAMMAR',
       a.source_type,
       NULL,
       NULL,
       a.source_topic_id,
       CASE WHEN a.source_topic_id IS NOT NULL THEN gt.published_revision_id END,
       CASE WHEN a.source_type = 'GRAMMAR_TOPIC'
            THEN jsonb_build_object(
                    'sourceType', a.source_type,
                    'topicIds', jsonb_build_array(a.source_topic_id::text),
                    'questionCount', a.total_count)
            ELSE jsonb_build_object(
                    'sourceType', a.source_type,
                    'topicIds', (SELECT COALESCE(jsonb_agg(t.topic_id::text ORDER BY t.topic_id::text),
                                                 '[]'::jsonb)
                                 FROM legacy_v13_attempt_source_topics t
                                 WHERE t.attempt_id = a.id),
                    'questionCount', a.total_count)
       END,
       a.request_id,
       a.request_hash,
       a.status,
       a.total_count,
       CASE WHEN a.status = 'SUBMITTED' THEN a.correct_count END,
       CASE WHEN a.status = 'SUBMITTED'
            THEN (SELECT count(*) FROM legacy_v13_attempt_answers ans
                  WHERE ans.attempt_id = a.id AND ans.selected_option_id IS NOT NULL)
       END,
       CASE WHEN a.status = 'SUBMITTED' THEN a.correct_count END,
       a.started_at,
       a.started_at,
       a.updated_at,
       NULL,
       CASE WHEN a.status = 'SUBMITTED' THEN a.submitted_at END,
       CASE WHEN a.status = 'SUBMITTED' THEN a.submitted_at END,
       CASE WHEN a.status = 'SUBMITTED' THEN 'MANUAL' END,
       NULL,
       CASE WHEN a.status = 'SUBMITTED'
            THEN floor(extract(epoch FROM (a.submitted_at - a.started_at)))::int
       END,
       'Asia/Ho_Chi_Minh',
       CASE WHEN a.status = 'SUBMITTED'
            THEN (a.submitted_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
       END,
       a.version,
       a.started_at,
       a.updated_at
FROM legacy_v13_attempts a
LEFT JOIN grammar_topics gt ON gt.id = a.source_topic_id;

INSERT INTO attempt_source_topics (attempt_id, topic_id, source_revision_id)
SELECT t.attempt_id, t.topic_id, gt.published_revision_id
FROM legacy_v13_attempt_source_topics t
JOIN grammar_topics gt ON gt.id = t.topic_id;

INSERT INTO attempt_question_snapshots (
    id, attempt_id, question_id, source_revision_id, group_snapshot_id, category, ordinal,
    question_type_code, schema_version, grading_policy_version,
    question_snapshot, answer_key_snapshot, explanation_snapshot)
SELECT gen_random_uuid(),
       s.attempt_id,
       s.question_id,
       q.published_revision_id,
       NULL,
       'GRAMMAR',
       s.position,
       s.question_type_code,
       1,
       'SINGLE_CHOICE_V1',
       jsonb_build_object(
               'prompt', s.prompt,
               'practiceMode', s.practice_mode,
               'questionTypeCode', s.question_type_code,
               'topicIds', (SELECT COALESCE(jsonb_agg(qt.topic_id::text ORDER BY qt.topic_id::text),
                                            '[]'::jsonb)
                            FROM question_topics qt
                            WHERE qt.question_id = s.question_id),
               'attributedTopicId', s.topic_id::text),
       jsonb_build_object(
               'correctOptionId', (SELECT o.id::text
                                   FROM legacy_v13_attempt_snapshot_options o
                                   WHERE o.attempt_id = s.attempt_id
                                     AND o.question_position = s.position
                                     AND o.is_correct)),
       jsonb_build_object('explanation', s.explanation)
FROM legacy_v13_attempt_question_snapshots s
JOIN questions q ON q.id = s.question_id;

INSERT INTO attempt_snapshot_options (snapshot_id, option_id, position, public_body)
SELECT ns.id, o.id, o.position, o.body
FROM legacy_v13_attempt_snapshot_options o
JOIN attempt_question_snapshots ns
  ON ns.attempt_id = o.attempt_id AND ns.ordinal = o.question_position;

INSERT INTO attempt_answers (
    id, attempt_id, snapshot_id, selected_option_id, response_payload, version,
    answered_at, is_correct, score, created_at, updated_at)
SELECT gen_random_uuid(),
       ns.attempt_id,
       ns.id,
       la.selected_option_id,
       NULL,
       0,
       la.answered_at,
       CASE WHEN a.status = 'SUBMITTED' THEN COALESCE(lo.is_correct, FALSE) END,
       CASE WHEN a.status = 'SUBMITTED'
            THEN CASE WHEN COALESCE(lo.is_correct, FALSE) THEN 1 ELSE 0 END
       END,
       a.started_at,
       a.updated_at
FROM attempt_question_snapshots ns
JOIN attempts a ON a.id = ns.attempt_id
LEFT JOIN legacy_v13_attempt_answers la
  ON la.attempt_id = ns.attempt_id AND la.question_position = ns.ordinal
LEFT JOIN legacy_v13_attempt_snapshot_options lo
  ON lo.attempt_id = la.attempt_id
 AND lo.question_position = la.question_position
 AND lo.id = la.selected_option_id;

-- study_sessions: khôi phục FK; id giữ nguyên nên mọi dòng cũ vẫn hợp lệ.
ALTER TABLE study_sessions ADD CONSTRAINT fk_study_sessions_user_id_attempt_id_attempts
    FOREIGN KEY (user_id, attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT;

-- Kiểm tra số dòng: lệch thì dừng migration (rollback toàn bộ), không để mất dữ liệu lặng lẽ.
DO $$
BEGIN
    IF (SELECT count(*) FROM attempts) <> (SELECT count(*) FROM legacy_v13_attempts) THEN
        RAISE EXCEPTION 'V18: attempts row count mismatch';
    END IF;
    IF (SELECT count(*) FROM attempt_source_topics) <> (SELECT count(*) FROM legacy_v13_attempt_source_topics) THEN
        RAISE EXCEPTION 'V18: attempt_source_topics row count mismatch';
    END IF;
    IF (SELECT count(*) FROM attempt_question_snapshots) <> (SELECT count(*) FROM legacy_v13_attempt_question_snapshots) THEN
        RAISE EXCEPTION 'V18: attempt_question_snapshots row count mismatch';
    END IF;
    IF (SELECT count(*) FROM attempt_snapshot_options) <> (SELECT count(*) FROM legacy_v13_attempt_snapshot_options) THEN
        RAISE EXCEPTION 'V18: attempt_snapshot_options row count mismatch';
    END IF;
    IF (SELECT count(*) FROM attempt_answers) <> (SELECT count(*) FROM attempt_question_snapshots) THEN
        RAISE EXCEPTION 'V18: attempt_answers must have one row per snapshot';
    END IF;
END $$;
