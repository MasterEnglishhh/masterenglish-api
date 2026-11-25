package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.practice.entity.Attempt;
import com.masterenglish.api.modules.practice.entity.AttemptSourceTopic;
import com.masterenglish.api.modules.practice.repository.AttemptAnswerRepository;
import com.masterenglish.api.modules.practice.repository.AttemptQuestionSnapshotRepository;
import com.masterenglish.api.modules.practice.repository.AttemptRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSnapshotOptionRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSourceTopicRepository;
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class PracticeServiceHistoryUnitTests {

    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final AttemptSourceTopicRepository sourceTopics =
            mock(AttemptSourceTopicRepository.class);
    private final UUID userId = UUID.randomUUID();

    private PracticeServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new PracticeServiceImpl(
                        mock(IdentityEvents.class),
                        mock(GrammarEvents.class),
                        attempts,
                        sourceTopics,
                        mock(AttemptQuestionSnapshotRepository.class),
                        mock(AttemptSnapshotOptionRepository.class),
                        mock(AttemptAnswerRepository.class),
                        mock(QuestionRepository.class),
                        mock(QuestionOptionRepository.class),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("listAttempts từ chối status hoặc sourceType không hợp lệ với VALIDATION_ERROR")
    void rejectsInvalidFilters() {
        Pageable pageable = PageRequest.of(0, 20);
        assertThatThrownBy(() -> service.listAttempts(userId, "INVALID", null, pageable))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> service.listAttempts(userId, null, "TOEIC_PART", pageable))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("listAttempts chuẩn hóa bộ lọc và nạp đúng topicIds cho cả SINGLE và MIXED")
    void normalizesFiltersAndHydratesMixedTopics() {
        UUID topicA = UUID.randomUUID();
        UUID topicB = UUID.randomUUID();
        UUID singleId = UUID.randomUUID();
        UUID mixedId = UUID.randomUUID();

        Attempt single =
                new Attempt(
                        singleId,
                        userId,
                        PracticeServiceImpl.GRAMMAR_TOPIC,
                        topicA,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        5,
                        NOW.minusSeconds(120));
        single.submit(4, 80, NOW);

        Attempt mixed =
                new Attempt(
                        mixedId,
                        userId,
                        PracticeServiceImpl.MIXED_GRAMMAR,
                        null,
                        UUID.randomUUID(),
                        "b".repeat(64),
                        10,
                        NOW.minusSeconds(60));

        Pageable pageable = PageRequest.of(0, 10);
        when(attempts.findHistory(userId, true, "SUBMITTED", true, "MIXED_GRAMMAR", pageable))
                .thenReturn(new PageImpl<>(List.of(single, mixed), pageable, 2));
        when(sourceTopics.findByIdAttemptIdInOrderByIdAttemptIdAscIdTopicIdAsc(List.of(mixedId)))
                .thenReturn(
                        List.of(
                                new AttemptSourceTopic(mixedId, topicA),
                                new AttemptSourceTopic(mixedId, topicB)));

        var page = service.listAttempts(userId, " submitted ", "mixed_grammar", pageable);

        verify(attempts).findHistory(userId, true, "SUBMITTED", true, "MIXED_GRAMMAR", pageable);
        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items().get(0).topicIds()).containsExactly(topicA);
        assertThat(page.items().get(0).score()).isEqualTo(80);
        assertThat(page.items().get(1).topicIds()).containsExactly(topicA, topicB);
        assertThat(page.items().get(1).score()).isNull();
    }
}
