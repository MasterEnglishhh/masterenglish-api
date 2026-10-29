package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.RateLimitExceededException;
import com.masterenglish.api.config.JwtProperties;
import com.masterenglish.api.modules.identity.entity.AuthRateLimit;
import com.masterenglish.api.modules.identity.entity.AuthRateLimitEvent;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitEventRepository;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository;
import com.masterenglish.api.modules.identity.service.RateLimiterService;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    public static final String SCOPE_LOGIN_EMAIL = "LOGIN_FAILURE_EMAIL";
    public static final String SCOPE_LOGIN_IP = "LOGIN_IP";

    public static final long EMAIL_FAILURE_THRESHOLD = 5;
    public static final long IP_FAILURE_THRESHOLD = 300;

    public static final Duration WINDOW_DURATION = Duration.ofMinutes(15);
    public static final Duration BLOCK_DURATION = Duration.ofMinutes(15);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AuthRateLimitRepository rateLimitRepository;
    private final AuthRateLimitEventRepository eventRepository;
    private final Clock clock;
    private final SecretKeySpec secretKeySpec;

    @Autowired
    public RateLimiterServiceImpl(
            AuthRateLimitRepository rateLimitRepository,
            AuthRateLimitEventRepository eventRepository,
            JwtProperties jwtProperties,
            @Value("${app.security.rate-limit.secret:}") String rateLimitSecret,
            Clock clock) {
        this.rateLimitRepository = Objects.requireNonNull(rateLimitRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.clock = Objects.requireNonNull(clock);
        String secret =
                (rateLimitSecret != null && !rateLimitSecret.isBlank())
                        ? rateLimitSecret
                        : Objects.requireNonNull(jwtProperties).secret();
        this.secretKeySpec =
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public RateLimiterServiceImpl(
            AuthRateLimitRepository rateLimitRepository,
            AuthRateLimitEventRepository eventRepository,
            JwtProperties jwtProperties,
            Clock clock) {
        this(rateLimitRepository, eventRepository, jwtProperties, null, clock);
    }

    @Override
    @Transactional(readOnly = true)
    public void checkLoginRateLimit(String email, String clientIp) {
        Instant now = clock.instant();
        long maxRetryAfterSeconds = 0;

        if (email != null && !email.isBlank()) {
            String emailHash = computeHmacSha256(normalizeEmail(email));
            Optional<AuthRateLimit> emailLimit =
                    rateLimitRepository.findByScopeAndSubjectHash(SCOPE_LOGIN_EMAIL, emailHash);
            if (emailLimit.isPresent() && emailLimit.get().isBlocked(now)) {
                long retryAfter =
                        Duration.between(now, emailLimit.get().getBlockedUntil()).toSeconds();
                maxRetryAfterSeconds = Math.max(maxRetryAfterSeconds, Math.max(1, retryAfter));
            }
        }

        if (clientIp != null && !clientIp.isBlank()) {
            String ipHash = computeHmacSha256(normalizeIp(clientIp));
            Optional<AuthRateLimit> ipLimit =
                    rateLimitRepository.findByScopeAndSubjectHash(SCOPE_LOGIN_IP, ipHash);
            if (ipLimit.isPresent() && ipLimit.get().isBlocked(now)) {
                long retryAfter =
                        Duration.between(now, ipLimit.get().getBlockedUntil()).toSeconds();
                maxRetryAfterSeconds = Math.max(maxRetryAfterSeconds, Math.max(1, retryAfter));
            }
        }

        if (maxRetryAfterSeconds > 0) {
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
                    SCOPE_LOGIN_EMAIL,
                    computeHmacSha256(normalizeEmail(email)),
                    EMAIL_FAILURE_THRESHOLD,
                    now);
        }

        if (clientIp != null && !clientIp.isBlank()) {
            recordFailureForSubject(
                    SCOPE_LOGIN_IP,
                    computeHmacSha256(normalizeIp(clientIp)),
                    IP_FAILURE_THRESHOLD,
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
        String emailHash = computeHmacSha256(normalizeEmail(email));

        rateLimitRepository
                .findByScopeAndSubjectHash(SCOPE_LOGIN_EMAIL, emailHash)
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

        Instant windowStart = now.minus(WINDOW_DURATION);
        long recentFailures =
                eventRepository.countByRateLimitIdAndOccurredAtAfter(
                        rateLimit.getId(), windowStart);

        if (recentFailures >= threshold) {
            rateLimit.blockUntil(now.plus(BLOCK_DURATION));
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

    private String computeHmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeIp(String clientIp) {
        return clientIp.trim();
    }
}
