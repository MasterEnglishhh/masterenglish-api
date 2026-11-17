package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.learning.dto.LearningProgressResponse;
import com.masterenglish.api.modules.learning.dto.ReviewHistoryItemDto;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface LearningProgressService {

    PageResponse<ReviewHistoryItemDto> getReviewHistory(UUID userId, Pageable pageable);

    LearningProgressResponse getProgress(UUID userId);
}
