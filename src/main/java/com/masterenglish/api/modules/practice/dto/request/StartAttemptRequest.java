package com.masterenglish.api.modules.practice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/attempts}. The request id travels in the {@code Idempotency-Key}
 * header. {@code questionCount} is required; there is no default (practice contract, section 3.2).
 */
public record StartAttemptRequest(
        @NotNull @Valid Source source, @NotNull @Min(1) @Max(100) Integer questionCount) {

    /**
     * {@code GRAMMAR_TOPIC} takes one topic; {@code MIXED_GRAMMAR} takes two to ten topics; {@code
     * MISTAKES} takes the learner's own mistake ids.
     */
    public record Source(
            @NotBlank String type, UUID topicId, List<UUID> topicIds, List<UUID> mistakeIds) {}
}
