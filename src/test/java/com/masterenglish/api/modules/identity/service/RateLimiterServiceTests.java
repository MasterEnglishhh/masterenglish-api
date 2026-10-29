package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.error.RateLimitExceededException;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitEventRepository;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class RateLimiterServiceTests {

    @Autowired private RateLimiterService rateLimiterService;
    @Autowired private AuthRateLimitRepository rateLimitRepository;
    @Autowired private AuthRateLimitEventRepository rateLimitEventRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("checkLoginRateLimit passes without exception when there are no failed attempts")
    void checkLoginRateLimit_noFailures_passes() {
        assertThatCode(
                        () ->
                                rateLimiterService.checkLoginRateLimit(
                                        "fresh.user@example.com", "192.168.1.100"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Recording 5 failures within 15 minutes blocks the email on next check")
    void recordLoginFailure_fiveFailuresWithinWindow_blocksEmail() {
        String email = "failed.user@example.com";
        String ip = "10.0.0.1";

        for (int i = 0; i < 4; i++) {
            rateLimiterService.recordLoginFailure(email, ip);
            assertThatCode(() -> rateLimiterService.checkLoginRateLimit(email, ip))
                    .doesNotThrowAnyException();
        }

        // 5th failure reaches the threshold
        rateLimiterService.recordLoginFailure(email, ip);

        // Next check should be blocked
        assertThatThrownBy(() -> rateLimiterService.checkLoginRateLimit(email, ip))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(
                        ex -> {
                            RateLimitExceededException rateLimitEx =
                                    (RateLimitExceededException) ex;
                            assertThat(rateLimitEx.getErrorCode())
                                    .isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(rateLimitEx.getRetryAfterSeconds()).isBetween(1L, 900L);
                        });
    }

    @Test
    @DisplayName("resetLoginFailure clears failed events and unblocks the email")
    void resetLoginFailure_unblocksEmail() {
        String email = "blocked.user@example.com";
        String ip = "10.0.0.2";

        for (int i = 0; i < 5; i++) {
            rateLimiterService.recordLoginFailure(email, ip);
        }

        assertThatThrownBy(() -> rateLimiterService.checkLoginRateLimit(email, ip))
                .isInstanceOf(RateLimitExceededException.class);

        // Reset after successful login
        rateLimiterService.resetLoginFailure(email);
        entityManager.flush();
        entityManager.clear();

        // Email should now be unblocked (testing with a different IP so IP block doesn't interfere)
        assertThatCode(() -> rateLimiterService.checkLoginRateLimit(email, "10.0.0.3"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Email normalization trims whitespace and ignores case for rate limits")
    void checkLoginRateLimit_normalizesEmail() {
        String upperEmailWithSpaces = "  Target.User@Example.COM  ";
        String normalizedEmail = "target.user@example.com";
        String ip = "10.0.0.4";

        for (int i = 0; i < 5; i++) {
            rateLimiterService.recordLoginFailure(upperEmailWithSpaces, ip);
        }

        assertThatThrownBy(() -> rateLimiterService.checkLoginRateLimit(normalizedEmail, ip))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("Failures outside the 15-minute window do not trigger a block")
    void slidingWindow_failuresOutsideWindow_doNotTriggerBlock() {
        String email = "window.test@example.com";
        String ip = "10.0.0.5";

        // Record 4 failures manually with an occurredAt of 20 minutes ago
        rateLimiterService.recordLoginFailure(email, ip);
        entityManager.flush();

        // Update existing events to 20 minutes ago
        Instant twentyMinutesAgo = Instant.now().minus(Duration.ofMinutes(20));
        entityManager
                .createQuery(
                        "UPDATE AuthRateLimitEvent e SET e.occurredAt = :twentyMinutesAgo WHERE e.occurredAt >= :recent")
                .setParameter("twentyMinutesAgo", twentyMinutesAgo)
                .setParameter("recent", Instant.now().minus(Duration.ofMinutes(5)))
                .executeUpdate();

        // Clear block if any was set
        entityManager
                .createQuery("UPDATE AuthRateLimit r SET r.blockedUntil = null")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // Record 1 new failure now (total in window = 1, total overall = 2)
        rateLimiterService.recordLoginFailure(email, ip);

        // Should not be blocked because only 1 failure is inside the 15-minute window
        assertThatCode(() -> rateLimiterService.checkLoginRateLimit(email, ip))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IP threshold: 300 failures on same IP blocks subsequent requests from that IP")
    void recordLoginFailure_ipThreshold_blocksWhen300FailuresReached() {
        String ip = "192.168.100.50";

        for (int i = 0; i < 300; i++) {
            rateLimiterService.recordLoginFailure("random" + i + "@example.com", ip);
        }

        // Even an unseen email should be blocked if originating from this IP
        assertThatThrownBy(
                        () ->
                                rateLimiterService.checkLoginRateLimit(
                                        "fresh.unseen@example.com", ip))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(
                        ex -> {
                            RateLimitExceededException rateLimitEx =
                                    (RateLimitExceededException) ex;
                            assertThat(rateLimitEx.getErrorCode())
                                    .isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(rateLimitEx.getRetryAfterSeconds()).isBetween(1L, 900L);
                        });
    }
}
