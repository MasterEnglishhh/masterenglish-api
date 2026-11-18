package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.request.ChangePasswordRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateProfileRequest;
import com.masterenglish.api.modules.identity.dto.response.LearningGoalResponse;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.LearningGoal;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.LearningGoalRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.BreachedPasswordChecker;
import com.masterenglish.api.modules.identity.service.RateLimiterService;
import com.masterenglish.api.modules.identity.service.RegistrationPasswordPolicy;
import com.masterenglish.api.modules.identity.service.UserProfileMapper;
import com.masterenglish.api.modules.identity.service.UserService;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final LearningGoalRepository goalRepository;
    private final AuthSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationPasswordPolicy passwordPolicy;
    private final BreachedPasswordChecker breachedPasswordChecker;
    private final RateLimiterService rateLimiterService;
    private final TransactionTemplate tx;

    public UserServiceImpl(
            UserRepository userRepository,
            LearningGoalRepository goalRepository,
            AuthSessionRepository sessionRepository,
            PasswordEncoder passwordEncoder,
            RegistrationPasswordPolicy passwordPolicy,
            BreachedPasswordChecker breachedPasswordChecker,
            RateLimiterService rateLimiterService,
            PlatformTransactionManager transactionManager) {
        this.rateLimiterService =
                Objects.requireNonNull(rateLimiterService, "rateLimiterService must not be null");
        this.tx = new TransactionTemplate(transactionManager);
        this.sessionRepository =
                Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.passwordEncoder =
                Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.passwordPolicy =
                Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.breachedPasswordChecker =
                Objects.requireNonNull(
                        breachedPasswordChecker, "breachedPasswordChecker must not be null");
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.goalRepository =
                Objects.requireNonNull(goalRepository, "goalRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse findProfileById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return userRepository
                .findById(id)
                .map(UserProfileMapper::from)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        var user = userRepository.findById(userId).orElseThrow(UserServiceImpl::userNotFound);
        // Avatar changes only through the media flow (M08), so keep the current one.
        user.updateProfile(
                request.firstName(), request.lastName(), request.phone(), user.getAvatarMediaId());
        return UserProfileMapper.from(user);
    }

    @Override
    // BCrypt and the external breach check run outside any transaction so they never hold a
    // DB connection; only the final write is transactional (same approach as password reset).
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        var user = userRepository.findById(userId).orElseThrow(UserServiceImpl::userNotFound);
        String email = user.getEmail();
        String currentHash = user.getPasswordHash();
        // Shares the login failure counter, so guessing here and at /login counts together.
        rateLimiterService.checkLoginRateLimit(email, null);
        // Login compares the NFC form, so the current password must be compared the same way.
        String current = Normalizer.normalize(request.currentPassword(), Normalizer.Form.NFC);
        if (currentHash == null || !passwordEncoder.matches(current, currentHash)) {
            rateLimiterService.recordLoginFailure(email, null);
            throw new BusinessException(
                    ErrorCode.PASSWORD_REJECTED, "Mật khẩu hiện tại không đúng.");
        }
        String normalized = passwordPolicy.normalizeAndValidate(request.newPassword());
        if (passwordEncoder.matches(normalized, currentHash)) {
            throw new BusinessException(
                    ErrorCode.PASSWORD_REJECTED, "Vui lòng chọn mật khẩu khác mật khẩu hiện tại.");
        }
        breachedPasswordChecker.rejectIfBreached(normalized);
        String encoded = passwordEncoder.encode(normalized);
        tx.executeWithoutResult(
                status -> {
                    var fresh =
                            userRepository
                                    .findByIdForUpdate(userId)
                                    .orElseThrow(UserServiceImpl::userNotFound);
                    // The password was changed by another request while we checked this one.
                    if (!Objects.equals(fresh.getPasswordHash(), currentHash)) {
                        throw new BusinessException(
                                ErrorCode.CONFLICT, "Mật khẩu vừa được đổi. Vui lòng thử lại.");
                    }
                    // changePassword bumps authVersion, which also kills outstanding access tokens.
                    fresh.changePassword(encoded);
                    sessionRepository.revokeAllActiveSessions(
                            userId, RevokeReason.PASSWORD_CHANGE, Instant.now());
                });
        try {
            rateLimiterService.resetLoginFailure(email);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.SERVICE_UNAVAILABLE) {
                throw exception;
            }
            log.warn("Could not clear the login rate limit after a committed password change");
        }
    }

    private static BusinessException userNotFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found");
    }

    @Override
    @Transactional(readOnly = true)
    public LearningGoalResponse findGoalByUserId(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return goalRepository
                .findById(userId)
                .map(UserServiceImpl::goalResponse)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND,
                                        "Chưa thiết lập mục tiêu học tập."));
    }

    @Override
    @Transactional
    public LearningGoalResponse updateGoal(UUID userId, UpdateLearningGoalRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (request.targetScore() % 5 != 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Điểm mục tiêu phải chia hết cho 5 (ví dụ: 500, 650, 750).");
        }
        if (request.targetDate() != null && request.targetDate().isBefore(LocalDate.now())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Ngày mục tiêu phải từ hôm nay trở đi.");
        }

        // Lock the user even when they do not yet have a goal.
        userRepository.findByIdForUpdate(userId).orElseThrow(UserServiceImpl::userNotFound);

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

        return goalResponse(goalRepository.save(goal));
    }

    private static LearningGoalResponse goalResponse(LearningGoal goal) {
        return new LearningGoalResponse(
                goal.getTargetScore(),
                goal.getDailyMinutes(),
                goal.getTargetDate(),
                goal.getUpdatedAt());
    }
}
