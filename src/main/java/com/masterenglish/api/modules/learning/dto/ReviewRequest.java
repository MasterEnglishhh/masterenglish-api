package com.masterenglish.api.modules.learning.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record ReviewRequest(
        @NotNull UUID wordSenseId,
        @NotNull UUID sourceWordId,
        @NotNull UUID sourceRevisionId,
        UUID deckId,
        UUID deckRevisionId,
        @NotNull
                @Pattern(
                        regexp = "^(AGAIN|HARD|GOOD)$",
                        message = "Rating must be AGAIN, HARD, or GOOD")
                String rating,
        @NotNull UUID reviewRequestId,
        Long expectedProgressVersion) {}
