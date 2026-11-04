package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.error.RateLimitExceededException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class RateLimiterServiceTests {

    @Autowired private RateLimiterService rateLimiter;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void fiveFailuresBlockFurtherVerificationForTheNormalizedEmail() {
        failFiveTimes("  Target.User@Example.COM  ", "10.0.0.1");
        assertBlocked("target.user@example.com", "10.0.0.2");
    }

    @Test
    void successfulLoginClearsEmailFailuresWhilePasswordCheckDoesNot() {
        String email = "login-reset@example.com";
        String ip = "10.0.0.3";
        for (int i = 0; i < 4; i++) {
            fail(email, ip);
        }
        assertThat(rateLimiter.verify(email, ip, false, () -> Optional.of(true))).contains(true);
        fail(email, ip);
        assertBlocked(email, ip);

        String other = "other-login-reset@example.com";
        for (int i = 0; i < 4; i++) {
            fail(other, ip);
        }
        assertThat(rateLimiter.verify(other, ip, true, () -> Optional.of(true))).contains(true);
        failFiveTimes(other, ip);
        assertBlocked(other, ip);
    }

    @Test
    void oldFailuresAndExpiredReservationsDoNotOccupySlots() {
        String email = "window-test@example.com";
        String ip = "10.0.0.4";
        fail(email, ip);
        jdbc.update(
                """
                UPDATE auth_rate_limit_events SET occurred_at = now() - interval '20 minutes'
                WHERE rate_limit_id IN (SELECT id FROM auth_rate_limits WHERE scope = 'LOGIN_FAILURE_EMAIL')
                """);
        jdbc.update(
                """
                INSERT INTO auth_rate_limit_events(rate_limit_id, reserved_until)
                SELECT id, now() - interval '1 minute' FROM auth_rate_limits WHERE scope = 'LOGIN_FAILURE_EMAIL'
                """);
        failFiveTimes(email, ip);
        assertBlocked(email, ip);
    }

    @Test
    void verifierExceptionReleasesReservationWithoutRecordingFailure() {
        String email = "cancelled-verification@example.com";
        String ip = "10.0.0.5";
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(
                            () ->
                                    rateLimiter.verify(
                                            email,
                                            ip,
                                            true,
                                            () -> {
                                                throw new IllegalStateException(
                                                        "test verification error");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
        }
        failFiveTimes(email, ip);
        assertBlocked(email, ip);
    }

    @Test
    void threeHundredFailuresFromSameIpBlockAnUnseenEmail() {
        String ip = "192.168.100.50";
        for (int i = 0; i < 300; i++) {
            fail("random" + i + "@example.com", ip);
        }
        assertBlocked("fresh.unseen@example.com", ip);
    }

    private void failFiveTimes(String email, String ip) {
        for (int i = 0; i < 5; i++) {
            fail(email, ip);
        }
    }

    private void fail(String email, String ip) {
        assertThat(rateLimiter.verify(email, ip, true, Optional::<Boolean>empty)).isEmpty();
    }

    private void assertBlocked(String email, String ip) {
        assertThatThrownBy(
                        () ->
                                rateLimiter.verify(
                                        email,
                                        ip,
                                        true,
                                        () -> {
                                            throw new AssertionError(
                                                    "A blocked request reached the password verifier");
                                        }))
                .isInstanceOfSatisfying(
                        RateLimitExceededException.class,
                        error -> {
                            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(error.getRetryAfterSeconds()).isBetween(1L, 900L);
                        });
    }
}
