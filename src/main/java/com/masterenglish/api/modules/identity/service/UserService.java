package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
import com.masterenglish.api.modules.identity.dto.response.LearningGoalResponse;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import java.util.UUID;

public interface UserService {

    UserProfileResponse findProfileById(UUID id);

    LearningGoalResponse findGoalByUserId(UUID userId);

    LearningGoalResponse updateGoal(UUID userId, UpdateLearningGoalRequest request);
}
