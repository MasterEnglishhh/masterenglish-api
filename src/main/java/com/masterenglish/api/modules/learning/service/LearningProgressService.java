package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.LearningEvents.DueDeckCountView;
import com.masterenglish.api.modules.learning.dto.response.LearningProgressResponse;
import com.masterenglish.api.modules.learning.dto.response.ReviewHistoryItemDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface LearningProgressService {

    PageResponse<ReviewHistoryItemDto> getReviewHistory(UUID userId, Pageable pageable);

    LearningProgressResponse getProgress(UUID userId);

    LearningProgressResponse getProgress(UUID userId, Instant asOf);

    List<DueDeckCountView> countDueCardsByDeck(UUID userId, Instant asOf);

    Set<LocalDate> findReviewActivityLocalDays(UUID userId, String timezone);
}
