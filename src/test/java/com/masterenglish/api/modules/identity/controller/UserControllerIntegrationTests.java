package com.masterenglish.api.modules.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.masterenglish.api.modules.identity.security.JwtTokenService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private JwtTokenService jwt;

    private MockMvc mockMvc;

    private static final String PASSWORD = "CorrectPassword123!@#";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_logs");
        refreshTokenRepository.deleteAllInBatch();
        authSessionRepository.deleteAllInBatch();
        rateLimitEventRepository.deleteAllInBatch();
        rateLimitRepository.deleteAllInBatch();
        jdbc.update("DELETE FROM user_roles");
        userRepository.deleteAllInBatch();
    }

    private User createVerifiedUser(String email, String rawPassword) {
        return createVerifiedUser(email, rawPassword, (short) 1);
    }

    private User createVerifiedUser(String email, String rawPassword, short roleId) {
        Role role = entityManager.find(Role.class, roleId);
        User user = new User(email, passwordEncoder.encode(rawPassword), "Huy", "Trịnh Đăng");
        user.addRole(role);
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

    @Test
    void updateProfile_persistsNamesAndOptionalPhoneForCurrentUser() throws Exception {
        User user = createVerifiedUser("profile@example.com", PASSWORD);
        User other = createVerifiedUser("other@example.com", PASSWORD);
        String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();
        Cookie access = new Cookie("access_token", token);

        mockMvc.perform(
                        put("/api/v1/users/me")
                                .cookie(access, csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"firstName":"  Ánh Ngọc  ","lastName":"  Nguyễn  ","phone":"  +84 901 234 567  "}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.roles[0]").value("LEARNER"))
                .andExpect(jsonPath("$.displayName").value("Nguyễn Ánh Ngọc"))
                .andExpect(jsonPath("$.phone").value("+84 901 234 567"));
        mockMvc.perform(get("/api/v1/users/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ánh Ngọc"))
                .andExpect(jsonPath("$.lastName").value("Nguyễn"))
                .andExpect(jsonPath("$.phone").value("+84 901 234 567"));
        assertThat(userRepository.findById(other.getId()).orElseThrow().getDisplayName())
                .isEqualTo(other.getDisplayName());

        mockMvc.perform(
                        put("/api/v1/users/me")
                                .cookie(access, csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"firstName":"Ánh Ngọc","lastName":"Nguyễn","phone":null}
                                        """))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPhone()).isNull();
    }

    @Test
    void updateProfile_requiresAuthenticationAndCsrfAndRejectsUneditableFields() throws Exception {
        User user = createVerifiedUser("profile@example.com", PASSWORD);
        Cookie access =
                new Cookie("access_token", loginAndGetAccessToken(user.getEmail(), PASSWORD));
        CsrfBootstrap csrf = bootstrapCsrf();
        String valid =
                """
                {"firstName":"An","lastName":"Nguyễn"}
                """;
        mockMvc.perform(
                        put("/api/v1/users/me")
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(valid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        put("/api/v1/users/me")
                                .cookie(access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(valid))
                .andExpect(status().isForbidden());
        for (String invalid :
                java.util.List.of(
                        """
                {"firstName":"   ","lastName":"Nguyễn"}
                """,
                        """
                {"firstName":"An","lastName":"Nguyễn","phone":"1234567890123456789012345678901"}
                """,
                        """
                {"firstName":"An","lastName":"Nguyễn","roles":["SYSTEM_ADMIN"]}
                """)) {
            mockMvc.perform(
                            put("/api/v1/users/me")
                                    .cookie(access, csrf.cookie())
                                    .header(csrf.headerName(), csrf.token())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalid))
                    .andExpect(status().isBadRequest());
        }
        assertThat(userRepository.findById(user.getId()).orElseThrow().getDisplayName())
                .isEqualTo(user.getDisplayName());
    }

    @Test
    void onlySystemAdminCanReplaceRolesAndChangesRevokeSessionsAndWriteAudit() throws Exception {
        User learner = createVerifiedUser("learner@example.test", PASSWORD);
        User content = createVerifiedUser("content@example.test", PASSWORD, (short) 2);
        User admin = createVerifiedUser("admin@example.test", PASSWORD, (short) 3);
        String learnerToken = loginAndGetAccessToken(learner.getEmail(), PASSWORD);
        String contentToken = loginAndGetAccessToken(content.getEmail(), PASSWORD);
        String adminToken = loginAndGetAccessToken(admin.getEmail(), PASSWORD);
        Set<String> requested = Set.of("LEARNER", "CONTENT_ADMIN");

        putRoles(learner.getId(), null, requested).andExpect(status().isUnauthorized());
        putRoles(learner.getId(), learnerToken, requested).andExpect(status().isForbidden());
        putRoles(learner.getId(), contentToken, requested).andExpect(status().isForbidden());
        var learnerClaims = jwt.parseAndValidateToken(learnerToken);
        String mismatchedRoles =
                jwt.generateAccessToken(
                        learner.getId(),
                        learnerClaims.sessionId(),
                        learnerClaims.authVersion(),
                        Set.of("SYSTEM_ADMIN"));
        putRoles(learner.getId(), mismatchedRoles, requested).andExpect(status().isForbidden());
        putRoles(learner.getId(), adminToken, Set.of()).andExpect(status().isBadRequest());
        putRoles(learner.getId(), adminToken, Set.of("UNKNOWN")).andExpect(status().isBadRequest());
        putRoles(admin.getId(), adminToken, Set.of("LEARNER")).andExpect(status().isConflict());

        putRoles(learner.getId(), adminToken, requested)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));
        var changed = userRepository.findById(learner.getId()).orElseThrow();
        assertThat(changed.getRoleNames()).containsExactlyInAnyOrderElementsOf(requested);
        assertThat(changed.getAuthVersion()).isEqualTo(learner.getAuthVersion() + 1);
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT ur.assigned_by FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                WHERE ur.user_id=? AND r.code='CONTENT_ADMIN'
                """,
                                UUID.class,
                                learner.getId()))
                .isEqualTo(admin.getId());
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT count(*) FROM refresh_tokens r JOIN auth_sessions s ON s.id=r.session_id
                WHERE s.user_id=? AND r.revoked_at IS NULL
                """,
                                Integer.class,
                                learner.getId()))
                .isZero();
        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("access_token", learnerToken)))
                .andExpect(status().isUnauthorized());
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT count(*) FROM audit_logs
                WHERE actor_user_id=? AND entity_id=? AND action='ROLE_CHANGE'
                  AND metadata->'before' = '["LEARNER"]'::jsonb
                  AND metadata->'after' = '["CONTENT_ADMIN","LEARNER"]'::jsonb
                """,
                                Integer.class,
                                admin.getId(),
                                learner.getId()))
                .isEqualTo(1);

        putRoles(learner.getId(), adminToken, requested).andExpect(status().isOk());
        assertThat(userRepository.findById(learner.getId()).orElseThrow().getAuthVersion())
                .isEqualTo(changed.getAuthVersion());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class))
                .isEqualTo(1);
        String newToken = loginAndGetAccessToken(learner.getEmail(), PASSWORD);
        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("access_token", newToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));
    }

    @Test
    void queuedRoleChangeRejectsTheSessionRevokedWhileWaiting() throws Exception {
        User admin = createVerifiedUser("revoked-admin@example.test", PASSWORD, (short) 3);
        User learner = createVerifiedUser("role-target@example.test", PASSWORD);
        String token = loginAndGetAccessToken(admin.getEmail(), PASSWORD);
        CsrfBootstrap csrf = bootstrapCsrf();

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> result;
            try (var blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement();
                        var row =
                                statement.executeQuery(
                                        "SELECT id FROM roles WHERE code = 'SYSTEM_ADMIN' FOR UPDATE")) {
                    assertThat(row.next()).isTrue();
                }
                result =
                        executor.submit(
                                () ->
                                        putRoles(
                                                        learner.getId(),
                                                        token,
                                                        Set.of("LEARNER", "SYSTEM_ADMIN"))
                                                .andReturn()
                                                .getResponse()
                                                .getStatus());

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting;
                do {
                    waiting =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            """
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND query ILIKE '%from roles%'
                              AND pid <> pg_backend_pid())
                            """,
                                            Boolean.class));
                    if (!waiting) Thread.sleep(20);
                } while (!waiting && System.nanoTime() < deadline);
                assertThat(waiting).isTrue();

                mockMvc.perform(
                                post("/api/v1/auth/logout")
                                        .cookie(new Cookie("access_token", token), csrf.cookie())
                                        .header(csrf.headerName(), csrf.token()))
                        .andExpect(status().isNoContent());
                blocker.commit();
            }
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(401);
        }
        assertThat(userRepository.findById(learner.getId()).orElseThrow().getRoleNames())
                .containsExactly("LEARNER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isZero();
    }

    @Test
    void concurrentSelfDemotionsCannotRemoveLastActiveSystemAdmin() throws Exception {
        User first = createVerifiedUser("first-admin@example.test", PASSWORD, (short) 3);
        User second = createVerifiedUser("second-admin@example.test", PASSWORD, (short) 3);
        String firstToken = loginAndGetAccessToken(first.getEmail(), PASSWORD);
        String secondToken = loginAndGetAccessToken(second.getEmail(), PASSWORD);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult =
                    executor.submit(
                            () -> {
                                start.await();
                                return putRoles(first.getId(), firstToken, Set.of("LEARNER"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var secondResult =
                    executor.submit(
                            () -> {
                                start.await();
                                return putRoles(second.getId(), secondToken, Set.of("LEARNER"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(
                            java.util.List.of(
                                    firstResult.get(15, TimeUnit.SECONDS),
                                    secondResult.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                JOIN users u ON u.id=ur.user_id
                WHERE r.code='SYSTEM_ADMIN' AND u.status='ACTIVE'
                """,
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void databaseOwnerCanBootstrapOnlyTheFirstSystemAdmin() throws Exception {
        User first = createVerifiedUser("bootstrap@example.test", PASSWORD);
        User other = createVerifiedUser("other@example.test", PASSWORD);
        String oldToken = loginAndGetAccessToken(first.getEmail(), PASSWORD);
        jdbc.queryForList("SELECT bootstrap_system_admin(?)", first.getId());
        assertThat(userRepository.findById(first.getId()).orElseThrow().getRoleNames())
                .containsExactlyInAnyOrder("LEARNER", "SYSTEM_ADMIN");
        assertThatThrownBy(
                        () -> jdbc.queryForList("SELECT bootstrap_system_admin(?)", other.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        mockMvc.perform(get("/api/v1/users/me").cookie(new Cookie("access_token", oldToken)))
                .andExpect(status().isUnauthorized());
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT count(*) FROM audit_logs WHERE actor_type='SYSTEM'
                AND action='ROLE_BOOTSTRAP' AND entity_id=?
                """,
                                Integer.class,
                                first.getId()))
                .isEqualTo(1);
    }

    private ResultActions putRoles(UUID target, String accessToken, Set<String> roles)
            throws Exception {
        CsrfBootstrap csrf = bootstrapCsrf();
        var request =
                put("/api/v1/admin/users/{id}/roles", target)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("roles", roles)));
        if (accessToken != null) request.cookie(new Cookie("access_token", accessToken));
        return mockMvc.perform(request);
    }

    private record CsrfBootstrap(Cookie cookie, String headerName, String token) {}
}
