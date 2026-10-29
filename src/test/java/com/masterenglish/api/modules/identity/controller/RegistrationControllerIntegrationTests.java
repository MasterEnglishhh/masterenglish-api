package com.masterenglish.api.modules.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.service.RegistrationMailer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
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
    RegistrationControllerIntegrationTests.ClockFixture.class
})
class RegistrationControllerIntegrationTests {
    private static final String PASSWORD = "Coffee and sun after rain!";
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PasswordEncoder encoder;
    @MockitoBean RegistrationMailer mailer;
    private MockMvc mvc;
    private final AtomicReference<String> deliveredCode = new AtomicReference<>();

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM registration_requests");
        jdbc.update("DELETE FROM otp_challenges");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM auth_rate_limit_events");
        jdbc.update("DELETE FROM auth_rate_limits");
        jdbc.update("DELETE FROM users");
        clock.set(Instant.parse("2026-10-29T10:00:00Z"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        deliveredCode.set(null);
        doAnswer(
                        invocation -> {
                            deliveredCode.set(invocation.getArgument(1));
                            return null;
                        })
                .when(mailer)
                .send(anyString(), anyString());
    }

    @Test
    void createsPendingUserAndActivatesOnlyAfterItsOtpWithoutIssuingJwt() throws Exception {
        Flow flow = register(" Learner@Example.com ", PASSWORD, "An", "Nguyễn Hoàng");
        assertThat(flow.otp()).matches("[0-9]{6}");
        assertThat(jdbc.queryForObject("SELECT status FROM users", String.class))
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isNull();
        assertThat(
                        encoder.matches(
                                PASSWORD,
                                jdbc.queryForObject(
                                        "SELECT pending_password_hash FROM registration_requests",
                                        String.class)))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT otp_hmac FROM otp_challenges", String.class))
                .hasSize(64)
                .doesNotContain(flow.otp());
        json("/api/v1/auth/login", Map.of("email", flow.email(), "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        verifyFlow(flow).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(jdbc.queryForObject("SELECT status FROM users", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT first_name FROM users", String.class))
                .isEqualTo("An");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pending_password_hash FROM registration_requests",
                                String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT otp_hmac FROM otp_challenges", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT code FROM roles JOIN user_roles ON role_id=roles.id",
                                String.class))
                .isEqualTo("LEARNER");
        json("/api/v1/auth/login", Map.of("email", flow.email(), "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("learner@example.com"));
    }

    @Test
    void otpIsSingleUseAndConcurrentVerificationHasOnlyOneWinner() throws Exception {
        Flow flow = register("race@example.com", PASSWORD, "An", "Nguyễn");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> verifyFlow(flow).andReturn().getResponse().getStatus());
            var second = pool.submit(() -> verifyFlow(flow).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void wrongAttemptsCommitAndSurviveNewOtpChallenges() throws Exception {
        Flow flow = register("attempts@example.com", PASSWORD, "An", "Nguyễn");
        String wrong = wrong(flow.otp());
        for (int attempt = 0; attempt < 4; attempt++)
            verifyFlow(new Flow(flow.id(), flow.email(), wrong)).andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM otp_challenges WHERE invalidated_at IS NULL",
                                Integer.class))
                .isEqualTo(4);
        clock.advance(Duration.ofSeconds(61));
        resend(flow).andExpect(status().isAccepted());
        Flow refreshed = new Flow(flow.id(), flow.email(), deliveredCode.get());
        verifyFlow(new Flow(flow.id(), flow.email(), wrong(refreshed.otp())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
        verifyFlow(refreshed).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(901));
        resend(flow).andExpect(status().isAccepted());
        verifyFlow(new Flow(flow.id(), flow.email(), deliveredCode.get()))
                .andExpect(status().isOk());
    }

    @Test
    void expiredOtpDoesNotActivateAndResendDoesNotExtendRegistrationCycle() throws Exception {
        Flow flow = register("expired@example.com", PASSWORD, "An", "Nguyễn");
        Instant requestExpiry =
                jdbc.queryForObject(
                                "SELECT expires_at FROM registration_requests",
                                java.sql.Timestamp.class)
                        .toInstant();
        clock.advance(Duration.ofSeconds(301));
        verifyFlow(flow)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        resend(flow).andExpect(status().isAccepted());
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT expires_at FROM registration_requests",
                                        java.sql.Timestamp.class)
                                .toInstant())
                .isEqualTo(requestExpiry);
        if (!flow.otp().equals(deliveredCode.get()))
            verifyFlow(flow).andExpect(status().isBadRequest());
        verifyFlow(new Flow(flow.id(), flow.email(), deliveredCode.get()))
                .andExpect(status().isOk());
    }

    @Test
    void rateLimitsResendAt60SecondsAndFiveSendsPerHour() throws Exception {
        Flow flow = register("quota@example.com", PASSWORD, "An", "Nguyễn");
        resend(flow)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        for (int send = 1; send < 5; send++) {
            clock.advance(Duration.ofSeconds(61));
            resend(flow).andExpect(status().isAccepted());
        }
        clock.advance(Duration.ofSeconds(61));
        resend(flow).andExpect(status().isTooManyRequests());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_rate_limit_events e JOIN auth_rate_limits r ON e.rate_limit_id=r.id WHERE r.scope='OTP_SEND_EMAIL'",
                                Integer.class))
                .isEqualTo(5);
    }

    @Test
    void aNewRegistrationCannotReuseTheOldOtpOrApplyAnotherRequestsCredentials() throws Exception {
        Flow first = register("replace@example.com", PASSWORD, "First", "Name");
        clock.advance(Duration.ofSeconds(61));
        String nextPassword = "Another completely new phrase!";
        Flow second = register("replace@example.com", nextPassword, "Second", "Person");
        verifyFlow(first).andExpect(status().isBadRequest());
        verifyFlow(second).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT first_name FROM users", String.class))
                .isEqualTo("Second");
        String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
        assertThat(encoder.matches(nextPassword, hash)).isTrue();
        assertThat(encoder.matches(PASSWORD, hash)).isFalse();
    }

    @Test
    void activeAccountsGetGenericResponsesAndTheirCredentialsCannotBeOverwritten()
            throws Exception {
        Flow flow = register("existing@example.com", PASSWORD, "Original", "Person");
        verifyFlow(flow).andExpect(status().isOk());
        clearInvocations(mailer);
        clock.advance(Duration.ofSeconds(61));
        json(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                flow.email(),
                                "password",
                                "Unrelated new password here!",
                                "firstName",
                                "Attacker",
                                "lastName",
                                "Name"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.registrationId").exists())
                .andExpect(jsonPath("$.otp").doesNotExist());
        verify(mailer, never()).send(anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT first_name FROM users", String.class))
                .isEqualTo("Original");
        assertThat(
                        encoder.matches(
                                PASSWORD,
                                jdbc.queryForObject(
                                        "SELECT password_hash FROM users", String.class)))
                .isTrue();
    }

    @Test
    void mailFailureInvalidatesSecretsButDoesNotResetSendQuota() throws Exception {
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Mail unavailable"))
                .when(mailer)
                .send(anyString(), anyString());
        json(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                "mail-fail@example.com",
                                "password",
                                PASSWORD,
                                "firstName",
                                "An",
                                "lastName",
                                "Nguyễn"))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT otp_hmac FROM otp_challenges", String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pending_password_hash FROM registration_requests",
                                String.class))
                .isNull();
        json(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                "mail-fail@example.com",
                                "password",
                                PASSWORD,
                                "firstName",
                                "An",
                                "lastName",
                                "Nguyễn"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void requestValidationAndCsrfRemainEnforcedAndNoCodeLeaksInResponse() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        json(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                "bad-email",
                                "password",
                                PASSWORD,
                                "firstName",
                                "An",
                                "lastName",
                                "Name"))
                .andExpect(status().isBadRequest());
        json(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                "weak@example.com",
                                "password",
                                "short",
                                "firstName",
                                "An",
                                "lastName",
                                "Name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_REJECTED"));
        json(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                "role@example.com",
                                "password",
                                PASSWORD,
                                "firstName",
                                "An",
                                "lastName",
                                "Name",
                                "roles",
                                List.of("SYSTEM_ADMIN")))
                .andExpect(status().isBadRequest());
        Flow flow = register("safe@example.com", PASSWORD, "An", "Name");
        json(
                        "/api/v1/auth/register/verify",
                        Map.of("registrationId", flow.id(), "email", flow.email(), "otp", "12345"))
                .andExpect(status().isBadRequest());
    }

    private record Flow(UUID id, String email, String otp) {}

    private Flow register(String email, String password, String firstName, String lastName)
            throws Exception {
        var result =
                json(
                                "/api/v1/auth/register",
                                Map.of(
                                        "email",
                                        email,
                                        "password",
                                        password,
                                        "firstName",
                                        firstName,
                                        "lastName",
                                        lastName))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.otp").doesNotExist())
                        .andExpect(jsonPath("$.resendAfterSeconds").value(60))
                        .andReturn();
        Map<?, ?> data = mapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
        return new Flow(
                UUID.fromString((String) data.get("registrationId")),
                email.strip().toLowerCase(java.util.Locale.ROOT),
                deliveredCode.get());
    }

    private ResultActions verifyFlow(Flow flow) throws Exception {
        return json(
                "/api/v1/auth/register/verify",
                Map.of("registrationId", flow.id(), "email", flow.email(), "otp", flow.otp()));
    }

    private ResultActions resend(Flow flow) throws Exception {
        return json(
                "/api/v1/auth/register/resend",
                Map.of("registrationId", flow.id(), "email", flow.email()));
    }

    private ResultActions json(String path, Object payload) throws Exception {
        var csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        Map<?, ?> token = mapper.readValue(csrf.getContentAsByteArray(), Map.class);
        return mvc.perform(
                post(path)
                        .with(
                                request -> {
                                    request.setRemoteAddr("198.51.100.5");
                                    return request;
                                })
                        .cookie(csrf.getCookie("XSRF-TOKEN"))
                        .header((String) token.get("headerName"), token.get("token"))
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)));
    }

    private static String wrong(String otp) {
        return otp.equals("000000") ? "999999" : "000000";
    }

    @TestConfiguration
    static class ClockFixture {
        @Bean
        @Primary
        MutableClock registrationClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>();

        void set(Instant value) {
            instant.set(value);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
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
            return instant.get();
        }
    }
}
