package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.request.ChangePasswordRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
import com.masterenglish.api.modules.identity.dto.request.UpdateProfileRequest;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import com.masterenglish.api.modules.identity.dto.response.LearningGoalResponse;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.LearningGoal;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.LearningGoalRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.impl.UserServiceImpl;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserServiceTests {

    private UserRepository userRepository;
    private LearningGoalRepository goalRepository;
    private AuthSessionRepository sessionRepository;
    private PasswordEncoder passwordEncoder;
    private BreachedPasswordChecker breachedPasswordChecker;
    private RateLimiterService rateLimiterService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        goalRepository = mock(LearningGoalRepository.class);
        sessionRepository = mock(AuthSessionRepository.class);
        passwordEncoder = new BCryptPasswordEncoder(4);
        breachedPasswordChecker = mock(BreachedPasswordChecker.class);
        rateLimiterService = mock(RateLimiterService.class);
        userService =
                new UserServiceImpl(
                        userRepository,
                        goalRepository,
                        sessionRepository,
                        passwordEncoder,
                        new RegistrationPasswordPolicy(),
                        breachedPasswordChecker,
                        rateLimiterService,
                        mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("changePassword accepts current password typed in decomposed (non-NFC) form")
    void changePassword_decomposedCurrent_accepted() {
        UUID userId = UUID.randomUUID();
        String nfc = "mật khẩu tiếng việt dài";
        User user = userWithPassword(nfc);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        String nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD);

        userService.changePassword(userId, new ChangePasswordRequest(nfd, "new-password-1234567"));

        assertThat(passwordEncoder.matches("new-password-1234567", user.getPasswordHash())).isTrue();
        verify(rateLimiterService).resetLoginFailure("test@example.com");
    }

    @Test
    @DisplayName("changePassword stops before checking the password when the account is rate limited")
    void changePassword_rateLimited_rejectedEarly() {
        UUID userId = UUID.randomUUID();
        User user = userWithPassword("old-password-123456");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        BusinessException limited = new BusinessException(ErrorCode.RATE_LIMITED, "slow down");
        doThrow(limited).when(rateLimiterService).checkLoginRateLimit("test@example.com", null);

        assertThatThrownBy(
                        () ->
                                userService.changePassword(
                                        userId,
                                        new ChangePasswordRequest("old-password-123456", "new-password-1234567")))
                .isSameAs(limited);
        assertThat(passwordEncoder.matches("old-password-123456", user.getPasswordHash())).isTrue();
        verify(sessionRepository, never()).revokeAllActiveSessions(any(), any(), any());
    }

    private User userWithPassword(String rawPassword) {
        return new User("test@example.com", passwordEncoder.encode(rawPassword), "John", "Doe");
    }

    @Test
    @DisplayName("updateProfile changes names and phone")
    void updateProfile_updatesFields() {
        UUID userId = UUID.randomUUID();
        User user = new User("test@example.com", "hash", "John", "Doe");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserProfileResponse response =
                userService.updateProfile(userId, new UpdateProfileRequest("An", "Nguyen", " 0901234567 "));

        assertThat(response.firstName()).isEqualTo("An");
        assertThat(response.lastName()).isEqualTo("Nguyen");
        assertThat(response.phone()).isEqualTo("0901234567");
    }

    @Test
    @DisplayName("changePassword stores new hash, bumps authVersion and revokes sessions")
    void changePassword_valid_revokesSessions() {
        UUID userId = UUID.randomUUID();
        User user = userWithPassword("old-password-123456");
        int versionBefore = user.getAuthVersion();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.changePassword(
                userId, new ChangePasswordRequest("old-password-123456", "new-password-1234567"));

        assertThat(passwordEncoder.matches("new-password-1234567", user.getPasswordHash())).isTrue();
        assertThat(user.getAuthVersion()).isEqualTo(versionBefore + 1);
        verify(sessionRepository)
                .revokeAllActiveSessions(eq(userId), eq(RevokeReason.PASSWORD_CHANGE), any());
    }

    @Test
    @DisplayName("changePassword rejects wrong current password without touching sessions")
    void changePassword_wrongCurrent_rejected() {
        UUID userId = UUID.randomUUID();
        User user = userWithPassword("old-password-123456");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(
                        () ->
                                userService.changePassword(
                                        userId,
                                        new ChangePasswordRequest("wrong-password-123456", "new-password-1234567")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        be -> assertThat(be.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_REJECTED));
        verify(rateLimiterService).recordLoginFailure("test@example.com", null);
        verify(sessionRepository, never()).revokeAllActiveSessions(any(), any(), any());
    }

    @Test
    @DisplayName("changePassword rejects new password equal to the current one")
    void changePassword_sameAsCurrent_rejected() {
        UUID userId = UUID.randomUUID();
        User user = userWithPassword("old-password-123456");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(
                        () ->
                                userService.changePassword(
                                        userId,
                                        new ChangePasswordRequest("old-password-123456", "old-password-123456")))
                .isInstanceOf(BusinessException.class);
        verify(sessionRepository, never()).revokeAllActiveSessions(any(), any(), any());
    }

    @Test
    @DisplayName("changePassword rejects too short new password")
    void changePassword_tooShort_rejected() {
        UUID userId = UUID.randomUUID();
        User user = userWithPassword("old-password-123456");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(
                        () ->
                                userService.changePassword(
                                        userId, new ChangePasswordRequest("old-password-123456", "short")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("findProfileById returns UserProfileResponse when user exists")
    void findProfileById_userExists_returnsProfile() {
        UUID userId = UUID.randomUUID();
        User user = new User("test@example.com", "hash", "John", "Doe");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.findProfileById(userId);

        assertThat(response).isNotNull();
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.lastName()).isEqualTo("Doe");
    }

    @Test
    @DisplayName("findProfileById throws RESOURCE_NOT_FOUND when user does not exist")
    void findProfileById_userNotFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findProfileById(userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                        });
    }

    @Test
    @DisplayName("findProfileById throws NullPointerException when id is null")
    void findProfileById_nullId_throwsNpe() {
        assertThatThrownBy(() -> userService.findProfileById(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("findGoalByUserId returns goal when exists")
    void findGoalByUserId_exists_returnsGoal() {
        UUID userId = UUID.randomUUID();
        LearningGoal goal = new LearningGoal(userId, (short) 750, 45, LocalDate.now().plusMonths(3));
        when(goalRepository.findById(userId)).thenReturn(Optional.of(goal));

        LearningGoalResponse res = userService.findGoalByUserId(userId);
        assertThat(res.targetScore()).isEqualTo((short) 750);
        assertThat(res.dailyMinutes()).isEqualTo(45);
    }

    @Test
    @DisplayName("findGoalByUserId throws RESOURCE_NOT_FOUND when not set")
    void findGoalByUserId_notExists_throwsNotFound() {
        UUID userId = UUID.randomUUID();
        when(goalRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findGoalByUserId(userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("updateGoal creates or updates goal when valid")
    void updateGoal_validRequest_savesGoal() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(true);
        when(goalRepository.findById(userId)).thenReturn(Optional.empty());
        when(goalRepository.save(org.mockito.ArgumentMatchers.any(LearningGoal.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateLearningGoalRequest req = new UpdateLearningGoalRequest((short) 650, 30, LocalDate.now().plusDays(60));
        LearningGoalResponse res = userService.updateGoal(userId, req);

        assertThat(res.targetScore()).isEqualTo((short) 650);
        assertThat(res.dailyMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("updateGoal rejects score not divisible by 5")
    void updateGoal_invalidScore_throwsValidationError() {
        UUID userId = UUID.randomUUID();
        UpdateLearningGoalRequest req = new UpdateLearningGoalRequest((short) 653, 30, LocalDate.now().plusDays(60));

        assertThatThrownBy(() -> userService.updateGoal(userId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("updateGoal rejects target date in the past")
    void updateGoal_pastDate_throwsValidationError() {
        UUID userId = UUID.randomUUID();
        UpdateLearningGoalRequest req = new UpdateLearningGoalRequest((short) 650, 30, LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> userService.updateGoal(userId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
