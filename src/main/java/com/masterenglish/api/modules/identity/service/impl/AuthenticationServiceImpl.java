package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.AuthSessionService;
import com.masterenglish.api.modules.identity.service.AuthenticationService;
import com.masterenglish.api.modules.identity.service.RateLimiterService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String DUMMY_BCRYPT_HASH =
            new BCryptPasswordEncoder(12).encode("dummy-password-for-timing-protection");

    private final UserRepository userRepository;
    private final AuthSessionService authSessionService;
    private final RateLimiterService rateLimiterService;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationServiceImpl(
            UserRepository userRepository,
            AuthSessionService authSessionService,
            RateLimiterService rateLimiterService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.authSessionService = Objects.requireNonNull(authSessionService);
        this.rateLimiterService = Objects.requireNonNull(rateLimiterService);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
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
}
