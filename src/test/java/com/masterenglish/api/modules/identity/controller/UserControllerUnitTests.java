package com.masterenglish.api.modules.identity.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.identity.dto.request.UpdateLearningGoalRequest;
import com.masterenglish.api.modules.identity.dto.response.LearningGoalResponse;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.identity.service.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class UserControllerUnitTests {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private UserService userServiceMock;
    private MockMvc mockMvc;
    private final UUID testUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userServiceMock = mock(UserService.class);

        HandlerMethodArgumentResolver currentUserResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(CurrentUser.class);
            }

            @Override
            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory) {
                User user = new User("learner@test.com", "hash", "Learner", "User");
                try {
                    var idField = user.getClass().getSuperclass().getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(user, testUserId);
                } catch (Exception ignored) {
                }
                return UserPrincipal.create(user);
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userServiceMock))
                .setCustomArgumentResolvers(currentUserResolver)
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns user profile")
    void getProfile_returnsProfile() throws Exception {
        when(userServiceMock.findProfileById(testUserId)).thenReturn(
                new UserProfileResponse(testUserId, "learner@test.com", "Learner", "User", "User Learner", Set.of("LEARNER"), null, null));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("learner@test.com"));
    }

    @Test
    @DisplayName("GET /api/v1/users/me/goal returns learning goal")
    void getGoal_returnsGoal() throws Exception {
        when(userServiceMock.findGoalByUserId(testUserId)).thenReturn(
                new LearningGoalResponse((short) 750, 45, LocalDate.now().plusMonths(2), Instant.now()));

        mockMvc.perform(get("/api/v1/users/me/goal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetScore").value(750))
                .andExpect(jsonPath("$.dailyMinutes").value(45));
    }

    @Test
    @DisplayName("PUT /api/v1/users/me/goal updates learning goal")
    void updateGoal_valid_returnsUpdated() throws Exception {
        UpdateLearningGoalRequest req = new UpdateLearningGoalRequest((short) 800, 60, LocalDate.now().plusMonths(3));
        when(userServiceMock.updateGoal(eq(testUserId), any())).thenReturn(
                new LearningGoalResponse((short) 800, 60, req.targetDate(), Instant.now()));

        mockMvc.perform(put("/api/v1/users/me/goal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetScore").value(800))
                .andExpect(jsonPath("$.dailyMinutes").value(60));
    }

    @Test
    @DisplayName("PUT /api/v1/users/me/goal rejects invalid targetScore or minutes")
    void updateGoal_invalidBean_returnsBadRequest() throws Exception {
        // Score < 10 or Minutes < 5
        UpdateLearningGoalRequest req = new UpdateLearningGoalRequest((short) 5, 2, LocalDate.now().plusMonths(1));

        mockMvc.perform(put("/api/v1/users/me/goal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
