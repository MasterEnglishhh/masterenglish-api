package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.modules.learning.dto.response.DueQueueResponse;
import java.util.UUID;

public interface DueQueueService {
    DueQueueResponse getDueQueue(UUID userId, UUID deckId, int limit);
}
