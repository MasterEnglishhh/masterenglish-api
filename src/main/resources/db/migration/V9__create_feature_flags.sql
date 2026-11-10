-- V9: Create feature_flags table and seed default system flags

CREATE TABLE feature_flags (
    key VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    category VARCHAR(32) NOT NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT feature_flags_c1 CHECK (category IN ('AI', 'STORAGE', 'AUTH', 'LEARNING')),
    CONSTRAINT feature_flags_c2 CHECK (btrim(key) <> ''),
    CONSTRAINT feature_flags_c3 CHECK (btrim(name) <> '')
);

CREATE INDEX ix_feature_flags_category ON feature_flags(category);

-- Seed initial feature flags matching Admin Portal FE
INSERT INTO feature_flags (key, name, description, enabled, category) VALUES
('ai_tutor_chat', 'AI Tutor Context (Claude/GPT)', 'Kích hoạt mô hình giải thích câu hỏi chuyên sâu và đối thoại ngữ cảnh khi làm bài.', TRUE, 'AI'),
('r2_direct_upload', 'Cloudflare R2 Direct Upload', 'Cho phép client upload media trực tiếp lên Cloudflare R2 qua presigned URL.', TRUE, 'STORAGE'),
('otp_registration_strict', 'Xác thực OTP Email nghiêm ngặt', 'Bắt buộc xác thực mã OTP 6 số qua email trước khi kích hoạt tài khoản.', TRUE, 'AUTH'),
('srs_spaced_repetition', 'Thuật toán SRS Spaced Repetition', 'Kích hoạt thuật toán lặp lại ngắt quãng SM-2 tùy biến trong ôn tập từ vựng.', TRUE, 'LEARNING');
