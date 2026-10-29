package com.masterenglish.api.modules.identity.controller;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.AuthMessageResponse;
import com.masterenglish.api.modules.identity.dto.LoginRequest;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.dto.UserProfileResponse;
import com.masterenglish.api.modules.identity.security.AuthCookieHelper;
import com.masterenglish.api.modules.identity.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Objects;
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

    public AuthController(
            AuthenticationService authenticationService, AuthCookieHelper authCookieHelper) {
        this.authenticationService =
                Objects.requireNonNull(
                        authenticationService, "authenticationService must not be null");
        this.authCookieHelper =
                Objects.requireNonNull(authCookieHelper, "authCookieHelper must not be null");
    }

    @PostMapping("/login")
    public ResponseEntity<UserProfileResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
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

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(UserProfileResponse.from(result.user()));
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

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(new AuthMessageResponse("Phiên làm việc đã được làm mới thành công."));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        String accessToken = authCookieHelper.extractAccessToken(httpRequest).orElse(null);
        String refreshToken = authCookieHelper.extractRefreshToken(httpRequest).orElse(null);

        authenticationService.logout(accessToken, refreshToken);

        ResponseCookie clearAccessCookie = authCookieHelper.clearAccessTokenCookie();
        ResponseCookie clearRefreshCookie = authCookieHelper.clearRefreshTokenCookie();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearAccessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie.toString())
                .build();
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
