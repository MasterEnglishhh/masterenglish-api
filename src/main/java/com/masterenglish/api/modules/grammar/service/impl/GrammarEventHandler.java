package com.masterenglish.api.modules.grammar.service.impl;

import com.masterenglish.api.common.events.GrammarEvents.PublishedLessonRequested;
import com.masterenglish.api.common.events.GrammarEvents.TopicVisibilityRequested;
import com.masterenglish.api.modules.grammar.service.GrammarQueryService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class GrammarEventHandler {

    private final GrammarQueryService grammar;

    GrammarEventHandler(GrammarQueryService grammar) {
        this.grammar = grammar;
    }

    @EventListener
    void on(PublishedLessonRequested event) {
        event.reply().complete(grammar.findPublishedLesson(event.lessonId()));
    }

    @EventListener
    void on(TopicVisibilityRequested event) {
        event.reply().complete(grammar.isVisibleTopic(event.topicId()));
    }
}
