package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.GrammarEvents.PublishedLessonRef;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.LearningEvents.LessonProgressSummaryView;
import com.masterenglish.api.modules.learning.dto.response.LessonProgressResponse;
import com.masterenglish.api.modules.learning.entity.LessonProgress;
import com.masterenglish.api.modules.learning.repository.LessonProgressRepository;
import com.masterenglish.api.modules.learning.service.LessonProgressService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessonProgressServiceImpl implements LessonProgressService {

    private static final String COMPLETED = "COMPLETED";
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final IdentityEvents identity;
    private final GrammarEvents grammar;
    private final LessonProgressRepository repository;
    private final Clock clock;

    public LessonProgressServiceImpl(
            IdentityEvents identity,
            GrammarEvents grammar,
            LessonProgressRepository repository,
            Clock clock) {
        this.identity = identity;
        this.grammar = grammar;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LessonProgressResponse updateProgress(UUID userId, UUID lessonId, String state) {
        identity.verifyAndLockUser(userId);
        PublishedLessonRef lesson =
                grammar.findPublishedLesson(lessonId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Grammar lesson not found."));
        Instant now = clock.instant();
        if (COMPLETED.equals(state)) {
            repository.upsertCompletion(userId, lessonId, lesson.revisionId(), now);
        } else if (IN_PROGRESS.equals(state)) {
            repository.insertInProgressIfAbsent(userId, lessonId, now);
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unsupported lesson state.");
        }
        return toResponse(
                repository
                        .findByIdUserIdAndIdLessonId(userId, lessonId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Lesson progress missing after update.")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LessonProgressResponse> getProgress(UUID userId) {
        return repository.findByIdUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(LessonProgressServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LessonProgressSummaryView getSummary(UUID userId) {
        List<LessonProgress> all = repository.findByIdUserIdOrderByUpdatedAtDesc(userId);
        Set<UUID> completedIds = new LinkedHashSet<>();
        Set<UUID> inProgressIds = new LinkedHashSet<>();
        for (LessonProgress item : all) {
            if (COMPLETED.equals(item.getState())) {
                completedIds.add(item.getId().getLessonId());
            } else if (IN_PROGRESS.equals(item.getState())) {
                inProgressIds.add(item.getId().getLessonId());
            }
        }
        return new LessonProgressSummaryView(
                completedIds.size(),
                inProgressIds.size(),
                Set.copyOf(completedIds),
                Set.copyOf(inProgressIds));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<LocalDate> findActivityLocalDays(UUID userId, String timezone) {
        String validZone = LearningProgressServiceImpl.validateTimezone(timezone);
        return repository.findDistinctActivityLocalDays(userId, validZone).stream()
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }

    private static LessonProgressResponse toResponse(LessonProgress progress) {
        return new LessonProgressResponse(
                progress.getId().getLessonId(),
                progress.getState(),
                progress.getCompletedAt(),
                progress.getLastCompletedRevisionId());
    }
}
