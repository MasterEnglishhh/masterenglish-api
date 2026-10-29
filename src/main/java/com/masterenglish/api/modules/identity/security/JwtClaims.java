package com.masterenglish.api.modules.identity.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record JwtClaims(
        UUID userId,
        UUID sessionId,
        int authVersion,
        Set<String> roles,
        Instant issuedAt,
        Instant expiresAt) {

    public JwtClaims {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
