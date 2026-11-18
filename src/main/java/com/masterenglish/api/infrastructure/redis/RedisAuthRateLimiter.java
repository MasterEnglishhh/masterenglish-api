package com.masterenglish.api.infrastructure.redis;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.error.RateLimitExceededException;
import com.masterenglish.api.modules.identity.service.LoginRateLimitSubjects;
import com.masterenglish.api.modules.identity.service.OtpRateLimitStore;
import com.masterenglish.api.modules.identity.service.RateLimiterService;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.security.rate-limit.backend", havingValue = "redis")
public class RedisAuthRateLimiter implements RateLimiterService, OtpRateLimitStore {

    private static final String KEY_PREFIX = "masterenglish:auth:{rate-limits}:";
    private static final String OTP_SEND_EMAIL = "OTP_SEND_EMAIL";
    private static final String OTP_SEND_IP = "OTP_SEND_IP";
    private static final String OTP_VERIFY_IP = "OTP_VERIFY_IP";

    private final RedisClient client;
    private final LoginRateLimitSubjects subjects;
    private final Clock clock;
    private final String script;
    private StatefulRedisConnection<String, String> connection;

    public RedisAuthRateLimiter(RedisClient client, LoginRateLimitSubjects subjects, Clock clock)
            throws IOException {
        this.client = client;
        this.subjects = subjects;
        this.clock = clock;
        this.script =
                new ClassPathResource("redis/auth-rate-limit.lua")
                        .getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public void checkLoginRateLimit(String email, String clientIp) {
        String emailHash = subjects.emailHash(email);
        String ipHash = subjects.ipHash(clientIp);
        List<String> keys = new ArrayList<>(2);
        if (emailHash != null) {
            keys.add(blockKey(LoginRateLimitSubjects.EMAIL_SCOPE, emailHash));
        }
        if (ipHash != null) {
            keys.add(blockKey(LoginRateLimitSubjects.IP_SCOPE, ipHash));
        }
        long wait = execute("blocked", clock.instant(), keys);
        if (wait > 0) {
            long retryAfter = Math.max(1, wait / 1000);
            log.debug("Login rate limit reached; retry after {} seconds", retryAfter);
            throw new RateLimitExceededException(
                    retryAfter, "Too many login attempts. Please try again later.");
        }
    }

    @Override
    public void recordLoginFailure(String email, String clientIp) {
        String emailHash = subjects.emailHash(email);
        String ipHash = subjects.ipHash(clientIp);
        List<String> keys = new ArrayList<>(4);
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                milliseconds(LoginRateLimitSubjects.WINDOW_SECONDS),
                                milliseconds(LoginRateLimitSubjects.BLOCK_SECONDS)));
        if (emailHash != null) {
            keys.add(eventKey(LoginRateLimitSubjects.EMAIL_SCOPE, emailHash));
            keys.add(blockKey(LoginRateLimitSubjects.EMAIL_SCOPE, emailHash));
            arguments.add(Integer.toString(LoginRateLimitSubjects.EMAIL_LIMIT));
        }
        if (ipHash != null) {
            keys.add(eventKey(LoginRateLimitSubjects.IP_SCOPE, ipHash));
            keys.add(blockKey(LoginRateLimitSubjects.IP_SCOPE, ipHash));
            arguments.add(Integer.toString(LoginRateLimitSubjects.IP_LIMIT));
        }
        execute("login-failure", clock.instant(), keys, arguments.toArray(String[]::new));
    }

    @Override
    public void resetLoginFailure(String email) {
        String emailHash = subjects.emailHash(email);
        if (emailHash != null) {
            String scope = LoginRateLimitSubjects.EMAIL_SCOPE;
            execute(
                    "reset",
                    clock.instant(),
                    List.of(eventKey(scope, emailHash), blockKey(scope, emailHash)));
        }
    }

    @Override
    public long reserveSend(String emailHash, String ipHash, Instant now) {
        return retryAfter(
                execute(
                        "otp-send",
                        now,
                        List.of(eventKey(OTP_SEND_EMAIL, emailHash), eventKey(OTP_SEND_IP, ipHash)),
                        milliseconds(SEND_WINDOW_SECONDS),
                        milliseconds(SEND_COOLDOWN_SECONDS),
                        Integer.toString(SEND_EMAIL_LIMIT),
                        Integer.toString(IP_LIMIT)));
    }

    @Override
    public long reserveVerification(String scope, String emailHash, String ipHash, Instant now) {
        return retryAfter(
                execute(
                        "otp-verify",
                        now,
                        List.of(blockKey(scope, emailHash), eventKey(OTP_VERIFY_IP, ipHash)),
                        milliseconds(VERIFY_WINDOW_SECONDS),
                        Integer.toString(IP_LIMIT)));
    }

    @Override
    public long checkVerificationBlocked(String scope, String emailHash, Instant now) {
        return retryAfter(execute("blocked", now, List.of(blockKey(scope, emailHash))));
    }

    @Override
    public long wrongCode(String scope, String emailHash, Instant now) {
        return retryAfter(
                execute(
                        "otp-wrong",
                        now,
                        List.of(eventKey(scope, emailHash), blockKey(scope, emailHash)),
                        milliseconds(VERIFY_WINDOW_SECONDS),
                        Integer.toString(VERIFY_EMAIL_LIMIT)));
    }

    private long execute(String operation, Instant now, List<String> keys, String... options) {
        if (keys.isEmpty()) {
            return 0;
        }
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                operation,
                                Long.toString(now.toEpochMilli()),
                                UUID.randomUUID().toString()));
        Collections.addAll(arguments, options);
        try {
            return connection()
                    .sync()
                    .eval(
                            script,
                            ScriptOutputType.INTEGER,
                            keys.toArray(String[]::new),
                            arguments.toArray(String[]::new));
        } catch (RedisException exception) {
            log.warn(
                    "Auth rate-limit Redis operation {} failed ({})",
                    operation,
                    exception.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Authentication temporarily unavailable.");
        }
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection;
    }

    @PreDestroy
    synchronized void closeConnection() {
        if (connection != null) {
            connection.close();
        }
    }

    private static String eventKey(String scope, String hash) {
        return KEY_PREFIX + scope + ":" + hash + ":events";
    }

    private static String blockKey(String scope, String hash) {
        return KEY_PREFIX + scope + ":" + hash + ":block";
    }

    private static String milliseconds(int seconds) {
        return Long.toString(seconds * 1000L);
    }

    private static long retryAfter(long milliseconds) {
        return (milliseconds + 999) / 1000;
    }
}
