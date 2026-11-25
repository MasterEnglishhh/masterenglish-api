package com.masterenglish.api.common.events;

import java.util.List;
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

    /** Every visible topic and every visible lesson, for learner progress aggregation. */
    public VisibleCatalog visibleCatalog() {
        return events.request(reply -> new VisibleCatalogRequested(reply));
    }

    public record PublishedLessonRef(UUID lessonId, UUID revisionId) {}

    public record PublishedLessonRequested(
            UUID lessonId, EventReply<Optional<PublishedLessonRef>> reply) {}

    public record TopicVisibilityRequested(UUID topicId, EventReply<Boolean> reply) {}

    /** Visible topics and lessons; a lesson is listed only when its topic is visible too. */
    public record VisibleCatalog(List<VisibleTopic> topics, List<VisibleLesson> lessons) {}

    public record VisibleTopic(UUID topicId, String name, int sortOrder) {}

    public record VisibleLesson(UUID lessonId, UUID topicId, UUID revisionId) {}

    public record VisibleCatalogRequested(EventReply<VisibleCatalog> reply) {}
}
