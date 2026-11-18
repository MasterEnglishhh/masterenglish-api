package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntryDto(
        UUID id,
        String actorEmail,
        String action,
        String entityType,
        UUID entityId,
        String details,
        String ipAddress,
        Instant timestamp,
        String severity) {}
