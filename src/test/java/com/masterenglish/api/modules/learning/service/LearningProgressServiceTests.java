package com.masterenglish.api.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.api.WordSenseCardDto;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.modules.learning.dto.response.LearningProgressResponse;
import com.masterenglish.api.modules.learning.dto.response.ReviewHistoryItemDto;
import com.masterenglish.api.modules.learning.entity.WordReview;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.repository.WordReviewRepository;
import com.masterenglish.api.modules.learning.service.impl.LearningProgressServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class LearningProgressServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    private WordReviewRepository reviewRepository;
    private UserWordProgressRepository progressRepository;
    private UserSavedWordRepository savedWordRepository;
    private VocabularyEvents vocabulary;
    private LearningProgressService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reviewRepository = mock(WordReviewRepository.class);
        progressRepository = mock(UserWordProgressRepository.class);
        savedWordRepository = mock(UserSavedWordRepository.class);
        vocabulary = mock(VocabularyEvents.class);
        service =
                new LearningProgressServiceImpl(
                        reviewRepository,
                        progressRepository,
                        savedWordRepository,
                        vocabulary,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void getReviewHistory_mapsPageAndKeepsPaging() {
        UUID senseId = UUID.randomUUID();
        UUID wordId = UUID.randomUUID();
        WordReview review =
                new WordReview(
                        null,
                        userId,
                        senseId,
                        wordId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "hash",
                        "GOOD",
                        "SM2_3BTN_V1",
                        "{}",
                        "{}",
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(3600),
                        NOW,
                        "UTC",
                        LocalDate.of(2026, 11, 17));
        PageRequest pageable = PageRequest.of(1, 5);
        when(reviewRepository.findByUserIdOrderByReviewedAtDescIdDesc(userId, pageable))
                .thenReturn(new PageImpl<>(List.of(review), pageable, 6));
        WordSenseCardDto card =
                new WordSenseCardDto(
                        null,
                        null,
                        0,
                        senseId,
                        wordId,
                        null,
                        "approach",
                        null,
                        "noun",
                        "B1",
                        "cách tiếp cận",
                        null,
                        List.of(),
                        null,
                        null,
                        null);
        when(vocabulary.getCardBySenseId(senseId)).thenReturn(Optional.of(card));

        PageResponse<ReviewHistoryItemDto> page = service.getReviewHistory(userId, pageable);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.totalItems()).isEqualTo(6);
        assertThat(page.items()).hasSize(1);
        ReviewHistoryItemDto item = page.items().get(0);
        assertThat(item.wordSenseId()).isEqualTo(senseId);
        assertThat(item.wordId()).isEqualTo(wordId);
        assertThat(item.rating()).isEqualTo("GOOD");
        assertThat(item.lemma()).isEqualTo("approach");
        assertThat(item.meaningVi()).isEqualTo("cách tiếp cận");
        assertThat(item.nextDueAt()).isEqualTo(NOW.plusSeconds(3600));

        when(vocabulary.getCardBySenseId(senseId)).thenReturn(Optional.empty());
        var archived = service.getReviewHistory(userId, pageable);
        assertThat(archived.totalItems()).isEqualTo(6);
        assertThat(archived.items())
                .singleElement()
                .satisfies(
                        history -> {
                            assertThat(history.wordSenseId()).isEqualTo(senseId);
                            assertThat(history.lemma()).isNull();
                            assertThat(history.meaningVi()).isNull();
                        });
    }

    @Test
    void getProgress_aggregatesCounts() {
        when(progressRepository.countByIdUserId(userId)).thenReturn(12L);
        when(progressRepository.countByIdUserIdAndIntervalDaysGreaterThanEqual(userId, 21))
                .thenReturn(3L);
        when(progressRepository.countEligibleDue(eq(userId), isNull(), eq(NOW))).thenReturn(4L);
        when(savedWordRepository.countByIdUserId(userId)).thenReturn(20L);
        when(reviewRepository.countByUserId(userId)).thenReturn(57L);
        when(reviewRepository.countByUserIdAndReviewedAtGreaterThanEqual(
                        eq(userId), eq(NOW.minusSeconds(7 * 24 * 3600L))))
                .thenReturn(9L);

        LearningProgressResponse result = service.getProgress(userId);

        assertThat(result).isEqualTo(new LearningProgressResponse(12, 3, 4, 20, 57, 9));
    }

    @Test
    void getProgress_newUser_allZero() {
        when(progressRepository.countEligibleDue(any(), any(), any())).thenReturn(0L);

        assertThat(service.getProgress(userId))
                .isEqualTo(new LearningProgressResponse(0, 0, 0, 0, 0, 0));
    }

    @Test
    void findReviewActivityLocalDays_canonicalizesLegacyAndRejectsOffsets() {
        when(reviewRepository.findDistinctActivityLocalDays(userId, "Asia/Ho_Chi_Minh"))
                .thenReturn(List.of("2026-11-17"));

        assertThat(service.findReviewActivityLocalDays(userId, "Asia/Saigon"))
                .containsExactly(LocalDate.of(2026, 11, 17));
        verify(reviewRepository).findDistinctActivityLocalDays(userId, "Asia/Ho_Chi_Minh");

        for (String bad :
                new String[] {null, " ", "+07:00", "UTC+07:00", "SystemV/AST4", "Mars/Base"}) {
            assertThatThrownBy(() -> service.findReviewActivityLocalDays(userId, bad))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR);
        }
    }
}
