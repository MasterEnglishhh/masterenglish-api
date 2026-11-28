package com.masterenglish.api.modules.admin.dto.request;

import java.util.Map;
import java.util.UUID;

/**
 * Mọi trường đều tùy chọn, như {@link SaveGrammarTopicDraftRequest}. {@code body} là Map, không
 * phải JsonNode: converter Jackson của Spring MVC không đọc được kiểu JsonNode của Jackson 2.
 */
public record SaveGrammarLessonDraftRequest(
        UUID topicId,
        String title,
        Integer position,
        Integer estimatedMinutes,
        Map<String, Object> body,
        UUID sourceId,
        String changeSummary) {}
