package com.masterenglish.api.modules.practice.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.practice.dto.request.AnswerRequest;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptSummaryResponse;
import com.masterenglish.api.modules.practice.service.PracticeService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @GetMapping
    public PageResponse<AttemptSummaryResponse> listAttempts(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return practice.listAttempts(
                currentUser.getId(), status, sourceType, PageRequestUtils.of(page, size));
    }

    @GetMapping("/{attemptId}")
    public AttemptResponse getAttempt(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID attemptId) {
        return practice.getAttempt(currentUser.getId(), attemptId);
    }

    @PutMapping("/{attemptId}/answers/{position}")
    public AttemptResponse saveAnswer(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable UUID attemptId,
            @PathVariable int position,
            @Valid @RequestBody AnswerRequest request) {
        return practice.saveAnswer(currentUser.getId(), attemptId, position, request.optionId());
    }

    @PostMapping("/{attemptId}/submit")
    public AttemptResponse submit(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID attemptId) {
        return practice.submit(currentUser.getId(), attemptId);
    }
}
