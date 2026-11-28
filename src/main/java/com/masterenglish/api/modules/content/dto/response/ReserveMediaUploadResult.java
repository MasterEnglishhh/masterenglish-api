package com.masterenglish.api.modules.content.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ReserveMediaUploadResult(
        UUID uploadRequestId,
        UUID mediaId,
        String tempBucket,
        String tempObjectKey,
        long declaredSizeBytes,
        long maxSizeBytes,
        Instant expiresAt,
        String status) {}
