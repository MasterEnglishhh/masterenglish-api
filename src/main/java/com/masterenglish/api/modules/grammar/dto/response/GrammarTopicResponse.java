package com.masterenglish.api.modules.grammar.dto.response;

import java.util.UUID;

public record GrammarTopicResponse(
        UUID id, String slug, String name, String description, String difficulty, int sortOrder) {}
