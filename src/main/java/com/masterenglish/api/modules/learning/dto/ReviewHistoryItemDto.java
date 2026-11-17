package com.masterenglish.api.modules.learning.dto;

import com.masterenglish.api.modules.learning.entity.WordReview;
import com.masterenglish.api.modules.vocabulary.query.WordSenseCardDto;
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
        Instant nextDueAt) {

    public static ReviewHistoryItemDto from(WordReview review, WordSenseCardDto card) {
        return new ReviewHistoryItemDto(
                review.getId(),
                review.getWordSenseId(),
                review.getSourceWordId(),
                card == null ? null : card.lemma(),
                card == null ? null : card.meaningVi(),
                review.getRating(),
                review.getReviewedAt(),
                review.getPreviousDueAt(),
                review.getNextDueAt());
    }
}
