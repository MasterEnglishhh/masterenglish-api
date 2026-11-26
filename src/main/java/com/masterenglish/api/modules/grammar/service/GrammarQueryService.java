package com.masterenglish.api.modules.grammar.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.GrammarEvents.PublishedLessonRef;
import com.masterenglish.api.common.events.GrammarEvents.VisibleCatalog;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonSummaryResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarTopicResponse;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface GrammarQueryService {

    /** {@code difficulty} and {@code q} are already validated; null means no filter. */
    PageResponse<GrammarTopicResponse> listTopics(String difficulty, String q, Pageable pageable);

    VisibleCatalog visibleCatalog();

    List<GrammarLessonSummaryResponse> listLessons(UUID topicId);

    GrammarLessonResponse getLesson(UUID lessonId);

    Optional<PublishedLessonRef> findPublishedLesson(UUID lessonId);

    boolean isVisibleTopic(UUID topicId);

    Optional<GrammarEvents.PublishedTopicRef> findPublishedTopic(UUID topicId);

    List<GrammarEvents.PublishedTopicRef> lockPublishedTopics(Collection<UUID> topicIds);
}
