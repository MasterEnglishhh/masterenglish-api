package com.masterenglish.api.modules.notifications.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learner reminder execution history (M10.11). Combines the reminder notification record with
 * per-channel delivery outcomes without exposing internal security mails or recipient addresses.
 */
public record ReminderHistoryResponse(
        UUID notificationId,
        String type,
        String ruleCode,
        String title,
        String body,
        String targetUrl,
        Instant readAt,
        Instant createdAt,
        List<DeliveryOutcome> deliveries) {

    public record DeliveryOutcome(
            String channel,
            String status,
            int attemptCount,
            String lastFailureCode,
            Instant sentAt,
            Instant finishedAt) {}
}
