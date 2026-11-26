package com.masterenglish.api.modules.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "content_notifications_enabled", nullable = false)
    private boolean contentNotificationsEnabled;

    @Column(name = "daily_reminder_time")
    private LocalTime dailyReminderTime;

    @Column(nullable = false, length = 60)
    private String timezone;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationPreference(UUID userId) {
        this.userId = Objects.requireNonNull(userId);
    }

    /** Callers validate the timezone and quiet-hour pair; the database checks them again. */
    public void update(
            boolean inAppEnabled,
            boolean emailEnabled,
            LocalTime dailyReminderTime,
            String timezone,
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            Instant now) {
        update(
                inAppEnabled,
                emailEnabled,
                this.contentNotificationsEnabled,
                dailyReminderTime,
                timezone,
                quietHoursStart,
                quietHoursEnd,
                now);
    }

    /** Callers validate the timezone and quiet-hour pair; the database checks them again. */
    public void update(
            boolean inAppEnabled,
            boolean emailEnabled,
            boolean contentNotificationsEnabled,
            LocalTime dailyReminderTime,
            String timezone,
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            Instant now) {
        this.inAppEnabled = inAppEnabled;
        this.emailEnabled = emailEnabled;
        this.contentNotificationsEnabled = contentNotificationsEnabled;
        this.dailyReminderTime = dailyReminderTime;
        this.timezone = Objects.requireNonNull(timezone);
        this.quietHoursStart = quietHoursStart;
        this.quietHoursEnd = quietHoursEnd;
        this.updatedAt = Objects.requireNonNull(now);
    }
}
