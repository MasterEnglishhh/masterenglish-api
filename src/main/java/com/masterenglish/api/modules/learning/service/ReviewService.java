package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.modules.learning.dto.request.ReviewRequest;
import com.masterenglish.api.modules.learning.dto.response.ReviewResponse;
import java.util.UUID;

public interface ReviewService {
    ReviewResponse reviewCard(UUID userId, ReviewRequest request);
}
