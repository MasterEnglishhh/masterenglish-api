package com.masterenglish.api.modules.grammar.service;

import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarLessonSummaryResponse;
import com.masterenglish.api.modules.grammar.dto.response.GrammarTopicResponse;
import java.util.List;
import java.util.UUID;

public interface GrammarQueryService {

    List<GrammarTopicResponse> listTopics();

    List<GrammarLessonSummaryResponse> listLessons(UUID topicId);

    GrammarLessonResponse getLesson(UUID lessonId);
}
