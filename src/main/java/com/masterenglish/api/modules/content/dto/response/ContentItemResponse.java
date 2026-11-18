package com.masterenglish.api.modules.content.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Value;

@Value
public class ContentItemResponse {
    UUID id;
    String contentType;
    UUID publishedRevisionId;
    long version;
    Instant createdAt;
}
