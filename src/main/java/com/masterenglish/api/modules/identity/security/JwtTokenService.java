package com.masterenglish.api.modules.identity.security;

import com.masterenglish.api.config.JwtProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    public static final Duration MAX_ACCESS_TOKEN_DURATION = Duration.ofMinutes(15);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final JWSSigner signer;
    private final JWSVerifier verifier;

    public JwtTokenService(JwtProperties jwtProperties, Clock clock) {
        this.jwtProperties =
                Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        byte[] secretBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        try {
            this.signer = new MACSigner(secretBytes);
            this.verifier = new MACVerifier(secretBytes);
        } catch (JOSEException e) {
            throw new IllegalArgumentException("Invalid JWT secret for HMAC-SHA256", e);
        }
    }

    public String generateAccessToken(
            UUID userId, UUID sessionId, int authVersion, Set<String> roles) {
        return generateAccessToken(userId, sessionId, authVersion, roles, null);
    }

    public String generateAccessToken(
            UUID userId, UUID sessionId, int authVersion, Set<String> roles, Duration duration) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        Instant now = clock.instant();
        Duration effectiveDuration =
                (duration != null && !duration.isNegative() && !duration.isZero())
                        ? duration
                        : jwtProperties.accessTokenDuration();
        if (effectiveDuration.compareTo(MAX_ACCESS_TOKEN_DURATION) > 0) {
            effectiveDuration = MAX_ACCESS_TOKEN_DURATION;
        }
        Instant expiresAt = now.plus(effectiveDuration);

        JWTClaimsSet claimsSet =
                new JWTClaimsSet.Builder()
                        .subject(userId.toString())
                        .issuer(jwtProperties.issuer())
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(expiresAt))
                        .claim("sessionId", sessionId.toString())
                        .claim("authVersion", authVersion)
                        .claim("roles", roles == null ? List.of() : new ArrayList<>(roles))
                        .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        try {
            signedJWT.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT access token", e);
        }

        return signedJWT.serialize();
    }

    public JwtClaims parseAndValidateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BadJwtException("Token must not be null or blank");
        }

        SignedJWT signedJWT;
        try {
            signedJWT = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new BadJwtException("Malformed JWT token", e);
        }

        if (!JWSAlgorithm.HS256.equals(signedJWT.getHeader().getAlgorithm())) {
            throw new BadJwtException(
                    "Unsupported JWT algorithm: " + signedJWT.getHeader().getAlgorithm());
        }

        try {
            if (!signedJWT.verify(verifier)) {
                throw new BadJwtException("Invalid JWT signature");
            }
        } catch (JOSEException e) {
            throw new BadJwtException("Failed to verify JWT signature", e);
        }

        JWTClaimsSet claimsSet;
        try {
            claimsSet = signedJWT.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new BadJwtException("Malformed JWT claims set", e);
        }

        Instant now = clock.instant();

        Date exp = claimsSet.getExpirationTime();
        if (exp == null) {
            throw new BadJwtException("JWT expiration time (exp) is missing");
        }
        Instant expiresAt = exp.toInstant();
        if (now.isAfter(expiresAt)) {
            throw new BadJwtException("JWT has expired at " + expiresAt);
        }

        if (jwtProperties.issuer() != null
                && !jwtProperties.issuer().equals(claimsSet.getIssuer())) {
            throw new BadJwtException("Invalid JWT issuer: " + claimsSet.getIssuer());
        }

        Date iat = claimsSet.getIssueTime();
        Instant issuedAt = iat != null ? iat.toInstant() : now;

        String sub = claimsSet.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new BadJwtException("JWT subject (sub) is missing");
        }
        UUID userId;
        try {
            userId = UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new BadJwtException("Invalid userId format in JWT sub claim", e);
        }

        Object sessionIdClaim = claimsSet.getClaim("sessionId");
        if (sessionIdClaim == null) {
            throw new BadJwtException("JWT sessionId claim is missing");
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdClaim.toString());
        } catch (IllegalArgumentException e) {
            throw new BadJwtException("Invalid sessionId format in JWT", e);
        }

        Object authVersionClaim = claimsSet.getClaim("authVersion");
        if (authVersionClaim == null) {
            throw new BadJwtException("JWT authVersion claim is missing");
        }
        int authVersion;
        if (authVersionClaim instanceof Number num) {
            authVersion = num.intValue();
        } else {
            try {
                authVersion = Integer.parseInt(authVersionClaim.toString());
            } catch (NumberFormatException e) {
                throw new BadJwtException("Invalid authVersion format in JWT", e);
            }
        }

        List<String> rolesList;
        try {
            rolesList = claimsSet.getStringListClaim("roles");
        } catch (ParseException e) {
            throw new BadJwtException("Invalid roles claim format in JWT", e);
        }
        Set<String> roles = rolesList == null ? Set.of() : Set.copyOf(rolesList);

        return new JwtClaims(userId, sessionId, authVersion, roles, issuedAt, expiresAt);
    }

    public String generateRawRefreshToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return HexFormat.of().formatHex(randomBytes);
    }

    public String hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Token must not be null or blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
