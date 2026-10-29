package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.RateLimitExceededException;
import com.masterenglish.api.config.RegistrationProperties;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Caller owns the transaction; buckets are locked before user/request/challenge rows. */
@Service
public class OtpRateLimiter {
    private final JdbcTemplate jdbc;
    private final String secret;

    public OtpRateLimiter(
            JdbcTemplate jdbc,
            RegistrationProperties properties,
            @Value("${app.security.rate-limit.secret:}") String rateSecret) {
        this.jdbc = jdbc;
        this.secret =
                rateSecret == null || rateSecret.isBlank() ? properties.otpSecret() : rateSecret;
    }

    private record Key(String scope, String hash) {}

    private record Bucket(UUID id, String scope, Instant blockedUntil, Instant lastSend) {}

    public RateLimitExceededException reserveSend(String email, String ip, Instant now) {
        List<Bucket> buckets =
                lock(
                        List.of(
                                new Key("OTP_SEND_EMAIL", hash(email)),
                                new Key("OTP_SEND_IP", hash(ip))),
                        now);
        long wait = 0;
        for (Bucket bucket : buckets) {
            if (bucket.scope().equals("OTP_SEND_EMAIL") && bucket.lastSend() != null)
                wait = Math.max(wait, seconds(now, bucket.lastSend().plusSeconds(60)));
            int threshold = bucket.scope().equals("OTP_SEND_EMAIL") ? 5 : 300;
            if (count(bucket.id(), now.minusSeconds(3600)) >= threshold) {
                Timestamp first =
                        jdbc.queryForObject(
                                "SELECT min(occurred_at) FROM auth_rate_limit_events WHERE rate_limit_id=? AND occurred_at>=?",
                                Timestamp.class,
                                bucket.id(),
                                Timestamp.from(now.minusSeconds(3600)));
                if (first != null)
                    wait = Math.max(wait, seconds(now, first.toInstant().plusSeconds(3600)));
            }
        }
        if (wait > 0) return limited(wait);
        for (Bucket bucket : buckets) {
            record(bucket.id(), now);
            jdbc.update(
                    "UPDATE auth_rate_limits SET reset_at=?,updated_at=? WHERE id=?",
                    Timestamp.from(now),
                    Timestamp.from(now),
                    bucket.id());
        }
        return null;
    }

    public RateLimitExceededException reserveVerification(String email, String ip, Instant now) {
        return reserveVerification(email, ip, "EMAIL_VERIFICATION", now);
    }

    public RateLimitExceededException reserveVerification(
            String email, String ip, String purpose, Instant now) {
        List<Bucket> buckets =
                lock(
                        List.of(
                                new Key(verificationScope(purpose), hash(email)),
                                new Key("OTP_VERIFY_IP", hash(ip))),
                        now);
        long wait = 0;
        for (Bucket bucket : buckets) {
            if (bucket.blockedUntil() != null)
                wait = Math.max(wait, seconds(now, bucket.blockedUntil()));
            if (bucket.scope().equals("OTP_VERIFY_IP")
                    && count(bucket.id(), now.minusSeconds(900)) >= 300) {
                Timestamp first =
                        jdbc.queryForObject(
                                "SELECT min(occurred_at) FROM auth_rate_limit_events WHERE rate_limit_id=? AND occurred_at>=?",
                                Timestamp.class,
                                bucket.id(),
                                Timestamp.from(now.minusSeconds(900)));
                if (first != null)
                    wait = Math.max(wait, seconds(now, first.toInstant().plusSeconds(900)));
            }
        }
        if (wait > 0) return limited(wait);
        for (Bucket bucket : buckets)
            if (bucket.scope().equals("OTP_VERIFY_IP")) record(bucket.id(), now);
        return null;
    }

    public RateLimitExceededException wrongCode(String email, Instant now) {
        return wrongCode(email, "EMAIL_VERIFICATION", now);
    }

    public RateLimitExceededException wrongCode(String email, String purpose, Instant now) {
        Bucket bucket =
                lock(List.of(new Key(verificationScope(purpose), hash(email))), now).getFirst();
        record(bucket.id(), now);
        // A completed block starts a fresh attempt window; resend never clears it.
        Instant window = now.minusSeconds(900);
        if (bucket.blockedUntil() != null
                && !bucket.blockedUntil().isAfter(now)
                && bucket.blockedUntil().isAfter(window)) window = bucket.blockedUntil();
        if (count(bucket.id(), window) >= 5) {
            jdbc.update(
                    "UPDATE auth_rate_limits SET blocked_until=?,updated_at=? WHERE id=?",
                    Timestamp.from(now.plusSeconds(900)),
                    Timestamp.from(now),
                    bucket.id());
            return limited(900);
        }
        return null;
    }

    private List<Bucket> lock(List<Key> keys, Instant now) {
        List<Key> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparing(Key::scope).thenComparing(Key::hash));
        List<Bucket> result = new ArrayList<>();
        for (Key key : ordered) {
            jdbc.update(
                    "INSERT INTO auth_rate_limits(id,scope,subject_hash,updated_at) VALUES (?,?,?,?) ON CONFLICT (scope,subject_hash) DO NOTHING",
                    UUID.randomUUID(),
                    key.scope(),
                    key.hash(),
                    Timestamp.from(now));
            result.add(
                    jdbc.queryForObject(
                            "SELECT id,scope,blocked_until,reset_at FROM auth_rate_limits WHERE scope=? AND subject_hash=? FOR UPDATE",
                            (rs, row) ->
                                    new Bucket(
                                            rs.getObject("id", UUID.class),
                                            rs.getString("scope"),
                                            rs.getTimestamp("blocked_until") == null
                                                    ? null
                                                    : rs.getTimestamp("blocked_until").toInstant(),
                                            rs.getTimestamp("reset_at") == null
                                                    ? null
                                                    : rs.getTimestamp("reset_at").toInstant()),
                            key.scope(),
                            key.hash()));
        }
        return result;
    }

    private static String verificationScope(String purpose) {
        return switch (purpose) {
            case "EMAIL_VERIFICATION", "PASSWORD_RESET", "PASSWORD_CHANGE" ->
                    "OTP_VERIFY_" + purpose;
            default -> throw new IllegalArgumentException("Unsupported OTP purpose");
        };
    }

    private long count(UUID id, Instant since) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auth_rate_limit_events WHERE rate_limit_id=? AND occurred_at>=?",
                Long.class,
                id,
                Timestamp.from(since));
    }

    private void record(UUID id, Instant now) {
        jdbc.update(
                "INSERT INTO auth_rate_limit_events(rate_limit_id,occurred_at) VALUES (?,?)",
                id,
                Timestamp.from(now));
    }

    private static long seconds(Instant from, Instant to) {
        return to.isAfter(from)
                ? Math.max(1, (Duration.between(from, to).toMillis() + 999) / 1000)
                : 0;
    }

    private static RateLimitExceededException limited(long seconds) {
        return new RateLimitExceededException(
                seconds, "Bạn đã thử quá nhiều lần. Vui lòng chờ trước khi thử lại.");
    }

    private String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize rate-limit HMAC.", e);
        }
    }
}
