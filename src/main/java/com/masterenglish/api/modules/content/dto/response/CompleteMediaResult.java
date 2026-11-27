package com.masterenglish.api.modules.content.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CompleteMediaResult(
        UUID uploadRequestId,
        UUID mediaId,
        String status,
        Instant verificationDeadlineAt,
        UUID claimToken,
        Instant leaseUntil,
        boolean leaseAcquired,
        Long stagedSizeBytes,
        String stagedSha256) {}
