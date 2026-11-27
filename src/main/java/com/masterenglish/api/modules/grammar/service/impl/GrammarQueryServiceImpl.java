package com.masterenglish.api.modules.grammar.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.GrammarEvents.PublishedLessonRef;
import com.masterenglish.api.common.events.GrammarEvents.VisibleCatalog;
import com.masterenglish.api.common.events.GrammarEvents.VisibleLesson;
import com.masterenglish.api.common.events.GrammarEvents.VisibleLessonView;
import com.masterenglish.api.common.events.GrammarEvents.VisibleTopic;
import com.masterenglish.api.common.events.GrammarEvents.VisibleTopicView;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonSummaryResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarTopicResponse;
import com.masterenglish.api.modules.grammar.entity.GrammarLesson;
import com.masterenglish.api.modules.grammar.entity.GrammarTopic;
import com.masterenglish.api.modules.grammar.repository.GrammarLessonRepository;
import com.masterenglish.api.modules.grammar.repository.GrammarTopicRepository;
import com.masterenglish.api.modules.grammar.service.GrammarQueryService;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrammarQueryServiceImpl implements GrammarQueryService {

    private final GrammarTopicRepository topics;
    private final GrammarLessonRepository lessons;
    private final ObjectMapper mapper;

    public GrammarQueryServiceImpl(
            GrammarTopicRepository topics, GrammarLessonRepository lessons, ObjectMapper mapper) {
        this.topics = topics;
        this.lessons = lessons;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<GrammarTopicResponse> listTopics(
            String difficulty, String q, Pageable pageable) {
        String pattern = q == null ? null : containsPattern(q);
        Page<GrammarTopic> page = topics.searchVisible(difficulty, pattern, pageable);
        return PageResponse.from(page, GrammarQueryServiceImpl::topicResponse);
    }

    /** Case-insensitive "contains" pattern; '!' escapes the LIKE wildcards in the user's text. */
    static String containsPattern(String q) {
        String escaped = q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    private static GrammarTopicResponse topicResponse(GrammarTopic t) {
        return new GrammarTopicResponse(
                t.getId(),
                t.getSlug(),
                t.getName(),
                t.getDescription(),
                t.getDifficulty(),
                t.getSortOrder());
    }

    @Override
    @Transactional(readOnly = true)
    public VisibleCatalog visibleCatalog() {
        List<VisibleTopic> topicRefs =
                topics.findVisibleTopicRefs().stream()
                        .map(t -> new VisibleTopic(t.getId(), t.getName(), t.getSortOrder()))
                        .toList();
        List<VisibleLesson> lessonRefs =
                lessons.findAllVisibleRefs().stream()
                        .map(
                                l ->
                                        new VisibleLesson(
                                                l.getId(),
                                                l.getTopicId(),
                                                l.getPublishedRevisionId()))
                        .toList();
        return new VisibleCatalog(topicRefs, lessonRefs);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrammarLessonSummaryResponse> listLessons(UUID topicId) {
        if (topics.findVisibleTopicById(topicId).isEmpty()) {
            throw notFound("Grammar topic not found.");
        }
        return lessons.findVisibleByTopicId(topicId).stream()
                .map(
                        l ->
                                new GrammarLessonSummaryResponse(
                                        l.getId(),
                                        l.getTitle(),
                                        l.getSortOrder(),
                                        l.getEstimatedMinutes()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GrammarLessonResponse getLesson(UUID lessonId) {
        GrammarLesson lesson =
                lessons.findVisibleById(lessonId)
                        .orElseThrow(() -> notFound("Grammar lesson not found."));
        return new GrammarLessonResponse(
                lesson.getId(),
                lesson.getTopicId(),
                lesson.getTitle(),
                body(lesson),
                lesson.getEstimatedMinutes());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedLessonRef> findPublishedLesson(UUID lessonId) {
        return lessons.findVisibleById(lessonId)
                .map(l -> new PublishedLessonRef(l.getId(), l.getPublishedRevisionId()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isVisibleTopic(UUID topicId) {
        return topics.findVisibleTopicById(topicId).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GrammarEvents.PublishedTopicRef> findPublishedTopic(UUID topicId) {
        return topics.findVisibleTopicById(topicId)
                .map(
                        t ->
                                new GrammarEvents.PublishedTopicRef(
                                        t.getId(), t.getPublishedRevisionId()));
    }

    /** Runs in the caller's transaction; the row locks it takes are released only at its end. */
    @Override
    @Transactional
    public List<GrammarEvents.PublishedTopicRef> lockPublishedTopics(Collection<UUID> topicIds) {
        if (topicIds.isEmpty()) {
            return List.of();
        }
        return topics.lockVisibleTopics(topicIds).stream()
                .map(t -> new GrammarEvents.PublishedTopicRef(t.getTopicId(), t.getRevisionId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisibleTopicView> listAllVisibleTopics() {
        return topics.findVisibleTopics().stream()
                .map(
                        t ->
                                new VisibleTopicView(
                                        t.getId(),
                                        t.getSlug(),
                                        t.getName(),
                                        t.getDifficulty(),
                                        t.getSortOrder()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisibleLessonView> listAllVisibleLessons() {
        return lessons.findAllVisibleLessons().stream()
                .map(
                        l ->
                                new VisibleLessonView(
                                        l.getId(),
                                        l.getTopicId(),
                                        l.getTitle(),
                                        l.getSortOrder(),
                                        l.getEstimatedMinutes()))
                .toList();
    }

    private Map<String, Object> body(GrammarLesson lesson) {
        try {
            return mapper.readValue(lesson.getBody(), new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Stored lesson body is invalid.");
        }
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
