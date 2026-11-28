package com.masterenglish.api.modules.content.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Value;

@Value
public class ContentRevisionResponse {
    UUID id;
    UUID contentItemId;
    int versionNo;
    String status;
    String snapshot;
    UUID reviewedBy;
    Instant reviewedAt;
    Instant publishedAt;
    String reviewNote;
    Instant updatedAt;
    long version;

    public ContentRevisionResponse(
            UUID id,
            UUID contentItemId,
            int versionNo,
            String status,
            String snapshot,
            UUID reviewedBy,
            Instant reviewedAt,
            Instant publishedAt,
            String reviewNote,
            Instant updatedAt) {
        this(
                id,
                contentItemId,
                versionNo,
                status,
                snapshot,
                reviewedBy,
                reviewedAt,
                publishedAt,
                reviewNote,
                updatedAt,
                0L);
    }

    public ContentRevisionResponse(
            UUID id,
            UUID contentItemId,
            int versionNo,
            String status,
            String snapshot,
            UUID reviewedBy,
            Instant reviewedAt,
            Instant publishedAt,
            String reviewNote,
            Instant updatedAt,
            long version) {
        this.id = id;
        this.contentItemId = contentItemId;
        this.versionNo = versionNo;
        this.status = status;
        this.snapshot = snapshot;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.publishedAt = publishedAt;
        this.reviewNote = reviewNote;
        this.updatedAt = updatedAt;
        this.version = version;
    }
}
