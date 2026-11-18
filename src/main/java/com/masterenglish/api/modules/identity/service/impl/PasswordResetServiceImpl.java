package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.request.PasswordResetOtpRequest;
import com.masterenglish.api.modules.identity.dto.request.ResetPasswordRequest;
import com.masterenglish.api.modules.identity.dto.response.PasswordResetChallengeResponse;
import com.masterenglish.api.modules.identity.dto.response.PasswordResetGrantResponse;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.BreachedPasswordChecker;
import com.masterenglish.api.modules.identity.service.OtpCodec;
import com.masterenglish.api.modules.identity.service.OtpRateLimiter;
import com.masterenglish.api.modules.identity.service.PasswordResetService;
import com.masterenglish.api.modules.identity.service.RegistrationMailer;
import com.masterenglish.api.modules.identity.service.RegistrationPasswordPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class PasswordResetServiceImpl implements PasswordResetService {
    private static final String PURPOSE = "PASSWORD_RESET";
    private final UserRepository store;
    private final OtpRateLimiter limiter;
    private final OtpCodec codec;
    private final RegistrationMailer mailer;
    private final RegistrationPasswordPolicy policy;
    private final BreachedPasswordChecker breaches;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PasswordResetServiceImpl(
            UserRepository store,
            OtpRateLimiter limiter,
            OtpCodec codec,
            RegistrationMailer mailer,
            RegistrationPasswordPolicy policy,
            BreachedPasswordChecker breaches,
            PasswordEncoder encoder,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.limiter = limiter;
        this.codec = codec;
        this.mailer = mailer;
        this.policy = policy;
        this.breaches = breaches;
        this.encoder = encoder;
        this.clock = clock;
        tx = new TransactionTemplate(transactions);
    }

    private record Verification(PasswordResetGrantResponse grant, BusinessException error) {}

    @Override
    public PasswordResetChallengeResponse request(String rawEmail, String clientIp) {
        mailer.ensureReady();
        String email = store.emailKey(rawEmail);
        BusinessException blocked =
                tx.execute(status -> limiter.reserveSend(email, ip(clientIp), clock.instant()));
        if (blocked != null) throw blocked;
        UUID id = UUID.randomUUID();
        String code = codec.generate();
        Instant now = clock.instant();
        boolean send =
                Boolean.TRUE.equals(
                        tx.execute(
                                status -> {
                                    var user = store.lockPasswordResetUser(email).orElse(null);
                                    if (user == null || !user.eligible()) return false;
                                    store.issue(user, id, codec.hmac(PURPOSE, id, code), now);
                                    return true;
                                }));
        // SMTP latency must not disclose whether an email is registered. No raw OTP is
        // queued in
        // DB.
        if (send)
            Thread.ofVirtual()
                    .name("password-reset-mail")
                    .start(
                            () -> {
                                try {
                                    mailer.sendPasswordReset(email, code);
                                } catch (RuntimeException error) {
                                    tx.executeWithoutResult(
                                            status -> {
                                                store.lockPasswordResetUser(email);
                                                store.invalidateFailedDelivery(id, clock.instant());
                                            });
                                    log.warn(
                                            "Password reset email delivery failed: {}",
                                            error.getClass().getSimpleName());
                                }
                            });
        return new PasswordResetChallengeResponse(
                id,
                now.plusSeconds(300),
                60,
                "Nếu email đã có tài khoản được xác minh, mã đặt lại mật khẩu sẽ được gửi đến hộp thư của bạn.");
    }

    @Override
    public PasswordResetGrantResponse verify(PasswordResetOtpRequest input, String clientIp) {
        String email = store.emailKey(input.email());
        Verification outcome =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            BusinessException blocked =
                                    limiter.reserveVerification(email, ip(clientIp), PURPOSE, now);
                            if (blocked != null) return new Verification(null, blocked);
                            var user = store.lockPasswordResetUser(email).orElse(null);
                            if (user != null) {
                                blocked =
                                        limiter.checkVerificationBlocked(
                                                email, PURPOSE, clock.instant());
                                if (blocked != null) return new Verification(null, blocked);
                            }
                            var challenge =
                                    user == null
                                            ? null
                                            : store.challenge(input.challengeId(), user.id())
                                                    .orElse(null);
                            if (user == null
                                    || !user.eligible()
                                    || challenge == null
                                    || challenge.closed()) return wrong(email, null, now);
                            if (!challenge.expiresAt().isAfter(now))
                                return new Verification(
                                        null,
                                        new BusinessException(
                                                ErrorCode.OTP_EXPIRED,
                                                "Mã đã hết hạn. Vui lòng yêu cầu mã mới."));
                            if (!codec.matches(
                                    challenge.hmac(), PURPOSE, challenge.id(), input.otp()))
                                return wrong(email, challenge.id(), now);
                            String token = codec.token();
                            store.issueGrant(user, challenge.id(), codec.hashToken(token), now);
                            return new Verification(
                                    new PasswordResetGrantResponse(token, now.plusSeconds(300)),
                                    null);
                        });
        if (outcome.error() != null) throw outcome.error();
        return outcome.grant();
    }

    @Override
    public void reset(ResetPasswordRequest input) {
        String email = store.emailKey(input.email());
        String hash = codec.hashToken(input.resetToken());
        String normalized = policy.normalizeAndValidate(input.newPassword());
        // Validate the high-entropy grant before doing external checks or BCrypt work.
        var account =
                tx.execute(
                        status -> {
                            var user = store.lockPasswordResetUser(email).orElse(null);
                            requireGrant(user, hash, clock.instant());
                            return user;
                        });
        if (encoder.matches(normalized, account.passwordHash()))
            throw new BusinessException(
                    ErrorCode.PASSWORD_REJECTED, "Vui lòng chọn mật khẩu khác mật khẩu hiện tại.");
        breaches.rejectIfBreached(normalized);
        String encoded = encoder.encode(normalized);
        tx.executeWithoutResult(
                status -> {
                    Instant now = clock.instant();
                    var user = store.lockPasswordResetUser(email).orElse(null);
                    var grant = requireGrant(user, hash, now);
                    store.completePasswordReset(user, grant, encoded, now);
                });
        Thread.ofVirtual()
                .name("password-reset-notice")
                .start(
                        () -> {
                            try {
                                mailer.sendPasswordResetNotice(email);
                            } catch (RuntimeException error) {
                                log.warn(
                                        "Password reset completed; security notification failed: {}",
                                        error.getClass().getSimpleName());
                            }
                        });
    }

    private UserRepository.Grant requireGrant(
            UserRepository.Account user, String hash, Instant now) {
        if (user == null || !user.eligible()) throw invalidGrant();
        var grant = store.grant(user.id(), hash).orElse(null);
        if (grant == null
                || grant.closed()
                || !grant.expiresAt().isAfter(now)
                || grant.authVersion() != user.authVersion()) throw invalidGrant();
        return grant;
    }

    private Verification wrong(String email, UUID challenge, Instant now) {
        if (challenge != null) store.recordWrongCode(challenge);
        BusinessException limit = limiter.wrongCode(email, PURPOSE, now);
        return new Verification(
                null,
                limit != null
                        ? limit
                        : new BusinessException(
                                ErrorCode.OTP_INVALID,
                                "Mã xác minh hoặc yêu cầu không hợp lệ. Vui lòng kiểm tra lại."));
    }

    private static BusinessException invalidGrant() {
        return new BusinessException(
                ErrorCode.RESET_TOKEN_INVALID,
                "Quyền đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng xác minh OTP lại.");
    }

    private static String ip(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }
}
