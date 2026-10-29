# Tài liệu Thiết kế Kỹ thuật: Phân hệ Xác thực & Quản lý Phiên (Identity & Session Management)

**Ngày lập:** 29/10/2026  
**Trạng thái:** Đã duyệt  
**Mục tiêu:** Cung cấp đầy đủ hạ tầng database, entity, cơ chế bảo mật (JWT + Session + Refresh Token rotation + Rate Limiting) và các API endpoints (`/api/v1/auth/login`, `/api/v1/users/me`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`) phục vụ frontend MasterEnglish.

---

## 1. Bối cảnh và Yêu cầu Nghiệp vụ

- Căn cứ SRS/SDD v5.0 (M01.02, M01.04, M01.05, Mục 6.4) và kế hoạch `docs/plans/2026-10-29-login-backend.md`.
- Hiện trạng: API đã có `GET /api/v1/auth/csrf`, bộ lọc CSRF SPA, CORS, BCrypt, `User`, `UserPrincipal`, `UserRepository`. Chưa có logic xử lý login, refresh, logout, `/users/me` và chưa có cơ chế trích xuất người dùng từ cookie JWT.
- Schema V2 hiện tại còn giữ `display_name`, `auth_sessions.device_label` và chưa có các trường kiểm soát trạng thái xác minh (`first_name`, `last_name`, `email_verified_at`, `locked_until`) cũng như bảng rate limiting.
- Cần thực hiện migration V3 nâng cấp schema từ V2 theo nguyên tắc `expand/backfill/validate/contract` của `MIGRATION_PLAN.md`.

---

## 2. Thiết kế Cơ sở Dữ liệu (Flyway Migration V3)

File migration: `src/main/resources/db/migration/V3__align_identity_account.sql`

### 2.1. Cập nhật bảng `users`
- Bổ sung cột:
  - `first_name VARCHAR(30)`
  - `last_name VARCHAR(30)`
  - `email_verified_at TIMESTAMPTZ`
  - `locked_until TIMESTAMPTZ`
- Backfill dữ liệu hiện có:
  - Tách `display_name` thành `first_name` và `last_name` cho các user đã có.
  - Đặt `email_verified_at = created_at` cho các tài khoản đang có `status = 'ACTIVE'`.
- Điều chỉnh ràng buộc:
  - Cho phép 4 trạng thái: `status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'DISABLED')`.
  - Tài khoản `ACTIVE` bắt buộc phải có `email_verified_at IS NOT NULL`.
  - Tài khoản `LOCKED` bắt buộc có `locked_until IS NOT NULL`.
  - Ràng buộc độ dài và trim: `first_name` và `last_name` tối đa 30 ký tự sau trim.

### 2.2. Cập nhật bảng `auth_sessions`
- Bổ sung cột `ip_address INET`.
- Xóa bỏ cột `device_label`.
- Bổ sung các lý do thu hồi (`revoke_reason`) hợp lệ:
  `'LOGOUT', 'PASSWORD_RESET', 'PASSWORD_CHANGE', 'SECURITY', 'ROLE_CHANGE', 'ACCOUNT_LOCKED', 'ACCOUNT_DISABLED', 'SESSION_LIMIT', 'EXPIRED'`.
- Đảm bảo ràng buộc thời hạn phiên: `expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days'`.

### 2.3. Tạo mới bảng Rate Limiting
- `auth_rate_limits`:
  - `id UUID PRIMARY KEY`
  - `scope VARCHAR(64) NOT NULL` (hỗ trợ `LOGIN_FAILURE_EMAIL`, `LOGIN_IP`)
  - `subject_hash VARCHAR(64) NOT NULL` (HMAC SHA-256)
  - `blocked_until TIMESTAMPTZ`
  - `reset_at TIMESTAMPTZ`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `CONSTRAINT uq_auth_rate_limits_scope_subject UNIQUE (scope, subject_hash)`
