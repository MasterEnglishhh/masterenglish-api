package com.masterenglish.api.common.events;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Synchronous queries answered by the grammar module. */
@Component
public class GrammarEvents {

    private final ModuleEvents events;

    GrammarEvents(ModuleEvents events) {
        this.events = events;
    }

    /** The lesson's currently published revision, or empty when the lesson is not visible. */
    public Optional<PublishedLessonRef> findPublishedLesson(UUID lessonId) {
        return events.request(reply -> new PublishedLessonRequested(lessonId, reply));
    }

    /** True when the topic is published and not archived. */
    public boolean isVisibleTopic(UUID topicId) {
        return events.request(reply -> new TopicVisibilityRequested(topicId, reply));
    }

    public record PublishedLessonRef(UUID lessonId, UUID revisionId) {}

    public record PublishedLessonRequested(
            UUID lessonId, EventReply<Optional<PublishedLessonRef>> reply) {}

    public record TopicVisibilityRequested(UUID topicId, EventReply<Boolean> reply) {}
}
