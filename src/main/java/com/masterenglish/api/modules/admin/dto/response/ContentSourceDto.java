package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ContentSourceDto(
        UUID id,
        String title,
        String sourceType,
        String sourceUrl,
        String author,
        String licenseCode,
        String status,
        Instant createdAt) {}
