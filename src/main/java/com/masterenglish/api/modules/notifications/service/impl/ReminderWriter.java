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

/** Writes one reminder and its deliveries in their own transaction. */
@Component
class ReminderWriter {

    static final String REVIEW_TARGET = "/learn/review";

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

    static String reviewDueTitle(long dueCards) {
        return "Bạn có " + dueCards + " thẻ cần ôn hôm nay";
    }

    static String reviewDueBody(long dueCards) {
        return dueCards + " thẻ Flashcard đã đến hạn ôn tập. Ôn ngay để giữ nhịp ghi nhớ.";
    }

    /**
     * @param emailRecipient address captured for the EMAIL delivery, or null for in-app only
     * @return false when the reminder for this user and day already exists
     */
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
        UUID id = UUID.randomUUID();
        int inserted =
                notifications.insertIfAbsent(
                        id,
                        userId,
                        ruleId,
                        NotificationType.REVIEW_DUE.name(),
                        reviewDueTitle(dueCards),
                        reviewDueBody(dueCards),
                        REVIEW_TARGET,
                        reviewDueKey(userId, localDay),
                        now);
        if (inserted == 0) return false;
        if (inApp) deliveries.saveAndFlush(NotificationDelivery.inAppDelivered(id, now));
        if (emailRecipient != null) {
            deliveries.saveAndFlush(NotificationDelivery.emailPending(id, emailRecipient, now));
        }
        return true;
    }
}
