package com.masterenglish.api.modules.grammar.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
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
    private static final Set<String> DIFFICULTIES = Set.of("BASIC", "INTERMEDIATE", "ADVANCED");
    private static final int MAX_SEARCH_LENGTH = 200;

    private final GrammarQueryService queryService;

    public GrammarController(GrammarQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/topics")
    public PageResponse<GrammarTopicResponse> listTopics(
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String q,
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
        return queryService.listTopics(difficultyFilter(difficulty), searchText(q), pageable);
    }

    private static String difficultyFilter(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return null;
        }
        String value = difficulty.trim();
        if (!DIFFICULTIES.contains(value)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "difficulty must be BASIC, INTERMEDIATE or ADVANCED.");
        }
        return value;
    }

    private static String searchText(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String value = q.trim();
        if (value.length() > MAX_SEARCH_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "q must be at most 200 characters.");
        }
        return value;
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
