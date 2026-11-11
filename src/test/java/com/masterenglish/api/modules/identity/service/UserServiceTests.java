package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
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
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        goalRepository = mock(LearningGoalRepository.class);
        userService = new UserServiceImpl(userRepository, goalRepository);
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
