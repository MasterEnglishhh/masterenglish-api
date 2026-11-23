package com.masterenglish.api.modules.practice.controller;

import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.practice.dto.request.MixedAttemptRequest;
import com.masterenglish.api.modules.practice.dto.request.StartAttemptRequest;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.ExerciseSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.StartAttemptResult;
import com.masterenglish.api.modules.practice.dto.response.TopicStatisticsResponse;
import com.masterenglish.api.modules.practice.service.PracticeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Grammar entry points into the shared practice engine. */
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

    @PostMapping("/topics/{topicId}/attempts")
    public ResponseEntity<AttemptResponse> startTopic(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable UUID topicId,
            @Valid @RequestBody StartAttemptRequest request) {
        StartAttemptResult result =
                practice.startTopicAttempt(
                        currentUser.getId(), topicId, request.requestId(), request.questionCount());
        return start(result);
    }

    @PostMapping("/attempts/mixed")
    public ResponseEntity<AttemptResponse> startMixed(
            @CurrentUser AuthenticatedUser currentUser,
            @Valid @RequestBody MixedAttemptRequest request) {
        StartAttemptResult result =
                practice.startMixedAttempt(
                        currentUser.getId(),
                        request.requestId(),
                        request.topicIds(),
                        request.questionCount());
        return start(result);
    }

    @GetMapping("/statistics/topics")
    public List<TopicStatisticsResponse> topicStatistics(
            @CurrentUser AuthenticatedUser currentUser) {
        return practice.topicStatistics(currentUser.getId());
    }

    /** 201 for a new attempt, 200 when the same request was replayed. */
    private static ResponseEntity<AttemptResponse> start(StartAttemptResult result) {
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.attempt());
    }
}
