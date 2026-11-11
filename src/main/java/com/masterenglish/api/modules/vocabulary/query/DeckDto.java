package com.masterenglish.api.modules.vocabulary.query;

import java.time.Instant;
import java.util.UUID;

public record DeckDto(
        UUID id,
        String title,
        String description,
        String level,
        long cardCount,
        Instant createdAt) {}
