package com.masterenglish.api.modules.grammar.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonSummaryResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarTopicResponse;
import com.masterenglish.api.modules.grammar.service.GrammarQueryService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/grammar")
@PreAuthorize("hasRole('LEARNER')")
public class GrammarController {

    private static final Set<String> TOPIC_SORT_FIELDS = Set.of("sortOrder", "name", "id");

    private final GrammarQueryService queryService;

    public GrammarController(GrammarQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/topics")
    public PageResponse<GrammarTopicResponse> listTopics(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection) {
        Pageable pageable =
                PageRequestUtils.of(
                        page,
                        size,
                        sortBy != null && !sortBy.isBlank() ? sortBy : "sortOrder",
                        sortDirection != null && !sortDirection.isBlank() ? sortDirection : "asc",
                        TOPIC_SORT_FIELDS);
        return queryService.listTopics(pageable);
    }

    @GetMapping("/topics/{topicId}/lessons")
    public List<GrammarLessonSummaryResponse> listLessons(@PathVariable UUID topicId) {
        return queryService.listLessons(topicId);
    }

    @GetMapping("/lessons/{lessonId}")
    public GrammarLessonResponse getLesson(@PathVariable UUID lessonId) {
        return queryService.getLesson(lessonId);
    }
}
