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
}
