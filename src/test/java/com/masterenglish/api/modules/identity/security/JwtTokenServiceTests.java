package com.masterenglish.api.modules.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.config.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

class JwtTokenServiceTests {

    private static final String TEST_SECRET =
            "masterenglish-test-secret-key-32-bytes-minimum-length-123456";
    private static final String TEST_ISSUER = "masterenglish-api";
    private static final Duration TEST_DURATION = Duration.ofMinutes(15);

    private Clock clock;
    private JwtProperties jwtProperties;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC);
        jwtProperties = new JwtProperties(TEST_SECRET, TEST_ISSUER, TEST_DURATION);
        jwtTokenService = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    @DisplayName("Successfully generates and parses valid JWT access token with all claims")
    void generateAndParseAccessToken_validToken_success() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        int authVersion = 3;
        Set<String> roles = Set.of("ROLE_LEARNER", "ROLE_ADMIN");

        String token =
                jwtTokenService.generateAccessToken(
                        userId, sessionId, authVersion, roles, Duration.ofMinutes(15));

        assertThat(token).isNotBlank();

        JwtClaims claims = jwtTokenService.parseAndValidateToken(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.authVersion()).isEqualTo(authVersion);
        assertThat(claims.roles()).containsExactlyInAnyOrder("ROLE_LEARNER", "ROLE_ADMIN");
        assertThat(claims.issuedAt()).isEqualTo(Instant.parse("2026-10-29T10:00:00Z"));
        assertThat(claims.expiresAt()).isEqualTo(Instant.parse("2026-10-29T10:15:00Z"));
    }

    @Test
    @DisplayName("Uses default duration when null duration is passed to generateAccessToken")
    void generateAccessToken_nullDuration_usesConfiguredDefault() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token =
                jwtTokenService.generateAccessToken(
                        userId, sessionId, 1, Set.of("ROLE_LEARNER"), null);

        JwtClaims claims = jwtTokenService.parseAndValidateToken(token);

        assertThat(claims.expiresAt()).isEqualTo(Instant.parse("2026-10-29T10:15:00Z"));
    }

    @Test
    @DisplayName("Throws BadJwtException when token is expired")
    void parseAndValidateToken_expiredToken_throwsBadJwtException() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // Token expires in 5 minutes (at 10:05:00Z)
        String token =
                jwtTokenService.generateAccessToken(
                        userId, sessionId, 1, Set.of("ROLE_LEARNER"), Duration.ofMinutes(5));

        // Advance clock by 6 minutes (to 10:06:00Z)
        Clock futureClock = Clock.fixed(Instant.parse("2026-10-29T10:06:00Z"), ZoneOffset.UTC);
        JwtTokenService verifyingService = new JwtTokenService(jwtProperties, futureClock);

        assertThatThrownBy(() -> verifyingService.parseAndValidateToken(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Throws BadJwtException when token signature is invalid")
    void parseAndValidateToken_invalidSignature_throwsBadJwtException() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        JwtProperties differentProperties =
                new JwtProperties(
                        "different-secret-key-32-bytes-minimum-length-abcdef",
                        TEST_ISSUER,
                        TEST_DURATION);
        JwtTokenService differentService = new JwtTokenService(differentProperties, clock);

        String tokenFromDifferentSecret =
                differentService.generateAccessToken(
                        userId, sessionId, 1, Set.of("ROLE_LEARNER"), TEST_DURATION);

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenFromDifferentSecret))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("Throws BadJwtException when token string is malformed or blank")
    void parseAndValidateToken_malformedOrBlankToken_throwsBadJwtException() {
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken("not-a-valid-jwt"))
                .isInstanceOf(BadJwtException.class);

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(""))
                .isInstanceOf(BadJwtException.class);

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(null))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    @DisplayName(
            "generateRawRefreshToken generates 64 lowercase hex characters from 32 random bytes")
    void generateRawRefreshToken_returns64HexChars() {
        String token1 = jwtTokenService.generateRawRefreshToken();
        String token2 = jwtTokenService.generateRawRefreshToken();

        assertThat(token1).matches("^[0-9a-f]{64}$");
        assertThat(token2).matches("^[0-9a-f]{64}$");
        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("hashToken produces deterministic SHA-256 hex string matching ^[0-9a-f]{64}$")
    void hashToken_computesExpectedSha256Hex() {
        String rawToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String hash1 = jwtTokenService.hashToken(rawToken);
        String hash2 = jwtTokenService.hashToken(rawToken);

        assertThat(hash1).matches("^[0-9a-f]{64}$");
        assertThat(hash1).isEqualTo(hash2);

        // Verify known SHA-256 test vector for "hello"
        // echo -n "hello" | sha256sum ->
        // 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertThat(jwtTokenService.hashToken("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("hashToken throws IllegalArgumentException on null or blank input")
    void hashToken_invalidInput_throwsException() {
        assertThatThrownBy(() -> jwtTokenService.hashToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwtTokenService.hashToken("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
