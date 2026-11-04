package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.request.ChangePasswordRequest;
import com.masterenglish.api.modules.identity.dto.request.PasswordChangeOtpRequest;
import com.masterenglish.api.modules.identity.dto.response.PasswordChangeChallengeResponse;
import com.masterenglish.api.modules.identity.entity.AuthSession;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.PasswordChangeStore;
import com.masterenglish.api.modules.identity.repository.RegistrationStore;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordChangeService {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordChangeService.class);
    private static final String PURPOSE = "PASSWORD_CHANGE";

    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final AuthSessionService sessionService;
    private final PasswordChangeStore store;
    private final RegistrationStore emailKeys;
    private final RateLimiterService passwordLimiter;
    private final OtpRateLimiter otpLimiter;
    private final OtpCodec codec;
    private final RegistrationMailer mailer;
    private final RegistrationPasswordPolicy policy;
    private final BreachedPasswordChecker breaches;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PasswordChangeService(
            UserRepository users,
            AuthSessionRepository sessions,
            AuthSessionService sessionService,
            PasswordChangeStore store,
            RegistrationStore emailKeys,
            RateLimiterService passwordLimiter,
            OtpRateLimiter otpLimiter,
            OtpCodec codec,
            RegistrationMailer mailer,
            RegistrationPasswordPolicy policy,
            BreachedPasswordChecker breaches,
            PasswordEncoder encoder,
            TransactionTemplate tx,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.store = store;
        this.emailKeys = emailKeys;
        this.passwordLimiter = passwordLimiter;
        this.otpLimiter = otpLimiter;
        this.codec = codec;
        this.mailer = mailer;
        this.policy = policy;
        this.breaches = breaches;
        this.encoder = encoder;
        this.tx = tx;
        this.clock = clock;
    }

    public PasswordChangeChallengeResponse request(
            UserPrincipal principal, ChangePasswordRequest request, String ip) {
        String email = emailKeys.emailKey(principal.getEmail());
        passwordLimiter.checkLoginRateLimit(email, ip);
        User snapshot =
                users.findById(principal.getId())
                        .orElseThrow(PasswordChangeService::unauthenticated);
        requireAccount(snapshot, principal);
        String current = Normalizer.normalize(request.currentPassword(), Normalizer.Form.NFC);
        if (current.getBytes(StandardCharsets.UTF_8).length > 72
                || !encoder.matches(current, snapshot.getPasswordHash())) {
            passwordLimiter.recordLoginFailure(email, ip);
            throw new BusinessException(
                    ErrorCode.PASSWORD_REJECTED, "Mật khẩu hiện tại không chính xác.");
        }
        String next = policy.normalizeAndValidate(request.newPassword());
        if (!next.equals(Normalizer.normalize(request.confirmPassword(), Normalizer.Form.NFC))) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Xác nhận mật khẩu mới không khớp.");
        }
        if (next.equals(current)) {
            throw new BusinessException(
                    ErrorCode.PASSWORD_REJECTED, "Vui lòng chọn mật khẩu khác mật khẩu hiện tại.");
        }
        mailer.ensureReady();
        breaches.rejectIfBreached(next);
        String hash = encoder.encode(next);
        UUID id = UUID.randomUUID();
        String code = codec.generate();
        String hmac = codec.hmac(PURPOSE, id, code);
        Instant expiresAt =
                tx.execute(
                        status -> {
                            reserveSend(email, ip);
                            var context = lockContext(principal);
                            Instant now = context.now();
                            Instant maxExpiry = now.plusSeconds(300);
                            Instant expiry =
                                    context.session().getExpiresAt().isBefore(maxExpiry)
                                            ? context.session().getExpiresAt()
                                            : maxExpiry;
                            store.issue(
                                    id,
                                    principal.getId(),
                                    principal.getSessionId(),
                                    principal.getAuthVersion(),
                                    hash,
                                    hmac,
                                    now,
                                    expiry);
                            return expiry;
                        });
        deliver(principal, id, code, hmac);
        return new PasswordChangeChallengeResponse(id, expiresAt, 60);
    }

    public PasswordChangeChallengeResponse resend(UserPrincipal principal, UUID id, String ip) {
        mailer.ensureReady();
        String code = codec.generate();
        String hmac = codec.hmac(PURPOSE, id, code);
        Instant expiresAt =
                tx.execute(
                        status -> {
                            reserveSend(emailKeys.emailKey(principal.getEmail()), ip);
                            var context = lockContext(principal);
                            var pending = findPending(principal, id);
                            if (pending == null) throw invalidCode();
                            if (!pending.expiresAt().isAfter(context.now())) throw expired();
                            store.resend(id, hmac, context.now());
                            return pending.expiresAt();
                        });
        deliver(principal, id, code, hmac);
        return new PasswordChangeChallengeResponse(id, expiresAt, 60);
    }

    public void confirm(UserPrincipal principal, PasswordChangeOtpRequest input, String ip) {
        String email = emailKeys.emailKey(principal.getEmail());
        BusinessException failure =
                tx.execute(
                        status -> {
                            var limit =
                                    otpLimiter.reserveVerification(
                                            email, rateIp(ip), PURPOSE, clock.instant());
                            if (limit != null) return limit;
                            var context = lockContext(principal);
                            UUID id = input.challengeId();
                            var pending = findPending(principal, id);
                            Instant now = context.now();
                            if (pending == null) return invalidCode();
                            if (!pending.expiresAt().isAfter(now)) return expired();
                            if (!codec.matches(pending.otpHmac(), PURPOSE, id, input.otp())) {
                                store.wrongCode(id);
                                var blocked = otpLimiter.wrongCode(email, PURPOSE, now);
                                return blocked == null ? invalidCode() : blocked;
                            }
                            store.complete(id, now);
                            context.user().changePassword(pending.passwordHash());
                            sessionService.revokeAllSessions(
                                    principal.getId(), RevokeReason.PASSWORD_CHANGE);
                            return null;
                        });
        if (failure != null) throw failure;
        Thread.ofVirtual()
                .name("password-change-notice")
                .start(
                        () -> {
                            try {
                                mailer.sendPasswordChangeNotice(email);
                            } catch (RuntimeException error) {
                                LOG.warn(
                                        "Password changed; security notification failed: {}",
                                        error.getClass().getSimpleName());
                            }
                        });
    }

    private PasswordChangeStore.Pending findPending(UserPrincipal principal, UUID id) {
        return store.find(
                        id, principal.getId(), principal.getSessionId(), principal.getAuthVersion())
                .orElse(null);
    }

    private void reserveSend(String email, String ip) {
        var limit = otpLimiter.reserveSend(email, rateIp(ip), clock.instant());
        if (limit != null) throw limit;
    }

    private Context lockContext(UserPrincipal principal) {
        User user =
                users.findByIdForUpdate(principal.getId())
                        .orElseThrow(PasswordChangeService::unauthenticated);
        requireAccount(user, principal);
        AuthSession session =
                sessions.findByIdForUpdate(principal.getSessionId())
                        .orElseThrow(PasswordChangeService::unauthenticated);
        Instant now = clock.instant();
        if (!session.getUser().getId().equals(user.getId())
                || session.getRevokedAt() != null
                || !session.getExpiresAt().isAfter(now)) throw unauthenticated();
        return new Context(user, session, now);
    }

    private void deliver(UserPrincipal principal, UUID id, String code, String hmac) {
        try {
            mailer.sendPasswordChange(principal.getEmail(), code);
        } catch (BusinessException failure) {
            tx.executeWithoutResult(
                    status -> {
                        users.findByIdForUpdate(principal.getId());
                        store.invalidateDelivery(id, hmac, clock.instant());
                    });
            throw failure;
        }
    }

    private static void requireAccount(User user, UserPrincipal principal) {
        if (!user.isActive()
                || user.getAuthVersion() != principal.getAuthVersion()
                || principal.getSessionId() == null) throw unauthenticated();
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(
                ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
    }

    private static BusinessException invalidCode() {
        return new BusinessException(
                ErrorCode.OTP_INVALID, "Mã hoặc yêu cầu đổi mật khẩu không hợp lệ.");
    }

    private static BusinessException expired() {
        return new BusinessException(ErrorCode.OTP_EXPIRED, "Yêu cầu đổi mật khẩu đã hết hạn.");
    }

    private static String rateIp(String ip) {
        return ip == null ? "unknown" : ip;
    }

    private record Context(User user, AuthSession session, Instant now) {}
}
