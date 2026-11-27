package com.masterenglish.api.modules.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record ReserveMediaUploadHttpRequest(
        @NotBlank(message = "fileName is required") String fileName,
        Long sizeBytes,
        Long declaredSizeBytes,
        @NotBlank(message = "mimeType is required") String mimeType,
        UUID sourceId) {

    public Long resolvedSizeBytes() {
        if (declaredSizeBytes != null) {
            return declaredSizeBytes;
        }
        return sizeBytes;
    }
}
