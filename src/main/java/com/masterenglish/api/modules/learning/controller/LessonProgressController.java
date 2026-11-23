package com.masterenglish.api.modules.learning.controller;

import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.learning.dto.request.LessonProgressRequest;
import com.masterenglish.api.modules.learning.dto.response.LessonProgressResponse;
import com.masterenglish.api.modules.learning.service.LessonProgressService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Grammar lesson progress is owned by learning; the URLs sit under the grammar catalogue. */
@RestController
@RequestMapping("/api/v1/grammar")
@PreAuthorize("hasRole('LEARNER')")
public class LessonProgressController {

    private final LessonProgressService progress;

    public LessonProgressController(LessonProgressService progress) {
        this.progress = progress;
    }

    @PutMapping("/lessons/{lessonId}/progress")
    public ResponseEntity<LessonProgressResponse> updateProgress(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable UUID lessonId,
            @Valid @RequestBody LessonProgressRequest request) {
        return ResponseEntity.ok(
                progress.updateProgress(currentUser.getId(), lessonId, request.state()));
    }

    @GetMapping("/progress")
    public List<LessonProgressResponse> getProgress(@CurrentUser AuthenticatedUser currentUser) {
        return progress.getProgress(currentUser.getId());
    }
}
