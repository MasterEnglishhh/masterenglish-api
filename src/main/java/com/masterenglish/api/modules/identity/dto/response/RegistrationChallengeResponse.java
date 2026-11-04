package com.masterenglish.api.modules.identity.dto.response;

import java.time.Instant;
import java.util.UUID;

public record RegistrationChallengeResponse(
                UUID registrationId, Instant expiresAt, long resendAfterSeconds, String message) {
}
