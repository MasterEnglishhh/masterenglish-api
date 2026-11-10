package com.masterenglish.api.modules.admin.dto.system;

import com.masterenglish.api.modules.admin.entity.FeatureFlag;
import java.time.Instant;

public record FeatureFlagDto(
        String key,
        String name,
        String description,
        boolean enabled,
        String category,
        Instant updatedAt) {

    public static FeatureFlagDto from(FeatureFlag flag) {
        return new FeatureFlagDto(
                flag.getKey(),
                flag.getName(),
                flag.getDescription(),
                flag.isEnabled(),
                flag.getCategory(),
                flag.getUpdatedAt());
    }
}
