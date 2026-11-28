package com.masterenglish.api.modules.practice.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.practice.dto.response.MistakeResponse;
import java.util.UUID;

public interface MistakeService {

    /**
     * The learner's mistakes, newest first; {@code state} and {@code topicId} are optional filters.
     */
    PageResponse<MistakeResponse> list(
            UUID userId, String state, UUID topicId, Integer page, Integer size);
}
