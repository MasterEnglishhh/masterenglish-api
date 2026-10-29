# Kế hoạch Triển khai: Phân hệ Xác thực & Quản lý Phiên (Identity & Session Management)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Xây dựng hoàn chỉnh hạ tầng backend cho phân hệ Identity & Authentication, bao gồm database migration V3, entity mapping, JWT & Cookie HttpOnly, quản lý phiên với giới hạn 5 phiên, xoay vòng refresh token, giới hạn lượt thử (rate limiting), cùng các API `/api/v1/auth/login`, `/api/v1/users/me`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`.

**Architecture:** Sử dụng kiến trúc kết hợp Access JWT ngắn hạn (15 phút) lưu trong HttpOnly cookie để phục vụ xác thực nhanh tại `JwtAuthenticationFilter`, kèm Database-backed Session trong PostgreSQL (`auth_sessions` và `refresh_tokens`) để đảm bảo thu hồi tức thì, bảo vệ xoay vòng refresh token an toàn và giới hạn 5 phiên đồng thời. Rate limiting sử dụng HMAC SHA-256 và sliding window 15 phút độc lập với user ID.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring Security 7.1, Spring Data JPA, Hibernate, PostgreSQL, Flyway, Nimbus JOSE JWT (`oauth2-resource-server`), Testcontainers, JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-29-login-backend-design.md`

## Global Constraints

- Access JWT có thời hạn tối đa 15 phút, chứa `sub`, `sessionId`, `authVersion`, `roles`.
- Phiên đăng nhập (`auth_sessions`) có thời hạn tối đa 7 ngày kể từ lúc tạo; refresh token không kéo dài thời hạn tuyệt đối này.
- Giới hạn tối đa 5 phiên đồng thời cho mỗi user; khi tạo phiên thứ 6, tự động thu hồi phiên cũ nhất (`ORDER BY created_at ASC`) với lý do `SESSION_LIMIT`.
- Cookie `access_token`: `HttpOnly=true`, `SameSite=Lax`, `Path=/`, `Max-Age=900`.
- Cookie `refresh_token`: `HttpOnly=true`, `SameSite=Lax`, `Path=/api/v1/auth`, `Max-Age=604800`.
- Cookie `Secure` thuộc tính đọc từ cấu hình `app.security.cookie-secure` (mặc định false ở local, true ở production).
- CSRF token (`X-XSRF-TOKEN`) bắt buộc kiểm tra cho mọi yêu cầu POST (`login`, `refresh`, `logout`).
- Trả thông báo lỗi 401 thống nhất `"Email hoặc mật khẩu không chính xác"` khi sai mật khẩu, email không tồn tại, tài khoản chưa kích hoạt hoặc bị khóa để triệt tiêu lỗ hổng User Enumeration.
- Rate limiting: 5 lần lỗi trong 15 phút theo email -> chặn 15 phút; 300 lần lỗi trong 15 phút theo IP -> chặn 15 phút. Trả về HTTP 429 kèm header `Retry-After`.
- Chuẩn hóa mã nguồn: tuân thủ `./gradlew.bat spotlessCheck`.

## Review Focus

1. **User Enumeration timing attack & error consistency:** Đăng nhập với email không tồn tại và email có thật nhưng sai mật khẩu phải trả về cùng mã HTTP 401 và cùng Problem Detail, không tiết lộ sự tồn tại của tài khoản.
2. **Session Limit boundary:** Đăng nhập lần thứ 5 thành công không thu hồi phiên nào; đăng nhập lần thứ 6 thu hồi đúng phiên có `created_at` cũ nhất với lý do `SESSION_LIMIT`.
3. **Concurrent Refresh Token race condition:** Khi hai request refresh gửi cùng một refresh token đồng thời, chỉ một request được cấp token mới; request còn lại bị từ chối hợp lệ mà không làm hỏng chuỗi token.
4. **Rate Limit reset & expiration:** Khi bị khóa 429, thời gian chờ `Retry-After` tính chính xác theo số giây còn lại của `blocked_until`. Khi người dùng đăng nhập thành công, bộ đếm lỗi của email được đặt lại.
5. **CSRF Enforcement on Auth:** Yêu cầu POST tới `/api/v1/auth/login`, `/refresh`, `/logout` thiếu header `X-XSRF-TOKEN` hoặc cookie `XSRF-TOKEN` không khớp phải bị từ chối 403 Forbidden trước khi thực thi logic nghiệp vụ.

---

### Task 1: Database Migration V3 & Entity Model Alignment

**Files:**
- Create: `src/main/resources/db/migration/V3__align_identity_account.sql`
- Modify: `src/main/java/com/masterenglish/api/modules/identity/entity/UserStatus.java`
- Modify: `src/main/java/com/masterenglish/api/modules/identity/entity/User.java`
- Modify: `src/main/java/com/masterenglish/api/modules/identity/security/UserPrincipal.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/entity/RevokeReason.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/entity/AuthSession.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/entity/RefreshToken.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/entity/AuthRateLimit.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/entity/AuthRateLimitEvent.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/repository/AuthSessionRepository.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/repository/RefreshTokenRepository.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/repository/AuthRateLimitRepository.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/repository/AuthRateLimitEventRepository.java`
- Modify: `src/test/java/com/masterenglish/api/IdentityDatabaseTests.java`

**Interfaces:**
- Produces:
  - `User`: `getFirstName()`, `getLastName()`, `getEmailVerifiedAt()`, `getLockedUntil()`, `getDisplayName()`
  - `AuthSession`: `getId()`, `getUser()`, `getIpAddress()`, `getUserAgent()`, `getExpiresAt()`, `getRevokedAt()`, `getRevokeReason()`, `revoke(RevokeReason)`
  - `RefreshToken`: `getId()`, `getSession()`, `getTokenHash()`, `getExpiresAt()`, `getRevokedAt()`, `getReplacedById()`, `revoke(UUID replacedById)`
  - `AuthRateLimit`: `getId()`, `getScope()`, `getSubjectHash()`, `getBlockedUntil()`, `getResetAt()`, `isBlocked()`
  - `AuthRateLimitEvent`: `getId()`, `getRateLimit()`, `getOccurredAt()`

- [ ] **Step 1: Write the failing tests for V3 database migration & entities**

Thêm các test cases vào `src/test/java/com/masterenglish/api/IdentityDatabaseTests.java`:
- `v3MigrationAddsFirstAndLastNameAndStatusPendingVerification()`
- `sessionLimitRevocationReasonCanBeSaved()`
- `authRateLimitsCanBePersistedAndQueriedByScopeAndHash()`

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat test --tests com.masterenglish.api.IdentityDatabaseTests`
Expected: FAIL do chưa có migration V3 và các entity mới.

