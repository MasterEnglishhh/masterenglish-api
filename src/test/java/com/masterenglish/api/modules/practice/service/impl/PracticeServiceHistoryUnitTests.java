package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.masterenglish.api.modules.practice.repository.AttemptSourceMistakeRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSourceTopicRepository;
import com.masterenglish.api.modules.practice.repository.MistakeRecordRepository;
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionTopicRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
    private static final String TZ = "Asia/Ho_Chi_Minh";

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
                        mock(AttemptSourceMistakeRepository.class),
                        mock(AttemptQuestionSnapshotRepository.class),
                        mock(AttemptSnapshotOptionRepository.class),
                        mock(AttemptAnswerRepository.class),
                        mock(QuestionRepository.class),
                        mock(QuestionOptionRepository.class),
                        mock(QuestionTopicRepository.class),
                        mock(MistakeRecordRepository.class),
                        mock(MistakeRecorder.class),
                        mock(ObjectMapper.class),
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
                        "{}",
                        UUID.randomUUID(),
                        "a".repeat(64),
                        (short) 5,
                        TZ,
                        NOW.minusSeconds(120));
        single.submit("MANUAL", NOW, NOW, 4, 5, ZoneId.of(TZ));

        Attempt mixed =
                new Attempt(
                        mixedId,
                        userId,
                        PracticeServiceImpl.MIXED_GRAMMAR,
                        null,
                        null,
                        "{}",
                        UUID.randomUUID(),
                        "b".repeat(64),
                        (short) 10,
                        TZ,
                        NOW.minusSeconds(60));

        Pageable pageable = PageRequest.of(0, 10);
        when(attempts.findHistory(userId, true, "SUBMITTED", true, "MIXED_GRAMMAR", pageable))
                .thenReturn(new PageImpl<>(List.of(single, mixed), pageable, 2));
        when(sourceTopics.findByIdAttemptIdInOrderByIdAttemptIdAscIdTopicIdAsc(List.of(mixedId)))
                .thenReturn(
                        List.of(
                                new AttemptSourceTopic(mixedId, topicA, UUID.randomUUID()),
                                new AttemptSourceTopic(mixedId, topicB, UUID.randomUUID())));

        var page = service.listAttempts(userId, " submitted ", "mixed_grammar", pageable);

        verify(attempts).findHistory(userId, true, "SUBMITTED", true, "MIXED_GRAMMAR", pageable);
        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items().get(0).topicIds()).containsExactly(topicA);
        assertThat(page.items().get(0).score()).isEqualTo(80);
        assertThat(page.items().get(1).topicIds()).containsExactly(topicA, topicB);
        assertThat(page.items().get(1).score()).isNull();
    }

    @Test
    @DisplayName("getPracticeSummary từ chối timezone không hợp lệ và raw offset trước khi gọi SQL")
    void getPracticeSummaryRejectsInvalidTimezone() {
        for (String invalid :
                new String[] {
                    "Mars/Base", " ", "+07:00", "UTC+07:00", "GMT+07:00", "SystemV/AST4"
                }) {
            assertThatThrownBy(
                            () ->
                                    service.getPracticeSummary(
                                            userId, NOW.minusSeconds(86400), invalid))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR);
        }
    }

    @Test
    @DisplayName("getPracticeSummary chuẩn hóa legacy IANA alias trước khi gọi SQL")
    void getPracticeSummaryCanonicalizesLegacyAlias() {
        when(attempts.findSubmittedLocalDays(userId, TZ)).thenReturn(List.of());
        when(attempts.topicAccuracySince(userId, NOW.minusSeconds(86400))).thenReturn(List.of());
        when(attempts.findTop5ByUserIdAndStatusOrderBySubmittedAtDescIdDesc(
                        userId, Attempt.SUBMITTED))
                .thenReturn(List.of());

        service.getPracticeSummary(userId, NOW.minusSeconds(86400), "Asia/Saigon");

        verify(attempts).findSubmittedLocalDays(userId, TZ);
    }
}
