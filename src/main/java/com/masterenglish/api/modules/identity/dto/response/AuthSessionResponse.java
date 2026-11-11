package com.masterenglish.api.modules.identity.dto.response;

import com.masterenglish.api.modules.identity.entity.AuthSession;
import java.time.Instant;
import java.util.UUID;

public record AuthSessionResponse(
        UUID id,
        boolean current,
        String userAgent,
        String ipAddress,
        Instant createdAt,
        Instant lastActiveAt,
        Instant expiresAt) {

    public static AuthSessionResponse from(AuthSession session, UUID currentSessionId) {
        return new AuthSessionResponse(
                session.getId(),
                session.getId().equals(currentSessionId),
                session.getUserAgent(),
                session.getIpAddress(),
                session.getCreatedAt(),
                session.getLastActiveAt(),
                session.getExpiresAt());
    }
}
