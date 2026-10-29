package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.error.RateLimitExceededException;
import com.masterenglish.api.config.JwtProperties;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.entity.AuthSession;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AuthenticationServiceTests {

    @Autowired private AuthenticationService authenticationService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JwtProperties jwtProperties;

    private static final String RAW_PASSWORD = "CorrectPassword123!@#";
    private User activeUser;

    @BeforeEach
    void setUp() {
        String encoded = passwordEncoder.encode(RAW_PASSWORD);
        activeUser = new User("auth.user@example.com", encoded, "Auth", "User");
        activeUser.addRole(entityManager.find(Role.class, (short) 1));
        activeUser.markEmailVerified(Instant.now());
        userRepository.saveAndFlush(activeUser);
        entityManager.clear();
        activeUser = userRepository.findById(activeUser.getId()).orElseThrow();
    }

    @Test
    @DisplayName("authenticate with correct credentials returns LoginResult with tokens")
    void authenticate_correctCredentials_success() {
        LoginResult result =
                authenticationService.authenticate(
                        "auth.user@example.com", RAW_PASSWORD, "Mozilla/5.0", "192.168.10.1");

        assertThat(result).isNotNull();
        assertThat(result.user().getEmail()).isEqualTo("auth.user@example.com");
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.rawRefreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("authenticate with wrong password throws UNAUTHENTICATED")
    void authenticate_wrongPassword_throwsUnauthenticated() {
        assertThatThrownBy(
                        () ->
                                authenticationService.authenticate(
                                        "auth.user@example.com",
                                        "WrongPassword999!",
                                        "Mozilla/5.0",
                                        "192.168.10.2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                            assertThat(be.getMessage())
                                    .isEqualTo("Email hoặc mật khẩu không chính xác");
                        });
    }

    @Test
    @DisplayName("authenticate with unknown email throws UNAUTHENTICATED")
    void authenticate_unknownEmail_throwsUnauthenticated() {
        assertThatThrownBy(
                        () ->
                                authenticationService.authenticate(
                                        "unknown.person@example.com",
                                        RAW_PASSWORD,
                                        "Mozilla/5.0",
                                        "192.168.10.3"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                            assertThat(be.getMessage())
                                    .isEqualTo("Email hoặc mật khẩu không chính xác");
                        });
    }

    @Test
    @DisplayName("authenticate with unverified account throws UNAUTHENTICATED")
    void authenticate_unverifiedAccount_throwsUnauthenticated() {
        String encoded = passwordEncoder.encode(RAW_PASSWORD);
        User pendingUser = new User("pending.user@example.com", encoded, "Pending", "User");
        pendingUser.addRole(entityManager.find(Role.class, (short) 1));
        // status is PENDING_VERIFICATION by default, emailVerifiedAt is null
        userRepository.saveAndFlush(pendingUser);

        assertThatThrownBy(
                        () ->
                                authenticationService.authenticate(
                                        "pending.user@example.com",
                                        RAW_PASSWORD,
                                        "Mozilla/5.0",
                                        "192.168.10.4"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                            assertThat(be.getMessage())
                                    .isEqualTo("Email hoặc mật khẩu không chính xác");
                        });
    }

    @Test
    @DisplayName("authenticate with locked account throws UNAUTHENTICATED")
    void authenticate_lockedAccount_throwsUnauthenticated() {
        String encoded = passwordEncoder.encode(RAW_PASSWORD);
        User lockedUser = new User("locked.user@example.com", encoded, "Locked", "User");
        lockedUser.addRole(entityManager.find(Role.class, (short) 1));
        lockedUser.markEmailVerified(Instant.now());
        lockedUser.lock(Instant.now().plusSeconds(3600));
        userRepository.saveAndFlush(lockedUser);

        assertThatThrownBy(
                        () ->
                                authenticationService.authenticate(
                                        "locked.user@example.com",
                                        RAW_PASSWORD,
                                        "Mozilla/5.0",
                                        "192.168.10.5"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                            assertThat(be.getMessage())
                                    .isEqualTo("Email hoặc mật khẩu không chính xác");
                        });
    }

    @Test
    @DisplayName("authenticate blocks with RateLimitExceededException after 5 failures")
    void authenticate_rateLimitBlock_afterFiveFailures() {
        String targetEmail = "ratelimit.target@example.com";
        String ip = "192.168.10.6";

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(
                            () ->
                                    authenticationService.authenticate(
                                            targetEmail, "wrong-pass", "Mozilla/5.0", ip))
                    .isInstanceOf(BusinessException.class);
        }

        // 6th attempt should be blocked by rate limiter
        assertThatThrownBy(
                        () ->
                                authenticationService.authenticate(
                                        targetEmail, RAW_PASSWORD, "Mozilla/5.0", ip))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(
                        ex -> {
                            RateLimitExceededException rle = (RateLimitExceededException) ex;
                            assertThat(rle.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(rle.getRetryAfterSeconds()).isPositive();
                        });
    }

    @Test
    @DisplayName("refresh delegates to authSessionService.rotateRefreshToken")
    void refresh_delegatesToAuthSessionService() {
        LoginResult loginResult =
                authenticationService.authenticate(
                        "auth.user@example.com", RAW_PASSWORD, "Mozilla/5.0", "192.168.10.7");

        RefreshResult refreshResult = authenticationService.refresh(loginResult.rawRefreshToken());

        assertThat(refreshResult).isNotNull();
        assertThat(refreshResult.accessToken()).isNotBlank();
        assertThat(refreshResult.rawRefreshToken()).isNotBlank();
        assertThat(refreshResult.rawRefreshToken()).isNotEqualTo(loginResult.rawRefreshToken());
    }

    @Test
    @DisplayName("logout delegates to authSessionService.revokeSession")
    void logout_delegatesToAuthSessionService() {
        LoginResult loginResult =
                authenticationService.authenticate(
                        "auth.user@example.com", RAW_PASSWORD, "Mozilla/5.0", "192.168.10.8");

        List<AuthSession> sessions = authSessionRepository.findByUserId(activeUser.getId());
        UUID sessionId = sessions.get(0).getId();

        authenticationService.logout(sessionId);

        AuthSession session = authSessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(session.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);
    }

    @Test
    @DisplayName("logout with valid accessToken and rawRefreshToken revokes session")
    void logout_withValidTokens_revokesSession() {
        LoginResult loginResult =
                authenticationService.authenticate(
                        "auth.user@example.com", RAW_PASSWORD, "Mozilla/5.0", "192.168.10.9");

        List<AuthSession> sessions = authSessionRepository.findByUserId(activeUser.getId());
        UUID sessionId = sessions.get(0).getId();

        authenticationService.logout(loginResult.accessToken(), loginResult.rawRefreshToken());

        AuthSession session = authSessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(session.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);
    }

    @Test
    @DisplayName("logout with expired accessToken (valid signature) revokes session")
    void logout_withExpiredAccessToken_revokesSession() throws Exception {
        LoginResult loginResult =
                authenticationService.authenticate(
                        "auth.user@example.com", RAW_PASSWORD, "Mozilla/5.0", "192.168.10.10");

        List<AuthSession> sessions = authSessionRepository.findByUserId(activeUser.getId());
        UUID sessionId = sessions.get(0).getId();

        SignedJWT expiredJwt =
                new SignedJWT(
                        new JWSHeader(JWSAlgorithm.HS256),
                        new JWTClaimsSet.Builder()
                                .subject(activeUser.getId().toString())
                                .issuer(jwtProperties.issuer())
                                .issueTime(Date.from(Instant.now().minusSeconds(1000)))
                                .expirationTime(Date.from(Instant.now().minusSeconds(100)))
                                .claim("sessionId", sessionId.toString())
                                .claim("authVersion", 1)
                                .claim("roles", List.of("ROLE_LEARNER"))
                                .build());
        expiredJwt.sign(new MACSigner(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)));
        String expiredToken = expiredJwt.serialize();

        authenticationService.logout(expiredToken, null);

        AuthSession session = authSessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(session.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);
    }

    @Test
    @DisplayName("logout with forged signature accessToken does not revoke victim session")
    void logout_withForgedAccessToken_doesNotRevokeSession() throws Exception {
        LoginResult loginResult =
                authenticationService.authenticate(
                        "auth.user@example.com", RAW_PASSWORD, "Mozilla/5.0", "192.168.10.11");

        List<AuthSession> sessions = authSessionRepository.findByUserId(activeUser.getId());
        UUID sessionId = sessions.get(0).getId();

        SignedJWT forgedJwt =
                new SignedJWT(
                        new JWSHeader(JWSAlgorithm.HS256),
                        new JWTClaimsSet.Builder()
                                .subject(activeUser.getId().toString())
                                .issuer(jwtProperties.issuer())
                                .issueTime(Date.from(Instant.now()))
                                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                                .claim("sessionId", sessionId.toString())
                                .claim("authVersion", 1)
                                .claim("roles", List.of("ROLE_LEARNER"))
                                .build());
        forgedJwt.sign(
                new MACSigner(
                        "untrusted-attacker-secret-key-32-bytes-length"
                                .getBytes(StandardCharsets.UTF_8)));
        String forgedToken = forgedJwt.serialize();

        authenticationService.logout(forgedToken, null);

        AuthSession session = authSessionRepository.findById(sessionId).orElseThrow();
        // Session must remain active and NOT revoked
        assertThat(session.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("logout with rawRefreshToken only revokes session")
    void logout_withRefreshTokenOnly_revokesSession() {
        LoginResult loginResult =
                authenticationService.authenticate(
                        "auth.user@example.com", RAW_PASSWORD, "Mozilla/5.0", "192.168.10.12");

        List<AuthSession> sessions = authSessionRepository.findByUserId(activeUser.getId());
        UUID sessionId = sessions.get(0).getId();

        authenticationService.logout(null, loginResult.rawRefreshToken());

        AuthSession session = authSessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(session.getRevokeReason()).isEqualTo(RevokeReason.LOGOUT);
    }
}
