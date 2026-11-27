package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.modules.learning.dto.response.DueQueueResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface DueQueueService {
    DueQueueResponse getDueQueue(UUID userId, UUID deckId, int limit);

    /** Due card counts per learner as of the given instant; learners with none are absent. */
    Map<UUID, Long> countDueCards(Set<UUID> userIds, Instant asOf);
}
