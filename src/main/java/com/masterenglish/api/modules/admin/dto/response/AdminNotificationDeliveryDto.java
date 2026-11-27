package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AdminNotificationDeliveryDto(
        UUID id,
        UUID notificationId,
        UUID userId,
        String type,
        String channel,
        String status,
        int attemptCount,
        Instant nextRetryAt,
        String lastFailureCode,
        String providerMessageId,
        Instant sentAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {}
