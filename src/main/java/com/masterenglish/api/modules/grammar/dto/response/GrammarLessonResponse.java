package com.masterenglish.api.modules.grammar.dto.response;

import java.util.Map;
import java.util.UUID;

/** The body is a plain map so the web layer's Jackson serializes it as JSON, not as a tree node. */
public record GrammarLessonResponse(
        UUID id, UUID topicId, String title, Map<String, Object> body, Short estimatedMinutes) {}
