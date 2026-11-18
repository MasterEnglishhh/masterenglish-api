package com.masterenglish.api.modules.content.dto.response;

import java.time.Instant;
import java.util.UUID;
import lombok.Value;

@Value
public class ContentSourceResponse {
    UUID id;
    String title;
    String sourceType;
    String sourceUrl;
    String author;
    String licenseCode;
    String licenseUrl;
    String attribution;
    String permissionNote;
    String status;
    Instant createdAt;
}
