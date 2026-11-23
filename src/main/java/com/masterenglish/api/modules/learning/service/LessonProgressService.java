package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.modules.learning.dto.response.LessonProgressResponse;
import java.util.List;
import java.util.UUID;

public interface LessonProgressService {

    /**
     * Sets the learner's progress on a visible grammar lesson. COMPLETED pins the published
     * revision; a completed record is never rewritten by later calls.
     */
    LessonProgressResponse updateProgress(UUID userId, UUID lessonId, String state);

    List<LessonProgressResponse> getProgress(UUID userId);
}
