package com.masterenglish.api.modules.identity.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PasswordChangeChallengeResponse(
        UUID challengeId, Instant expiresAt, long resendAfterSeconds) {}
