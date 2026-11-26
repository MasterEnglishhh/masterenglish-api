package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.events.IdentityEvents.DailyGoalTarget;
import com.masterenglish.api.common.events.IdentityEvents.LearningGoalView;
import com.masterenglish.api.modules.identity.dto.request.ChangePasswordRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateProfileRequest;
import com.masterenglish.api.modules.identity.dto.response.LearningGoalResponse;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserService {

    UserProfileResponse findProfileById(UUID id);

    UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request);

    void changePassword(UUID userId, ChangePasswordRequest request);

    LearningGoalResponse findGoalByUserId(UUID userId);

    Optional<LearningGoalView> findOptionalGoalByUserId(UUID userId);

    LearningGoalResponse updateGoal(UUID userId, UpdateLearningGoalRequest request);

    /** Emails of ACTIVE users among the given IDs, for reminder delivery. */
    Map<UUID, String> findActiveEmails(Set<UUID> userIds);

    /**
     * Verified ACTIVE LEARNER emails (excluding CONTENT_ADMIN and SYSTEM_ADMIN) for content
     * announcements.
     */
    Map<UUID, String> findActiveLearnerEmails(Set<UUID> userIds);

    /** Active users among the given IDs that have a daily learning goal configured. */
    Map<UUID, DailyGoalTarget> findDailyGoalTargets(Set<UUID> userIds);
}
