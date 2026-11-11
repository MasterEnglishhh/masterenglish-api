# Hợp đồng API chi tiết: Identity, Xác thực & Hồ sơ Học viên (M01)

**Ngày cập nhật:** 12/11/2026  
**Module Backend:** `com.masterenglish.api.modules.identity`  
**Phiên bản API:** `v1`  
**Cơ chế xác thực:** Session Cookie (`ACCESS_TOKEN`), CSRF Header (`X-XSRF-TOKEN`).  
**Định dạng lỗi chung:** RFC 7807 (`application/problem+json`) với `{ type, title, status, detail, code, traceId, errors }`.

---

## 1. Danh mục Endpoint Phân hệ Identity & User (M01)

| Method | URI | Quyền hạn | Chức năng | Trạng thái |
|---|---|---|---|---|
| `GET` | `/api/v1/auth/csrf` | Public (Không cần login) | Lấy CSRF token (header `X-XSRF-TOKEN` & cookie) | Sẵn sàng |
| `POST` | `/api/v1/auth/register` | Public | Đăng ký tài khoản mới (tạo thử thách OTP) | Sẵn sàng |
| `POST` | `/api/v1/auth/register/resend` | Public | Gửi lại mã OTP kích hoạt tài khoản | Sẵn sàng |
| `POST` | `/api/v1/auth/register/verify` | Public | Xác thực OTP 6 số để kích hoạt tài khoản | Sẵn sàng |
| `POST` | `/api/v1/auth/login` | Public | Đăng nhập (cấp cookie `access_token` & `refresh_token`) | Sẵn sàng |
| `POST` | `/api/v1/auth/refresh` | Public (yêu cầu cookie) | Làm mới phiên đăng nhập (xoay vòng refresh token) | Sẵn sàng |
| `POST` | `/api/v1/auth/logout` | Public (yêu cầu cookie) | Đăng xuất (thu hồi phiên trong DB, xóa cookie) | Sẵn sàng |
| `POST` | `/api/v1/auth/password/forgot` | Public | Yêu cầu gửi OTP quên mật khẩu | Sẵn sàng |
| `POST` | `/api/v1/auth/password/verify` | Public | Xác thực OTP quên mật khẩu (nhận grant token) | Sẵn sàng |
| `POST` | `/api/v1/auth/password/reset` | Public | Đặt lại mật khẩu mới bằng grant token | Sẵn sàng |
| `GET` | `/api/v1/users/me` | Authenticated (Cần đăng nhập) | Xem thông tin tài khoản hiện tại | Sẵn sàng |
| `GET` | `/api/v1/users/me/goal` | Authenticated | **M01.06: Xem mục tiêu học tập TOEIC** | **Mới hoàn thành** |
| `PUT` | `/api/v1/users/me/goal` | Authenticated | **M01.06: Cập nhật mục tiêu học tập TOEIC** | **Mới hoàn thành** |

---

## 2. Chi tiết Hợp đồng Tính năng M01.06: Mục tiêu Học tập (Learning Goal)

### 2.1. Lấy thông tin Mục tiêu hiện tại (`GET /api/v1/users/me/goal`)
- **Mô tả:** Lấy mục tiêu điểm TOEIC và thời gian học mỗi ngày của học viên đang đăng nhập.
- **Yêu cầu:** Gửi kèm cookie `ACCESS_TOKEN`.
- **Response thành công:** `200 OK`
```json
{
  "targetScore": 750,
  "dailyMinutes": 45,
  "targetDate": "2027-06-30",
  "updatedAt": "2026-11-11T18:10:21.612242Z"
}
```
- **Response khi chưa thiết lập:** `404 Not Found` (Problem Detail)
```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Chưa thiết lập mục tiêu học tập.",
  "code": "RESOURCE_NOT_FOUND",
  "traceId": "c3e981bf-4b95-4eb8-b0a5-2980cf7bc8e2",
  "errors": []
}
```

---

### 2.2. Thiết lập / Cập nhật Mục tiêu TOEIC (`PUT /api/v1/users/me/goal`)
- **Mô tả:** Học viên đặt hoặc cập nhật mục tiêu điểm thi TOEIC và số phút tự học mỗi ngày.
- **Yêu cầu:** Gửi kèm cookie `ACCESS_TOKEN` và header `X-XSRF-TOKEN`.
- **Request Body:**
```json
{
  "targetScore": 750,
  "dailyMinutes": 45,
  "targetDate": "2027-06-30"
}
```

#### Quy chuẩn Validate:
1. `targetScore` (`Short`, bắt buộc):
   - Giá trị từ `10` đến `990`.
   - **Bắt buộc chia hết cho 5** (theo chuẩn thang điểm TOEIC: 450, 500, 650, 750, 800...).
2. `dailyMinutes` (`Integer`, bắt buộc):
   - Số phút học mục tiêu mỗi ngày: Từ `5` đến `240` phút.
3. `targetDate` (`String` dạng `YYYY-MM-DD`, tùy chọn):
   - Nếu có, ngày mục tiêu phải là ngày hôm nay hoặc trong tương lai (`>= LocalDate.now()`).

- **Response thành công:** `200 OK`
```json
{
  "targetScore": 750,
  "dailyMinutes": 45,
  "targetDate": "2027-06-30",
  "updatedAt": "2026-11-11T18:10:21.612242Z"
}
```

- **Response lỗi Validate (`400 Bad Request`):**
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Điểm mục tiêu phải chia hết cho 5 (ví dụ: 500, 650, 750).",
  "code": "VALIDATION_ERROR",
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "errors": []
}
```

---

## 3. TypeScript Interfaces cho Frontend (`masterenglish-web`)

FE có thể copy trực tiếp vào `src/features/learning/types/` hoặc `src/features/auth/types/`:

```typescript
export interface LearningGoal {
  targetScore: number;     // 10 - 990, chia het cho 5
  dailyMinutes: number;    // 5 - 240 phut
  targetDate: string | null; // ISO Date YYYY-MM-DD
  updatedAt: string;       // ISO Timestamp
}

export interface UpdateLearningGoalRequest {
  targetScore: number;
  dailyMinutes: number;
  targetDate?: string | null;
}
```
