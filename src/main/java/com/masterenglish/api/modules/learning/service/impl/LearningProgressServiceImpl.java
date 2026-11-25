package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.api.WordSenseCardDto;
import com.masterenglish.api.common.events.LearningEvents.DueDeckCountView;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.modules.learning.dto.response.LearningProgressResponse;
import com.masterenglish.api.modules.learning.dto.response.ReviewHistoryItemDto;
import com.masterenglish.api.modules.learning.entity.WordReview;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.repository.WordReviewRepository;
import com.masterenglish.api.modules.learning.service.LearningProgressService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningProgressServiceImpl implements LearningProgressService {

    // shortcut: "mastered" is a fixed 21-day interval, move to config when product defines it.
    static final int MASTERED_INTERVAL_DAYS = 21;

    private final WordReviewRepository reviewRepository;
    private final UserWordProgressRepository progressRepository;
    private final UserSavedWordRepository savedWordRepository;
    private final VocabularyEvents vocabulary;
    private final Clock clock;

    public LearningProgressServiceImpl(
            WordReviewRepository reviewRepository,
            UserWordProgressRepository progressRepository,
            UserSavedWordRepository savedWordRepository,
            VocabularyEvents vocabulary,
            Clock clock) {
        this.vocabulary = Objects.requireNonNull(vocabulary);
        this.reviewRepository = Objects.requireNonNull(reviewRepository);
        this.progressRepository = Objects.requireNonNull(progressRepository);
        this.savedWordRepository = Objects.requireNonNull(savedWordRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReviewHistoryItemDto> getReviewHistory(UUID userId, Pageable pageable) {
        Objects.requireNonNull(userId, "userId must not be null");
        // One lookup per row (page size ≤ 100), same pattern as the due queue and saved words.
        return PageResponse.from(
                reviewRepository.findByUserIdOrderByReviewedAtDescIdDesc(userId, pageable),
                review ->
                        historyItem(
                                review,
                                vocabulary.getCardBySenseId(review.getWordSenseId()).orElse(null)));
    }

    @Override
    @Transactional(readOnly = true)
    public LearningProgressResponse getProgress(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Instant now = clock.instant();
        return new LearningProgressResponse(
                progressRepository.countByIdUserId(userId),
                progressRepository.countByIdUserIdAndIntervalDaysGreaterThanEqual(
                        userId, MASTERED_INTERVAL_DAYS),
                // Same eligibility rules as GET /reviews/due so both numbers agree.
                progressRepository.countEligibleDue(userId, null, now),
                savedWordRepository.countByIdUserId(userId),
                reviewRepository.countByUserId(userId),
                reviewRepository.countByUserIdAndReviewedAtGreaterThanEqual(
                        userId, now.minus(Duration.ofDays(7))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueDeckCountView> countDueCardsByDeck(UUID userId, Instant asOf) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");
        return progressRepository.countEligibleDueByDeck(userId, asOf).stream()
                .map(row -> new DueDeckCountView(row.getDeckId(), row.getDueCount()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<LocalDate> findReviewActivityLocalDays(UUID userId, String timezone) {
        Objects.requireNonNull(userId, "userId must not be null");
        String validZone = validateTimezone(timezone);
        return reviewRepository.findDistinctActivityLocalDays(userId, validZone).stream()
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }

    static String validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.VALIDATION_ERROR,
                    "timezone must not be blank.");
        }
        try {
            return ZoneId.of(timezone.trim()).getId();
        } catch (DateTimeException exception) {
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.VALIDATION_ERROR,
                    "Invalid timezone: " + timezone);
        }
    }

    private static ReviewHistoryItemDto historyItem(WordReview review, WordSenseCardDto card) {
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
