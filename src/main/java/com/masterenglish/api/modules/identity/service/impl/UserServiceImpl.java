package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
import com.masterenglish.api.modules.identity.dto.response.LearningGoalResponse;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.LearningGoal;
import com.masterenglish.api.modules.identity.repository.LearningGoalRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.UserService;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final LearningGoalRepository goalRepository;

    public UserServiceImpl(
            UserRepository userRepository,
            LearningGoalRepository goalRepository) {
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.goalRepository =
                Objects.requireNonNull(goalRepository, "goalRepository must not be null");
    }

    @Override
    public UserProfileResponse findProfileById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return userRepository
                .findById(id)
                .map(UserProfileResponse::from)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
    }

    @Override
    public LearningGoalResponse findGoalByUserId(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return goalRepository
                .findById(userId)
                .map(LearningGoalResponse::from)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "Chưa thiết lập mục tiêu học tập."));
    }

    @Override
    @Transactional
    public LearningGoalResponse updateGoal(UUID userId, UpdateLearningGoalRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (request.targetScore() % 5 != 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Điểm mục tiêu phải chia hết cho 5 (ví dụ: 500, 650, 750).");
        }
        if (request.targetDate() != null && request.targetDate().isBefore(LocalDate.now())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Ngày mục tiêu phải từ hôm nay trở đi.");
        }

        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found");
        }

        LearningGoal goal =
                goalRepository
                        .findById(userId)
                        .map(
                                existing -> {
                                    existing.update(
                                            request.targetScore(),
                                            request.dailyMinutes(),
                                            request.targetDate());
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new LearningGoal(
                                                userId,
                                                request.targetScore(),
                                                request.dailyMinutes(),
                                                request.targetDate()));

        return LearningGoalResponse.from(goalRepository.save(goal));
    }
}
