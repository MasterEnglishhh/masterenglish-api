package com.masterenglish.api.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.LoginRateLimitSubjects;
import com.masterenglish.api.modules.identity.service.OtpRateLimitStore;
import com.masterenglish.api.modules.identity.service.RateLimiterService;
import com.masterenglish.api.modules.identity.service.RegistrationMailer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "app.security.rate-limit.backend=redis",
            "app.registration.breach-check-enabled=false"
        })
@Import(TestcontainersConfiguration.class)
@Testcontainers
class RedisAuthRateLimiterTests {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "app.security.rate-limit.redis-url",
                () -> "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
    }

    @Autowired private WebApplicationContext context;
    @Autowired private AuthRateLimitRepository postgresCounters;
    @Autowired private RateLimiterService loginLimiter;
    @Autowired private OtpRateLimitStore otpLimiter;
    @Autowired private LoginRateLimitSubjects subjects;
    @Autowired private RedisClient redisClient;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private RegistrationMailer mailer;

    @Test
    void registrationChecksRedisCooldownAndActivatesUserThroughJpa() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String email = "redis-register@example.test";
        String registration =
                jsonMapper.writeValueAsString(
                        Map.of(
                                "email", email,
                                "password", "Coffee and sun after rain!",
                                "firstName", "An",
                                "lastName", "Nguyễn"));
        var result =
                mvc.perform(
                                post("/api/v1/auth/register")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registration))
                        .andExpect(status().isAccepted())
                        .andReturn();
        var deliveredCode = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(email), deliveredCode.capture());
        String registrationId =
                jsonMapper
                        .readTree(result.getResponse().getContentAsByteArray())
                        .path("registrationId")
                        .asString();
        String resend =
                jsonMapper.writeValueAsString(
                        Map.of("email", email, "registrationId", registrationId));
        mvc.perform(
                        post("/api/v1/auth/register/resend")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(resend))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        String verification =
                jsonMapper.writeValueAsString(
                        Map.of(
                                "email",
                                email,
                                "registrationId",
                                registrationId,
                                "otp",
                                deliveredCode.getValue()));
        mvc.perform(
                        post("/api/v1/auth/register/verify")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verification))
                .andExpect(status().isOk());
        assertThat(users.findForAuthenticationByEmail(email))
                .hasValueSatisfying(
                        user -> assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE));
        assertThat(postgresCounters.count()).isZero();
    }

    @Test
    void loginEnforcesRedisLimitAndReturnsRetryAfter() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String email = "redis-login@example.test";
        String payload =
                """
                {"email":"redis-login@example.test","password":"WrongPassword123!"}
                """;
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(
                            post("/api/v1/auth/login")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        try (var connection = redisClient.connect()) {
            String key =
                    "masterenglish:auth:{rate-limits}:LOGIN_FAILURE_EMAIL:"
                            + subjects.emailHash(email)
                            + ":block";
            assertThat(connection.sync().pttl(key)).isPositive().isLessThanOrEqualTo(900_000);
        }
        assertThat(postgresCounters.count()).isZero();
        loginLimiter.resetLoginFailure(" REDIS-LOGIN@EXAMPLE.TEST ");
        loginLimiter.checkLoginRateLimit(email, null);
    }

    @Test
    void passwordChangeSharesTheRedisLoginEmailLimit() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String email = "redis-password-change@example.test";
        var user =
                new User(
                        email,
                        passwordEncoder.encode("Correct fixture password 123!"),
                        "Test",
                        "Learner");
        user.markEmailVerified(Instant.now());
        var principal = UserPrincipal.create(users.saveAndFlush(user));
        String login =
                jsonMapper.writeValueAsString(
                        Map.of("email", email, "password", "Wrong password 123!"));
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(
                            post("/api/v1/auth/login")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(login))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(
                        post("/api/v1/users/me/password")
                                .with(csrf())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                principal))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                Map.of(
                                                        "currentPassword", "Wrong password 123!",
                                                        "newPassword",
                                                                "Another fixture password 234!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"));
        mvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void otpSendReservesEmailAndIpAtomicallyAndKeepsSlidingQuota() throws Exception {
        Instant now = Instant.now();
        String email = "send-email-hash";
        String ip = "send-ip-hash";
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> otpLimiter.reserveSend(email, ip, now));
            var second = executor.submit(() -> otpLimiter.reserveSend(email, ip, now));
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0L, 60L);
        }
        assertThat(otpLimiter.reserveSend(email, ip, now.plusMillis(59_100))).isEqualTo(1);
        for (int minute = 1; minute < 5; minute++) {
            assertThat(otpLimiter.reserveSend(email, ip, now.plusSeconds(minute * 60L))).isZero();
        }
        assertThat(otpLimiter.reserveSend(email, ip, now.plusSeconds(300))).isEqualTo(3300);
        assertThat(otpLimiter.reserveSend(email, ip, now.plusSeconds(3600))).isZero();
        assertThat(otpLimiter.reserveSend(email, ip, now.plusSeconds(3660))).isZero();
    }

    @Test
    void otpAttemptsBlockByPurposeAndStartFreshAfterCooldown() {
        String scope = "OTP_VERIFY_EMAIL_VERIFICATION";
        String email = "verification-email-hash";
        Instant now = Instant.now();
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(otpLimiter.wrongCode(scope, email, now)).isZero();
        }
        assertThat(otpLimiter.wrongCode(scope, email, now)).isEqualTo(900);
        assertThat(otpLimiter.checkVerificationBlocked(scope, email, now.plusMillis(899_100)))
                .isEqualTo(1);
        assertThat(otpLimiter.reserveVerification(scope, email, "verification-ip-hash", now))
                .isEqualTo(900);
        assertThat(
                        otpLimiter.reserveVerification(
                                "OTP_VERIFY_PASSWORD_RESET", email, "verification-ip-hash", now))
                .isZero();
        assertThat(otpLimiter.wrongCode(scope, email, now.plusSeconds(900))).isZero();
    }

    @Test
    void unavailableRedisFailsClosedForLoginAndOtp() throws Exception {
        RedisClient unavailable = mock(RedisClient.class);
        when(unavailable.connect()).thenThrow(new RedisConnectionException("test"));
        var limiter = new RedisAuthRateLimiter(unavailable, subjects, Clock.systemUTC());

        assertThatThrownBy(() -> limiter.checkLoginRateLimit("outage@example.test", null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> limiter.reserveSend("outage-email", "outage-ip", Instant.now()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
