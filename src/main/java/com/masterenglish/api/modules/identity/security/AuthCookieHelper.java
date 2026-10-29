package com.masterenglish.api.modules.identity.security;

import com.masterenglish.api.config.WebSecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieHelper {

    public static final String ACCESS_TOKEN_COOKIE_NAME = "access_token";
    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    public static final String ACCESS_TOKEN_PATH = "/";
    public static final String REFRESH_TOKEN_PATH = "/api/v1/auth";
    public static final String SAME_SITE_LAX = "Lax";

    private final WebSecurityProperties webSecurityProperties;

    public AuthCookieHelper(WebSecurityProperties webSecurityProperties) {
        this.webSecurityProperties =
                Objects.requireNonNull(
                        webSecurityProperties, "webSecurityProperties must not be null");
    }

    public ResponseCookie createAccessTokenCookie(String token, Duration maxAge) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(maxAge, "maxAge must not be null");
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(webSecurityProperties.cookieSecure())
                .sameSite(SAME_SITE_LAX)
                .path(ACCESS_TOKEN_PATH)
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie createRefreshTokenCookie(String token, Duration maxAge) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(maxAge, "maxAge must not be null");
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(webSecurityProperties.cookieSecure())
                .sameSite(SAME_SITE_LAX)
                .path(REFRESH_TOKEN_PATH)
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie clearAccessTokenCookie() {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(webSecurityProperties.cookieSecure())
                .sameSite(SAME_SITE_LAX)
                .path(ACCESS_TOKEN_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    public ResponseCookie clearRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(webSecurityProperties.cookieSecure())
                .sameSite(SAME_SITE_LAX)
                .path(REFRESH_TOKEN_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    public Optional<String> extractAccessToken(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ACCESS_TOKEN_COOKIE_NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return Optional.of(value.strip());
                    }
                }
            }
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).strip();
            if (!token.isBlank()) {
                return Optional.of(token);
            }
        }

        return Optional.empty();
    }

    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        if (request == null) {
            return Optional.empty();
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return Optional.of(value.strip());
                    }
                }
            }
        }

        return Optional.empty();
    }
}
