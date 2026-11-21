package com.masterenglish.api.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.GrammarEvents.PublishedLessonRef;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.learning.dto.response.LessonProgressResponse;
import com.masterenglish.api.modules.learning.entity.LessonProgress;
import com.masterenglish.api.modules.learning.entity.LessonProgress.LessonProgressId;
import com.masterenglish.api.modules.learning.repository.LessonProgressRepository;
import com.masterenglish.api.modules.learning.service.impl.LessonProgressServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LessonProgressServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-21T08:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID lessonId = UUID.randomUUID();
    private final UUID firstRevision = UUID.randomUUID();
    private final UUID laterRevision = UUID.randomUUID();

    private IdentityEvents identity;
    private GrammarEvents grammar;
    private LessonProgressRepository repository;
    private LessonProgressService service;

    @BeforeEach
    void setUp() {
        identity = mock(IdentityEvents.class);
        grammar = mock(GrammarEvents.class);
        repository = mock(LessonProgressRepository.class);
        service =
                new LessonProgressServiceImpl(
                        identity, grammar, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void completingVisibleLessonPinsCurrentPublishedRevision() {
        LessonProgress row = progressRow("COMPLETED", firstRevision, NOW);
        when(grammar.findPublishedLesson(lessonId))
                .thenReturn(Optional.of(new PublishedLessonRef(lessonId, firstRevision)));
        when(repository.findByIdUserIdAndIdLessonId(userId, lessonId)).thenReturn(Optional.of(row));

        LessonProgressResponse response = service.updateProgress(userId, lessonId, "COMPLETED");

        verify(identity).verifyAndLockUser(userId);
        verify(repository).upsertCompletion(userId, lessonId, firstRevision, NOW);
        assertThat(response.lessonId()).isEqualTo(lessonId);
        assertThat(response.state()).isEqualTo("COMPLETED");
        assertThat(response.completedAt()).isEqualTo(NOW);
        assertThat(response.lastCompletedRevisionId()).isEqualTo(firstRevision);
    }

    @Test
    void startingLessonDoesNotPinAnyRevision() {
        LessonProgress row = progressRow("IN_PROGRESS", null, null);
        when(grammar.findPublishedLesson(lessonId))
                .thenReturn(Optional.of(new PublishedLessonRef(lessonId, firstRevision)));
        when(repository.findByIdUserIdAndIdLessonId(userId, lessonId)).thenReturn(Optional.of(row));

        LessonProgressResponse response = service.updateProgress(userId, lessonId, "IN_PROGRESS");

        verify(repository).insertInProgressIfAbsent(userId, lessonId, NOW);
        verify(repository, never()).upsertCompletion(any(), any(), any(), any());
        assertThat(response.state()).isEqualTo("IN_PROGRESS");
        assertThat(response.lastCompletedRevisionId()).isNull();
    }

    @Test
    void unpublishedOrUnknownLessonIsRejectedBeforeAnyWrite() {
        when(grammar.findPublishedLesson(lessonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProgress(userId, lessonId, "COMPLETED"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(repository, never()).upsertCompletion(any(), any(), any(), any());
        verify(repository, never()).insertInProgressIfAbsent(any(), any(), any());
    }

    @Test
    void completionReturnsTheStoredRevisionWhenANewerOneIsPublished() {
        // The lesson now has a newer revision; the stored completion must keep the first one.
        LessonProgress row = progressRow("COMPLETED", firstRevision, NOW.minusSeconds(60));
        when(grammar.findPublishedLesson(lessonId))
                .thenReturn(Optional.of(new PublishedLessonRef(lessonId, laterRevision)));
        when(repository.findByIdUserIdAndIdLessonId(userId, lessonId)).thenReturn(Optional.of(row));

        LessonProgressResponse response = service.updateProgress(userId, lessonId, "COMPLETED");

        assertThat(response.lastCompletedRevisionId()).isEqualTo(firstRevision);
        assertThat(response.completedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void unsupportedStateIsRejected() {
        when(grammar.findPublishedLesson(lessonId))
                .thenReturn(Optional.of(new PublishedLessonRef(lessonId, firstRevision)));

        assertThatThrownBy(() -> service.updateProgress(userId, lessonId, "MASTERED"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private LessonProgress progressRow(String state, UUID revision, Instant completedAt) {
        LessonProgress row = mock(LessonProgress.class);
        when(row.getId()).thenReturn(new LessonProgressId(userId, lessonId));
        when(row.getState()).thenReturn(state);
        when(row.getLastCompletedRevisionId()).thenReturn(revision);
        when(row.getCompletedAt()).thenReturn(completedAt);
        return row;
    }
}
