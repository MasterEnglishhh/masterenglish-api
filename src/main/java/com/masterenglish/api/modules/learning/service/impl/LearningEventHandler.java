package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.events.LearningEvents.DailyStudySecondsRequested;
import com.masterenglish.api.common.events.LearningEvents.DueCardsCountRequested;
import com.masterenglish.api.common.events.LearningEvents.DueDecksCountRequested;
import com.masterenglish.api.common.events.LearningEvents.LearningActivityDaysRequested;
import com.masterenglish.api.common.events.LearningEvents.LessonProgressSummaryRequested;
import com.masterenglish.api.common.events.LearningEvents.UserDailyStudySecondsRequested;
import com.masterenglish.api.common.events.LearningEvents.VocabularyProgressRequested;
import com.masterenglish.api.common.events.LearningEvents.VocabularyProgressView;
import com.masterenglish.api.modules.learning.dto.response.LearningProgressResponse;
import com.masterenglish.api.modules.learning.service.DueQueueService;
import com.masterenglish.api.modules.learning.service.LearningProgressService;
import com.masterenglish.api.modules.learning.service.LessonProgressService;
import com.masterenglish.api.modules.learning.service.StudySessionService;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class LearningEventHandler {
    private final DueQueueService dueQueue;
    private final LearningProgressService progressService;
    private final LessonProgressService lessonProgressService;
    private final StudySessionService studySessions;

    LearningEventHandler(
            DueQueueService dueQueue,
            LearningProgressService progressService,
            LessonProgressService lessonProgressService,
            StudySessionService studySessions) {
        this.dueQueue = dueQueue;
        this.progressService = progressService;
        this.lessonProgressService = lessonProgressService;
        this.studySessions = studySessions;
    }

    @EventListener
    void on(DueCardsCountRequested event) {
        event.reply().complete(dueQueue.countDueCards(event.userIds(), event.asOf()));
    }

    @EventListener
    void on(VocabularyProgressRequested event) {
        LearningProgressResponse progress =
                progressService.getProgress(event.userId(), event.asOf());
        event.reply()
                .complete(
                        new VocabularyProgressView(
                                progress.studiedSenses(),
                                progress.masteredSenses(),
                                progress.dueNow(),
                                progress.savedSenses(),
                                progress.totalReviews(),
                                progress.reviewsLast7Days()));
    }

    @EventListener
    void on(LessonProgressSummaryRequested event) {
        event.reply().complete(lessonProgressService.getSummary(event.userId()));
    }

    @EventListener
    void on(DueDecksCountRequested event) {
        event.reply().complete(progressService.countDueCardsByDeck(event.userId(), event.asOf()));
    }

    @EventListener
    @Transactional(readOnly = true)
    void on(LearningActivityDaysRequested event) {
        Set<LocalDate> days =
                new HashSet<>(
                        progressService.findReviewActivityLocalDays(
                                event.userId(), event.timezone()));
        days.addAll(lessonProgressService.findActivityLocalDays(event.userId(), event.timezone()));
        days.addAll(studySessions.findActivityLocalDays(event.userId()));
        event.reply().complete(Set.copyOf(days));
    }

    @EventListener
    void on(DailyStudySecondsRequested event) {
        event.reply().complete(studySessions.sumDailyStudySeconds(event.userLocalDays()));
    }

    @EventListener
    void on(UserDailyStudySecondsRequested event) {
        event.reply()
                .complete(studySessions.getDailyStudySeconds(event.userId(), event.localDay()));
    }
}
