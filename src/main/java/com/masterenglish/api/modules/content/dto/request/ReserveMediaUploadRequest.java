package com.masterenglish.api.modules.content.dto.request;

import java.util.UUID;

public record ReserveMediaUploadRequest(
        String fileName, String mimeType, Long declaredSizeBytes, UUID sourceId) {}
