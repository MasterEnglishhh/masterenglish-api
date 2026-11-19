-- V11: M10 Thông báo & lời nhắc học tập. Bốn bảng lấy nguyên văn từ docs/database/proposals/approved-baseline.sql.
-- Các cột OTP của notification_deliveries được giữ theo baseline nhưng chưa dùng trong phạm vi nhắc học.

-- Notifications: Nhắc học opt-in theo giờ; email bảo mật không phụ thuộc email nhắc học.
CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    daily_reminder_time TIME,
    timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notification_preferences_c1 CHECK ((quiet_hours_start IS NULL)=(quiet_hours_end IS NULL)),
    CONSTRAINT notification_preferences_c2 CHECK (quiet_hours_start IS NULL OR quiet_hours_start<>quiet_hours_end)
);

-- Notifications: Danh mục quy tắc nhắc, không có chiến dịch quảng bá/browser push.
CREATE TABLE reminder_rules (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    minimum_gap_minutes INTEGER NOT NULL DEFAULT 1440,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT reminder_rules_c1 CHECK (minimum_gap_minutes >= 0)
);

-- Notifications: Thông báo/dedupe; bản ghi INTERNAL dùng cho mail bảo mật, không vào feed learner.
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    reminder_rule_id UUID,
    type VARCHAR(24) NOT NULL,
    visibility VARCHAR(12) NOT NULL DEFAULT 'USER',
    title VARCHAR(180) NOT NULL,
    body TEXT NOT NULL,
    target_url VARCHAR(500),
    dedupe_key VARCHAR(200) NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notifications_c1 UNIQUE (user_id, dedupe_key),
    CONSTRAINT notifications_c2 CHECK (type IN ('REVIEW_DUE','DAILY_GOAL','CONTENT_NEW','SYSTEM','SECURITY')),
    CONSTRAINT notifications_c3 CHECK (visibility IN ('USER','INTERNAL')),
    CONSTRAINT notifications_c4 CHECK (target_url IS NULL OR (target_url LIKE '/%' AND target_url NOT LIKE '//%'))
);

