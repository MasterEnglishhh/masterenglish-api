package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminRevisionHistoryDto(
        UUID revisionId,
        UUID contentItemId,
        int versionNo,
        String status,
        long version,
        String changeSummary,
        List<UUID> sourceIds,
        UUID createdBy,
        UUID reviewedBy,
        Instant reviewedAt,
        String reviewNote,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {}
