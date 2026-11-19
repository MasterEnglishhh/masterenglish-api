package com.masterenglish.api.modules.grammar.controller;

import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonSummaryResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarTopicResponse;
import com.masterenglish.api.modules.grammar.service.GrammarQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/grammar")
@PreAuthorize("hasRole('LEARNER')")
public class GrammarController {

    private final GrammarQueryService queryService;

    public GrammarController(GrammarQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/topics")
    public List<GrammarTopicResponse> listTopics() {
        return queryService.listTopics();
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