- [ ] **Step 3: Implement V3 migration script and JPA entities**

1. Tạo `V3__align_identity_account.sql`:
   - Thêm `first_name`, `last_name`, `email_verified_at`, `locked_until` vào `users`.
   - Backfill `display_name` sang `first_name` và `last_name`, cập nhật `email_verified_at = created_at` cho user `ACTIVE`.
   - Cập nhật check constraint `ck_users_status` cho phép `PENDING_VERIFICATION`, `ACTIVE`, `LOCKED`, `DISABLED`.
   - Cập nhật `auth_sessions`: thêm `ip_address INET`, drop `device_label`, mở rộng `ck_auth_sessions_revoke_reason` bao gồm `SESSION_LIMIT` và `ACCOUNT_DISABLED`.
   - Tạo bảng `auth_rate_limits` và `auth_rate_limit_events`.
2. Cập nhật `UserStatus` và `User`:
   - Thêm `PENDING_VERIFICATION` vào `UserStatus`.
   - Bổ sung `firstName`, `lastName`, `emailVerifiedAt`, `lockedUntil` vào `User`. Cập nhật `displayName` thành getter kết hợp họ và tên.
   - Cập nhật constructor và logic xác thực `isActive()`.
3. Tạo các entities `AuthSession`, `RefreshToken`, `AuthRateLimit`, `AuthRateLimitEvent` và các repository tương ứng.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat test --tests com.masterenglish.api.IdentityDatabaseTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V3__align_identity_account.sql src/main/java/com/masterenglish/api/modules/identity/ src/test/java/com/masterenglish/api/IdentityDatabaseTests.java
git commit -m "feat(identity): add V3 migration and align entity model with approved baseline"
```

---

### Task 2: Token Utilities & Rate Limiting Service

**Files:**
- Create: `src/main/java/com/masterenglish/api/config/JwtProperties.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/security/JwtTokenService.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/security/JwtClaims.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/service/RateLimiterService.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/service/impl/RateLimiterServiceImpl.java`
- Create: `src/test/java/com/masterenglish/api/modules/identity/security/JwtTokenServiceTests.java`
- Create: `src/test/java/com/masterenglish/api/modules/identity/service/RateLimiterServiceTests.java`

**Interfaces:**
- Consumes:
  - `AuthRateLimitRepository`, `AuthRateLimitEventRepository`
- Produces:
  - `JwtTokenService`:
    - `String generateAccessToken(UUID userId, UUID sessionId, int authVersion, Set<String> roles, Duration duration)`
    - `JwtClaims parseAndValidateToken(String token)`
    - `String generateRawRefreshToken()`
    - `String hashToken(String rawToken)`
  - `RateLimiterService`:
    - `void checkLoginRateLimit(String email, String clientIp)` (ném `BusinessException(RATE_LIMIT_EXCEEDED)` nếu bị chặn)
    - `void recordLoginFailure(String email, String clientIp)`
    - `void resetLoginFailure(String email)`

- [ ] **Step 1: Write failing tests for JwtTokenService and RateLimiterService**

Tạo `JwtTokenServiceTests.java` kiểm tra:
- Ký JWT hợp lệ, parse đúng claims (`sub`, `sessionId`, `authVersion`, `roles`).
- Reject token hết hạn hoặc sai chữ ký.
- Tạo chuỗi refresh token 64 ký tự và hash SHA-256 đúng chuẩn regex `^[0-9a-f]{64}$`.

Tạo `RateLimiterServiceTests.java` kiểm tra:
- Chưa có lỗi thì `checkLoginRateLimit` pass.
- Ghi nhận 5 lần lỗi trong 15 phút -> lần tiếp theo ném exception kèm `Retry-After`.
- Đăng nhập thành công -> reset bộ đếm lỗi.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.*`
Expected: FAIL do chưa có `JwtTokenService` và `RateLimiterService`.

