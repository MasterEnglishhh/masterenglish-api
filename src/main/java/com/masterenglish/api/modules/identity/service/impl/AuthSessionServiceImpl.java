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
import com.masterenglish.api.modules.identity.security.JwtTokenService;
import com.masterenglish.api.modules.identity.service.AuthSessionService;
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

    private final AuthSessionRepository authSessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final Clock clock;

    public AuthSessionServiceImpl(
            AuthSessionRepository authSessionRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenService jwtTokenService,
            Clock clock) {
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository);
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository);
        this.jwtTokenService = Objects.requireNonNull(jwtTokenService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public LoginResult createSession(User user, String userAgent, String clientIp) {
        Objects.requireNonNull(user, "user must not be null");
        Instant now = clock.instant();

        long activeCount = authSessionRepository.countActiveSessions(user.getId(), now);
        if (activeCount >= MAX_ACTIVE_SESSIONS) {
            List<AuthSession> oldestSessions =
                    authSessionRepository.findActiveSessionsOldestFirst(user.getId(), now);
            int toRevoke = (int) (activeCount - MAX_ACTIVE_SESSIONS + 1);
            for (int i = 0; i < Math.min(toRevoke, oldestSessions.size()); i++) {
                AuthSession oldest = oldestSessions.get(i);
                oldest.revoke(RevokeReason.SESSION_LIMIT, now);
                authSessionRepository.save(oldest);
                refreshTokenRepository
                        .findBySessionIdAndRevokedAtIsNull(oldest.getId())
                        .ifPresent(
                                token -> {
                                    token.revoke(null, now);
                                    refreshTokenRepository.save(token);
                                });
            }
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
    @Transactional
    public RefreshResult rotateRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }

        Instant now = clock.instant();
        String tokenHash = jwtTokenService.hashToken(rawRefreshToken);

        RefreshToken oldToken =
                refreshTokenRepository
                        .findByTokenHashWithSessionAndUser(tokenHash)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.UNAUTHENTICATED,
                                                "Phiên làm việc không hợp lệ hoặc đã hết hạn."));

        AuthSession session = oldToken.getSession();
        if (session == null) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }

        if (oldToken.getRevokedAt() != null) {
            if (session.getRevokedAt() == null) {
                session.revoke(RevokeReason.SECURITY, now);
                authSessionRepository.save(session);
            }
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }

        if (session.getRevokedAt() != null) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }

        if (!oldToken.getExpiresAt().isAfter(now) || !session.getExpiresAt().isAfter(now)) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }

        User user = session.getUser();
        if (user == null
                || !user.isActive()
                || user.getEmailVerifiedAt() == null
                || user.getStatus() == UserStatus.LOCKED) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }

        String newRawRefreshToken = jwtTokenService.generateRawRefreshToken();
        String newTokenHash = jwtTokenService.hashToken(newRawRefreshToken);

        RefreshToken newToken =
                new RefreshToken(session, newTokenHash, session.getExpiresAt(), now);

        oldToken.revoke(newToken.getId(), now);
        oldToken.markUsed(now);
        refreshTokenRepository.saveAndFlush(oldToken);

        newToken = refreshTokenRepository.saveAndFlush(newToken);

        session.touch(now);
        authSessionRepository.save(session);

        Duration remainingSessionDuration = Duration.between(now, session.getExpiresAt());
        if (remainingSessionDuration.isNegative() || remainingSessionDuration.isZero()) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }

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
        Instant now = clock.instant();
        authSessionRepository
                .findById(sessionId)
                .ifPresent(
                        session -> {
                            if (session.getRevokedAt() == null) {
                                session.revoke(reason != null ? reason : RevokeReason.LOGOUT, now);
                                authSessionRepository.save(session);
                            }
                            refreshTokenRepository
                                    .findBySessionIdAndRevokedAtIsNull(sessionId)
                                    .ifPresent(
                                            token -> {
                                                token.revoke(null, now);
                                                refreshTokenRepository.save(token);
                                            });
                        });
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
