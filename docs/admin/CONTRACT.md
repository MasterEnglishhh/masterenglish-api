# Hợp đồng API chi tiết: Admin Portal (Quản trị Hệ thống & Nội dung)

**Ngày ban hành:** 11/11/2026  
**Module Backend:** `com.masterenglish.api.modules.admin`  
**Phiên bản API:** `v1`  
**Cơ chế xác thực:** Session Cookie (`ACCESS_TOKEN`), CSRF Header (`X-XSRF-TOKEN`).  
**Định dạng lỗi chung:** RFC 7807 (`application/problem+json`) với `{ type, title, status, detail, code, traceId, errors }`.

---

## 1. Tổng quan phân quyền (RBAC)

| Vai trò (RoleCode) | Mô tả quyền hạn trong Admin Portal |
|---|---|
| `SYSTEM_ADMIN` | Toàn quyền: Xem Dashboard, quản lý User/Roles/Lock, Quản trị Nội dung, Feature Flags, Quotas, Audit Logs. |
| `CONTENT_ADMIN` | Quyền nội dung: Xem Dashboard, CRUD bản thảo Từ vựng/Bộ thẻ, duyệt/từ chối/xuất bản Revisions, quản lý nguồn trích dẫn. |
| `LEARNER` | Bị từ chối (`403 Forbidden`) đối với tất cả endpoint `/api/v1/admin/*`. |

---

## 2. Danh mục API chi tiết

### 2.1. Phân hệ Tổng quan (Overview Dashboard)
*Yêu cầu quyền:* `SYSTEM_ADMIN` hoặc `CONTENT_ADMIN`

#### `GET /api/v1/admin/overview/kpis`
- **Mô tả:** Lấy danh sách thẻ KPI hiển thị đầu trang Dashboard.
- **Request:** Không tham số.
- **Response:** `200 OK`
```json
[
  {
    "id": "total-users",
    "title": "Người dùng đăng ký",
    "value": "14,820",
    "subValue": "1,240 hoạt động",
    "changePercent": 12.5,
    "trendText": "+12.5% so với tuần trước",
    "trendDirection": "up",
    "iconName": "users"
  },
  {
    "id": "content-bank",
    "title": "Ngân hàng nội dung",
    "value": "3,450",
    "subValue": "128 chờ duyệt",
    "changePercent": 8.2,
    "trendText": "+8.2% câu hỏi & từ vựng mới",
    "trendDirection": "up",
    "iconName": "book-open"
  },
  {
    "id": "storage-r2",
    "title": "Dung lượng Cloudflare R2",
    "value": "42.8 GB",
    "subValue": "/ 100 GB định mức",
    "changePercent": 42.8,
    "trendText": "42.8% tổng bộ nhớ",
    "trendDirection": "neutral",
    "iconName": "database"
  },
  {
    "id": "security-health",
    "title": "Trạng thái hệ thống",
    "value": "99.98%",
    "subValue": "0 cảnh báo rủi ro",
    "changePercent": 0.0,
    "trendText": "Toàn bộ API hoạt động ổn định",
    "trendDirection": "neutral",
    "iconName": "shield-check"
  }
]
```

#### `GET /api/v1/admin/overview/activities`
- **Mô tả:** Lấy dòng hoạt động gần đây (Recent Activities stream).
- **Query params:**
  - `limit`: `integer` (mặc định `10`, tối đa `50`).
- **Response:** `200 OK`
```json
[
  {
    "id": "7b0d876a-5c20-41bf-9e12-bdf94291f0c2",
    "actorEmail": "admin@masterenglish.edu.vn",
    "actorName": "Admin",
    "action": "USER_ROLES_UPDATED",
    "target": "USER",
    "module": "IDENTITY",
    "timestamp": "2026-11-10T14:30:00Z",
    "status": "SUCCESS"
  }
]
```

---

### 2.2. Phân hệ Quản lý Người dùng & Quyền (User Management)
*Yêu cầu quyền:* `SYSTEM_ADMIN`

#### `GET /api/v1/admin/users`
- **Mô tả:** Lọc, tìm kiếm và phân trang người dùng.
- **Query params:**
  - `search`: `string` (tìm theo email hoặc tên, case-insensitive).
  - `role`: `"LEARNER" | "CONTENT_ADMIN" | "SYSTEM_ADMIN"` (tùy chọn).
  - `status`: `"ACTIVE" | "LOCKED" | "PENDING_VERIFICATION" | "DISABLED"` (tùy chọn).
  - `page`: `integer` (bắt đầu từ `0`, mặc định `0`).
  - `size`: `integer` (mặc định `20`, tối đa `100`).
  - `sortBy`: `"createdAt" | "email" | "id"` (mặc định `"createdAt"`).
  - `sortDirection`: `"asc" | "desc"` (mặc định `"desc"`).
