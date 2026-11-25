package com.masterenglish.api.modules.practice.controller;

import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.practice.dto.request.AnswerRequest;
import com.masterenglish.api.modules.practice.dto.request.StartAttemptRequest;
import com.masterenglish.api.modules.practice.dto.response.AnswerResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.StartAttemptResult;
import com.masterenglish.api.modules.practice.dto.response.StatisticsSummaryResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attempts")
@PreAuthorize("hasRole('LEARNER')")
public class AttemptController {

    private final PracticeService practice;

    public AttemptController(PracticeService practice) {
        this.practice = practice;
    }

    /** 201 for a new attempt; 200 when the same Idempotency-Key and body were replayed. */
    @PostMapping
    public ResponseEntity<AttemptResponse> start(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody StartAttemptRequest request) {
        StartAttemptRequest.Source source = request.source();
        StartAttemptResult result =
                practice.start(
                        currentUser.getId(),
                        idempotencyKey,
                        source.type(),
                        source.topicId(),
                        source.topicIds(),
                        source.mistakeIds(),
                        request.questionCount());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.attempt());
    }

    /** Resume: reads the sealed snapshot; settles time-outs and abandons on the way. */
    @GetMapping("/{attemptId}")
    public AttemptResponse getAttempt(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID attemptId) {
        return practice.getAttempt(currentUser.getId(), attemptId);
    }

    @PutMapping("/{attemptId}/answers/{snapshotId}")
    public AnswerResponse saveAnswer(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable UUID attemptId,
            @PathVariable UUID snapshotId,
            @Valid @RequestBody AnswerRequest request) {
        return practice.saveAnswer(
                currentUser.getId(),
                attemptId,
                snapshotId,
                request.selectedOptionId(),
                request.expectedVersion());
    }

    @PostMapping("/{attemptId}/submit")
    public AttemptResponse submit(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID attemptId) {
        return practice.submit(currentUser.getId(), attemptId);
    }

    /** Per-topic allocation view with the weak-topic flag and the last-30-days window. */
    @GetMapping("/statistics/topics")
    public List<TopicStatisticsResponse> topicStatistics(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam String category,
            @RequestParam String view) {
        return practice.topicStatistics(currentUser.getId(), category, view);
    }

    /** Overall totals; every question counts once, whatever topics it belongs to. */
    @GetMapping("/statistics/summary")
    public StatisticsSummaryResponse statisticsSummary(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam String category,
            @RequestParam String view) {
        return practice.statisticsSummary(currentUser.getId(), category, view);
    }
}
