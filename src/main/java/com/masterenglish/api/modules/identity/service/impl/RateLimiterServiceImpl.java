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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RateLimiterServiceImpl implements RateLimiterService {

    public static final String SCOPE_LOGIN_EMAIL = "LOGIN_FAILURE_EMAIL";
    public static final String SCOPE_LOGIN_IP = "LOGIN_IP";

    public static final long EMAIL_FAILURE_THRESHOLD = 5;
    public static final long IP_FAILURE_THRESHOLD = 300;

    public static final Duration WINDOW_DURATION = Duration.ofMinutes(15);
    public static final Duration BLOCK_DURATION = Duration.ofMinutes(15);

    private static final Duration RESERVATION_DURATION = Duration.ofSeconds(30);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AuthRateLimitRepository rateLimitRepository;
    private final AuthRateLimitEventRepository eventRepository;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SecretKeySpec secretKeySpec;

    public RateLimiterServiceImpl(
            AuthRateLimitRepository rateLimitRepository,
            AuthRateLimitEventRepository eventRepository,
            JwtProperties jwtProperties,
            @Value("${app.security.rate-limit.secret:}") String rateLimitSecret,
            Clock clock,
            TransactionTemplate transactions) {
        this.rateLimitRepository = Objects.requireNonNull(rateLimitRepository);
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        String secret =
                (rateLimitSecret != null && !rateLimitSecret.isBlank())
                        ? rateLimitSecret
                        : Objects.requireNonNull(jwtProperties).secret();
        this.secretKeySpec =
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    @Override
    public <T> Optional<T> verify(
            String email,
            String clientIp,
            boolean resetEmailOnSuccess,
            Supplier<Optional<T>> verification) {
        Attempt attempt = transactions.execute(status -> reserve(email, clientIp));
        Optional<T> result;
        try {
            result = Objects.requireNonNull(verification.get());
        } catch (RuntimeException | Error failure) {
            try {
                transactions.executeWithoutResult(status -> settle(attempt, Outcome.CANCELLED));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        Outcome outcome = Outcome.FAILURE;
        if (result.isPresent()) {
            outcome = resetEmailOnSuccess ? Outcome.LOGIN : Outcome.VERIFIED;
        }
        Outcome verifiedOutcome = outcome;
        if (!Boolean.TRUE.equals(
                transactions.execute(status -> settle(attempt, verifiedOutcome)))) {
            throw new RateLimitExceededException(1);
        }
        return result;
    }

    private Attempt reserve(String email, String clientIp) {
        // Lock email before IP in both transactions. BCrypt runs between transactions.
        List<AuthRateLimit> buckets = new ArrayList<>(2);
        if (email != null && !email.isBlank()) {
            buckets.add(lockBucket(SCOPE_LOGIN_EMAIL, normalizeEmail(email)));
        }
        if (clientIp != null && !clientIp.isBlank()) {
            buckets.add(lockBucket(SCOPE_LOGIN_IP, normalizeIp(clientIp)));
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant windowStart = now.minus(WINDOW_DURATION);
        long retryAfter = 0;
        for (AuthRateLimit bucket : buckets) {
            if (bucket.isBlocked(now)) {
                Duration remaining = Duration.between(now, bucket.getBlockedUntil());
                long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
                retryAfter = Math.max(retryAfter, seconds);
            } else if (eventRepository.countOccupiedSlots(bucket.getId(), now, windowStart)
                    >= threshold(bucket)) {
                retryAfter = Math.max(retryAfter, 1);
            }
        }
        if (retryAfter > 0) {
            throw new RateLimitExceededException(retryAfter);
        }

        Instant expiresAt = now.plus(RESERVATION_DURATION);
        List<Slot> slots = new ArrayList<>(buckets.size());
        for (AuthRateLimit bucket : buckets) {
            eventRepository.deleteExpiredReservations(bucket.getId(), now, windowStart);
            var event = eventRepository.save(AuthRateLimitEvent.reserve(bucket, now, expiresAt));
            slots.add(new Slot(bucket.getId(), event.getId()));
        }
        return new Attempt(slots, expiresAt);
    }

    private boolean settle(Attempt attempt, Outcome outcome) {
        List<AuthRateLimit> buckets =
                attempt.slots().stream()
                        .map(
                                slot ->
                                        rateLimitRepository
                                                .findBucketForUpdate(slot.bucketId())
                                                .orElseThrow())
                        .toList();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean active = attempt.expiresAt().isAfter(now);
        for (int i = 0; i < buckets.size(); i++) {
            AuthRateLimit bucket = buckets.get(i);
            long eventId = attempt.slots().get(i).eventId();
            if (active && outcome == Outcome.FAILURE) {
                eventRepository.findById(eventId).orElseThrow().recordFailure(now);
                long failures =
                        eventRepository.countByRateLimitIdAndOccurredAtAfter(
                                bucket.getId(), now.minus(WINDOW_DURATION));
                if (failures >= threshold(bucket) && !bucket.isBlocked(now)) {
                    bucket.blockUntil(now.plus(BLOCK_DURATION));
                }
            } else {
                eventRepository.deleteById(eventId);
                if (active
                        && outcome == Outcome.LOGIN
                        && SCOPE_LOGIN_EMAIL.equals(bucket.getScope())) {
                    eventRepository.deleteFailures(bucket.getId());
                    bucket.blockUntil(null);
                    bucket.resetAt(now);
                }
            }
        }
        return active;
    }

    private AuthRateLimit lockBucket(String scope, String subject) {
        String hash = computeHmacSha256(subject);
        rateLimitRepository.insertIfNotExists(UUID.randomUUID(), scope, hash, clock.instant());
        return rateLimitRepository.findBucketForUpdate(scope, hash).orElseThrow();
    }

    private static long threshold(AuthRateLimit bucket) {
        return SCOPE_LOGIN_EMAIL.equals(bucket.getScope())
                ? EMAIL_FAILURE_THRESHOLD
                : IP_FAILURE_THRESHOLD;
    }

    private record Slot(UUID bucketId, long eventId) {}

    private record Attempt(List<Slot> slots, Instant expiresAt) {}

    private enum Outcome {
        FAILURE,
        LOGIN,
        VERIFIED,
        CANCELLED
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
