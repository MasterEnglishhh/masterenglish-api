package com.masterenglish.api.modules.grammar.dto.response;

import java.util.UUID;

public record GrammarLessonSummaryResponse(
        UUID id, String title, int sortOrder, Short estimatedMinutes) {}
