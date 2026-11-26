package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes notifications and deliveries in short per-learner transactions. */
@Component
class ReminderWriter {

    static final String REVIEW_TARGET = "/learn/review";
    static final String DAILY_GOAL_TARGET = "/learn";
    static final String CONTENT_DECK_TARGET_PREFIX = "/learn/vocabulary?deck=";
    private static final int MAX_TITLE_LENGTH = 180;

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationPreferenceRepository preferences;

    ReminderWriter(
            NotificationRepository notifications,
            NotificationDeliveryRepository deliveries,
            NotificationPreferenceRepository preferences) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.preferences = preferences;
    }

    static String reviewDueKey(UUID userId, LocalDate localDay) {
        return "REVIEW_DUE:" + userId + ":" + localDay;
    }

    static String dailyGoalKey(UUID userId, LocalDate localDay) {
        return "DAILY_GOAL:" + userId + ":" + localDay;
    }

    static String contentNewDeckKey(UUID userId, UUID deckId) {
        return "CONTENT_NEW:" + userId + ":DECK:" + deckId;
    }

    static String reviewDueTitle(long dueCards) {
        return "Bạn có " + dueCards + " thẻ cần ôn hôm nay";
    }

    static String reviewDueBody(long dueCards) {
        return dueCards + " thẻ Flashcard đã đến hạn ôn tập. Ôn ngay để giữ nhịp ghi nhớ.";
    }

    static String dailyGoalTitle(int remainingMinutes) {
        return "Còn " + remainingMinutes + " phút để đạt mục tiêu hôm nay";
    }

    static String dailyGoalBody(int dailyMinutes, int actualMinutes) {
        return "Bạn đã học "
                + actualMinutes
                + "/"
                + dailyMinutes
                + " phút mục tiêu hôm nay. Hoàn thành nốt để giữ nhịp học tập nhé.";
    }

    static String contentNewDeckTitle(String deckTitle) {
        String safeTitle =
                deckTitle == null || deckTitle.isBlank() ? "Bộ thẻ mới" : deckTitle.strip();
        String full = "Bộ từ vựng mới: " + safeTitle;
        return full.length() <= MAX_TITLE_LENGTH
                ? full
                : full.substring(0, MAX_TITLE_LENGTH - 3) + "...";
    }

    static String contentNewDeckBody(String deckTitle, String level) {
        String safeTitle =
                deckTitle == null || deckTitle.isBlank() ? "Bộ từ vựng mới" : deckTitle.strip();
        String levelPart =
                level == null || level.isBlank() ? "" : " (trình độ " + level.strip() + ")";
        return "Bộ từ vựng \""
                + safeTitle
                + "\""
                + levelPart
                + " vừa được phát hành. Vào học ngay.";
    }

    static String contentNewDeckTarget(UUID deckId) {
        return CONTENT_DECK_TARGET_PREFIX + deckId;
    }

    @Transactional
    public boolean createReviewDue(
            UUID userId,
            UUID ruleId,
            int minimumGapMinutes,
            LocalDate localDay,
            long dueCards,
            boolean inApp,
            String emailRecipient,
            Instant now) {
        return createRuleReminder(
                userId,
                ruleId,
                minimumGapMinutes,
                NotificationType.REVIEW_DUE,
                reviewDueTitle(dueCards),
                reviewDueBody(dueCards),
                REVIEW_TARGET,
                reviewDueKey(userId, localDay),
                inApp,
                emailRecipient,
                now);
    }

    @Transactional
    public boolean createDailyGoal(
            UUID userId,
            UUID ruleId,
            int minimumGapMinutes,
            LocalDate localDay,
            int dailyMinutes,
            int actualMinutes,
            int remainingMinutes,
            boolean inApp,
            String emailRecipient,
            Instant now) {
        return createRuleReminder(
                userId,
                ruleId,
                minimumGapMinutes,
                NotificationType.DAILY_GOAL,
                dailyGoalTitle(remainingMinutes),
                dailyGoalBody(dailyMinutes, actualMinutes),
                DAILY_GOAL_TARGET,
                dailyGoalKey(userId, localDay),
                inApp,
                emailRecipient,
                now);
    }

    /**
     * Creates one CONTENT_NEW announcement per opted-in learner without reminder_rules (ADR-04).
     */
    @Transactional
    public boolean createContentNewDeck(
            UUID userId,
            UUID deckId,
            String deckTitle,
            String level,
            boolean inApp,
            String emailRecipient,
            Instant now) {
        return insertNotificationAndDeliveries(
                userId,
                null,
                NotificationType.CONTENT_NEW,
                contentNewDeckTitle(deckTitle),
                contentNewDeckBody(deckTitle, level),
                contentNewDeckTarget(deckId),
                contentNewDeckKey(userId, deckId),
                inApp,
                emailRecipient,
                now);
    }

    private boolean createRuleReminder(
            UUID userId,
            UUID ruleId,
            int minimumGapMinutes,
            NotificationType type,
            String title,
            String body,
            String targetUrl,
            String dedupeKey,
            boolean inApp,
            String emailRecipient,
            Instant now) {
        // Lock the learner's preference row so concurrent schedulers racing across midnight
        // serialize and observe committed reminders when enforcing minimum_gap_minutes.
        if (preferences.findForUpdate(userId).isEmpty()) {
            return false;
        }
        Instant gapStart = now.minus(Duration.ofMinutes(minimumGapMinutes));
        boolean withinMinimumGap =
                notifications.findLastReminders(ruleId, List.of(userId)).stream()
                        .anyMatch(last -> last.getLastCreatedAt().isAfter(gapStart));
        if (withinMinimumGap) {
            return false;
        }
        return insertNotificationAndDeliveries(
                userId,
                ruleId,
                type,
                title,
                body,
                targetUrl,
                dedupeKey,
                inApp,
                emailRecipient,
                now);
    }

    private boolean insertNotificationAndDeliveries(
            UUID userId,
            UUID ruleId,
            NotificationType type,
            String title,
            String body,
            String targetUrl,
            String dedupeKey,
            boolean inApp,
            String emailRecipient,
            Instant now) {
        UUID id = UUID.randomUUID();
        int inserted =
                notifications.insertIfAbsent(
                        id, userId, ruleId, type.name(), title, body, targetUrl, dedupeKey, now);
        if (inserted == 0) {
            return false;
        }
        if (inApp) {
            deliveries.saveAndFlush(NotificationDelivery.inAppDelivered(id, now));
        }
        if (emailRecipient != null) {
            deliveries.saveAndFlush(NotificationDelivery.emailPending(id, emailRecipient, now));
        }
        return true;
    }
}
