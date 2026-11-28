package com.masterenglish.api.modules.admin.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateQuestionDraftRequest(
        @NotNull(message = "data is required") Map<String, Object> data,
        List<UUID> sourceIds,
        String changeSummary) {}
