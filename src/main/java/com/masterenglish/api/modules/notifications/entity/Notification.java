package com.masterenglish.api.modules.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Title and body are public text: never put OTPs, tokens or email addresses here. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "reminder_rule_id", updatable = false)
    private UUID reminderRuleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private NotificationVisibility visibility;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "target_url", length = 500)
    private String targetUrl;

    @Column(name = "dedupe_key", nullable = false, length = 200, updatable = false)
    private String dedupeKey;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Notification(
            UUID userId,
            UUID reminderRuleId,
            NotificationType type,
            NotificationVisibility visibility,
            String title,
            String body,
            String targetUrl,
            String dedupeKey,
            Instant now) {
        this.id = UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId);
        this.reminderRuleId = reminderRuleId;
        this.type = Objects.requireNonNull(type);
        this.visibility = Objects.requireNonNull(visibility);
        this.title = Objects.requireNonNull(title);
        this.body = Objects.requireNonNull(body);
        this.targetUrl = targetUrl;
        this.dedupeKey = Objects.requireNonNull(dedupeKey);
        this.createdAt = Objects.requireNonNull(now);
    }

    /** Keeps the first read time when the learner opens the notification again. */
    public void markRead(Instant now) {
        if (readAt == null) readAt = Objects.requireNonNull(now);
    }
}