- **Response:** `200 OK`
```json
{
  "items": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "email": "learner@example.com",
      "displayName": "Nguyễn Văn A",
      "firstName": "A",
      "lastName": "Nguyễn Văn",
      "phone": "0987654321",
      "status": "ACTIVE",
      "roles": ["LEARNER"],
      "authVersion": 1,
      "activeSessionsCount": 2,
      "createdAt": "2026-10-01T08:00:00Z",
      "lastLoginAt": null,
      "lockReason": null,
      "lockedUntil": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1,
  "hasNext": false
}
```

#### `GET /api/v1/admin/users/{id}`
- **Mô tả:** Xem chi tiết một người dùng.
- **Response:** `200 OK` (Cùng cấu trúc `AdminUserDto` như một phần tử trong danh sách).

#### `PUT /api/v1/admin/users/{id}/roles`
- **Mô tả:** Gán/thu hồi quyền người dùng. Bắt buộc giữ lại ít nhất 1 role. Tự động tăng `authVersion` và thu hồi session hiện tại để áp dụng quyền mới.
- **Request Body:**
```json
{
  "roles": ["LEARNER", "CONTENT_ADMIN"]
}
```
- **Response:** `200 OK` (`AdminUserDto`).

#### `POST /api/v1/admin/users/{id}/lock`
- **Mô tả:** Khóa tài khoản người dùng, thu hồi toàn bộ session đăng nhập lập tức.
- **Request Body:**
```json
{
  "reason": "Vi phạm điều khoản sử dụng",
  "lockedUntil": "2026-12-31T23:59:59Z"
}
```
- **Response:** `200 OK` (`AdminUserDto`).

#### `POST /api/v1/admin/users/{id}/unlock`
- **Mô tả:** Mở khóa tài khoản về trạng thái `ACTIVE`.
- **Request Body:** Không có.
- **Response:** `200 OK` (`AdminUserDto`).

#### `POST /api/v1/admin/users/{id}/revoke-sessions`
- **Mô tả:** Thu hồi toàn bộ phiên đăng nhập đang hoạt động của người dùng (tăng `authVersion`).
- **Request Body:** Không có.
- **Response:** `200 OK`
```json
{
  "message": "Đã thu hồi toàn bộ phiên đăng nhập của người dùng."
}
```

---

### 2.3. Phân hệ Quản trị Nội dung (Content Ops)
*Yêu cầu quyền:* `CONTENT_ADMIN` hoặc `SYSTEM_ADMIN`

#### `GET /api/v1/admin/content/items`
- **Mô tả:** Danh sách nội dung (Từ vựng, Bộ thẻ) kèm phiên bản và trạng thái mới nhất.
- **Query params:**
  - `search`: `string` (tùy chọn).
  - `contentType`: `"WORD" | "DECK" | "ALL"` (tùy chọn).
  - `status`: `"DRAFT" | "IN_REVIEW" | "APPROVED" | "PUBLISHED" | "ARCHIVED" | "ALL"` (tùy chọn).
  - `page`, `size`: `integer`.
- **Response:** `200 OK`
```json
{
  "items": [
    {
      "id": "18000000-0000-0000-0000-000000000001",
      "title": "resilience",
      "code": "WORD-resilience",
      "module": "VOCABULARY",
      "type": "WORD",
      "status": "PUBLISHED",
      "versionNo": 1,
      "summary": "Khả năng phục hồi, kiên cường",
      "author": "Admin",
      "reviewer": null,
      "updatedAt": "2026-11-05T10:00:00Z",
      "level": "B2",
      "latestRevisionId": "28000000-0000-0000-0000-000000000001"
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1,
  "hasNext": false
}
```

#### `POST /api/v1/admin/content/words`
- **Mô tả:** Tạo bản thảo từ vựng mới (`DRAFT`).
- **Request Body:**
```json
{
  "lemma": "meticulous",
  "partOfSpeech": "ADJECTIVE",
  "cefrLevel": "C1",
  "meaningVi": "Tỉ mỉ, kỹ lưỡng, cẩn thận từng chi tiết",
  "definitionEn": "Showing great attention to detail; very careful and precise",
  "ipa": "/məˈtɪkjələs/",
  "examplesEn": ["He was meticulous about keeping his records clean."],
  "examplesVi": ["Anh ấy rất tỉ mỉ trong việc giữ sổ sách sạch sẽ."],
  "sourceId": null,
  "changeSummary": "Bổ sung từ vựng C1 học thuật"
}
```
- **Response:** `201 Created` (`AdminContentItemDto`).