- `auth_rate_limit_events`:
  - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
  - `rate_limit_id UUID NOT NULL REFERENCES auth_rate_limits(id) ON DELETE CASCADE`
  - `occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - Index trên `(rate_limit_id, occurred_at)` để tối ưu đếm sliding window.

---

## 3. Kiến trúc Bên trong Module Identity

### 3.1. Entity & Repository
- **`User`:** Cập nhật các trường `firstName`, `lastName`, `emailVerifiedAt`, `lockedUntil`. Cung cấp phương thức tiện ích `getDisplayName()` (kết hợp `lastName + " " + firstName`). Cập nhật `UserStatus` enum thêm `PENDING_VERIFICATION`.
- **`AuthSession`:** Entity JPA map với `auth_sessions`, các quan hệ với `User`, trường `ipAddress`, `userAgent`, `expiresAt`, `revokedAt`, `revokeReason`.
- **`RefreshToken`:** Entity JPA map với `refresh_tokens`, các trường `tokenHash`, quan hệ với `AuthSession`, `replacedBy`, `revokedAt`, `expiresAt`.
- **`AuthRateLimit` & `AuthRateLimitEvent`:** Quản lý số lần thử lỗi và thời gian chờ.

### 3.2. Quản lý Phiên (Session Management) & Giới hạn 5 phiên
- Mỗi lượt đăng nhập tạo một `AuthSession` mới có hạn tối đa 7 ngày (`expiresAt = now + 7 days`).
- Trước khi lưu phiên mới, kiểm tra số lượng phiên chưa bị thu hồi của user (`revokedAt IS NULL AND expiresAt > now`).
- Nếu số phiên $\ge 5$, hệ thống tự động thu hồi phiên cũ nhất (`ORDER BY created_at ASC LIMIT 1`) với `revokeReason = 'SESSION_LIMIT'`.

### 3.3. Cơ chế Token: JWT Access Token & Refresh Token Rotation
- **Access JWT:**
  - Ký bằng HMAC-SHA256 với secret từ biến cấu hình `app.security.jwt.secret`.
  - Thời hạn: tối đa 15 phút (và không vượt quá thời hạn còn lại của `AuthSession`).
  - Payload claims: `sub` (userId), `sessionId`, `authVersion`, `roles`, `iat`, `exp`.
- **Refresh Token:**
  - Sinh ngẫu nhiên 64 ký tự hex sử dụng `SecureRandom`.
  - Chỉ lưu giá trị băm SHA-256 trong cột `token_hash`. Chuỗi raw chỉ gửi về client qua cookie.
  - Khi refresh (`POST /api/v1/auth/refresh`):
    - Khóa phiên theo thứ tự giao dịch để tránh xung đột concurrent refresh.
    - Đánh dấu token cũ đã thu hồi (`revoked_at = now`), chèn token mới thay thế (`replaced_by_id`).
    - Cấp access JWT mới và refresh cookie mới. Hạn tuyệt đối của phiên không thay đổi.

### 3.4. Cơ chế Giới hạn Thử (Rate Limiting)
- Sử dụng HMAC SHA-256 (với secret `app.security.rate-limit.secret` hoặc JWT secret) để băm email/IP.
- Cửa sổ trượt 15 phút:
  - Nếu email thử sai $\ge 5$ lần trong 15 phút: đặt `blocked_until = now + 15 phút`.
  - Nếu IP thử sai $\ge 300$ lần trong 15 phút: đặt `blocked_until = now + 15 phút`.
- Kiểm tra trước khi đối chiếu mật khẩu: nếu đang trong thời gian bị chặn, trả ngay HTTP `429 Too Many Requests` kèm header `Retry-After: <số giây còn lại>`.
- Khi đăng nhập thành công: reset bộ đếm lỗi cho email tương ứng.

### 3.5. Security Filter & Cookie Configuration
- **Cookie Attributes:**
  - `access_token`: `HttpOnly=true`, `SameSite=Lax`, `Path=/`, `Max-Age=900` (15 phút), `Secure` theo `app.security.cookie-secure`.
  - `refresh_token`: `HttpOnly=true`, `SameSite=Lax`, `Path=/api/v1/auth`, `Max-Age=604800` (7 ngày), `Secure` theo `app.security.cookie-secure`.
- **`JwtAuthenticationFilter`:**
  - Đọc `access_token` từ cookie (hoặc `Authorization: Bearer`).
  - Xác thực chữ ký và thời hạn JWT.
  - Kiểm tra tính hợp lệ của phiên trong DB: `auth_sessions` tồn tại, `revoked_at IS NULL`, `expires_at > now`, và `user.auth_version == jwt.authVersion`.
  - Khởi tạo `UserPrincipal` và đặt vào `SecurityContextHolder`.

---

## 4. Hợp đồng API (API Contracts)

### 4.1. `POST /api/v1/auth/login`
- **Headers:** `Content-Type: application/json`, `X-XSRF-TOKEN: <token>`
- **Request Body:**
  ```json
  {
    "email": "learner@example.com",
    "password": "Password123!@#"
  }
  ```
- **Response Thành Công (200 OK):**
  - Đặt Set-Cookie: `access_token=...; HttpOnly; SameSite=Lax; Path=/`
  - Đặt Set-Cookie: `refresh_token=...; HttpOnly; SameSite=Lax; Path=/api/v1/auth`
  - Response Body:
    ```json
    {
      "id": "c1f19890-3490-4c28-98e3-a6dc28eb7199",
      "email": "learner@example.com",
      "firstName": "Huy",
      "lastName": "Trịnh Đăng",
      "displayName": "Trịnh Đăng Huy",
      "roles": ["LEARNER"],
      "phone": null,
      "avatarMediaId": null
    }
    ```
- **Lỗi Thường Gặp:**
  - `401 Unauthorized`: Trả về Problem Detail khi email không tồn tại, sai mật khẩu, tài khoản chưa kích hoạt hoặc bị khóa (thông báo thống nhất `"Email hoặc mật khẩu không chính xác"` để tránh User Enumeration).
  - `429 Too Many Requests`: Khi bị giới hạn lượt thử (kèm header `Retry-After`).
  - `403 Forbidden`: Khi thiếu hoặc sai CSRF token.

### 4.2. `GET /api/v1/users/me`
- **Headers:** Cookie chứa `access_token`
- **Response Thành Công (200 OK):** Trả về thông tin profile người dùng như cấu trúc DTO ở Login.
- **Lỗi:** `401 Unauthorized` nếu không có cookie, token hết hạn, hoặc phiên bị thu hồi.

### 4.3. `POST /api/v1/auth/refresh`
- **Headers:** `X-XSRF-TOKEN: <token>`, Cookie chứa `refresh_token`
- **Response Thành Công (200 OK):**
  - Đặt lại Set-Cookie: `access_token` và `refresh_token` mới.
  - Body:
    ```json
    {
      "message": "Phiên làm việc đã được làm mới thành công."
    }
    ```
- **Lỗi:** `401 Unauthorized` nếu refresh token không hợp lệ, phiên đã hết hạn hoặc bị thu hồi (đồng thời xóa cookie).

### 4.4. `POST /api/v1/auth/logout`
- **Headers:** `X-XSRF-TOKEN: <token>`, Cookie chứa `access_token` hoặc `refresh_token`
- **Response Thành Công (204 No Content):**
  - Thu hồi phiên trong DB với `revoke_reason = 'LOGOUT'`.
  - Đặt lại cookie với `Max-Age=0` để trình duyệt xóa sạch cả 2 token.

---

## 5. Chiến lược Kiểm thử (Testing Strategy)

1. **Database & Migration Tests:**
   - Kiểm tra migration V3 chạy thành công trên nền V1 + V2.
   - Kiểm tra các ràng buộc mới: độ dài họ tên, ràng buộc email verified khi ACTIVE, ràng buộc phiên 7 ngày, ràng buộc `refresh_tokens`.
2. **Unit Tests:**
   - Dịch vụ băm SHA-256 và sinh token.
   - Dịch vụ JWT (ký, parse, kiểm tra claims và authVersion).
   - Rate limiting service (đếm sliding window, tự mở sau khi hết hạn, chặn đúng ngưỡng 5 và 300).
3. **Integration Tests (MockMvc / WebMvcTest / SpringBootTest):**
   - Đăng nhập thành công: kiểm tra nhận đúng 2 cookie (HttpOnly, SameSite, Secure) và payload thông tin user.
   - Đăng nhập thất bại: sai mật khẩu, email chưa tồn tại, tài khoản chưa verify -> đều trả 401 thống nhất.
   - Rate limit: thử sai 5 lần -> lần thứ 6 trả 429 kèm Retry-After.
   - Session limit: đăng nhập lần thứ 6 -> phiên thứ nhất bị thu hồi với reason `SESSION_LIMIT`.
   - `GET /api/v1/users/me`: có cookie hợp lệ trả 200, không có hoặc phiên bị revoke trả 401.
   - `POST /api/v1/auth/refresh`: xoay token thành công, tái sử dụng token cũ bị từ chối 401.
   - `POST /api/v1/auth/logout`: thu hồi phiên, xóa cookie, trả 204.
   - CSRF check: thiếu X-XSRF-TOKEN trên POST bị 403.
4. **Code Quality:** Đảm bảo toàn bộ test pass (`./gradlew.bat test`) và code sạch (`./gradlew.bat spotlessCheck`).
