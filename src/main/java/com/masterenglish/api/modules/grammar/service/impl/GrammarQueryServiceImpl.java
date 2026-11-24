package com.masterenglish.api.modules.grammar.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents.PublishedLessonRef;
import com.masterenglish.api.common.events.GrammarEvents.VisibleLessonView;
import com.masterenglish.api.common.events.GrammarEvents.VisibleTopicView;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonSummaryResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarTopicResponse;
import com.masterenglish.api.modules.grammar.entity.GrammarLesson;
import com.masterenglish.api.modules.grammar.repository.GrammarLessonRepository;
import com.masterenglish.api.modules.grammar.repository.GrammarTopicRepository;
import com.masterenglish.api.modules.grammar.service.GrammarQueryService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    // shortcut: sorts and pages the whole visible catalog in memory; move paging into SQL once
    // topics reach a few hundred.
    @Override
    @Transactional(readOnly = true)
    public PageResponse<GrammarTopicResponse> listTopics(Pageable pageable) {
        List<GrammarTopicResponse> all =
                topics.findVisibleTopics().stream()
                        .map(
                                t ->
                                        new GrammarTopicResponse(
                                                t.getId(),
                                                t.getSlug(),
                                                t.getName(),
                                                t.getDescription(),
                                                t.getDifficulty(),
                                                t.getSortOrder()))
                        .sorted(topicOrder(pageable.getSort()))
                        .toList();
        int from =
                (int)
                        Math.min(
                                (long) pageable.getPageNumber() * pageable.getPageSize(),
                                all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return PageResponse.from(new PageImpl<>(all.subList(from, to), pageable, all.size()));
    }

    /** Sort keys come from the controller's allowlist; id is always the final tie-breaker. */
    private static Comparator<GrammarTopicResponse> topicOrder(Sort sort) {
        Comparator<GrammarTopicResponse> order = (a, b) -> 0;
        for (Sort.Order o : sort) {
            Comparator<GrammarTopicResponse> field =
                    switch (o.getProperty()) {
                        case "sortOrder" ->
                                Comparator.comparingInt(GrammarTopicResponse::sortOrder);
                        case "name" -> Comparator.comparing(GrammarTopicResponse::name);
                        case "id" -> Comparator.comparing(GrammarTopicResponse::id);
                        default -> throw new IllegalArgumentException("Unsupported sort property.");
                    };
            order = order.thenComparing(o.isDescending() ? field.reversed() : field);
        }
        return order.thenComparing(Comparator.comparing(GrammarTopicResponse::id));
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
