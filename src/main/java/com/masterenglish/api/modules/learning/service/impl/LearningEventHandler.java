package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.events.LearningEvents.DueCardsCountRequested;
import com.masterenglish.api.modules.learning.service.DueQueueService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class LearningEventHandler {
    private final DueQueueService dueQueue;

    LearningEventHandler(DueQueueService dueQueue) {
        this.dueQueue = dueQueue;
    }

    @EventListener
    void on(DueCardsCountRequested event) {
        event.reply().complete(dueQueue.countDueCards(event.userIds(), event.asOf()));
    }
}