- [ ] **Step 3: Implement JwtProperties, JwtTokenService, and RateLimiterService**

1. `JwtProperties`: Cấu hình secret, issuer, expiration (mặc định 15 phút).
2. `JwtTokenService`: Sử dụng Nimbus JOSE JWT (sẵn có từ `spring-boot-starter-security-oauth2-resource-server`) với HMAC-SHA256 (`MACSigner` / `MACVerifier`).
3. `RateLimiterServiceImpl`:
   - Tính HMAC SHA-256 của email và IP.
   - Truy vấn `auth_rate_limits`, đếm số lượng `auth_rate_limit_events` trong vòng 15 phút gần nhất.
   - Nếu $\ge 5$ lỗi email hoặc $\ge 300$ lỗi IP, đặt `blocked_until = now + 15 phút`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.security.JwtTokenServiceTests --tests com.masterenglish.api.modules.identity.service.RateLimiterServiceTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/masterenglish/api/config/JwtProperties.java src/main/java/com/masterenglish/api/modules/identity/security/ src/main/java/com/masterenglish/api/modules/identity/service/ src/test/java/com/masterenglish/api/modules/identity/
git commit -m "feat(identity): implement JwtTokenService and RateLimiterService"
```

---

### Task 3: Session Management & Authentication Core Service

**Files:**
- Create: `src/main/java/com/masterenglish/api/modules/identity/service/AuthSessionService.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/service/impl/AuthSessionServiceImpl.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/service/AuthenticationService.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/service/impl/AuthenticationServiceImpl.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/dto/LoginResult.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/dto/RefreshResult.java`
- Create: `src/test/java/com/masterenglish/api/modules/identity/service/AuthSessionServiceTests.java`
- Create: `src/test/java/com/masterenglish/api/modules/identity/service/AuthenticationServiceTests.java`

**Interfaces:**
- Consumes:
  - `UserRepository`, `AuthSessionRepository`, `RefreshTokenRepository`, `JwtTokenService`, `RateLimiterService`, `PasswordEncoder`
- Produces:
  - `AuthSessionService`:
    - `LoginResult createSession(User user, String userAgent, String clientIp)`
    - `RefreshResult rotateRefreshToken(String rawRefreshToken)`
    - `void revokeSession(UUID sessionId, RevokeReason reason)`
    - `boolean isSessionActive(UUID sessionId, int userAuthVersion)`
  - `AuthenticationService`:
    - `LoginResult authenticate(String email, String password, String userAgent, String clientIp)`
    - `RefreshResult refresh(String rawRefreshToken)`
    - `void logout(UUID sessionId)`

- [ ] **Step 1: Write failing tests for AuthSessionService and AuthenticationService**

Kiểm tra:
- Đăng nhập đúng thông tin: tạo session, refresh token và JWT.
- Đăng nhập sai mật khẩu: gọi `recordLoginFailure`, ném `BusinessException(INVALID_CREDENTIALS)`.
- Đăng nhập email không tồn tại: cùng ném `INVALID_CREDENTIALS`.
- Đăng nhập tài khoản `PENDING_VERIFICATION` hoặc `LOCKED`: cùng ném `INVALID_CREDENTIALS`.
- Tạo phiên thứ 6: phiên cũ nhất trong 5 phiên trước bị thu hồi với reason `SESSION_LIMIT`.
- Refresh token rotation: token cũ bị revoke, token mới được cấp, phiên giữ nguyên `expiresAt`.
- Xoay token đã bị revoke hoặc không tồn tại: ném `INVALID_CREDENTIALS`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.service.AuthSessionServiceTests --tests com.masterenglish.api.modules.identity.service.AuthenticationServiceTests`
Expected: FAIL do chưa triển khai service.

