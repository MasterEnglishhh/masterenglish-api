package com.masterenglish.api.modules.content.admin;

import com.masterenglish.api.modules.content.entity.AuditLog;
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
        String severity) {

    public static AuditLogEntryDto from(AuditLog log, String actorEmail) {
        String severity = "INFO";
        if (log.getAction().contains("LOCK") || log.getAction().contains("DELETE") || log.getAction().contains("REVOKE") || log.getAction().contains("REJECT")) {
            severity = "WARNING";
        }
        return new AuditLogEntryDto(
                log.getId(),
                actorEmail != null ? actorEmail : (log.getActorUserId() != null ? log.getActorUserId().toString() : "SYSTEM"),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getReason() != null ? log.getReason() : log.getAction(),
                log.getIpAddress(),
                log.getCreatedAt(),
                severity);
    }
}
