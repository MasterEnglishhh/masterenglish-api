package com.masterenglish.api.modules.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitEventRepository;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.RefreshTokenRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {"app.security.cookie-secure=false"})
class UserControllerIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private AuthRateLimitRepository rateLimitRepository;
    @Autowired private AuthRateLimitEventRepository rateLimitEventRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;

    private MockMvc mockMvc;

    private static final String PASSWORD = "CorrectPassword123!@#";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        refreshTokenRepository.deleteAllInBatch();
        authSessionRepository.deleteAllInBatch();
        rateLimitEventRepository.deleteAllInBatch();
        rateLimitRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private User createVerifiedUser(String email, String rawPassword) {
        Role learnerRole = entityManager.find(Role.class, (short) 1);
        User user = new User(email, passwordEncoder.encode(rawPassword), "Huy", "Trịnh Đăng");
        user.addRole(learnerRole);
        user.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private CsrfBootstrap bootstrapCsrf() throws Exception {
        var response =
                mockMvc.perform(get("/api/v1/auth/csrf"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        Map<?, ?> payload = jsonMapper.readValue(response.getContentAsByteArray(), Map.class);
        return new CsrfBootstrap(
                cookie, (String) payload.get("headerName"), (String) payload.get("token"));
    }

    private String extractCookieValue(MockHttpServletResponse response, String cookieName) {
        Cookie cookie = response.getCookie(cookieName);
        if (cookie != null) {
            return cookie.getValue();
        }
        for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(cookieName + "=")) {
                int endIndex = header.indexOf(';');
                return endIndex != -1
                        ? header.substring(cookieName.length() + 1, endIndex)
                        : header.substring(cookieName.length() + 1);
            }
        }
        return null;
    }

    private String loginAndGetAccessToken(String email, String password) throws Exception {
        CsrfBootstrap csrf = bootstrapCsrf();
        String loginBody =
                """
                {
                    "email": "%s",
                    "password": "%s"
                }
                """
                        .formatted(email, password);

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody))
                        .andExpect(status().isOk())
                        .andReturn();

        return extractCookieValue(result.getResponse(), "access_token");
    }

    @Test
    @DisplayName("Test 2: Gọi GET /api/v1/users/me với access_token nhận được trả đúng profile")
    void getCurrentUser_withValidAccessTokenCookie_returnsUserProfile() throws Exception {
        User user = createVerifiedUser("learner@example.com", PASSWORD);
        String accessToken = loginAndGetAccessToken("learner@example.com", PASSWORD);
        assertThat(accessToken).isNotBlank();

        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("access_token", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value("learner@example.com"))
                .andExpect(jsonPath("$.firstName").value("Huy"))
                .andExpect(jsonPath("$.lastName").value("Trịnh Đăng"))
                .andExpect(jsonPath("$.displayName").value("Trịnh Đăng Huy"))
                .andExpect(jsonPath("$.roles[0]").value("LEARNER"));
    }

    @Test
    @DisplayName("Gọi GET /api/v1/users/me với Authorization Bearer header trả đúng profile")
    void getCurrentUser_withValidBearerHeader_returnsUserProfile() throws Exception {
        User user = createVerifiedUser("learner@example.com", PASSWORD);
        String accessToken = loginAndGetAccessToken("learner@example.com", PASSWORD);

        mockMvc.perform(
                        get("/api/v1/users/me")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value("learner@example.com"));
    }

    @Test
    @DisplayName("Gọi GET /api/v1/users/me không có cookie trả 401 ProblemDetail")
    void getCurrentUser_withoutAuthentication_returns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("Gọi GET /api/v1/users/me với access_token không hợp lệ trả 401 ProblemDetail")
    void getCurrentUser_withInvalidAccessToken_returns401ProblemDetail() throws Exception {
        mockMvc.perform(
                        get("/api/v1/users/me")
                                .cookie(new Cookie("access_token", "invalid.jwt.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private record CsrfBootstrap(Cookie cookie, String headerName, String token) {}
}
