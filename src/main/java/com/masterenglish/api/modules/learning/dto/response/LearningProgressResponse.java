package com.masterenglish.api.modules.learning.dto.response;

public record LearningProgressResponse(
        long studiedSenses,
        long masteredSenses,
        long dueNow,
        long savedSenses,
        long totalReviews,
        long reviewsLast7Days) {}
