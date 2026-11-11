package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.modules.learning.dto.ReviewRequest;
import com.masterenglish.api.modules.learning.dto.ReviewResponse;
import java.util.UUID;

public interface ReviewService {
    ReviewResponse reviewCard(UUID userId, ReviewRequest request);
}
