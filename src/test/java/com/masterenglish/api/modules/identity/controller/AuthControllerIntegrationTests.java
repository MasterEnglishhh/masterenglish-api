package com.masterenglish.api.modules.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class AuthControllerIntegrationTests {

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

    private static final String CORRECT_PASSWORD = "CorrectPassword123!@#";

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

    private String findSetCookieHeader(MockHttpServletResponse response, String cookieName) {
        for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(cookieName + "=")) {
                return header;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Test 1: Đăng nhập thành công trả 200, 2 Set-Cookie hợp lệ, body đúng user info")
    void login_success_returns200_setCookies_andUserProfile() throws Exception {
        User user = createVerifiedUser("learner@example.com", CORRECT_PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();

        String requestBody =
                """
                {
                    "email": "learner@example.com",
                    "password": "%s"
                }
                """
                        .formatted(CORRECT_PASSWORD);

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(user.getId().toString()))
                        .andExpect(jsonPath("$.email").value("learner@example.com"))
                        .andExpect(jsonPath("$.firstName").value("Huy"))
                        .andExpect(jsonPath("$.lastName").value("Trịnh Đăng"))
                        .andExpect(jsonPath("$.displayName").value("Trịnh Đăng Huy"))
                        .andExpect(jsonPath("$.roles[0]").value("LEARNER"))
                        .andReturn();

        MockHttpServletResponse response = result.getResponse();

        String accessCookie = findSetCookieHeader(response, "access_token");
        assertThat(accessCookie).isNotNull();
        assertThat(accessCookie).contains("HttpOnly");
        assertThat(accessCookie).contains("SameSite=Lax");
        assertThat(accessCookie).contains("Path=/");
        assertThat(accessCookie).contains("Max-Age=900");

        String refreshCookie = findSetCookieHeader(response, "refresh_token");
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie).contains("HttpOnly");
        assertThat(refreshCookie).contains("SameSite=Lax");
        assertThat(refreshCookie).contains("Path=/api/v1/auth");
        assertThat(refreshCookie).contains("Max-Age=604800");
    }

    @Test
    @DisplayName(
            "Test 3: Đăng nhập sai mật khẩu trả 401 ProblemDetail thông báo 'Email hoặc mật khẩu không chính xác'")
    void login_wrongPassword_returns401ProblemDetail() throws Exception {
        createVerifiedUser("learner@example.com", CORRECT_PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();

        String requestBody =
                """
                {
                    "email": "learner@example.com",
                    "password": "WrongPassword123!"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không chính xác"));
    }

    @Test
    @DisplayName("Đăng nhập email không tồn tại trả 401 ProblemDetail cùng thông báo thống nhất")
    void login_nonExistentEmail_returns401ProblemDetail() throws Exception {
        CsrfBootstrap csrf = bootstrapCsrf();

        String requestBody =
                """
                {
                    "email": "nonexistent@example.com",
                    "password": "AnyPassword123!"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không chính xác"));
    }

    @Test
    @DisplayName(
            "Test 4: Đăng nhập sai 5 lần liên tiếp trả 429 Too Many Requests kèm header Retry-After")
    void login_rateLimitExceeded_returns429WithRetryAfterHeader() throws Exception {
        createVerifiedUser("ratelimit@example.com", CORRECT_PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();

        String requestBody =
                """
                {
                    "email": "ratelimit@example.com",
                    "password": "WrongPassword123!"
                }
                """;

        // 5 consecutive failed attempts
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .cookie(csrf.cookie())
                                    .header(csrf.headerName(), csrf.token())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt should trigger 429 Too Many Requests
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("Test 5: Thiếu CSRF token ở POST login/refresh/logout bị từ chối 403")
    void authEndpoints_missingCsrfToken_returns403Forbidden() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"learner@example.com\",\"password\":\"pwd\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Test 6: POST /api/v1/auth/refresh với refresh cookie trả token mới")
    void refresh_withValidRefreshTokenCookie_rotatesTokensSuccessfully() throws Exception {
        createVerifiedUser("learner@example.com", CORRECT_PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();

        String loginBody =
                """
                {
                    "email": "learner@example.com",
                    "password": "%s"
                }
                """
                        .formatted(CORRECT_PASSWORD);

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody))
                        .andExpect(status().isOk())
                        .andReturn();

        String initialRefreshToken = extractCookieValue(loginResult.getResponse(), "refresh_token");
        assertThat(initialRefreshToken).isNotBlank();

        // Perform refresh
        MvcResult refreshResult =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .cookie(
                                                csrf.cookie(),
                                                new Cookie("refresh_token", initialRefreshToken))
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.message")
                                        .value("Phiên làm việc đã được làm mới thành công."))
                        .andReturn();

        MockHttpServletResponse response = refreshResult.getResponse();
        String newAccessCookie = findSetCookieHeader(response, "access_token");
        String newRefreshCookie = findSetCookieHeader(response, "refresh_token");

        assertThat(newAccessCookie).isNotNull();
        assertThat(newRefreshCookie).isNotNull();

        String newRefreshToken = extractCookieValue(response, "refresh_token");
        assertThat(newRefreshToken).isNotBlank().isNotEqualTo(initialRefreshToken);
    }

    @Test
    @DisplayName("Test 7: Tái sử dụng refresh token cũ trả 401 và thu hồi phiên")
    void refresh_reuseOldRefreshToken_returns401() throws Exception {
        createVerifiedUser("learner@example.com", CORRECT_PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();

        String loginBody =
                """
                {
                    "email": "learner@example.com",
                    "password": "%s"
                }
                """
                        .formatted(CORRECT_PASSWORD);

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody))
                        .andExpect(status().isOk())
                        .andReturn();

        String initialRefreshToken = extractCookieValue(loginResult.getResponse(), "refresh_token");

        // Rotate once: initialRefreshToken is revoked and replaced
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .cookie(
                                        csrf.cookie(),
                                        new Cookie("refresh_token", initialRefreshToken))
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Reusing initialRefreshToken must fail with 401
        MvcResult reuseResult =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .cookie(
                                                csrf.cookie(),
                                                new Cookie("refresh_token", initialRefreshToken))
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                        .andReturn();

        // Should also clear cookies
        String accessCookie = findSetCookieHeader(reuseResult.getResponse(), "access_token");
        String refreshCookie = findSetCookieHeader(reuseResult.getResponse(), "refresh_token");
        assertThat(accessCookie).contains("Max-Age=0");
        assertThat(refreshCookie).contains("Max-Age=0");
    }

    @Test
    @DisplayName(
            "Test 8: POST /api/v1/auth/logout trả 204 và xóa cookie; sau đó gọi /users/me trả 401")
    void logout_revokesSessionAndClearsCookies() throws Exception {
        createVerifiedUser("learner@example.com", CORRECT_PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();

        String loginBody =
                """
                {
                    "email": "learner@example.com",
                    "password": "%s"
                }
                """
                        .formatted(CORRECT_PASSWORD);

        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(csrf.cookie())
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody))
                        .andExpect(status().isOk())
                        .andReturn();

        String accessToken = extractCookieValue(loginResult.getResponse(), "access_token");
        String refreshToken = extractCookieValue(loginResult.getResponse(), "refresh_token");

        // Logout
        MvcResult logoutResult =
                mockMvc.perform(
                                post("/api/v1/auth/logout")
                                        .cookie(
                                                csrf.cookie(),
                                                new Cookie("access_token", accessToken),
                                                new Cookie("refresh_token", refreshToken))
                                        .header(csrf.headerName(), csrf.token())
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isNoContent())
                        .andReturn();

        String clearAccess = findSetCookieHeader(logoutResult.getResponse(), "access_token");
        String clearRefresh = findSetCookieHeader(logoutResult.getResponse(), "refresh_token");
        assertThat(clearAccess).contains("Max-Age=0");
        assertThat(clearRefresh).contains("Max-Age=0");

        // Subsequent call to /api/v1/users/me with revoked token returns 401
        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("access_token", accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private record CsrfBootstrap(Cookie cookie, String headerName, String token) {}
}
