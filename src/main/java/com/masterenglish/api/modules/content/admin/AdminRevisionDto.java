package com.masterenglish.api.modules.content.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminRevisionDto(
        UUID revisionId,
        UUID contentItemId,
        String status,
        int versionNo,
        String reviewNote,
        Instant publishedAt,
        Instant updatedAt) {}
