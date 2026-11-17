package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.learning.dto.LearningProgressResponse;
import com.masterenglish.api.modules.learning.dto.ReviewHistoryItemDto;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.repository.WordReviewRepository;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LearningProgressServiceImpl implements LearningProgressService {

    // shortcut: "mastered" is a fixed 21-day interval, move to config when product defines it.
    static final int MASTERED_INTERVAL_DAYS = 21;

    private final WordReviewRepository reviewRepository;
    private final UserWordProgressRepository progressRepository;
    private final UserSavedWordRepository savedWordRepository;
    private final VocabularyQueryService vocabularyQueryService;
    private final Clock clock;

    public LearningProgressServiceImpl(
            WordReviewRepository reviewRepository,
            UserWordProgressRepository progressRepository,
            UserSavedWordRepository savedWordRepository,
            VocabularyQueryService vocabularyQueryService,
            Clock clock) {
        this.vocabularyQueryService = Objects.requireNonNull(vocabularyQueryService);
        this.reviewRepository = Objects.requireNonNull(reviewRepository);
        this.progressRepository = Objects.requireNonNull(progressRepository);
        this.savedWordRepository = Objects.requireNonNull(savedWordRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PageResponse<ReviewHistoryItemDto> getReviewHistory(UUID userId, Pageable pageable) {
        Objects.requireNonNull(userId, "userId must not be null");
        // One lookup per row (page size ≤ 100), same pattern as the due queue and saved words.
        return PageResponse.from(
                reviewRepository.findByUserIdOrderByReviewedAtDescIdDesc(userId, pageable),
                review ->
                        ReviewHistoryItemDto.from(
                                review,
                                vocabularyQueryService
                                        .getCardBySenseId(review.getWordSenseId())
                                        .orElse(null)));
    }

    @Override
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
}
