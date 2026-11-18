package com.masterenglish.api.modules.admin.dto.response;

import java.time.Instant;

public record FeatureFlagDto(
        String key,
        String name,
        String description,
        boolean enabled,
        String category,
        Instant updatedAt) {}
