package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.entity.AuthSession;
import com.masterenglish.api.modules.identity.entity.RefreshToken;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.RefreshTokenRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.JwtClaims;
import com.masterenglish.api.modules.identity.security.JwtTokenService;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AuthSessionServiceTests {

    @Autowired private AuthSessionService authSessionService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private EntityManager entityManager;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("session.tester@example.com", "hashed-pwd", "Session", "Tester");
        testUser.addRole(entityManager.find(Role.class, (short) 1));
        testUser.markEmailVerified(Instant.now());
        userRepository.saveAndFlush(testUser);
        entityManager.clear();
        testUser = userRepository.findById(testUser.getId()).orElseThrow();
    }

    @Test
    @DisplayName("createSession creates active session, refresh token, and valid access JWT")
    void createSession_success() {
        LoginResult result =
                authSessionService.createSession(testUser, "Mozilla/5.0", "192.168.1.1");

        assertThat(result).isNotNull();
        assertThat(result.user().getId()).isEqualTo(testUser.getId());
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.rawRefreshToken()).isNotBlank();
        assertThat(result.accessTokenDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(result.refreshTokenDuration().toDays()).isEqualTo(7);

        // Verify session in DB
        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        assertThat(sessions).hasSize(1);
        AuthSession session = sessions.get(0);
        assertThat(session.getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(session.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(session.getRevokedAt()).isNull();

        // Verify RefreshToken in DB
        String hashedToken = jwtTokenService.hashToken(result.rawRefreshToken());
        RefreshToken token = refreshTokenRepository.findByTokenHash(hashedToken).orElseThrow();
        assertThat(token.getSession().getId()).isEqualTo(session.getId());
        assertThat(token.getRevokedAt()).isNull();

        // Verify JWT claims
        JwtClaims claims = jwtTokenService.parseAndValidateToken(result.accessToken());
        assertThat(claims.userId()).isEqualTo(testUser.getId());
        assertThat(claims.sessionId()).isEqualTo(session.getId());
        assertThat(claims.authVersion()).isEqualTo(testUser.getAuthVersion());
    }

    @Test
    @DisplayName("Creating 6th session revokes oldest session with SESSION_LIMIT")
    void createSession_sixthSession_revokesOldestSession() throws Exception {
        // Create 5 sessions with slight delay so createdAt is distinct
        for (int i = 0; i < 5; i++) {
            authSessionService.createSession(testUser, "Agent " + i, "10.0.0." + i);
            Thread.sleep(10);
        }

        List<AuthSession> activeSessionsBefore =
                authSessionRepository.findActiveSessionsOldestFirst(
                        testUser.getId(), Instant.now());
        assertThat(activeSessionsBefore).hasSize(5);
        UUID oldestSessionId = activeSessionsBefore.get(0).getId();

        // Create 6th session
        LoginResult sixthResult = authSessionService.createSession(testUser, "Agent 6", "10.0.0.6");
        assertThat(sixthResult).isNotNull();

        // Oldest session must now be revoked with SESSION_LIMIT
        AuthSession revokedSession = authSessionRepository.findById(oldestSessionId).orElseThrow();
        assertThat(revokedSession.getRevokedAt()).isNotNull();
        assertThat(revokedSession.getRevokeReason()).isEqualTo(RevokeReason.SESSION_LIMIT);

        // Oldest session's refresh token must also be revoked
        Optional<RefreshToken> oldTokenOpt =
                refreshTokenRepository.findBySessionIdAndRevokedAtIsNull(oldestSessionId);
        assertThat(oldTokenOpt).isEmpty();

        // Active sessions count should still be 5
        long activeCount =
                authSessionRepository.countActiveSessions(testUser.getId(), Instant.now());
        assertThat(activeCount).isEqualTo(5);
    }

    @Test
    @DisplayName("rotateRefreshToken rotates token, revokes old token, and keeps session expiry")
    void rotateRefreshToken_success() {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");
        String oldRawToken = loginResult.rawRefreshToken();
        String oldTokenHash = jwtTokenService.hashToken(oldRawToken);

        RefreshResult refreshResult = authSessionService.rotateRefreshToken(oldRawToken);

        assertThat(refreshResult).isNotNull();
        assertThat(refreshResult.accessToken()).isNotBlank();
        assertThat(refreshResult.rawRefreshToken()).isNotBlank();
        assertThat(refreshResult.rawRefreshToken()).isNotEqualTo(oldRawToken);

        // Old token should be revoked and replaced_by_id set
        RefreshToken oldToken = refreshTokenRepository.findByTokenHash(oldTokenHash).orElseThrow();
        assertThat(oldToken.getRevokedAt()).isNotNull();
        assertThat(oldToken.getReplacedById()).isNotNull();

        // New token should be active in DB
        String newTokenHash = jwtTokenService.hashToken(refreshResult.rawRefreshToken());
        RefreshToken newToken = refreshTokenRepository.findByTokenHash(newTokenHash).orElseThrow();
        assertThat(newToken.getRevokedAt()).isNull();
        assertThat(oldToken.getReplacedById()).isEqualTo(newToken.getId());
    }

    @Test
    @DisplayName("rotateRefreshToken with remaining session under 15m caps accessTokenDuration")
    void rotateRefreshToken_remainingUnder15m_capsAccessTokenDuration() {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");
        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        AuthSession session = sessions.get(0);

        // Set session expiry to 5 minutes from now (created 6 days ago)
        Instant sixDaysAgo = Instant.now().minus(Duration.ofDays(6));
        Instant fiveMinutesFromNow = Instant.now().plus(Duration.ofMinutes(5));
        entityManager
                .createQuery(
                        "UPDATE AuthSession s SET s.createdAt = :created, s.expiresAt = :expired WHERE s.id = :id")
                .setParameter("created", sixDaysAgo)
                .setParameter("expired", fiveMinutesFromNow)
                .setParameter("id", session.getId())
                .executeUpdate();
        entityManager
                .createQuery(
                        "UPDATE RefreshToken r SET r.issuedAt = :issued, r.expiresAt = :expired WHERE r.session.id = :id")
                .setParameter("issued", sixDaysAgo)
                .setParameter("expired", fiveMinutesFromNow)
                .setParameter("id", session.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        RefreshResult refreshResult =
                authSessionService.rotateRefreshToken(loginResult.rawRefreshToken());

        assertThat(refreshResult.accessTokenDuration()).isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(refreshResult.refreshTokenDuration()).isLessThanOrEqualTo(Duration.ofMinutes(5));

        JwtClaims claims = jwtTokenService.parseAndValidateToken(refreshResult.accessToken());
        Duration tokenLifetime = Duration.between(claims.issuedAt(), claims.expiresAt());
        assertThat(tokenLifetime).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("rotateRefreshToken with already revoked token throws UNAUTHENTICATED")
    void rotateRefreshToken_revokedToken_throwsUnauthenticated() {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");
        String oldRawToken = loginResult.rawRefreshToken();

        // Rotate once (oldRawToken becomes revoked, replacement token active)
        RefreshResult refreshResult = authSessionService.rotateRefreshToken(oldRawToken);
        String replacementHash = jwtTokenService.hashToken(refreshResult.rawRefreshToken());

        // Rotate again with the same (now revoked) token -> reuse detected
        assertThatThrownBy(() -> authSessionService.rotateRefreshToken(oldRawToken))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                            assertThat(be.getMessage())
                                    .isEqualTo("Phiên làm việc không hợp lệ hoặc đã hết hạn.");
                        });

        // Verify session was revoked with RevokeReason.SECURITY
        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        assertThat(sessions).hasSize(1);
        AuthSession session = sessions.get(0);
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(session.getRevokeReason()).isEqualTo(RevokeReason.SECURITY);

        // Verify active replacement token was also revoked
        assertThat(refreshTokenRepository.findBySessionIdAndRevokedAtIsNull(session.getId()))
                .isEmpty();
        RefreshToken replacementToken =
                refreshTokenRepository.findByTokenHash(replacementHash).orElseThrow();
        assertThat(replacementToken.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("createSession with blank or empty clientIp normalizes to null")
    void createSession_blankClientIp_normalizesToNull() {
        LoginResult blankResult = authSessionService.createSession(testUser, "Mozilla/5.0", "   ");
        LoginResult emptyResult = authSessionService.createSession(testUser, "Mozilla/5.0", "");

        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        assertThat(sessions).extracting(AuthSession::getIpAddress).containsOnlyNulls();
    }

    @Test
    @DisplayName(
            "rotateRefreshToken sequential rotations succeed and maintain single unrevoked token")
    void rotateRefreshToken_sequentialRotations_succeed() {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");
        String currentToken = loginResult.rawRefreshToken();

        for (int i = 0; i < 3; i++) {
            RefreshResult refreshResult = authSessionService.rotateRefreshToken(currentToken);
            assertThat(refreshResult.rawRefreshToken()).isNotEqualTo(currentToken);
            currentToken = refreshResult.rawRefreshToken();
        }

        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        assertThat(sessions).hasSize(1);
        UUID sessionId = sessions.get(0).getId();

        // There should be exactly 1 active (unrevoked) token for this session
        Optional<RefreshToken> activeToken =
                refreshTokenRepository.findBySessionIdAndRevokedAtIsNull(sessionId);
        assertThat(activeToken).isPresent();
        assertThat(activeToken.get().getTokenHash())
                .isEqualTo(jwtTokenService.hashToken(currentToken));
    }

    @Test
    @DisplayName("rotateRefreshToken with nonexistent token throws UNAUTHENTICATED")
    void rotateRefreshToken_nonexistentToken_throwsUnauthenticated() {
        String randomRawToken = jwtTokenService.generateRawRefreshToken();

        assertThatThrownBy(() -> authSessionService.rotateRefreshToken(randomRawToken))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                        });
    }

    @Test
    @DisplayName("rotateRefreshToken on expired session throws UNAUTHENTICATED")
    void rotateRefreshToken_expiredSession_throwsUnauthenticated() {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");

        // Manually expire the session and token in DB while respecting ck_auth_sessions_expiry
        // (expires_at > created_at)
        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        AuthSession session = sessions.get(0);
        Instant eightDaysAgo = Instant.now().minus(Duration.ofDays(8));
        Instant oneDayAgo = Instant.now().minus(Duration.ofDays(1));
        entityManager
                .createQuery(
                        "UPDATE AuthSession s SET s.createdAt = :created, s.expiresAt = :expired WHERE s.id = :id")
                .setParameter("created", eightDaysAgo)
                .setParameter("expired", oneDayAgo)
                .setParameter("id", session.getId())
                .executeUpdate();
        entityManager
                .createQuery(
                        "UPDATE RefreshToken r SET r.issuedAt = :issued, r.expiresAt = :expired WHERE r.session.id = :id")
                .setParameter("issued", eightDaysAgo)
                .setParameter("expired", oneDayAgo)
                .setParameter("id", session.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(
                        () -> authSessionService.rotateRefreshToken(loginResult.rawRefreshToken()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                        });
    }

    @Test
    @DisplayName("revokeSession revokes session with specified reason and revokes active token")
    void revokeSession_success() {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");
        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        UUID sessionId = sessions.get(0).getId();

        authSessionService.revokeSession(sessionId, RevokeReason.LOGOUT);

        AuthSession revokedSession = authSessionRepository.findById(sessionId).orElseThrow();
        assertThat(revokedSession.getRevokedAt()).isNotNull();
        assertThat(revokedSession.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);

        Optional<RefreshToken> activeToken =
                refreshTokenRepository.findBySessionIdAndRevokedAtIsNull(sessionId);
        assertThat(activeToken).isEmpty();
    }

    @Test
    @DisplayName("isSessionActive returns true for valid session and false for revoked/invalid")
    void isSessionActive_validation() {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");
        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        UUID sessionId = sessions.get(0).getId();

        // Valid session & correct authVersion
        assertThat(authSessionService.isSessionActive(sessionId, testUser.getAuthVersion()))
                .isTrue();

        // Mismatched authVersion
        assertThat(authSessionService.isSessionActive(sessionId, testUser.getAuthVersion() + 1))
                .isFalse();

        // Nonexistent session
        assertThat(authSessionService.isSessionActive(UUID.randomUUID(), testUser.getAuthVersion()))
                .isFalse();

        // After revoke
        authSessionService.revokeSession(sessionId, RevokeReason.LOGOUT);
        assertThat(authSessionService.isSessionActive(sessionId, testUser.getAuthVersion()))
                .isFalse();
    }
}
