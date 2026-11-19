-- V11: Notifications & reminder system (thông báo nhắc học).
-- Adds four tables: notification_preferences, reminder_rules, notifications, notification_deliveries.

-- ── Preference per user ───────────────────────────────────────────────────────
CREATE TABLE notification_preferences (
    user_id                UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    in_app_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    email_enabled          BOOLEAN     NOT NULL DEFAULT FALSE,
    daily_reminder_time    TIME,
    timezone               VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    quiet_hours_start      TIME,
    quiet_hours_end        TIME,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT notification_preferences_c1
        CHECK (
            (quiet_hours_start IS NULL AND quiet_hours_end IS NULL)
            OR (quiet_hours_start IS NOT NULL AND quiet_hours_end IS NOT NULL
                AND quiet_hours_start <> quiet_hours_end)
        )
);

-- ── System-defined reminder rules (seeded below) ─────────────────────────────
CREATE TABLE reminder_rules (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 VARCHAR(40) NOT NULL UNIQUE,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    minimum_gap_minutes  INTEGER     NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT reminder_rules_c1 CHECK (minimum_gap_minutes > 0 AND btrim(code) <> '')
);

INSERT INTO reminder_rules (code, enabled, minimum_gap_minutes)
VALUES ('REVIEW_DUE', TRUE, 720);

-- ── Notification rows ─────────────────────────────────────────────────────────
CREATE TABLE notifications (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reminder_rule_id UUID        REFERENCES reminder_rules (id) ON DELETE SET NULL,
    type             VARCHAR(24) NOT NULL,
    visibility       VARCHAR(12) NOT NULL DEFAULT 'PRIVATE',
    title            VARCHAR(180) NOT NULL,
    body             TEXT        NOT NULL,
    target_url       VARCHAR(500),
    dedupe_key       VARCHAR(200) NOT NULL,
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT notifications_c1 CHECK (type IN ('REVIEW_DUE', 'SYSTEM')),
    CONSTRAINT notifications_c2 CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT notifications_c3 CHECK (btrim(title) <> '' AND btrim(body) <> ''),
    CONSTRAINT notifications_c4 CHECK (
        target_url IS NULL
        OR (target_url NOT LIKE 'http://%' AND target_url NOT LIKE 'https://%' AND target_url NOT LIKE '//%')
    ),
    CONSTRAINT notifications_uq_user_dedupe UNIQUE (user_id, dedupe_key)
);

CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_user_unread  ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;

-- ── Delivery outbox ───────────────────────────────────────────────────────────
CREATE TABLE notification_deliveries (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id     UUID        NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    channel             VARCHAR(8)  NOT NULL,
    recipient_address   VARCHAR(254),
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count       SMALLINT    NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ,
    claim_token         UUID,
    lease_until         TIMESTAMPTZ,
    provider_message_id VARCHAR(180),
    last_failure_code   VARCHAR(64),
    sent_at             TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT notification_deliveries_c1 CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT notification_deliveries_c2 CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    CONSTRAINT notification_deliveries_c3 CHECK (attempt_count >= 0 AND attempt_count <= 4),
    CONSTRAINT notification_deliveries_c4 CHECK (
        channel <> 'EMAIL' OR recipient_address IS NOT NULL
    ),
    CONSTRAINT notification_deliveries_c5 CHECK (
        status <> 'PROCESSING' OR claim_token IS NOT NULL
    ),
    CONSTRAINT notification_deliveries_uq_notification_channel
        UNIQUE (notification_id, channel)
);

CREATE INDEX ix_notification_deliveries_pending
    ON notification_deliveries (next_retry_at)
    WHERE status = 'PENDING' AND next_retry_at IS NOT NULL;
