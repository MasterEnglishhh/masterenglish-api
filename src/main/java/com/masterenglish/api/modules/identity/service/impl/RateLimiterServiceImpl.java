package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.RateLimitExceededException;
import com.masterenglish.api.modules.identity.entity.AuthRateLimit;
import com.masterenglish.api.modules.identity.entity.AuthRateLimitEvent;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitEventRepository;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository;
import com.masterenglish.api.modules.identity.service.LoginRateLimitSubjects;
import com.masterenglish.api.modules.identity.service.RateLimiterService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@ConditionalOnProperty(
        name = "app.security.rate-limit.backend",
        havingValue = "postgres",
        matchIfMissing = true)
public class RateLimiterServiceImpl implements RateLimiterService {

    private final AuthRateLimitRepository rateLimitRepository;
    private final AuthRateLimitEventRepository eventRepository;
    private final Clock clock;
    private final LoginRateLimitSubjects subjects;

    public RateLimiterServiceImpl(
            AuthRateLimitRepository rateLimitRepository,
            AuthRateLimitEventRepository eventRepository,
            LoginRateLimitSubjects subjects,
            Clock clock) {
        this.rateLimitRepository = Objects.requireNonNull(rateLimitRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.clock = Objects.requireNonNull(clock);
        this.subjects = Objects.requireNonNull(subjects);
    }

    @Override
    @Transactional(readOnly = true)
    public void checkLoginRateLimit(String email, String clientIp) {
        Instant now = clock.instant();
        long maxRetryAfterSeconds = 0;

        if (email != null && !email.isBlank()) {
            String emailHash = subjects.emailHash(email);
            Optional<AuthRateLimit> emailLimit =
                    rateLimitRepository.findByScopeAndSubjectHash(
                            LoginRateLimitSubjects.EMAIL_SCOPE, emailHash);
            if (emailLimit.isPresent() && emailLimit.get().isBlocked(now)) {
                long retryAfter =
                        Duration.between(now, emailLimit.get().getBlockedUntil()).toSeconds();
                maxRetryAfterSeconds = Math.max(maxRetryAfterSeconds, Math.max(1, retryAfter));
            }
        }

        if (clientIp != null && !clientIp.isBlank()) {
            String ipHash = subjects.ipHash(clientIp);
            Optional<AuthRateLimit> ipLimit =
                    rateLimitRepository.findByScopeAndSubjectHash(
                            LoginRateLimitSubjects.IP_SCOPE, ipHash);
            if (ipLimit.isPresent() && ipLimit.get().isBlocked(now)) {
                long retryAfter =
                        Duration.between(now, ipLimit.get().getBlockedUntil()).toSeconds();
                maxRetryAfterSeconds = Math.max(maxRetryAfterSeconds, Math.max(1, retryAfter));
            }
        }

        if (maxRetryAfterSeconds > 0) {
            log.debug("Login rate limit reached; retry after {} seconds", maxRetryAfterSeconds);
            throw new RateLimitExceededException(
                    maxRetryAfterSeconds, "Too many login attempts. Please try again later.");
        }
    }

    @Override
    @Transactional
    public void recordLoginFailure(String email, String clientIp) {
        Instant now = clock.instant();

        if (email != null && !email.isBlank()) {
            recordFailureForSubject(
                    LoginRateLimitSubjects.EMAIL_SCOPE,
                    subjects.emailHash(email),
                    LoginRateLimitSubjects.EMAIL_LIMIT,
                    now);
        }

        if (clientIp != null && !clientIp.isBlank()) {
            recordFailureForSubject(
                    LoginRateLimitSubjects.IP_SCOPE,
                    subjects.ipHash(clientIp),
                    LoginRateLimitSubjects.IP_LIMIT,
                    now);
        }
    }

    @Override
    @Transactional
    public void resetLoginFailure(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        Instant now = clock.instant();
        String emailHash = subjects.emailHash(email);

        rateLimitRepository
                .findByScopeAndSubjectHash(LoginRateLimitSubjects.EMAIL_SCOPE, emailHash)
                .ifPresent(
                        rateLimit -> {
                            rateLimit.blockUntil(null);
                            rateLimit.resetAt(now);
                            rateLimitRepository.save(rateLimit);
                            eventRepository.deleteByRateLimitId(rateLimit.getId());
                        });
    }

    private void recordFailureForSubject(
            String scope, String subjectHash, long threshold, Instant now) {
        AuthRateLimit rateLimit = getOrCreateRateLimit(scope, subjectHash);

        eventRepository.save(new AuthRateLimitEvent(rateLimit, now));

        Instant windowStart = now.minusSeconds(LoginRateLimitSubjects.WINDOW_SECONDS);
        long recentFailures =
                eventRepository.countByRateLimitIdAndOccurredAtAfter(
                        rateLimit.getId(), windowStart);

        if (recentFailures >= threshold) {
            rateLimit.blockUntil(now.plusSeconds(LoginRateLimitSubjects.BLOCK_SECONDS));
            rateLimitRepository.save(rateLimit);
        }
    }

    private AuthRateLimit getOrCreateRateLimit(String scope, String subjectHash) {
        return rateLimitRepository
                .findByScopeAndSubjectHash(scope, subjectHash)
                .orElseGet(
                        () -> {
                            rateLimitRepository.insertIfNotExists(
                                    UUID.randomUUID(), scope, subjectHash, clock.instant());
                            return rateLimitRepository
                                    .findByScopeAndSubjectHash(scope, subjectHash)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "Failed to get or create rate limit"));
                        });
    }
}
