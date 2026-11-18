package com.masterenglish.api.modules.learning.dto.response;

import java.time.Instant;
import java.util.UUID;

/** {@code lemma} and {@code meaningVi} are null when the sense is no longer published. */
public record ReviewHistoryItemDto(
        UUID reviewId,
        UUID wordSenseId,
        UUID wordId,
        String lemma,
        String meaningVi,
        String rating,
        Instant reviewedAt,
        Instant previousDueAt,
        Instant nextDueAt) {}
