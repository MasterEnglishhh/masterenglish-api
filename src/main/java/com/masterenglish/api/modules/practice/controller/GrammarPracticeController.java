package com.masterenglish.api.modules.practice.controller;

import com.masterenglish.api.modules.practice.dto.response.ExerciseSummaryResponse;
import com.masterenglish.api.modules.practice.service.PracticeService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Grammar catalogue entry point into the shared practice engine. Attempts live under /attempts. */
@RestController
@RequestMapping("/api/v1/grammar")
@PreAuthorize("hasRole('LEARNER')")
public class GrammarPracticeController {

    private final PracticeService practice;

    public GrammarPracticeController(PracticeService practice) {
        this.practice = practice;
    }

    @GetMapping("/topics/{topicId}/exercises")
    public ExerciseSummaryResponse exercises(@PathVariable UUID topicId) {
        return practice.exercises(topicId);
    }
}
