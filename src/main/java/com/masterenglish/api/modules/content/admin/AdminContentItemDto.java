package com.masterenglish.api.modules.content.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminContentItemDto(
        UUID id,
        String title,
        String code,
        String module,
        String type,
        String status,
        int versionNo,
        String summary,
        String author,
        String reviewer,
        Instant updatedAt,
        String level,
        UUID latestRevisionId) {}
