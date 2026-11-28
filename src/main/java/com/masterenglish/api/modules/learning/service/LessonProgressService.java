package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.events.LearningEvents.LessonProgressSummaryView;
import com.masterenglish.api.modules.learning.dto.response.LessonProgressResponse;
import com.masterenglish.api.modules.learning.dto.response.TopicProgressResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface LessonProgressService {

    /**
     * Sets the learner's progress on a visible grammar lesson. COMPLETED pins the published
     * revision; a completed record is never rewritten by later calls.
     */
    LessonProgressResponse updateProgress(UUID userId, UUID lessonId, String state);

    List<LessonProgressResponse> getProgress(UUID userId);

    /** Completed and total visible lessons per visible topic, with the update flag. */
    List<TopicProgressResponse> getTopicProgress(UUID userId);

    LessonProgressSummaryView getSummary(UUID userId);

    Set<LocalDate> findActivityLocalDays(UUID userId, String timezone);
}
