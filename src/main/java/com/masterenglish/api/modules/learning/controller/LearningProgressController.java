package com.masterenglish.api.modules.learning.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.learning.dto.response.LearningProgressResponse;
import com.masterenglish.api.modules.learning.dto.response.ReviewHistoryItemDto;
import com.masterenglish.api.modules.learning.service.LearningProgressService;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabulary")
@PreAuthorize("hasRole('LEARNER')")
public class LearningProgressController {

    private final LearningProgressService progressService;

    public LearningProgressController(LearningProgressService progressService) {
        this.progressService = Objects.requireNonNull(progressService);
    }

    @GetMapping("/reviews/history")
    public PageResponse<ReviewHistoryItemDto> getReviewHistory(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return progressService.getReviewHistory(
                currentUser.getId(), PageRequestUtils.of(page, size));
    }

    @GetMapping("/progress")
    public LearningProgressResponse getProgress(@CurrentUser AuthenticatedUser currentUser) {
        return progressService.getProgress(currentUser.getId());
    }
}