#### `POST /api/v1/admin/content/decks`
- **Mô tả:** Tạo bản thảo bộ Flashcard mới (`DRAFT`).
- **Request Body:**
```json
{
  "title": "TOEIC Essential 600 - Business",
  "description": "Bộ từ vựng trọng tâm kinh doanh trong đề thi TOEIC",
  "level": "B2",
  "wordSenseIds": [],
  "sourceId": null,
  "changeSummary": "Khởi tạo bộ thẻ mới"
}
```
- **Response:** `201 Created` (`AdminContentItemDto`).

#### `POST /api/v1/admin/content/revisions/{id}/submit`
- **Mô tả:** Nộp bản thảo lên hàng đợi phê duyệt (`DRAFT` → `IN_REVIEW`).
- **Response:** `200 OK`
```json
{
  "revisionId": "uuid",
  "contentItemId": "uuid",
  "status": "IN_REVIEW",
  "versionNo": 1,
  "reviewNote": null,
  "publishedAt": null,
  "updatedAt": "2026-11-10T15:00:00Z"
}
```

#### `POST /api/v1/admin/content/revisions/{id}/approve`
- **Mô tả:** Phê duyệt bản thảo (`IN_REVIEW` → `APPROVED`).
- **Request Body:**
```json
{
  "reviewNote": "Đạt chuẩn định dạng và phiên âm"
}
```
- **Response:** `200 OK` (`AdminRevisionDto`).

#### `POST /api/v1/admin/content/revisions/{id}/reject`
- **Mô tả:** Từ chối bản thảo (`IN_REVIEW` → `REJECTED`). Bắt buộc có lý do.
- **Request Body:**
```json
{
  "reviewNote": "Cần bổ sung ví dụ minh họa thứ hai cho rõ ngữ cảnh."
}
```
- **Response:** `200 OK` (`AdminRevisionDto`).

#### `POST /api/v1/admin/content/revisions/{id}/publish`
- **Mô tả:** Xuất bản nội dung (`APPROVED` → `PUBLISHED`). Tự động chiếu dữ liệu snapshot vào các bảng nghiệp vụ (`words`, `word_senses`, `decks`, `deck_words`).
- **Response:** `200 OK` (`AdminRevisionDto`).

#### `POST /api/v1/admin/content/items/{id}/archive`
- **Mô tả:** Lưu trữ / ẩn nội dung (`archived = true`).
- **Request Body:**
```json
{
  "reason": "Bộ từ vựng đã lỗi thời theo chuẩn format mới"
}
```
- **Response:** `200 OK`
```json
{
  "message": "Nội dung đã được chuyển sang trạng thái lưu trữ."
}
```

#### `GET /api/v1/admin/content/sources`
- **Mô tả:** Danh sách nguồn trích dẫn & giấy phép bản quyền.
- **Query params:** `search`, `page`, `size`.
- **Response:** `200 OK` (`PageResponse<ContentSourceDto>`).

#### `POST /api/v1/admin/content/sources`
- **Mô tả:** Thêm nguồn trích dẫn mới.
- **Request Body:**
```json
{
  "title": "Oxford Advanced Learner's Dictionary 10th",
  "sourceType": "EXTERNAL",
  "sourceUrl": "https://www.oxfordlearnersdictionaries.com",
  "author": "Oxford University Press",
  "licenseCode": "EDITORIAL_CITATION",
  "licenseUrl": null,
  "attribution": "Trích dẫn định nghĩa và ví dụ học thuật",
  "permissionNote": null
}
```
- **Response:** `201 Created` (`ContentSourceDto`).

---

### 2.4. Phân hệ Hệ thống & Chính sách (System & Policy)
*Yêu cầu quyền:* `SYSTEM_ADMIN`

