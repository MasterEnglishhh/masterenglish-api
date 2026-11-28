package com.masterenglish.api.modules.admin.dto.request;

import jakarta.validation.constraints.Min;
import java.util.UUID;

public record CreateRevisionFromPublishedRequest(
        UUID baseRevisionId,
        @Min(value = 0, message = "expectedItemVersion must be non-negative")
                Long expectedItemVersion,
        String changeSummary) {}
