package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminContentDetailDto(
        UUID id,
        UUID revisionId,
        String contentType,
        String status,
        int versionNo,
        long version,
        long itemVersion,
        Map<String, Object> data,
        List<UUID> sourceIds,
        String changeSummary,
        UUID createdBy,
        UUID reviewedBy,
        Instant reviewedAt,
        String reviewNote,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {}
