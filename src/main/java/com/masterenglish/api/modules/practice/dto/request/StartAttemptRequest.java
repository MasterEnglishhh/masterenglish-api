package com.masterenglish.api.modules.practice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code requestId} makes the start call idempotent: a retry returns the same attempt. */
public record StartAttemptRequest(
        @NotNull UUID requestId, @Min(1) @Max(50) Integer questionCount) {}
