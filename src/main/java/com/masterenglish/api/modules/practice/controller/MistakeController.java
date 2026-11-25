package com.masterenglish.api.modules.practice.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.practice.dto.response.MistakeResponse;
import com.masterenglish.api.modules.practice.service.MistakeService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The learner's mistake notebook. Practice from these mistakes is an attempt with MISTAKES. */
@RestController
@RequestMapping("/api/v1/mistakes")
@PreAuthorize("hasRole('LEARNER')")
public class MistakeController {

    private final MistakeService mistakes;

    public MistakeController(MistakeService mistakes) {
        this.mistakes = mistakes;
    }

    @GetMapping
    public PageResponse<MistakeResponse> list(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) UUID topicId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return mistakes.list(currentUser.getId(), state, topicId, page, size);
    }
}
