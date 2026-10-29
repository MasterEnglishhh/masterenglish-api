package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.config.RegistrationProperties;
import com.masterenglish.api.modules.identity.dto.RegisterRequest;
import com.masterenglish.api.modules.identity.dto.RegistrationChallengeResponse;
import com.masterenglish.api.modules.identity.dto.RegistrationOtpRequest;
import com.masterenglish.api.modules.identity.dto.ResendRegistrationOtpRequest;
import com.masterenglish.api.modules.identity.repository.RegistrationStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RegistrationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CYCLE = Duration.ofDays(7);
    private final RegistrationStore store;
    private final OtpRateLimiter limiter;
    private final PasswordEncoder encoder;
    private final RegistrationPasswordPolicy policy;
    private final BreachedPasswordChecker breachChecker;
    private final RegistrationMailer mailer;
    private final RegistrationProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RegistrationService(
            RegistrationStore store,
            OtpRateLimiter limiter,
            PasswordEncoder encoder,
            RegistrationPasswordPolicy policy,
            BreachedPasswordChecker breachChecker,
            RegistrationMailer mailer,
            RegistrationProperties properties,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.limiter = limiter;
        this.encoder = encoder;
        this.policy = policy;
        this.breachChecker = breachChecker;
        this.mailer = mailer;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    private record Issued(
            UUID registrationId, UUID challengeId, Instant expiresAt, String email, String otp) {
        @Override
        public String toString() {
            return "Issued[redacted]";
        }
    }

    public RegistrationChallengeResponse register(RegisterRequest input, String clientIp) {
        mailer.ensureReady();
        String normalized = policy.normalizeAndValidate(input.password());
        String email = store.emailKey(input.email());
        String ip = ip(clientIp);
        // Commit quota reservations independently of SMTP/network failures.
        BusinessException blocked =
                tx.execute(status -> limiter.reserveSend(email, ip, clock.instant()));
        if (blocked != null) throw blocked;
        breachChecker.rejectIfBreached(normalized);
        String encoded = encoder.encode(normalized);
        Issued issued =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            var user = store.createAndLockUser(email, now);
                            if (!user.status().equals("PENDING_VERIFICATION"))
                                return generic(UUID.randomUUID(), now);
                            if (!user.createdAt().plus(CYCLE).isAfter(now))
                                user = store.restartExpiredPendingCycle(user, now);
                            store.invalidateOpen(user.id(), now);
                            UUID id = UUID.randomUUID();
                            UUID challengeId = UUID.randomUUID();
                            String otp = generateCode();
                            Instant cycleEnd = user.createdAt().plus(CYCLE);
                            Instant expires = min(now.plusSeconds(300), cycleEnd);
                            store.insertChallenge(
                                    challengeId, user.id(), hmac(challengeId, otp), now, expires);
                            store.insertRequest(
                                    id,
                                    user.id(),
                                    challengeId,
                                    input.firstName(),
                                    input.lastName(),
                                    encoded,
                                    now,
                                    cycleEnd);
                            return new Issued(id, challengeId, expires, email, otp);
                        });
        return deliver(issued);
    }

    public RegistrationChallengeResponse resend(
            ResendRegistrationOtpRequest input, String clientIp) {
        mailer.ensureReady();
        String email = store.emailKey(input.email());
        BusinessException blocked =
                tx.execute(status -> limiter.reserveSend(email, ip(clientIp), clock.instant()));
        if (blocked != null) throw blocked;
        Issued issued =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            var user = store.lockUser(email).orElse(null);
                            if (user == null || !user.status().equals("PENDING_VERIFICATION"))
                                return generic(input.registrationId(), now);
                            var request =
                                    store.find(input.registrationId(), user.id()).orElse(null);
                            if (request == null
                                    || request.closed()
                                    || !request.requestExpiresAt().isAfter(now))
                                return generic(input.registrationId(), now);
                            UUID challengeId = UUID.randomUUID();
                            String otp = generateCode();
                            Instant expires = min(now.plusSeconds(300), request.requestExpiresAt());
                            store.replaceChallenge(
                                    request, challengeId, hmac(challengeId, otp), now, expires);
                            return new Issued(request.id(), challengeId, expires, email, otp);
                        });
        return deliver(issued);
    }

    public void verify(RegistrationOtpRequest input, String clientIp) {
        String email = store.emailKey(input.email());
        BusinessException error =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            BusinessException blocked =
                                    limiter.reserveVerification(email, ip(clientIp), now);
                            if (blocked != null) return blocked;
                            var user = store.lockUser(email).orElse(null);
                            var request =
                                    user == null
                                            ? null
                                            : store.find(input.registrationId(), user.id())
                                                    .orElse(null);
                            if (user == null
                                    || !user.status().equals("PENDING_VERIFICATION")
                                    || request == null
                                    || request.closed()) {
                                BusinessException limit = limiter.wrongCode(email, now);
                                return limit != null ? limit : invalid();
                            }
                            if (!request.requestExpiresAt().isAfter(now))
                                return new BusinessException(
                                        ErrorCode.REGISTRATION_EXPIRED,
                                        "Lượt đăng ký đã hết hạn. Vui lòng đăng ký lại.");
                            if (!request.otpExpiresAt().isAfter(now))
                                return new BusinessException(
                                        ErrorCode.OTP_EXPIRED,
                                        "Mã xác minh đã hết hạn. Vui lòng yêu cầu mã mới.");
                            String expected = request.otpHmac();
                            if (expected == null
                                    || !MessageDigest.isEqual(
                                            expected.getBytes(StandardCharsets.US_ASCII),
                                            hmac(request.challengeId(), input.otp())
                                                    .getBytes(StandardCharsets.US_ASCII))) {
                                store.recordWrongCode(request.challengeId());
                                BusinessException limit = limiter.wrongCode(email, now);
                                return limit != null ? limit : invalid();
                            }
                            store.complete(request, now);
                            return null;
                        });
        // Throw only AFTER commit, so invalid attempts and blocks cannot roll back.
        if (error != null) throw error;
    }

    private RegistrationChallengeResponse deliver(Issued issued) {
        if (issued.email() != null) {
            try {
                mailer.send(issued.email(), issued.otp());
            } catch (BusinessException e) {
                tx.executeWithoutResult(
                        status -> {
                            var user = store.lockUser(issued.email()).orElse(null);
                            if (user != null) {
                                var current =
                                        store.find(issued.registrationId(), user.id()).orElse(null);
                                if (current != null
                                        && !current.closed()
                                        && current.challengeId().equals(issued.challengeId()))
                                    store.invalidateOpen(user.id(), clock.instant());
                            }
                        });
                throw e;
            }
        }
        return new RegistrationChallengeResponse(
                issued.registrationId(),
                issued.expiresAt(),
                60,
                "Nếu email có thể đăng ký, mã xác minh đã được gửi. Nếu đã có tài khoản, bạn có thể đăng nhập.");
    }

    private static Issued generic(UUID id, Instant now) {
        return new Issued(id, null, now.plusSeconds(300), null, null);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String ip(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }

    private static String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.OTP_INVALID,
                "Mã xác minh hoặc lượt đăng ký không hợp lệ. Vui lòng kiểm tra lại.");
    }

    private String hmac(UUID challengeId, String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            properties.otpSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    ("EMAIL_VERIFICATION:" + challengeId + ":" + otp)
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize OTP HMAC.", e);
        }
    }
}