- [ ] **Step 3: Implement AuthSessionService and AuthenticationService**

Triển khai logic:
1. `AuthSessionServiceImpl`:
   - Đếm active sessions của user (`revokedAt IS NULL AND expiresAt > now`). Nếu $\ge 5$, lấy phiên có `created_at` cũ nhất, gán `revokedAt = now` và `revokeReason = RevokeReason.SESSION_LIMIT`.
   - Tạo mới `AuthSession` với `expiresAt = now + 7 days`.
   - Tạo mới `RefreshToken` với hash SHA-256 từ chuỗi raw.
   - Tạo access JWT 15 phút.
   - Rotation: truy vấn token theo hash với pessimistic write lock (hoặc lock trên session), revoke token cũ, thêm token mới trỏ tới `replaced_by_id`.
2. `AuthenticationServiceImpl`:
   - Kiểm tra `rateLimiterService.checkLoginRateLimit`.
   - Tìm user theo email. So sánh BCrypt mật khẩu. Kiểm tra `user.isActive()` và `user.getEmailVerifiedAt() != null`.
   - Nếu bất kỳ bước nào sai: gọi `rateLimiterService.recordLoginFailure(email, clientIp)`, ném lỗi `INVALID_CREDENTIALS`.
   - Nếu hợp lệ: gọi `rateLimiterService.resetLoginFailure(email)`, sau đó gọi `authSessionService.createSession`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.service.AuthSessionServiceTests --tests com.masterenglish.api.modules.identity.service.AuthenticationServiceTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/masterenglish/api/modules/identity/service/ src/main/java/com/masterenglish/api/modules/identity/dto/ src/test/java/com/masterenglish/api/modules/identity/service/
