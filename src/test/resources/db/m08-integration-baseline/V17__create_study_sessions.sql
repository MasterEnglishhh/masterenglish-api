-- V17: Bảng study_sessions (thuộc module learning) lấy nguyên mẫu từ docs/database/proposals/approved-baseline.sql
-- phục vụ đo thời gian học thực tế theo heartbeat (UC06), nhắc mục tiêu hằng ngày (M10.06) và Dashboard (M02.06).

CREATE TABLE study_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    word_sense_id UUID,
    lesson_id UUID,
    attempt_id UUID,
    timezone VARCHAR(60) NOT NULL,
    local_day DATE NOT NULL,
    credit_token UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    last_credited_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    active_seconds NUMERIC(12,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT study_sessions_c1 CHECK (source_type IN ('FLASHCARD', 'LESSON', 'PRACTICE')),
    CONSTRAINT study_sessions_c2 CHECK (
        (source_type = 'FLASHCARD' AND word_sense_id IS NOT NULL AND num_nonnulls(lesson_id, attempt_id) = 0)
        OR (source_type = 'LESSON' AND lesson_id IS NOT NULL AND num_nonnulls(word_sense_id, attempt_id) = 0)
        OR (source_type = 'PRACTICE' AND attempt_id IS NOT NULL AND num_nonnulls(word_sense_id, lesson_id) = 0)
    ),
    CONSTRAINT study_sessions_c3 CHECK (
        last_credited_at >= started_at
        AND (closed_at IS NULL OR closed_at >= last_credited_at)
    ),
    CONSTRAINT study_sessions_c4 CHECK (
        active_seconds >= 0
        AND active_seconds <= EXTRACT(EPOCH FROM (last_credited_at - started_at))
        AND version >= 0
    )
);

ALTER TABLE study_sessions
    ADD CONSTRAINT fk_study_sessions_user_id_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_study_sessions_word_sense_id_word_senses
        FOREIGN KEY (word_sense_id) REFERENCES word_senses (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_study_sessions_lesson_id_grammar_lessons
        FOREIGN KEY (lesson_id) REFERENCES grammar_lessons (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_study_sessions_user_id_attempt_id_attempts
        FOREIGN KEY (user_id, attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_study_one_open ON study_sessions (user_id) WHERE closed_at IS NULL;
CREATE INDEX ix_study_day ON study_sessions (user_id, local_day);

COMMENT ON TABLE study_sessions IS 'Đoạn thời gian hoạt động ước tính, không cộng trùng nhiều tab/thiết bị.';
COMMENT ON COLUMN study_sessions.id IS 'Đoạn hoạt động';
COMMENT ON COLUMN study_sessions.user_id IS 'Chủ đoạn';
COMMENT ON COLUMN study_sessions.source_type IS 'FLASHCARD/LESSON/PRACTICE';
COMMENT ON COLUMN study_sessions.word_sense_id IS 'Ngữ cảnh thẻ nếu có';
COMMENT ON COLUMN study_sessions.lesson_id IS 'Ngữ cảnh bài học nếu có';
COMMENT ON COLUMN study_sessions.attempt_id IS 'Ngữ cảnh luyện nếu có';
COMMENT ON COLUMN study_sessions.timezone IS 'Timezone ở thời điểm ghi nhận';
COMMENT ON COLUMN study_sessions.local_day IS 'Chia đoạn khi qua ngày/đổi timezone';
COMMENT ON COLUMN study_sessions.credit_token IS 'Chủ nhịp ghi nhận hiện tại, không thay đăng nhập';
COMMENT ON COLUMN study_sessions.started_at IS 'Mốc bắt đầu';
COMMENT ON COLUMN study_sessions.last_credited_at IS 'Server cập nhật, không tin client clock';
COMMENT ON COLUMN study_sessions.closed_at IS 'Đóng khi ngừng/đổi ngữ cảnh';
COMMENT ON COLUMN study_sessions.active_seconds IS 'Mỗi heartbeat cộng tối đa 30 giây';
COMMENT ON COLUMN study_sessions.version IS 'Chống cộng lại';
