package com.masterenglish.api.modules.practice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record MixedAttemptRequest(
        @NotNull UUID requestId,
        @NotNull @Size(min = 2, max = 10) List<@NotNull UUID> topicIds,
        @Min(1) @Max(50) Integer questionCount) {}
