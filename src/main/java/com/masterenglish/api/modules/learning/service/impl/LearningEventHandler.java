package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.events.LearningEvents.DailyStudySecondsRequested;
import com.masterenglish.api.common.events.LearningEvents.DueCardsCountRequested;
import com.masterenglish.api.common.events.LearningEvents.UserDailyStudySecondsRequested;
import com.masterenglish.api.modules.learning.service.DueQueueService;
import com.masterenglish.api.modules.learning.service.StudySessionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class LearningEventHandler {
    private final DueQueueService dueQueue;
    private final StudySessionService studySessions;

    LearningEventHandler(DueQueueService dueQueue, StudySessionService studySessions) {
        this.dueQueue = dueQueue;
        this.studySessions = studySessions;
    }

    @EventListener
    void on(DueCardsCountRequested event) {
        event.reply().complete(dueQueue.countDueCards(event.userIds(), event.asOf()));
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
