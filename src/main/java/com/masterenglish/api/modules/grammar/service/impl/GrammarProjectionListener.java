package com.masterenglish.api.modules.grammar.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.modules.grammar.entity.GrammarLesson;
import com.masterenglish.api.modules.grammar.entity.GrammarTopic;
import com.masterenglish.api.modules.grammar.repository.GrammarLessonRepository;
import com.masterenglish.api.modules.grammar.repository.GrammarTopicRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Copies a published grammar revision into the read tables, inside the publication transaction. */
@Component
public class GrammarProjectionListener {

    private static final String TOPIC = GrammarSnapshotValidator.TOPIC;
    private static final String LESSON = GrammarSnapshotValidator.LESSON;

    private final GrammarTopicRepository topics;
    private final GrammarLessonRepository lessons;
    private final ObjectMapper mapper;
    private final Clock clock;

    public GrammarProjectionListener(
            GrammarTopicRepository topics,
            GrammarLessonRepository lessons,
            ObjectMapper mapper,
            Clock clock) {
        this.topics = topics;
        this.lessons = lessons;
        this.mapper = mapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(RevisionPublished event) {
        String type = event.type();
        if (!TOPIC.equals(type) && !LESSON.equals(type)) {
            return;
        }
        JsonNode root = root(event.snapshot());
        Instant now = clock.instant();
        if (TOPIC.equals(type)) {
            projectTopic(event.itemId(), event.revisionId(), root, now);
        } else {
            projectLesson(event.itemId(), event.revisionId(), root, now);
        }
    }

    private void projectTopic(UUID topicId, UUID revisionId, JsonNode root, Instant now) {
        String slug = root.path("slug").asText();
        String name = root.path("name").asText();
        String description = textOrNull(root, "description");
        String difficulty = textOrNull(root, "difficulty");
        int sortOrder = root.path("sort_order").asInt(0);
        topics.findById(topicId)
                .ifPresentOrElse(
                        existing ->
                                existing.update(
                                        revisionId,
                                        slug,
                                        name,
                                        description,
                                        difficulty,
                                        sortOrder,
                                        now),
                        () ->
                                topics.save(
                                        new GrammarTopic(
                                                topicId,
                                                revisionId,
                                                slug,
                                                name,
                                                description,
                                                difficulty,
                                                sortOrder,
                                                now)));
    }

    private void projectLesson(UUID lessonId, UUID revisionId, JsonNode root, Instant now) {
        UUID topicId = UUID.fromString(root.path("topic_id").asText());
        String title = root.path("title").asText();
        int sortOrder = root.path("position").asInt(0);
        Short estimatedMinutes =
                root.hasNonNull("estimated_minutes")
                        ? (short) root.path("estimated_minutes").asInt()
                        : null;
        String body = root.path("body").toString();
        lessons.findById(lessonId)
                .ifPresentOrElse(
                        existing ->
                                existing.update(
                                        revisionId,
                                        topicId,
                                        title,
                                        body,
                                        sortOrder,
                                        estimatedMinutes,
                                        now),
                        () ->
                                lessons.save(
                                        new GrammarLesson(
                                                lessonId,
                                                revisionId,
                                                topicId,
                                                title,
                                                body,
                                                sortOrder,
                                                estimatedMinutes,
                                                now)));
    }

    private JsonNode root(String snapshot) {
        try {
            JsonNode root = mapper.readTree(snapshot);
            return root.has("data") ? root.path("data") : root;
        } catch (Exception ex) {
            throw new IllegalStateException("Published grammar snapshot is not valid JSON.", ex);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
