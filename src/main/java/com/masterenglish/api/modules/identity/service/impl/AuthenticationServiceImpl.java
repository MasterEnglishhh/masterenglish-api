package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.RefreshTokenRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.JwtClaims;
import com.masterenglish.api.modules.identity.security.JwtTokenService;
import com.masterenglish.api.modules.identity.service.AuthSessionService;
import com.masterenglish.api.modules.identity.service.AuthenticationService;
import com.masterenglish.api.modules.identity.service.RateLimiterService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String DUMMY_BCRYPT_HASH =
            new BCryptPasswordEncoder(12).encode("dummy-password-for-timing-protection");

    private final UserRepository userRepository;
    private final AuthSessionService authSessionService;
    private final RateLimiterService rateLimiterService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthenticationServiceImpl(
            UserRepository userRepository,
            AuthSessionService authSessionService,
            RateLimiterService rateLimiterService,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            RefreshTokenRepository refreshTokenRepository) {
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.authSessionService =
                Objects.requireNonNull(authSessionService, "authSessionService must not be null");
        this.rateLimiterService =
                Objects.requireNonNull(rateLimiterService, "rateLimiterService must not be null");
        this.passwordEncoder =
                Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.jwtTokenService =
                Objects.requireNonNull(jwtTokenService, "jwtTokenService must not be null");
        this.refreshTokenRepository =
                Objects.requireNonNull(
                        refreshTokenRepository, "refreshTokenRepository must not be null");
    }

    @Override
    public LoginResult authenticate(
            String email, String password, String userAgent, String clientIp) {
        rateLimiterService.checkLoginRateLimit(email, clientIp);

        Optional<User> userOpt =
                (email != null && !email.isBlank())
                        ? userRepository.findForAuthenticationByEmail(email.strip())
                        : Optional.empty();

        boolean valid = false;
        User user = null;

        if (userOpt.isPresent()) {
            user = userOpt.get();
            boolean passwordMatches =
                    password != null
                            && !password.isBlank()
                            && passwordEncoder.matches(password, user.getPasswordHash());
            boolean active = user.isActive();
            boolean emailVerified = user.getEmailVerifiedAt() != null;
            boolean notLocked = user.getStatus() != UserStatus.LOCKED;

            valid = passwordMatches && active && emailVerified && notLocked;
        } else {
            passwordEncoder.matches(password != null ? password : "", DUMMY_BCRYPT_HASH);
        }

        if (!valid) {
            rateLimiterService.recordLoginFailure(email, clientIp);
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Email hoặc mật khẩu không chính xác");
        }

        rateLimiterService.resetLoginFailure(email);
        return authSessionService.createSession(user, userAgent, clientIp);
    }

    @Override
    public RefreshResult refresh(String rawRefreshToken) {
        return authSessionService.rotateRefreshToken(rawRefreshToken);
    }

    @Override
    public void logout(UUID sessionId) {
        authSessionService.revokeSession(sessionId, RevokeReason.LOGOUT);
    }

    @Override
    @Transactional
    public void logout(String accessToken, String rawRefreshToken) {
        UUID sessionId = null;

        if (accessToken != null && !accessToken.isBlank()) {
            try {
                JwtClaims claims = jwtTokenService.parseTokenVerifyingSignature(accessToken);
                sessionId = claims.sessionId();
            } catch (Exception ignored) {
                // Invalid signature, malformed, or unverified claims - do NOT extract or trust
                // sessionId
            }
        }

        if (sessionId == null && rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            try {
                String tokenHash = jwtTokenService.hashToken(rawRefreshToken);
                sessionId =
                        refreshTokenRepository
                                .findByTokenHash(tokenHash)
                                .map(token -> token.getSession().getId())
                                .orElse(null);
            } catch (Exception ignored) {
            }
        }

        if (sessionId != null) {
            logout(sessionId);
        }
    }
}
