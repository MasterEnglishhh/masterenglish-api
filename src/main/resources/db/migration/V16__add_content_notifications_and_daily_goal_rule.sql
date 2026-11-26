-- V16: Hoàn thiện schema M10 cho thông báo nội dung mới (M10.07) và quy tắc nhắc mục tiêu hằng ngày (M10.06).

ALTER TABLE notification_preferences
    ADD COLUMN content_notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN notification_preferences.content_notifications_enabled IS
    'Nhận thông báo khi có bộ từ vựng mới xuất bản (opt-in, mặc định tắt)';

-- Seed quy tắc nhắc mục tiêu hằng ngày (M10.06); giữ nguyên minimum_gap_minutes=720 như REVIEW_DUE
-- để tránh trôi giờ nhắc sang ngày hôm sau khi scheduler quét bù trễ.
INSERT INTO reminder_rules (id, code, enabled, minimum_gap_minutes)
VALUES ('6d1c3f0e-6b7a-4c51-9a0e-1f2b3c4d5e02', 'DAILY_GOAL', TRUE, 720)
ON CONFLICT (code) DO NOTHING;
