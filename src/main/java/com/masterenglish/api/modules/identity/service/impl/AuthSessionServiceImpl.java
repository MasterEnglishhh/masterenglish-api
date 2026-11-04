package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.entity.AuthSession;
import com.masterenglish.api.modules.identity.entity.RefreshToken;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.RefreshTokenRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.JwtTokenService;
import com.masterenglish.api.modules.identity.service.AuthSessionService;
import com.masterenglish.api.modules.identity.service.RefreshTokenRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionServiceImpl implements AuthSessionService {

    public static final int MAX_ACTIVE_SESSIONS = 5;
    public static final Duration SESSION_DURATION = Duration.ofDays(7);
    public static final Duration ACCESS_TOKEN_DURATION = Duration.ofMinutes(15);
    public static final Duration ROTATION_GRACE_PERIOD = Duration.ofSeconds(15);

    private final AuthSessionRepository authSessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final Clock clock;

    public AuthSessionServiceImpl(
            AuthSessionRepository authSessionRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            JwtTokenService jwtTokenService,
            Clock clock) {
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository);
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.jwtTokenService = Objects.requireNonNull(jwtTokenService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public LoginResult createSession(User authenticatedUser, String userAgent, String clientIp) {
        // Serialize admissions for this user, including when there are no sessions to lock.
        User user = userRepository.findByIdForUpdate(authenticatedUser.getId()).orElse(null);
        if (user == null
                || !user.isActive()
                || user.getEmailVerifiedAt() == null
                || user.getAuthVersion() != authenticatedUser.getAuthVersion()) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Email hoặc mật khẩu không chính xác");
        }
        Instant now = clock.instant();

        List<UUID> activeSessions =
                authSessionRepository.findActiveSessionIdsOldestFirst(user.getId(), now);
        for (int i = 0; i <= activeSessions.size() - MAX_ACTIVE_SESSIONS; i++) {
            revokeSession(activeSessions.get(i), RevokeReason.SESSION_LIMIT);
        }

        Instant sessionExpiresAt = now.plus(SESSION_DURATION);
        AuthSession session = new AuthSession(user, clientIp, userAgent, sessionExpiresAt, now);
        session = authSessionRepository.saveAndFlush(session);

        String rawRefreshToken = jwtTokenService.generateRawRefreshToken();
        String tokenHash = jwtTokenService.hashToken(rawRefreshToken);
        RefreshToken refreshToken =
                new RefreshToken(session, tokenHash, session.getExpiresAt(), now);
        refreshTokenRepository.saveAndFlush(refreshToken);

        Duration accessTokenDuration = ACCESS_TOKEN_DURATION;
        String accessToken =
                jwtTokenService.generateAccessToken(
                        user.getId(),
                        session.getId(),
                        user.getAuthVersion(),
                        user.getRoleNames(),
                        accessTokenDuration);

        Duration refreshTokenDuration = Duration.between(now, session.getExpiresAt());
        return new LoginResult(
                user, accessToken, rawRefreshToken, accessTokenDuration, refreshTokenDuration);
    }

    @Override
    @Transactional(noRollbackFor = RefreshTokenRejectedException.class)
    public RefreshResult rotateRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new RefreshTokenRejectedException(false);
        }

        String tokenHash = jwtTokenService.hashToken(rawRefreshToken);
        UUID sessionId =
                refreshTokenRepository
                        .findSessionIdByTokenHash(tokenHash)
                        .orElseThrow(() -> new RefreshTokenRejectedException(false));
        // Every path that mutates tokens locks their session before reading token state.
        AuthSession session =
                authSessionRepository
                        .findByIdForUpdate(sessionId)
                        .orElseThrow(() -> new RefreshTokenRejectedException(false));
        Instant now = clock.instant();
        if (session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now)) {
            throw new RefreshTokenRejectedException(false);
        }
        RefreshToken oldToken =
                refreshTokenRepository
                        .findByTokenHash(tokenHash)
                        .orElseThrow(() -> new RefreshTokenRejectedException(false));

        if (oldToken.getRevokedAt() != null) {
            if (oldToken.getReplacedById() != null) {
                if (!now.isAfter(oldToken.getRevokedAt().plus(ROTATION_GRACE_PERIOD))) {
                    throw new RefreshTokenRejectedException(true);
                }
                revokeLockedSession(session, RevokeReason.SECURITY, now);
            }
            throw new RefreshTokenRejectedException(false);
        }

        if (!oldToken.getExpiresAt().isAfter(now)) {
            throw new RefreshTokenRejectedException(false);
        }

        User user = session.getUser();
        if (user == null
                || !user.isActive()
                || user.getEmailVerifiedAt() == null
                || user.getStatus() == UserStatus.LOCKED) {
            throw new RefreshTokenRejectedException(false);
        }

        String newRawRefreshToken = jwtTokenService.generateRawRefreshToken();
        String newTokenHash = jwtTokenService.hashToken(newRawRefreshToken);

        RefreshToken newToken =
                new RefreshToken(session, newTokenHash, session.getExpiresAt(), now);

        oldToken.revoke(newToken.getId(), now);
        oldToken.markUsed(now);
        refreshTokenRepository.saveAndFlush(oldToken);

        refreshTokenRepository.saveAndFlush(newToken);

        session.touch(now);
        authSessionRepository.save(session);

        Duration remainingSessionDuration = Duration.between(now, session.getExpiresAt());
        Duration accessTokenDuration =
                remainingSessionDuration.compareTo(ACCESS_TOKEN_DURATION) < 0
                        ? remainingSessionDuration
                        : ACCESS_TOKEN_DURATION;

        String accessToken =
                jwtTokenService.generateAccessToken(
                        user.getId(),
                        session.getId(),
                        user.getAuthVersion(),
                        user.getRoleNames(),
                        accessTokenDuration);

        return new RefreshResult(
                accessToken, newRawRefreshToken, accessTokenDuration, remainingSessionDuration);
    }

    @Override
    @Transactional
    public void revokeSession(UUID sessionId, RevokeReason reason) {
        if (sessionId == null) {
            return;
        }
        authSessionRepository
                .findByIdForUpdate(sessionId)
                .ifPresent(
                        session ->
                                revokeLockedSession(
                                        session,
                                        reason != null ? reason : RevokeReason.LOGOUT,
                                        clock.instant()));
    }

    private void revokeLockedSession(AuthSession session, RevokeReason reason, Instant now) {
        if (session.getRevokedAt() == null) {
            session.revoke(reason, now);
        }
        refreshTokenRepository
                .findBySessionIdAndRevokedAtIsNull(session.getId())
                .ifPresent(token -> token.revoke(now));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSessionActive(UUID sessionId, int userAuthVersion) {
        if (sessionId == null) {
            return false;
        }
        Instant now = clock.instant();
        return authSessionRepository
                .findActiveSessionWithUser(sessionId, now)
                .map(
                        session -> {
                            User user = session.getUser();
                            return user != null
                                    && user.isActive()
                                    && user.getEmailVerifiedAt() != null
                                    && user.getStatus() != UserStatus.LOCKED
                                    && user.getAuthVersion() == userAuthVersion;
                        })
                .orElse(false);
    }
}
