package com.masterenglish.api.modules.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.service.OtpCodec;
import com.masterenglish.api.modules.identity.service.RegistrationMailer;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "app.security.cookie-secure=false",
            "app.registration.breach-check-enabled=false"
        })
@Import({
    TestcontainersConfiguration.class,
    PasswordResetControllerIntegrationTests.ClockFixture.class
})
class PasswordResetControllerIntegrationTests {
    @Autowired OtpCodec codec;
    private static final String EMAIL = "reset@example.test",
            OLD = "Old password with coffee!",
            NEW = "New password with sunlight!";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableClock clock;
    @MockitoBean RegistrationMailer mailer;
    private MockMvc mvc;
    private final AtomicReference<String> code = new AtomicReference<>();
    private static final String CHANGE = "/api/v1/auth/password/change";

    @BeforeEach
    void setup() {
        for (String table :
                List.of(
                        "password_change_requests",
                        "password_reset_grants",
                        "registration_requests",
                        "otp_challenges",
                        "refresh_tokens",
                        "auth_sessions",
                        "auth_rate_limit_events",
                        "auth_rate_limits",
                        "users")) jdbc.update("DELETE FROM " + table);
        clock.time.set(Instant.parse("2026-10-29T12:00:00Z"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        doAnswer(
                        call -> {
                            code.set(call.getArgument(1));
                            return null;
                        })
                .when(mailer)
                .sendPasswordReset(anyString(), anyString());
        doAnswer(
                        call -> {
                            code.set(call.getArgument(1));
                            return null;
                        })
                .when(mailer)
                .sendPasswordChange(anyString(), anyString());
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,first_name,last_name,status,email_verified_at,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?,?)",
                id,
                EMAIL,
                encoder.encode(OLD),
                "An",
                "Nguyễn",
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,1)", id);
    }

