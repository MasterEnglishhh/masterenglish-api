package com.masterenglish.api.modules.identity.dto.response;

import java.time.Instant;

public record PasswordResetGrantResponse(String resetToken, Instant expiresAt) {
    @Override
    public String toString() {
        return "PasswordResetGrantResponse[redacted]";
    }
}
