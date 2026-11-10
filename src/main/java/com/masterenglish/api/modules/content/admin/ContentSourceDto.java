package com.masterenglish.api.modules.content.admin;

import com.masterenglish.api.modules.content.entity.ContentSource;
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
        Instant createdAt) {

    public static ContentSourceDto from(ContentSource source) {
        return new ContentSourceDto(
                source.getId(),
                source.getTitle(),
                source.getSourceType(),
                source.getSourceUrl(),
                source.getAuthor(),
                source.getLicenseCode(),
                source.getStatus(),
                source.getCreatedAt());
    }
}
