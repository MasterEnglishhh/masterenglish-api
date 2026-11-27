package com.masterenglish.api.modules.learning.controller;

import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.learning.dto.request.CloseStudySessionRequest;
import com.masterenglish.api.modules.learning.dto.request.StartStudySessionRequest;
import com.masterenglish.api.modules.learning.dto.request.StudyHeartbeatRequest;
import com.masterenglish.api.modules.learning.dto.response.StudySessionResponse;
import com.masterenglish.api.modules.learning.service.StudySessionService;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/study-sessions")
@PreAuthorize("hasRole('LEARNER')")
public class StudySessionController {

    private final StudySessionService studySessionService;

    public StudySessionController(StudySessionService studySessionService) {
        this.studySessionService = Objects.requireNonNull(studySessionService);
    }

    @PostMapping
    public ResponseEntity<StudySessionResponse> startSession(
            @CurrentUser AuthenticatedUser currentUser,
            @Valid @RequestBody StartStudySessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(studySessionService.startSession(currentUser.getId(), request));
    }

    @PostMapping("/{sessionId}/heartbeat")
    public StudySessionResponse heartbeat(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable UUID sessionId,
            @Valid @RequestBody StudyHeartbeatRequest request) {
        return studySessionService.heartbeat(currentUser.getId(), sessionId, request);
    }

    @PostMapping("/{sessionId}/close")
    public StudySessionResponse closeSession(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable UUID sessionId,
            @Valid @RequestBody CloseStudySessionRequest request) {
        return studySessionService.closeSession(currentUser.getId(), sessionId, request);
    }
}
