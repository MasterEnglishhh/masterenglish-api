package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.AuthMessageResponse;
import com.masterenglish.api.modules.identity.dto.LoginRequest;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.dto.UserProfileResponse;
import com.masterenglish.api.modules.identity.repository.RefreshTokenRepository;
import com.masterenglish.api.modules.identity.security.AuthCookieHelper;
import com.masterenglish.api.modules.identity.security.JwtClaims;
import com.masterenglish.api.modules.identity.security.JwtTokenService;
import com.masterenglish.api.modules.identity.service.AuthenticationService;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final AuthCookieHelper authCookieHelper;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthController(
            AuthenticationService authenticationService,
            AuthCookieHelper authCookieHelper,
            JwtTokenService jwtTokenService,
            RefreshTokenRepository refreshTokenRepository) {
        this.authenticationService =
                Objects.requireNonNull(
                        authenticationService, "authenticationService must not be null");
        this.authCookieHelper =
                Objects.requireNonNull(authCookieHelper, "authCookieHelper must not be null");
        this.jwtTokenService =
                Objects.requireNonNull(jwtTokenService, "jwtTokenService must not be null");
        this.refreshTokenRepository =
                Objects.requireNonNull(
                        refreshTokenRepository, "refreshTokenRepository must not be null");
    }

    @PostMapping("/login")
    public ResponseEntity<UserProfileResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);

        LoginResult result =
                authenticationService.authenticate(
                        request.email(), request.password(), userAgent, clientIp);

        ResponseCookie accessCookie =
                authCookieHelper.createAccessTokenCookie(
                        result.accessToken(), result.accessTokenDuration());
        ResponseCookie refreshCookie =
                authCookieHelper.createRefreshTokenCookie(
                        result.rawRefreshToken(), result.refreshTokenDuration());

        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(UserProfileResponse.from(result.user()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthMessageResponse> refresh(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String rawRefreshToken =
                authCookieHelper
                        .extractRefreshToken(httpRequest)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.UNAUTHENTICATED,
                                                "Phiên làm việc không hợp lệ hoặc đã hết hạn."));

        RefreshResult result;
        try {
            result = authenticationService.refresh(rawRefreshToken);
        } catch (BusinessException e) {
            httpResponse.addHeader(
                    HttpHeaders.SET_COOKIE, authCookieHelper.clearAccessTokenCookie().toString());
            httpResponse.addHeader(
                    HttpHeaders.SET_COOKIE, authCookieHelper.clearRefreshTokenCookie().toString());
            throw e;
        }

        ResponseCookie accessCookie =
                authCookieHelper.createAccessTokenCookie(
                        result.accessToken(), result.accessTokenDuration());
        ResponseCookie refreshCookie =
                authCookieHelper.createRefreshTokenCookie(
                        result.rawRefreshToken(), result.refreshTokenDuration());

        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(
                new AuthMessageResponse("Phiên làm việc đã được làm mới thành công."));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UUID sessionId = null;

        Optional<String> accessTokenOpt = authCookieHelper.extractAccessToken(httpRequest);
        if (accessTokenOpt.isPresent()) {
            try {
                JwtClaims claims = jwtTokenService.parseAndValidateToken(accessTokenOpt.get());
                sessionId = claims.sessionId();
            } catch (Exception e) {
                try {
                    SignedJWT signedJWT = SignedJWT.parse(accessTokenOpt.get());
                    Object sidClaim = signedJWT.getJWTClaimsSet().getClaim("sessionId");
                    if (sidClaim != null) {
                        sessionId = UUID.fromString(sidClaim.toString());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (sessionId == null) {
            Optional<String> refreshTokenOpt = authCookieHelper.extractRefreshToken(httpRequest);
            if (refreshTokenOpt.isPresent()) {
                try {
                    String tokenHash = jwtTokenService.hashToken(refreshTokenOpt.get());
                    sessionId =
                            refreshTokenRepository
                                    .findByTokenHash(tokenHash)
                                    .map(token -> token.getSession().getId())
                                    .orElse(null);
                } catch (Exception ignored) {
                }
            }
        }

        if (sessionId != null) {
            authenticationService.logout(sessionId);
        }

        httpResponse.addHeader(
                HttpHeaders.SET_COOKIE, authCookieHelper.clearAccessTokenCookie().toString());
        httpResponse.addHeader(
                HttpHeaders.SET_COOKIE, authCookieHelper.clearRefreshTokenCookie().toString());

        return ResponseEntity.noContent().build();
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIndex = xForwardedFor.indexOf(',');
            return (commaIndex != -1 ? xForwardedFor.substring(0, commaIndex) : xForwardedFor)
                    .strip();
        }
        return request.getRemoteAddr();
    }
}