    @Test
    void fullResetRevokesEverySessionAndOldPasswordWithoutAutomaticLogin() throws Exception {
        Cookie oldAccess =
                json("/api/v1/auth/login", Map.of("email", EMAIL, "password", OLD))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("access_token");
        json("/api/v1/auth/login", Map.of("email", EMAIL, "password", OLD))
                .andExpect(status().isOk());
        Flow flow = request(EMAIL);
        String token = grant(flow);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT grant_hash FROM password_reset_grants", String.class))
                .isNotEqualTo(token)
                .hasSize(64);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL",
                                Integer.class))
                .isEqualTo(2);
        var reset = reset(EMAIL, token, NEW).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reset.getHeaders("Set-Cookie"))
                .anyMatch(value -> value.startsWith("access_token=") && value.contains("Max-Age=0"))
                .anyMatch(
                        value -> value.startsWith("refresh_token=") && value.contains("Max-Age=0"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT auth_version FROM users", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT grant_hash FROM password_reset_grants", String.class))
                .isNull();
        mvc.perform(get("/api/v1/users/me").cookie(oldAccess)).andExpect(status().isUnauthorized());
        json("/api/v1/auth/login", Map.of("email", EMAIL, "password", OLD))
                .andExpect(status().isUnauthorized());
        json("/api/v1/auth/login", Map.of("email", EMAIL, "password", NEW))
                .andExpect(status().isOk());
        reset(EMAIL, token, "Another fresh password here!").andExpect(status().isBadRequest());
    }

    @Test
    void unknownAndDisabledEmailsHaveTheSamePublicResponse() throws Exception {
        var unknown =
                json("/api/v1/auth/password/forgot", Map.of("email", "unknown@example.test"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.resetToken").doesNotExist())
                        .andExpect(jsonPath("$.otp").doesNotExist())
                        .andReturn()
                        .getResponse();
        jdbc.update("UPDATE users SET status='DISABLED'");
        var disabled =
                json("/api/v1/auth/password/forgot", Map.of("email", EMAIL))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        assertThat(data(unknown.getContentAsByteArray()).get("message"))
                .isEqualTo(data(disabled.getContentAsByteArray()).get("message"));
        verify(mailer, never()).sendPasswordReset(anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_challenges", Integer.class))
                .isZero();
    }

    @Test
    void wrongOtpCommitsAndDoesNotResetWhenCodeIsResent() throws Exception {
        Flow flow = request(EMAIL);
        for (int i = 0; i < 4; i++)
            verifyCode(new Flow(flow.id(), flow.email(), wrong(flow.code())))
                    .andExpect(status().isBadRequest());
        clock.advance(61);
        Flow next = request(EMAIL);
        verifyCode(new Flow(next.id(), next.email(), wrong(next.code())))
                .andExpect(status().isTooManyRequests());
        verifyCode(next).andExpect(status().isTooManyRequests());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_rate_limit_events e JOIN auth_rate_limits r ON r.id=e.rate_limit_id WHERE r.scope='OTP_VERIFY_PASSWORD_RESET'",
                                Integer.class))
                .isEqualTo(5);
    }

    @Test
    void expiredOtpAndGrantCannotReset() throws Exception {
        Flow flow = request(EMAIL);
        clock.advance(301);
        verifyCode(flow)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        Flow next = request(EMAIL);
        String token = grant(next);
        clock.advance(301);
        reset(EMAIL, token, NEW)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
        assertThat(
                        encoder.matches(
                                OLD,
                                jdbc.queryForObject(
                                        "SELECT password_hash FROM users", String.class)))
                .isTrue();
    }

    @Test
    void requestingAnotherOtpPreservesGrantButVerifyingANewCodeReplacesIt() throws Exception {
        String first = grant(request(EMAIL));
        clock.advance(61);
        Flow next = request(EMAIL);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM password_reset_grants WHERE grant_hash IS NOT NULL",
                                Integer.class))
                .isEqualTo(1);
        String second = grant(next);
        reset(EMAIL, first, NEW).andExpect(status().isBadRequest());
        reset(EMAIL, second, NEW).andExpect(status().isOk());
    }

    @Test
    void samePasswordIsRejectedWithoutConsumingTheGrant() throws Exception {
        String token = grant(request(EMAIL));
        reset(EMAIL, token, OLD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"));
        reset(EMAIL, token, NEW).andExpect(status().isOk());
    }

    @Test
    void grantsAreBoundToUserAndSecurityVersionAndNeverUnlockDisabledAccounts() throws Exception {
        String token = grant(request(EMAIL));
        reset("other@example.test", token, NEW).andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET auth_version=auth_version+1");
        reset(EMAIL, token, NEW).andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET status='DISABLED'");
        reset(EMAIL, token, NEW).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT status FROM users", String.class))
                .isEqualTo("DISABLED");
    }

    @Test
    void concurrentResetHasOneWinnerAndNotificationFailureDoesNotUndoPasswordChange()
            throws Exception {
        String token = grant(request(EMAIL));
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Unavailable"))
                .when(mailer)
                .sendPasswordResetNotice(anyString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> reset(EMAIL, token, NEW).andReturn().getResponse().getStatus());
            var b =
                    pool.submit(
                            () ->
                                    reset(EMAIL, token, "A different valid new password!")
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 400);
        }
        assertThat(jdbc.queryForObject("SELECT auth_version FROM users", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void csrfAndSendQuotasCannotBeBypassedAndOtpIsSingleUse() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/password/reset")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        Flow flow = request(EMAIL);
        json("/api/v1/auth/password/forgot", Map.of("email", EMAIL))
                .andExpect(status().isTooManyRequests());
        grant(flow);
        verifyCode(flow).andExpect(status().isBadRequest());
        reset(EMAIL, "a".repeat(64), NEW).andExpect(status().isBadRequest());
    }

    @Test
    void registrationOtpCannotBeUsedToResetAndEmailSendBudgetIsShared() throws Exception {
        UUID id = UUID.randomUUID();
        UUID user = jdbc.queryForObject("SELECT id FROM users", UUID.class);
        jdbc.update(
                "INSERT INTO otp_challenges(id,user_id,purpose,otp_hmac,expires_at,last_sent_at,created_at) VALUES (?,?,'EMAIL_VERIFICATION',?,?,?,?)",
                id,
                user,
                codec.hmac("EMAIL_VERIFICATION", id, "001234"),
                Timestamp.from(clock.instant().plusSeconds(300)),
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
        verifyCode(new Flow(id, EMAIL, "001234")).andExpect(status().isBadRequest());
        json(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                EMAIL,
                                "password",
                                OLD,
                                "firstName",
                                "An",
                                "lastName",
                                "Nguyễn"))
                .andExpect(status().isAccepted());
        json("/api/v1/auth/password/forgot", Map.of("email", EMAIL))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aValidGrantStillWorksAfterRequestingAnotherOtpWithoutVerifyingIt() throws Exception {
        String token = grant(request(EMAIL));
        clock.advance(61);
        request(EMAIL);
        reset(EMAIL, token, NEW).andExpect(status().isOk());
    }

    @Test
    void changeRequiresCurrentPasswordAndIssuingSessionThenRevokesAllCredentials()
            throws Exception {
        var primary = login(OLD);
        var other = login(OLD);
        Cookie access = primary.getCookie("access_token");
        Cookie otherAccess = other.getCookie("access_token");
        json(CHANGE, changePayload(OLD)).andExpect(status().isUnauthorized());
        json(CHANGE, changePayload("wrong password"), access)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"));
        verify(mailer, never()).sendPasswordChange(anyString(), anyString());

        var challenge =
                json(CHANGE, changePayload(OLD), access)
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.otp").doesNotExist())
                        .andReturn()
                        .getResponse();
        var id = data(challenge.getContentAsByteArray()).get("challengeId");
        assertThat(code.get()).matches("[0-9]{6}");
        assertThat(jdbc.queryForObject("SELECT otp_hmac FROM otp_challenges", String.class))
                .isNotEqualTo(code.get())
                .hasSize(64);
        assertThat(jdbc.queryForObject("SELECT auth_version FROM users", Integer.class)).isZero();
        var verification = Map.of("challengeId", id, "otp", code.get());
        json(CHANGE + "/verify", verification, otherAccess).andExpect(status().isBadRequest());
        json(CHANGE + "/verify", Map.of("challengeId", id, "otp", wrong(code.get())), access)
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM otp_challenges", Integer.class))
                .isEqualTo(1);

        var result =
                json(CHANGE + "/verify", verification, access)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(result.getHeaders("Set-Cookie"))
                .anyMatch(
                        value -> value.startsWith("access_token=") && value.contains("Max-Age=0"));
        assertThat(jdbc.queryForObject("SELECT auth_version FROM users", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pending_password_hash FROM password_change_requests",
                                String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_sessions WHERE revoke_reason='PASSWORD_CHANGE'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                                Integer.class))
                .isZero();
        json(CHANGE + "/verify", verification, access).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").cookie(otherAccess))
                .andExpect(status().isUnauthorized());
        json("/api/v1/auth/refresh", Map.of(), other.getCookie("refresh_token"))
                .andExpect(status().isUnauthorized());
        json("/api/v1/auth/login", Map.of("email", EMAIL, "password", OLD))
                .andExpect(status().isUnauthorized());
        login(NEW);
    }

    @Test
    void concurrentPasswordChangesShareTheCurrentPasswordAttemptBudget() throws Exception {
        Cookie access = login(OLD).getCookie("access_token");
        int attempts = 8;
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return json(
                                                    CHANGE,
                                                    changePayload("incorrect current password"),
                                                    access)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            boolean started = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertThat(started).isTrue();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(25, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(400, 429);
            assertThat(statuses.stream().filter(status -> status == 400)).hasSize(5);
        }
        json("/api/v1/auth/login", Map.of("email", EMAIL, "password", OLD))
                .andExpect(status().isTooManyRequests());
        verify(mailer, never()).sendPasswordChange(anyString(), anyString());
    }

    @Test
    void passwordChangeResendKeepsOriginalDeadlineAndSharesOtpSendLimit() throws Exception {
        Cookie access = login(OLD).getCookie("access_token");
        var initial =
                json(CHANGE, changePayload(OLD), access)
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        var challenge = data(initial.getContentAsByteArray());
        var resend = Map.of("challengeId", challenge.get("challengeId"));
        json(CHANGE + "/resend", resend, access).andExpect(status().isTooManyRequests());
        json("/api/v1/auth/password/forgot", Map.of("email", EMAIL))
                .andExpect(status().isTooManyRequests());
        clock.advance(61);
        json(CHANGE + "/resend", resend, access)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expiresAt").value(challenge.get("expiresAt")));
        clock.advance(240);
        json(
                        CHANGE + "/verify",
                        Map.of("challengeId", challenge.get("challengeId"), "otp", code.get()),
                        access)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        login(OLD);
    }

    @Test
    void passwordResetInvalidatesPendingPasswordChangeAndClearsItsHash() throws Exception {
        Cookie access = login(OLD).getCookie("access_token");
        json(CHANGE, changePayload(OLD), access).andExpect(status().isAccepted());
        clock.advance(61);
        String grant = grant(request(EMAIL));
        reset(EMAIL, grant, NEW).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pending_password_hash FROM password_change_requests",
                                String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM password_change_requests WHERE invalidated_at IS NOT NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    private MockHttpServletResponse login(String password) throws Exception {
        return json("/api/v1/auth/login", Map.of("email", EMAIL, "password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
    }

    private static Map<String, String> changePayload(String current) {
        return Map.of("currentPassword", current, "newPassword", NEW, "confirmPassword", NEW);
    }

    private record Flow(UUID id, String email, String code) {
        @Override
        public String toString() {
            return "ResetFlow[redacted]";
        }
    }

    private Flow request(String email) throws Exception {
        code.set(null);
        var response =
                json("/api/v1/auth/password/forgot", Map.of("email", email))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (code.get() == null && System.nanoTime() < end) Thread.sleep(10);
        assertThat(code.get()).matches("[0-9]{6}");
        return new Flow(
                UUID.fromString((String) data(response.getContentAsByteArray()).get("challengeId")),
                email,
                code.get());
    }

    private String grant(Flow flow) throws Exception {
        var response = verifyCode(flow).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getCookie("access_token")).isNull();
        return (String) data(response.getContentAsByteArray()).get("resetToken");
    }

    private ResultActions verifyCode(Flow flow) throws Exception {
        return json(
                "/api/v1/auth/password/verify",
                Map.of("challengeId", flow.id(), "email", flow.email(), "otp", flow.code()));
    }

    private ResultActions reset(String email, String token, String password) throws Exception {
        return json(
                "/api/v1/auth/password/reset",
                Map.of("email", email, "resetToken", token, "newPassword", password));
    }

    private Map<?, ?> data(byte[] bytes) {
        return mapper.readValue(bytes, Map.class);
    }

    private ResultActions json(String path, Object payload, Cookie... authCookies)
            throws Exception {
        var csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        var token = data(csrf.getContentAsByteArray());
        var requestBuilder =
                post(path)
                        .with(
                                request -> {
                                    request.setRemoteAddr("198.51.100.8");
                                    return request;
                                })
                        .cookie(csrf.getCookie("XSRF-TOKEN"))
                        .header((String) token.get("headerName"), token.get("token"))
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload));
        if (authCookies.length > 0) requestBuilder.cookie(authCookies);
        return mvc.perform(requestBuilder);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "999999" : "000000";
    }

    @TestConfiguration
    static class ClockFixture {
        @Bean
        @Primary
        MutableClock resetClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>();

        void advance(long seconds) {
            time.updateAndGet(value -> value.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    }
}
