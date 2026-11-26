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

    public List<VisibleTopicView> listVisibleTopics() {
        return events.request(VisibleTopicsRequested::new);
    }

    public List<VisibleLessonView> listVisibleLessons() {
        return events.request(VisibleLessonsRequested::new);
    }

    public record PublishedLessonRef(UUID lessonId, UUID revisionId) {}

    public record VisibleTopicView(
            UUID id, String slug, String name, String difficulty, int sortOrder) {}

    public record VisibleLessonView(
            UUID id, UUID topicId, String title, int sortOrder, Short estimatedMinutes) {}

    public record PublishedLessonRequested(
            UUID lessonId, EventReply<Optional<PublishedLessonRef>> reply) {}

    public record TopicVisibilityRequested(UUID topicId, EventReply<Boolean> reply) {}

    public record VisibleTopicsRequested(EventReply<List<VisibleTopicView>> reply) {}

    public record VisibleLessonsRequested(EventReply<List<VisibleLessonView>> reply) {}
}