-- Notifications: Queue gửi có claim/lease; payload OTP chỉ lưu mã hóa, không rõ trong event/log.
CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    channel VARCHAR(8) NOT NULL,
    recipient_address VARCHAR(254),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    provider_message_id VARCHAR(180),
    last_failure_code VARCHAR(64),
    sent_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    otp_challenge_id UUID,
    encrypted_payload BYTEA,
    encryption_key_id VARCHAR(64),
    sensitive_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notification_deliveries_c1 UNIQUE (notification_id, channel),
    CONSTRAINT notification_deliveries_c2 CHECK (channel IN ('IN_APP','EMAIL')),
    CONSTRAINT notification_deliveries_c3 CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED')),
    CONSTRAINT notification_deliveries_c4 CHECK (attempt_count BETWEEN 0 AND 4),
    CONSTRAINT notification_deliveries_c5 CHECK (channel<>'EMAIL' OR recipient_address IS NOT NULL),
    CONSTRAINT notification_deliveries_c6 CHECK ((status='PROCESSING')=(claim_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT notification_deliveries_c7 CHECK ((claim_token IS NULL)=(lease_until IS NULL)),
    CONSTRAINT notification_deliveries_c8 CHECK ((status='SENT')=(sent_at IS NOT NULL)),
    CONSTRAINT notification_deliveries_c9 CHECK ((status IN ('SENT','FAILED'))=(finished_at IS NOT NULL)),
    CONSTRAINT notification_deliveries_c10 CHECK (status NOT IN ('SENT','FAILED') OR next_retry_at IS NULL),
    CONSTRAINT notification_deliveries_c11 CHECK ((encrypted_payload IS NULL)=(encryption_key_id IS NULL)),
    CONSTRAINT notification_deliveries_c12 CHECK (channel='EMAIL' OR (encrypted_payload IS NULL AND otp_challenge_id IS NULL))
);

ALTER TABLE notification_preferences ADD CONSTRAINT fk_notification_preferences_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_user_id_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT;
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_reminder_rule_id_reminder_rules FOREIGN KEY (reminder_rule_id) REFERENCES reminder_rules (id) ON DELETE RESTRICT;
ALTER TABLE notification_deliveries ADD CONSTRAINT fk_notification_deliveries_notification_id_notifications FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE RESTRICT;
ALTER TABLE notification_deliveries ADD CONSTRAINT fk_notification_deliveries_otp_challenge_id_otp_challenges FOREIGN KEY (otp_challenge_id) REFERENCES otp_challenges (id) ON DELETE RESTRICT;

COMMENT ON TABLE notification_preferences IS 'Nhắc học opt-in theo giờ; email bảo mật không phụ thuộc email nhắc học.';
COMMENT ON COLUMN notification_preferences.user_id IS 'Người nhận';
COMMENT ON COLUMN notification_preferences.in_app_enabled IS 'Kênh in-app';
COMMENT ON COLUMN notification_preferences.email_enabled IS 'Email nhắc học, không phải OTP';
COMMENT ON COLUMN notification_preferences.daily_reminder_time IS 'NULL là chưa đặt lịch';
COMMENT ON COLUMN notification_preferences.timezone IS 'IANA timezone';
COMMENT ON COLUMN notification_preferences.quiet_hours_start IS 'NULL cùng quiet_hours_end nếu tắt';
COMMENT ON COLUMN notification_preferences.quiet_hours_end IS 'Cho phép qua nửa đêm';
COMMENT ON COLUMN notification_preferences.updated_at IS 'Mốc sửa';
COMMENT ON TABLE reminder_rules IS 'Danh mục quy tắc nhắc, không có chiến dịch quảng bá/browser push.';
COMMENT ON COLUMN reminder_rules.id IS 'Rule ID';
COMMENT ON COLUMN reminder_rules.code IS 'Mã do backend hỗ trợ';
COMMENT ON COLUMN reminder_rules.enabled IS 'Cờ vận hành';
COMMENT ON COLUMN reminder_rules.minimum_gap_minutes IS 'Khoảng tối thiểu giữa các nhắc cùng loại';
COMMENT ON COLUMN reminder_rules.created_at IS 'Mốc tạo';
CREATE INDEX ix_notifications_feed ON notifications(user_id, created_at DESC) WHERE visibility='USER';
COMMENT ON TABLE notifications IS 'Thông báo/dedupe; bản ghi INTERNAL dùng cho mail bảo mật, không vào feed learner.';
COMMENT ON COLUMN notifications.id IS 'Notification ID';
COMMENT ON COLUMN notifications.user_id IS 'Người nhận';
COMMENT ON COLUMN notifications.reminder_rule_id IS 'Quy tắc nhắc nếu có';
COMMENT ON COLUMN notifications.type IS 'Loại sự kiện';
COMMENT ON COLUMN notifications.visibility IS 'USER hoặc INTERNAL';
COMMENT ON COLUMN notifications.title IS 'Không chứa secret';
COMMENT ON COLUMN notifications.body IS 'Nội dung công khai, không chứa OTP/token';
COMMENT ON COLUMN notifications.target_url IS 'Chỉ đường dẫn nội bộ';
COMMENT ON COLUMN notifications.dedupe_key IS 'User/sự kiện/ngày/ngữ cảnh';
COMMENT ON COLUMN notifications.read_at IS 'Mốc đọc';
COMMENT ON COLUMN notifications.created_at IS 'Mốc tạo';
CREATE INDEX ix_delivery_pending ON notification_deliveries(next_retry_at, created_at) WHERE status='PENDING';
CREATE INDEX ix_delivery_lease ON notification_deliveries(lease_until) WHERE status='PROCESSING';
COMMENT ON TABLE notification_deliveries IS 'Queue gửi có claim/lease; payload OTP chỉ lưu mã hóa, không rõ trong event/log.';
COMMENT ON COLUMN notification_deliveries.id IS 'Delivery ID';
COMMENT ON COLUMN notification_deliveries.notification_id IS 'Thông báo sở hữu';
COMMENT ON COLUMN notification_deliveries.channel IS 'IN_APP/EMAIL';
COMMENT ON COLUMN notification_deliveries.recipient_address IS 'Địa chỉ email đã chốt cho lần gửi';
COMMENT ON COLUMN notification_deliveries.status IS 'Trạng thái gửi';
COMMENT ON COLUMN notification_deliveries.attempt_count IS 'Tối đa 1 lần đầu + 3 retry';
COMMENT ON COLUMN notification_deliveries.next_retry_at IS 'Lần được nhận xử lý tiếp';
COMMENT ON COLUMN notification_deliveries.claim_token IS 'Claim hiện tại';
COMMENT ON COLUMN notification_deliveries.lease_until IS 'Lease 60 giây có gia hạn';
COMMENT ON COLUMN notification_deliveries.provider_message_id IS 'Receipt nếu nhà cung cấp có';
COMMENT ON COLUMN notification_deliveries.last_failure_code IS 'Lỗi đã làm sạch';
COMMENT ON COLUMN notification_deliveries.sent_at IS 'Mốc gửi thành công';
COMMENT ON COLUMN notification_deliveries.finished_at IS 'Mốc kết thúc, dùng retention';
COMMENT ON COLUMN notification_deliveries.otp_challenge_id IS 'Mail OTP chỉ gửi khi challenge còn hợp lệ';
COMMENT ON COLUMN notification_deliveries.encrypted_payload IS 'Ciphertext cho dữ liệu gửi nhạy cảm, không chứa khóa giải mã';
COMMENT ON COLUMN notification_deliveries.encryption_key_id IS 'ID khóa ở môi trường/kho secret';
COMMENT ON COLUMN notification_deliveries.sensitive_expires_at IS 'Không gửi OTP đã hết hạn';
COMMENT ON COLUMN notification_deliveries.created_at IS 'Mốc tạo';
COMMENT ON COLUMN notification_deliveries.updated_at IS 'Mốc cập nhật';

-- Seed quy tắc nhắc Flashcard đến hạn (M10.05). minimum_gap_minutes=720 thay vì default 1440:
-- scheduler chạy 15 phút/lần và có thể gửi bù trễ; khoảng cách 24 giờ sẽ đẩy giờ nhắc ngày sau trễ dần.
INSERT INTO reminder_rules (id, code, enabled, minimum_gap_minutes)
VALUES ('6d1c3f0e-6b7a-4c51-9a0e-1f2b3c4d5e01', 'REVIEW_DUE', TRUE, 720);
