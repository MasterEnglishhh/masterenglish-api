package com.masterenglish.api.modules.grammar.service.impl;

import com.masterenglish.api.common.events.GrammarEvents.LockPublishedTopicsRequested;
import com.masterenglish.api.common.events.GrammarEvents.PublishedLessonRequested;
import com.masterenglish.api.common.events.GrammarEvents.PublishedTopicRequested;
import com.masterenglish.api.common.events.GrammarEvents.TopicVisibilityRequested;
import com.masterenglish.api.common.events.GrammarEvents.VisibleCatalogRequested;
import com.masterenglish.api.common.events.GrammarEvents.VisibleLessonsRequested;
import com.masterenglish.api.common.events.GrammarEvents.VisibleTopicsRequested;
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

    @EventListener
    void on(VisibleCatalogRequested event) {
        event.reply().complete(grammar.visibleCatalog());
    }

    @EventListener
    void on(VisibleTopicsRequested event) {
        event.reply().complete(grammar.listAllVisibleTopics());
    }

    @EventListener
    void on(VisibleLessonsRequested event) {
        event.reply().complete(grammar.listAllVisibleLessons());
    }

    @EventListener
    void on(PublishedTopicRequested event) {
        event.reply().complete(grammar.findPublishedTopic(event.topicId()));
    }

    @EventListener
    void on(LockPublishedTopicsRequested event) {
        event.reply().complete(grammar.lockPublishedTopics(event.topicIds()));
    }
}
