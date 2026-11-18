package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.modules.identity.repository.AuthRateLimitEventRepository;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository.Bucket;
import com.masterenglish.api.modules.identity.service.OtpRateLimitStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Caller owns the transaction; buckets are locked before user/request/challenge rows. */
@ConditionalOnProperty(
        name = "app.security.rate-limit.backend",
        havingValue = "postgres",
        matchIfMissing = true)
@Service
class PostgresOtpRateLimitStore implements OtpRateLimitStore {
    private final AuthRateLimitRepository store;
    private final AuthRateLimitEventRepository events;

    PostgresOtpRateLimitStore(AuthRateLimitRepository store, AuthRateLimitEventRepository events) {
        this.store = store;
        this.events = events;
    }

    private record Key(String scope, String hash) {}

    @Override
    public long reserveSend(String emailHash, String ipHash, Instant now) {
        List<Bucket> buckets =
                lock(
                        List.of(
                                new Key("OTP_SEND_EMAIL", emailHash),
                                new Key("OTP_SEND_IP", ipHash)),
                        now);
        long wait = 0;
        for (Bucket bucket : buckets) {
            if (bucket.scope().equals("OTP_SEND_EMAIL") && bucket.lastSend() != null)
                wait =
                        Math.max(
                                wait,
                                seconds(now, bucket.lastSend().plusSeconds(SEND_COOLDOWN_SECONDS)));
            int threshold = bucket.scope().equals("OTP_SEND_EMAIL") ? SEND_EMAIL_LIMIT : IP_LIMIT;
            if (count(bucket.id(), now.minusSeconds(SEND_WINDOW_SECONDS)) >= threshold) {
                Instant first =
                        events.firstEvent(bucket.id(), now.minusSeconds(SEND_WINDOW_SECONDS));
                if (first != null)
                    wait = Math.max(wait, seconds(now, first.plusSeconds(SEND_WINDOW_SECONDS)));
            }
        }
        if (wait > 0) return wait;
        for (Bucket bucket : buckets) {
            record(bucket.id(), now);
            store.markSent(bucket.id(), now);
        }
        return 0;
    }

    @Override
    public long reserveVerification(String scope, String emailHash, String ipHash, Instant now) {
        List<Bucket> buckets =
                lock(List.of(new Key(scope, emailHash), new Key("OTP_VERIFY_IP", ipHash)), now);
        long wait = 0;
        for (Bucket bucket : buckets) {
            if (bucket.blockedUntil() != null)
                wait = Math.max(wait, seconds(now, bucket.blockedUntil()));
            if (bucket.scope().equals("OTP_VERIFY_IP")
                    && count(bucket.id(), now.minusSeconds(VERIFY_WINDOW_SECONDS)) >= IP_LIMIT) {
                Instant first =
                        events.firstEvent(bucket.id(), now.minusSeconds(VERIFY_WINDOW_SECONDS));
                if (first != null)
                    wait = Math.max(wait, seconds(now, first.plusSeconds(VERIFY_WINDOW_SECONDS)));
            }
        }
        if (wait > 0) return wait;
        for (Bucket bucket : buckets)
            if (bucket.scope().equals("OTP_VERIFY_IP")) record(bucket.id(), now);
        return 0;
    }

    @Override
    public long wrongCode(String scope, String emailHash, Instant now) {
        Bucket bucket = lock(List.of(new Key(scope, emailHash)), now).getFirst();
        record(bucket.id(), now);
        // A completed block starts a fresh attempt window; resend never clears it.
        Instant window = now.minusSeconds(VERIFY_WINDOW_SECONDS);
        if (bucket.blockedUntil() != null
                && !bucket.blockedUntil().isAfter(now)
                && bucket.blockedUntil().isAfter(window)) window = bucket.blockedUntil();
        if (count(bucket.id(), window) >= VERIFY_EMAIL_LIMIT) {
            store.block(bucket.id(), now.plusSeconds(VERIFY_WINDOW_SECONDS), now);
            return VERIFY_WINDOW_SECONDS;
        }
        return 0;
    }

    private List<Bucket> lock(List<Key> keys, Instant now) {
        List<Key> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparing(Key::scope).thenComparing(Key::hash));
        List<Bucket> buckets = new ArrayList<>();
        for (Key key : ordered) buckets.add(store.lock(key.scope(), key.hash(), now));
        return buckets;
    }

    private long count(UUID id, Instant since) {
        return events.countByRateLimitIdAndOccurredAtAfter(id, since);
    }

    private void record(UUID id, Instant now) {
        events.record(id, now);
    }

    private static long seconds(Instant from, Instant to) {
        return to.isAfter(from)
                ? Math.max(1, (Duration.between(from, to).toMillis() + 999) / 1000)
                : 0;
    }
}