git commit -m "feat(identity): implement AuthSessionService and AuthenticationService"
```

---

### Task 4: Cookie Helper, Security Filter & Security Configuration

**Files:**
- Create: `src/main/java/com/masterenglish/api/modules/identity/security/AuthCookieHelper.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/security/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/masterenglish/api/config/SecurityConfiguration.java`
- Modify: `src/main/java/com/masterenglish/api/common/security/SecurityProblemHandler.java`
- Create: `src/test/java/com/masterenglish/api/modules/identity/security/JwtAuthenticationFilterTests.java`

**Interfaces:**
- Consumes:
  - `JwtTokenService`, `AuthSessionService`, `UserRepository`, `WebSecurityProperties`
- Produces:
  - `AuthCookieHelper`:
    - `ResponseCookie createAccessTokenCookie(String token, Duration maxAge)`
    - `ResponseCookie createRefreshTokenCookie(String token, Duration maxAge)`
    - `ResponseCookie clearAccessTokenCookie()`
    - `ResponseCookie clearRefreshTokenCookie()`
    - `Optional<String> extractAccessToken(HttpServletRequest request)`
    - `Optional<String> extractRefreshToken(HttpServletRequest request)`
  - `JwtAuthenticationFilter`: trích xuất JWT, kiểm tra phiên hoạt động, thiết lập `UserPrincipal` vào `SecurityContextHolder`.

- [ ] **Step 1: Write failing tests for AuthCookieHelper and JwtAuthenticationFilter**

Kiểm tra:
- `AuthCookieHelper`: tạo cookie đúng tên (`access_token`, `refresh_token`), đúng Path (`/` và `/api/v1/auth`), `HttpOnly=true`, `SameSite=Lax`, `Secure` theo properties.
- `JwtAuthenticationFilter`:
  - Request có cookie `access_token` hợp lệ + phiên hợp lệ: SecurityContext có `UserPrincipal`.
  - Request có cookie JWT hết hạn: SecurityContext trống, filter tiếp tục chuỗi.
  - Request có cookie JWT mà phiên DB đã bị revoke: SecurityContext trống.
  - Request không có cookie: filter tiếp tục chuỗi.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.security.JwtAuthenticationFilterTests`
Expected: FAIL do chưa có helper và filter.

- [ ] **Step 3: Implement AuthCookieHelper, JwtAuthenticationFilter, and update SecurityConfiguration**

1. Tạo `AuthCookieHelper` dùng `ResponseCookie.from(...)`.
2. Tạo `JwtAuthenticationFilter extends OncePerRequestFilter`.
3. Cập nhật `SecurityConfiguration`:
   - Thêm `JwtAuthenticationFilter` trước `UsernamePasswordAuthenticationFilter`.
   - Cấu hình permitAll cho `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`.
   - Các API khác (bao gồm `GET /api/v1/users/me`) bắt buộc `authenticated()`.
   - Giữ nguyên cấu hình CSRF (`.spa()`) và CORS hiện có.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.security.JwtAuthenticationFilterTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/masterenglish/api/modules/identity/security/ src/main/java/com/masterenglish/api/config/SecurityConfiguration.java src/test/java/com/masterenglish/api/modules/identity/security/