#### `GET /api/v1/admin/system/feature-flags`
- **Mô tả:** Lấy danh sách toàn bộ cờ tính năng runtime.
- **Response:** `200 OK`
```json
[
  {
    "key": "ai_tutor_chat",
    "name": "AI Tutor Context (Claude/GPT)",
    "description": "Kích hoạt mô hình giải thích câu hỏi chuyên sâu và đối thoại ngữ cảnh khi làm bài.",
    "enabled": true,
    "category": "AI",
    "updatedAt": "2026-11-10T10:00:00Z"
  },
  {
    "key": "r2_direct_upload",
    "name": "Cloudflare R2 Direct Upload",
    "description": "Cho phép client upload media trực tiếp lên Cloudflare R2 qua presigned URL.",
    "enabled": true,
    "category": "STORAGE",
    "updatedAt": "2026-11-10T10:00:00Z"
  },
  {
    "key": "otp_registration_strict",
    "name": "Xác thực OTP Email nghiêm ngặt",
    "description": "Bắt buộc xác thực mã OTP 6 số qua email trước khi kích hoạt tài khoản.",
    "enabled": true,
    "category": "AUTH",
    "updatedAt": "2026-11-10T10:00:00Z"
  },
  {
    "key": "srs_spaced_repetition",
    "name": "Thuật toán SRS Spaced Repetition",
    "description": "Kích hoạt thuật toán lặp lại ngắt quãng SM-2 tùy biến trong ôn tập từ vựng.",
    "enabled": true,
    "category": "LEARNING",
    "updatedAt": "2026-11-10T10:00:00Z"
  }
]
```

#### `PUT /api/v1/admin/system/feature-flags/{key}`
- **Mô tả:** Bật/tắt cờ tính năng (tự động ghi nhận audit log `UPDATE_FEATURE_FLAG`).
- **Request Body:**
```json
{
  "enabled": false
}
```
- **Response:** `200 OK` (`FeatureFlagDto`).

#### `GET /api/v1/admin/system/quotas/storage` (hoặc `/meters/storage`)
- **Mô tả:** Thống kê mức sử dụng dung lượng lưu trữ Cloudflare R2.
- **Response:** `200 OK`
```json
{
  "bucket": "masterenglish-media-r2",
  "usedBytes": 45980000000,
  "maxBytes": 107374182400,
  "usedFormatted": "42.8 GB",
  "limitFormatted": "100.0 GB",
  "percentUsed": 42.8,
  "filesCount": 1420
}
```

#### `GET /api/v1/admin/system/quotas/ai` (hoặc `/meters/ai-quota`)
- **Mô tả:** Thống kê hạn mức token AI trong ngày.
- **Response:** `200 OK`
```json
[
  {
    "provider": "Anthropic / OpenAI",
    "model": "claude-3-5-sonnet & gpt-4o-mini",
    "dailyTokenUsage": 1420500,
    "dailyTokenLimit": 5000000,
    "percentUsed": 28.4,
    "resetHours": 6
  }
]
```

#### `GET /api/v1/admin/system/audit-logs`
- **Mô tả:** Tra cứu nhật ký kiểm toán hệ thống có phân trang và bộ lọc.
- **Query params:**
  - `search`: `string` (hành động, lý do).
  - `entityType`: `"ALL" | "USER" | "ROLE" | "CONTENT" | "FEATURE_FLAG" | "SESSION"` (tùy chọn).
  - `page`, `size`: `integer`.
- **Response:** `200 OK`
```json
{
  "items": [
    {
      "id": "e2a96950-c65f-4bf1-bf17-09f1965bb35d",
      "actorEmail": "admin@masterenglish.edu.vn",
      "action": "UPDATE_FEATURE_FLAG",
      "entityType": "FEATURE_FLAG",
      "entityId": null,
      "details": "Chuyển trạng thái cờ AI Tutor sang TẮT",
      "ipAddress": "127.0.0.1",
      "timestamp": "2026-11-10T16:20:00Z",
      "severity": "INFO"
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1,
  "hasNext": false
}
```

---

## 3. Quy chuẩn lỗi (RFC 7807 Problem Detail)

Khi xảy ra lỗi, API luôn trả HTTP status code tương ứng kèm body:
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Review note is required when rejecting.",
  "code": "VALIDATION_ERROR",
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "errors": [
    {
      "field": "reviewNote",
      "message": "Review note is required when rejecting."
    }
  ]
}
```

Các mã lỗi thường gặp:
- `UNAUTHENTICATED` (`401`): Chưa gửi cookie phiên hoặc token hết hạn.
- `ACCESS_DENIED` (`403`): Thiếu vai trò (`SYSTEM_ADMIN` hoặc `CONTENT_ADMIN`).
- `RESOURCE_NOT_FOUND` (`404`): Không tìm thấy User, Revision, Content Item hoặc Feature Flag.
- `CONFLICT` (`409`): Sai trạng thái vòng đời (ví dụ: cố publish khi chưa approve, hoặc submit khi không phải DRAFT).
- `VALIDATION_ERROR` (`400`): Dữ liệu đầu vào sai quy chuẩn.
