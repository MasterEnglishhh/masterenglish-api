package com.masterenglish.api.modules.identity.security;

import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final AuthCookieHelper authCookieHelper;
    private final JwtTokenService jwtTokenService;
    private final AuthSessionService authSessionService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(
            AuthCookieHelper authCookieHelper,
            JwtTokenService jwtTokenService,
            AuthSessionService authSessionService,
            UserRepository userRepository) {
        this.authCookieHelper =
                Objects.requireNonNull(authCookieHelper, "authCookieHelper must not be null");
        this.jwtTokenService =
                Objects.requireNonNull(jwtTokenService, "jwtTokenService must not be null");
        this.authSessionService =
                Objects.requireNonNull(authSessionService, "authSessionService must not be null");
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Optional<String> tokenOpt = authCookieHelper.extractAccessToken(request);
        if (tokenOpt.isPresent()) {
            String token = tokenOpt.get();
            try {
                JwtClaims claims = jwtTokenService.parseAndValidateToken(token);
                if (authSessionService.isSessionActive(claims.sessionId(), claims.authVersion())) {
                    Optional<User> userOpt = userRepository.findById(claims.userId());
                    if (userOpt.isPresent()) {
                        User user = userOpt.get();
                        if (user.isActive() && user.getAuthVersion() == claims.authVersion()) {
                            UserPrincipal principal = UserPrincipal.create(user);
                            UsernamePasswordAuthenticationToken authentication =
                                    new UsernamePasswordAuthenticationToken(
                                            principal, null, principal.getAuthorities());
                            authentication.setDetails(
                                    new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("JWT authentication filter skipped: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
