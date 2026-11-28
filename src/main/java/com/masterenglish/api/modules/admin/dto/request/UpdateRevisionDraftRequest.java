package com.masterenglish.api.modules.admin.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateRevisionDraftRequest(
        @NotNull(message = "expectedVersion is required")
                @Min(value = 0, message = "expectedVersion must be non-negative")
                Long expectedVersion,
        @NotNull(message = "data is required") Map<String, Object> data,
        @NotNull(message = "sourceIds is required") List<UUID> sourceIds,
        String changeSummary) {}
