package com.masterenglish.api.modules.notifications.dto.response;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        String title,
        String body,
        String targetUrl,
        Instant readAt,
        Instant createdAt) {}