git commit -m "feat(security): configure JwtAuthenticationFilter and AuthCookieHelper"
```

---

### Task 5: AuthController & UserController Endpoints & Integration Tests

**Files:**
- Create: `src/main/java/com/masterenglish/api/modules/identity/dto/LoginRequest.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/dto/UserProfileResponse.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/dto/AuthMessageResponse.java`
- Create: `src/main/java/com/masterenglish/api/modules/identity/controller/AuthController.java`
- Modify: `src/main/java/com/masterenglish/api/modules/identity/controller/UserController.java`
- Modify: `src/main/java/com/masterenglish/api/common/error/GlobalExceptionHandler.java`
- Create: `src/test/java/com/masterenglish/api/modules/identity/controller/AuthControllerIntegrationTests.java`
- Create: `src/test/java/com/masterenglish/api/modules/identity/controller/UserControllerIntegrationTests.java`

**Interfaces:**
- Consumes:
  - `AuthenticationService`, `AuthCookieHelper`, `UserService`
- Produces:
  - `POST /api/v1/auth/login` -> 200 OK + Set-Cookie + `UserProfileResponse`
  - `GET /api/v1/users/me` -> 200 OK + `UserProfileResponse`
  - `POST /api/v1/auth/refresh` -> 200 OK + Set-Cookie + `AuthMessageResponse`
  - `POST /api/v1/auth/logout` -> 204 No Content + Clear-Cookie

- [ ] **Step 1: Write comprehensive integration tests for Auth & User endpoints**

Tạo `AuthControllerIntegrationTests.java` và `UserControllerIntegrationTests.java`:
- Test 1: Đăng nhập thành công trả 200, 2 Set-Cookie hợp lệ, body đúng user info.
- Test 2: Gọi `GET /api/v1/users/me` với `access_token` nhận được trả đúng profile.
- Test 3: Đăng nhập sai mật khẩu trả 401 ProblemDetail, thông báo "Email hoặc mật khẩu không chính xác".
- Test 4: Đăng nhập sai 5 lần liên tiếp trả 429 Too Many Requests kèm header `Retry-After`.
- Test 5: Thiếu CSRF token ở POST login/refresh/logout bị từ chối 403.
- Test 6: `POST /api/v1/auth/refresh` với refresh cookie trả token mới.
- Test 7: Tái sử dụng refresh token cũ trả 401.
- Test 8: `POST /api/v1/auth/logout` trả 204 và xóa cookie; sau đó gọi `/users/me` trả 401.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.controller.*`
Expected: FAIL do chưa có controller endpoints.

- [ ] **Step 3: Implement AuthController, UserController, and GlobalExceptionHandler updates**

1. Tạo `LoginRequest` (validation annotations `@NotBlank`, `@Email`).
2. Tạo `UserProfileResponse` (`id`, `email`, `firstName`, `lastName`, `displayName`, `roles`, `phone`, `avatarMediaId`).
3. Tạo `AuthController`:
   - `@PostMapping("/api/v1/auth/login")`: gọi `authenticationService.authenticate(...)`, thêm `Set-Cookie` cho `access_token` và `refresh_token`, trả về `UserProfileResponse`.
   - `@PostMapping("/api/v1/auth/refresh")`: đọc `refresh_token` từ request, gọi `authenticationService.refresh(...)`, set lại cookie, trả về `{ "message": "Phiên làm việc đã được làm mới thành công." }`.
   - `@PostMapping("/api/v1/auth/logout")`: lấy sessionId từ authentication hoặc refresh token, gọi `authenticationService.logout(...)`, clear cả 2 cookie, trả về `204 No Content`.
4. Cập nhật `UserController`:
   - `@GetMapping("/api/v1/users/me")`: đọc `@CurrentUser UserPrincipal`, tìm user trong DB hoặc trả profile response.
5. Cập nhật `GlobalExceptionHandler`:
   - Bắt `RateLimitExceededException` (hoặc `BusinessException` với code tương ứng), gắn header `Retry-After` vào response với status `HttpStatus.TOO_MANY_REQUESTS`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests com.masterenglish.api.modules.identity.controller.*`
Expected: PASS

- [ ] **Step 5: Run full test suite and spotlessCheck**

Run:
```bash
./gradlew.bat test
./gradlew.bat spotlessCheck
```
Expected: PASS toàn bộ test suite và code formatting đạt chuẩn.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/masterenglish/api/modules/identity/controller/ src/main/java/com/masterenglish/api/modules/identity/dto/ src/main/java/com/masterenglish/api/common/error/ src/test/java/com/masterenglish/api/modules/identity/controller/
git commit -m "feat(identity): implement AuthController, UserController and integration tests"
```
