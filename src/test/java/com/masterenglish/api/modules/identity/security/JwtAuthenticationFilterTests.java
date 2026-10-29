package com.masterenglish.api.modules.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.config.WebSecurityProperties;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.AuthSessionService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTests {

    @Nested
    @DisplayName("AuthCookieHelper Tests")
    class AuthCookieHelperTests {

        private WebSecurityProperties secureProperties;
        private WebSecurityProperties insecureProperties;
        private AuthCookieHelper secureCookieHelper;
        private AuthCookieHelper insecureCookieHelper;

        @BeforeEach
        void setUp() {
            secureProperties = new WebSecurityProperties(List.of("http://localhost:3000"), true);
            insecureProperties = new WebSecurityProperties(List.of("http://localhost:3000"), false);
            secureCookieHelper = new AuthCookieHelper(secureProperties);
            insecureCookieHelper = new AuthCookieHelper(insecureProperties);
        }

        @Test
        @DisplayName(
                "createAccessTokenCookie produces cookie with correct attributes and secure flag")
        void createAccessTokenCookie_attributes() {
            ResponseCookie secureCookie =
                    secureCookieHelper.createAccessTokenCookie(
                            "jwt-access-token", Duration.ofMinutes(15));
            assertThat(secureCookie.getName()).isEqualTo("access_token");
            assertThat(secureCookie.getValue()).isEqualTo("jwt-access-token");
            assertThat(secureCookie.isHttpOnly()).isTrue();
            assertThat(secureCookie.isSecure()).isTrue();
            assertThat(secureCookie.getSameSite()).isEqualTo("Lax");
            assertThat(secureCookie.getPath()).isEqualTo("/");
            assertThat(secureCookie.getMaxAge()).isEqualTo(Duration.ofMinutes(15));

            ResponseCookie insecureCookie =
                    insecureCookieHelper.createAccessTokenCookie(
                            "jwt-access-token", Duration.ofMinutes(15));
            assertThat(insecureCookie.isSecure()).isFalse();
        }

        @Test
        @DisplayName(
                "createRefreshTokenCookie produces cookie with /api/v1/auth path and correct attributes")
        void createRefreshTokenCookie_attributes() {
            ResponseCookie secureCookie =
                    secureCookieHelper.createRefreshTokenCookie(
                            "raw-refresh-token", Duration.ofDays(7));
            assertThat(secureCookie.getName()).isEqualTo("refresh_token");
            assertThat(secureCookie.getValue()).isEqualTo("raw-refresh-token");
            assertThat(secureCookie.isHttpOnly()).isTrue();
            assertThat(secureCookie.isSecure()).isTrue();
            assertThat(secureCookie.getSameSite()).isEqualTo("Lax");
            assertThat(secureCookie.getPath()).isEqualTo("/api/v1/auth");
            assertThat(secureCookie.getMaxAge()).isEqualTo(Duration.ofDays(7));

            ResponseCookie insecureCookie =
                    insecureCookieHelper.createRefreshTokenCookie(
                            "raw-refresh-token", Duration.ofDays(7));
            assertThat(insecureCookie.isSecure()).isFalse();
        }

        @Test
        @DisplayName("clearAccessTokenCookie produces cookie with Max-Age=0 and empty value")
        void clearAccessTokenCookie_attributes() {
            ResponseCookie cookie = secureCookieHelper.clearAccessTokenCookie();
            assertThat(cookie.getName()).isEqualTo("access_token");
            assertThat(cookie.getValue()).isEmpty();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.isSecure()).isTrue();
            assertThat(cookie.getSameSite()).isEqualTo("Lax");
            assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("clearRefreshTokenCookie produces cookie with Max-Age=0 and /api/v1/auth path")
        void clearRefreshTokenCookie_attributes() {
            ResponseCookie cookie = secureCookieHelper.clearRefreshTokenCookie();
            assertThat(cookie.getName()).isEqualTo("refresh_token");
            assertThat(cookie.getValue()).isEmpty();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.isSecure()).isTrue();
            assertThat(cookie.getSameSite()).isEqualTo("Lax");
            assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
            assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("extractAccessToken extracts from cookie if present")
        void extractAccessToken_fromCookie() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("access_token", "cookie-token-value"));

            Optional<String> token = secureCookieHelper.extractAccessToken(request);
            assertThat(token).contains("cookie-token-value");
        }

        @Test
        @DisplayName(
                "extractAccessToken falls back to Authorization Bearer header if cookie absent")
        void extractAccessToken_fromAuthorizationHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer header-token-value");

            Optional<String> token = secureCookieHelper.extractAccessToken(request);
            assertThat(token).contains("header-token-value");
        }

        @Test
        @DisplayName(
                "extractAccessToken prefers cookie over Authorization header when both present")
        void extractAccessToken_prefersCookieOverHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("access_token", "cookie-token"));
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer header-token");

            Optional<String> token = secureCookieHelper.extractAccessToken(request);
            assertThat(token).contains("cookie-token");
        }

        @Test
        @DisplayName(
                "extractAccessToken returns empty when neither cookie nor Bearer header is present")
        void extractAccessToken_returnsEmptyWhenMissing() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            assertThat(secureCookieHelper.extractAccessToken(request)).isEmpty();

            request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
            assertThat(secureCookieHelper.extractAccessToken(request)).isEmpty();
        }

        @Test
        @DisplayName("extractRefreshToken extracts from cookie")
        void extractRefreshToken_fromCookie() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("refresh_token", "refresh-token-value"));

            Optional<String> token = secureCookieHelper.extractRefreshToken(request);
            assertThat(token).contains("refresh-token-value");
        }

        @Test
        @DisplayName("extractRefreshToken returns empty when cookie absent or blank")
        void extractRefreshToken_emptyWhenAbsentOrBlank() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            assertThat(secureCookieHelper.extractRefreshToken(request)).isEmpty();

            request.setCookies(new Cookie("refresh_token", "   "));
            assertThat(secureCookieHelper.extractRefreshToken(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("JwtAuthenticationFilter Execution Tests")
    class FilterExecutionTests {

        @Mock private AuthCookieHelper authCookieHelper;
        @Mock private JwtTokenService jwtTokenService;
        @Mock private AuthSessionService authSessionService;
        @Mock private UserRepository userRepository;

        private JwtAuthenticationFilter filter;
        private UUID userId;
        private UUID sessionId;
        private User user;
        private Role learnerRole;

        @BeforeEach
        void setUp() throws Exception {
            SecurityContextHolder.clearContext();
            filter =
                    new JwtAuthenticationFilter(
                            authCookieHelper, jwtTokenService, authSessionService, userRepository);

            userId = UUID.randomUUID();
            sessionId = UUID.randomUUID();

            var constructor = Role.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            learnerRole = constructor.newInstance();
            ReflectionTestUtils.setField(learnerRole, "id", (short) 1);
            ReflectionTestUtils.setField(learnerRole, "code", RoleCode.LEARNER);
            ReflectionTestUtils.setField(learnerRole, "name", "Learner");

            user = new User("learner@example.com", "hashed_pwd", "Huy", "Trịnh Đăng");
            ReflectionTestUtils.setField(user, "id", userId);
            user.addRole(learnerRole);
            user.markEmailVerified(Instant.now());
        }

        @AfterEach
        void tearDown() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName(
                "Request with valid access_token cookie and active DB session populates SecurityContext")
        void validAccessTokenCookie_authenticatesUser() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            String tokenString = "valid.jwt.token";
            when(authCookieHelper.extractAccessToken(request)).thenReturn(Optional.of(tokenString));

            JwtClaims claims =
                    new JwtClaims(
                            userId,
                            sessionId,
                            user.getAuthVersion(),
                            Set.of("ROLE_LEARNER"),
                            Instant.now().minusSeconds(60),
                            Instant.now().plusSeconds(840));
            when(jwtTokenService.parseAndValidateToken(tokenString)).thenReturn(claims);
            when(authSessionService.isSessionActive(sessionId, user.getAuthVersion()))
                    .thenReturn(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            filter.doFilter(request, response, filterChain);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication).isInstanceOf(UsernamePasswordAuthenticationToken.class);
            assertThat(authentication.isAuthenticated()).isTrue();

            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            assertThat(principal.getId()).isEqualTo(userId);
            assertThat(principal.getEmail()).isEqualTo("learner@example.com");
            assertThat(principal.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_LEARNER");

            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Request with valid Authorization Bearer token populates SecurityContext")
        void validBearerToken_authenticatesUser() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            String tokenString = "valid.bearer.token";
            when(authCookieHelper.extractAccessToken(request)).thenReturn(Optional.of(tokenString));

            JwtClaims claims =
                    new JwtClaims(
                            userId,
                            sessionId,
                            user.getAuthVersion(),
                            Set.of("ROLE_LEARNER"),
                            Instant.now().minusSeconds(60),
                            Instant.now().plusSeconds(840));
            when(jwtTokenService.parseAndValidateToken(tokenString)).thenReturn(claims);
            when(authSessionService.isSessionActive(sessionId, user.getAuthVersion()))
                    .thenReturn(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            filter.doFilter(request, response, filterChain);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.isAuthenticated()).isTrue();
            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Request with expired JWT leaves SecurityContext unauthenticated and proceeds")
        void expiredJwt_leavesUnauthenticated() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            String tokenString = "expired.jwt.token";
            when(authCookieHelper.extractAccessToken(request)).thenReturn(Optional.of(tokenString));
            when(jwtTokenService.parseAndValidateToken(tokenString))
                    .thenThrow(new BadJwtException("JWT has expired"));

            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName(
                "Request with revoked DB session leaves SecurityContext unauthenticated and proceeds")
        void revokedDbSession_leavesUnauthenticated() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            String tokenString = "valid.jwt.token";
            when(authCookieHelper.extractAccessToken(request)).thenReturn(Optional.of(tokenString));

            JwtClaims claims =
                    new JwtClaims(
                            userId,
                            sessionId,
                            user.getAuthVersion(),
                            Set.of("ROLE_LEARNER"),
                            Instant.now().minusSeconds(60),
                            Instant.now().plusSeconds(840));
            when(jwtTokenService.parseAndValidateToken(tokenString)).thenReturn(claims);
            when(authSessionService.isSessionActive(sessionId, user.getAuthVersion()))
                    .thenReturn(false);

            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName(
                "Request without cookie or header leaves SecurityContext unauthenticated and proceeds")
        void missingToken_leavesUnauthenticated() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            when(authCookieHelper.extractAccessToken(request)).thenReturn(Optional.empty());

            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName(
                "Request with user authVersion mismatch leaves SecurityContext unauthenticated")
        void userAuthVersionMismatch_leavesUnauthenticated() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            String tokenString = "valid.jwt.token";
            when(authCookieHelper.extractAccessToken(request)).thenReturn(Optional.of(tokenString));

            int oldAuthVersion = user.getAuthVersion();
            user.changePassword("newHashedPassword123"); // increments authVersion

            JwtClaims claims =
                    new JwtClaims(
                            userId,
                            sessionId,
                            oldAuthVersion,
                            Set.of("ROLE_LEARNER"),
                            Instant.now().minusSeconds(60),
                            Instant.now().plusSeconds(840));
            when(jwtTokenService.parseAndValidateToken(tokenString)).thenReturn(claims);
            when(authSessionService.isSessionActive(sessionId, oldAuthVersion)).thenReturn(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(filterChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("Request with non-active user leaves SecurityContext unauthenticated")
        void inactiveUser_leavesUnauthenticated() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain filterChain = new MockFilterChain();

            String tokenString = "valid.jwt.token";
            when(authCookieHelper.extractAccessToken(request)).thenReturn(Optional.of(tokenString));

            user.changeStatus(UserStatus.DISABLED);

            JwtClaims claims =
                    new JwtClaims(
                            userId,
                            sessionId,
                            user.getAuthVersion(),
                            Set.of("ROLE_LEARNER"),
                            Instant.now().minusSeconds(60),
                            Instant.now().plusSeconds(840));
            when(jwtTokenService.parseAndValidateToken(tokenString)).thenReturn(claims);
            when(authSessionService.isSessionActive(sessionId, user.getAuthVersion()))
                    .thenReturn(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(filterChain.getRequest()).isNotNull();
        }
    }
}
