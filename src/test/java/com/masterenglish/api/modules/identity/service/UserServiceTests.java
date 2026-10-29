package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.impl.UserServiceImpl;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserServiceTests {

    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserServiceImpl(userRepository);
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
}
