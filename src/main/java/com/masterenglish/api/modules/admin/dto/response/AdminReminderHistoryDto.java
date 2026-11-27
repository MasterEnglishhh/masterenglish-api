package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminReminderHistoryDto(
        UUID notificationId,
        UUID userId,
        String type,
        UUID reminderRuleId,
        String reminderRuleCode,
        String targetUrl,
        Instant readAt,
        Instant createdAt,
        List<DeliverySummaryDto> deliveries) {

    public record DeliverySummaryDto(
            String channel,
            String status,
            int attemptCount,
            String lastFailureCode,
            Instant sentAt,
            Instant finishedAt) {}
}
