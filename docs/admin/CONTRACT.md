# Hợp đồng API chi tiết: Admin Portal (Quản trị Hệ thống & Nội dung)

**Ngày ban hành:** 11/11/2026\
**Module Backend:** `com.masterenglish.api.modules.admin`\
**Phiên bản API:** `v1`\
**Cơ chế xác thực:** Session Cookie (`ACCESS_TOKEN`), CSRF Header (`X-XSRF-TOKEN`).\
**Định dạng lỗi chung:** RFC 7807 (`application/problem+json`) với `{ type, title, status, detail, code, traceId, errors }`.

---

## 1. Tổng quan phân quyền (RBAC)

| Vai trò (RoleCode) | Mô tả quyền hạn trong Admin Portal |
|---|---|
| `SYSTEM_ADMIN` | Toàn quyền: Xem Dashboard, quản lý User/Roles/Lock, Quản trị Nội dung, Feature Flags (chế độ chỉ xem), Quotas, Audit Logs. |
| `CONTENT_ADMIN` | Quyền nội dung: Xem Dashboard, CRUD bản thảo Từ vựng/Bộ thẻ, duyệt/từ chối/xuất bản Revisions, quản lý nguồn trích dẫn. |
| `LEARNER` | Bị từ chối (`403 Forbidden`) đối với tất cả endpoint `/api/v1/admin/*`. |

---

## 2. Danh mục API chi tiết

### 2.1. Phân hệ Tổng quan (Overview Dashboard)
*Yêu cầu quyền:* `SYSTEM_ADMIN` hoặc `CONTENT_ADMIN`

#### `GET /api/v1/admin/overview/kpis`
- **Mô tả:** Lấy danh sách thẻ KPI hiển thị đầu trang Dashboard. Không còn claim tăng trưởng hoặc sức khỏe hệ thống giả:
  - `total-users` và `content-bank`: `changePercent` và `trendText` trả về `null` do chưa có nguồn đo tăng trưởng thực tế.
  - `storage-r2`: giữ số liệu đo đạc thực tế từ Cloudflare R2 (`storage.percentUsed()`).
  - `security-health`: trả về `value: "Chưa có dữ liệu"`, `subValue: null`, `changePercent: null`, `trendText: null`; không suy đoán chỉ số giả như `99.98%` hay `0 cảnh báo`.
- **Request:** Không tham số.
- **Response:** `200 OK`
```json
[
  {
    "id": "total-users",
    "title": "Người dùng đăng ký",
    "value": "14,820",
    "subValue": "1,240 hoạt động",
    "changePercent": null,
    "trendText": null,
    "trendDirection": "neutral",
    "iconName": "users"
  },
  {
    "id": "content-bank",
    "title": "Ngân hàng nội dung",
    "value": "3,450",
    "subValue": "128 chờ duyệt",
    "changePercent": null,
    "trendText": null,
    "trendDirection": "neutral",
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
    "value": "Chưa có dữ liệu",
    "subValue": null,
    "changePercent": null,
    "trendText": null,
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
- **Mô tả:** Danh sách nội dung (Từ vựng, Bộ thẻ, Chủ điểm & Bài học ngữ pháp) kèm phiên bản và trạng thái mới nhất.
- **Query params:**
  - `domain`: `"GRAMMAR" | "VOCABULARY" | "ALL"` (tùy chọn). Khi `domain=GRAMMAR`, chỉ trả về các nội dung thuộc ngữ pháp (`GRAMMAR_TOPIC`, `GRAMMAR_LESSON`), không lẫn `WORD`/`DECK`/`QUESTION`. Nếu kết hợp xung đột (`domain=GRAMMAR&contentType=WORD`), trả về danh sách rỗng. Domain không hợp lệ trả về `400 Bad Request`.
  - `search`: `string` (tùy chọn).
  - `contentType`: `"WORD" | "DECK" | "GRAMMAR_TOPIC" | "GRAMMAR_LESSON" | "ALL"` (tùy chọn).
  - `status`: `"DRAFT" | "IN_REVIEW" | "APPROVED" | "PUBLISHED" | "ARCHIVED" | "ALL"` (tùy chọn).
  - `domain`: `"GRAMMAR"` (tùy chọn). Chỉ lọc `GRAMMAR_TOPIC` và `GRAMMAR_LESSON`, không gộp `QUESTION`. Giá trị khác trả `400`. Mục grammar có `module: "GRAMMAR"`.
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

#### `GET /api/v1/admin/content/items/{id}`
- **Mô tả:** Đọc chi tiết một nội dung kèm revision hiện hành (DRAFT nếu đang mở, hoặc PUBLISHED), optimistic lock token `version` (của revision) và `itemVersion` (của content_item), dữ liệu snapshot payload và danh sách mã nguồn trích dẫn.
- **Response:** `200 OK` (`AdminContentDetailDto`):
```json
{
  "id": "18000000-0000-0000-0000-000000000001",
  "revisionId": "28000000-0000-0000-0000-000000000002",
  "contentType": "WORD",
  "status": "DRAFT",
  "versionNo": 2,
  "version": 1,
  "itemVersion": 1,
  "data": {
    "lemma": "resilient",
    "language_code": "en",
    "ipa": "/rɪˈzɪliənt/",
    "senses": [
      {
        "id": "c2000000-0000-0000-0000-000000000001",
        "sense_no": 1,
        "part_of_speech": "ADJECTIVE",
        "cefr_level": "B2",
        "meaning_vi": "kiên cường",
        "definition_en": "able to recover quickly",
        "examples": []
      }
    ]
  },
  "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "changeSummary": "Bản nháp v2",
  "createdBy": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reviewedBy": null,
  "reviewedAt": null,
  "reviewNote": null,
  "publishedAt": null,
  "createdAt": "2026-11-05T10:00:00Z",
  "updatedAt": "2026-11-10T12:00:00Z"
}
```
- **Lỗi:**
  - `401 Unauthorized`: Chưa đăng nhập.
  - `403 Forbidden`: Người dùng vai trò `LEARNER`.
  - `404 Not Found`: Không tìm thấy nội dung với `id`.

#### `GET /api/v1/admin/content/items/{id}/revisions`
- **Mô tả:** Lấy danh sách lịch sử phân trang các bản sửa đổi (`revisions`) của nội dung, sắp xếp `versionNo DESC, id DESC`.
- **Query params:**
  - `page`: `integer` (mặc định 0).
  - `size`: `integer` (mặc định 20, tối đa 100).
- **Response:** `200 OK` (`PageResponse<AdminRevisionHistoryDto>`):
```json
{
  "items": [
    {
      "revisionId": "28000000-0000-0000-0000-000000000002",
      "contentItemId": "18000000-0000-0000-0000-000000000001",
      "versionNo": 2,
      "status": "DRAFT",
      "version": 0,
      "changeSummary": "Bắt đầu v2",
      "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
      "createdBy": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "reviewedBy": null,
      "reviewedAt": null,
      "reviewNote": null,
      "publishedAt": null,
      "createdAt": "2026-11-10T12:00:00Z",
      "updatedAt": "2026-11-10T12:00:00Z"
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

#### `POST /api/v1/admin/content/grammar/topics`, `PUT /api/v1/admin/content/grammar/topics/{id}`
- **Mô tả:** Tạo bản nháp chủ điểm `GRAMMAR_TOPIC` (`POST`), hoặc sửa bản nháp của mục `{id}` (`PUT`). `POST` nhận `CreateGrammarDraftRequest` (`data`, `sourceIds`, `changeSummary`). `PUT` nhận `SaveGrammarTopicDraftRequest` (flat fields). Mọi trường tùy chọn; validator của `grammar` kiểm tra khi xuất bản. Quy tắc sửa tại chỗ, 404, 409 xem [grammar/CONTRACT.md](../grammar/CONTRACT.md) mục 7.
- **Request Body (POST):**
```json
{
  "data": {
    "name": "Articles & Determiners",
    "slug": "articles-and-determiners",
    "description": "A, An, The usage",
    "difficulty": "BASIC",
    "sort_order": 1
  },
  "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "changeSummary": "Khởi tạo chủ điểm mạo từ"
}
```
- **Request Body (PUT):**
```json
{
  "slug": "present-simple",
  "name": "Thì hiện tại đơn",
  "description": "Diễn tả thói quen và sự thật hiển nhiên",
  "difficulty": "BASIC",
  "sortOrder": 1,
  "sourceId": null,
  "changeSummary": "Khởi tạo chủ điểm"
}
```
- **Response:** `201 Created` khi tạo, `200 OK` khi sửa (`AdminContentItemDto`, `module: "GRAMMAR"`).
- **Lỗi:**
  - `400 Bad Request`: Trường dữ liệu không thuộc allowlist, nguồn trích dẫn không tồn tại, hoặc dữ liệu lồng nhau sai quy chuẩn.
  - `401 Unauthorized`: Chưa đăng nhập.
  - `403 Forbidden`: Người dùng vai trò `LEARNER` hoặc thiếu token CSRF.

#### `POST /api/v1/admin/content/grammar/lessons`, `PUT /api/v1/admin/content/grammar/lessons/{id}`
- **Mô tả:** Tạo bản nháp bài học `GRAMMAR_LESSON` (`POST`), hoặc sửa bản nháp của mục `{id}` (`PUT`). `POST` nhận `CreateGrammarDraftRequest`. `PUT` nhận `SaveGrammarLessonDraftRequest`. `pinned_topic_revision_id` được ghim theo revision đang xuất bản của chủ điểm lúc lưu.
- **Request Body (POST):**
```json
{
  "data": {
    "topic_id": "18000000-0000-0000-0000-000000000001",
    "pinned_topic_revision_id": "28000000-0000-0000-0000-000000000001",
    "title": "Definite Article",
    "slug": "definite-article",
    "position": 1,
    "body_schema_version": 1,
    "estimated_minutes": 10,
    "body": {
      "blocks": [
        { "type": "heading", "text": "Overview" },
        { "type": "paragraph", "text": "The definite article 'the' is used..." }
      ]
    }
  },
  "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "changeSummary": "Khởi tạo bài học mạo từ xác định"
}
```
- **Request Body (PUT):**
```json
{
  "topicId": "41000000-0000-0000-0000-000000000001",
  "title": "Bài 1: Khẳng định",
  "position": 1,
  "estimatedMinutes": 10,
  "body": {
    "blocks": [
      { "type": "heading", "text": "Cấu trúc" },
      { "type": "paragraph", "text": "Chủ ngữ + động từ nguyên mẫu (s/es)." }
    ]
  },
  "sourceId": null,
  "changeSummary": "Khởi tạo bài học"
}
```
- **Response:** `201 Created` khi tạo, `200 OK` khi sửa (`AdminContentItemDto`, `module: "GRAMMAR"`).
- **Lỗi:**
  - `400 Bad Request`: Trường dữ liệu không thuộc allowlist, body vượt trần 100.000 ký tự / 1 MiB UTF-8 / độ sâu 16 mức, chứa HTML, nguồn không tồn tại.
  - `401 Unauthorized`: Chưa đăng nhập.
  - `403 Forbidden`: Không đủ quyền hoặc thiếu token CSRF.

#### `POST /api/v1/admin/content/questions`
- **Mô tả:** Tạo bản thảo câu hỏi mới (`QUESTION`) kèm revision DRAFT (versionNo = 1). Hỗ trợ partial draft (chấp nhận lưu bản nháp chưa đủ options hoặc topic). Danh mục hỗ trợ: `GRAMMAR`, `VOCABULARY`, `TOEIC`. Đối với câu hỏi độc lập TOEIC, bắt buộc `part_number` thuộc `{1, 2, 5}`.
- **Request Body:**
```json
{
  "data": {
    "category": "GRAMMAR",
    "part_number": null,
    "question_type_code": "SINGLE_CHOICE",
    "practice_mode": "STANDARD",
    "difficulty": "HARD",
    "prompt": "She ___ to school every day.",
    "group": null,
    "options": [
      { "id": "4fa85f64-5717-4562-b3fc-2c963f66afa1", "position": 1, "body": "goes" },
      { "id": "4fa85f64-5717-4562-b3fc-2c963f66afa2", "position": 2, "body": "go" }
    ],
    "answer_key": {
      "correctOptionId": "4fa85f64-5717-4562-b3fc-2c963f66afa1"
    },
    "explanation": "Third person singular takes -s.",
    "materials": [],
    "topic_ids": ["18000000-0000-0000-0000-000000000001"],
    "word_sense_ids": []
  },
  "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "changeSummary": "Khởi tạo câu hỏi ngữ pháp thì hiện tại đơn"
}
```
- **Response:** `201 Created` (`AdminContentItemDto`).
- **Lỗi:**
  - `400 Bad Request`: Trường dữ liệu không thuộc allowlist (`category`, `part_number`, `question_type_code`, `practice_mode`, `difficulty`, `prompt`, `group`, `options`, `answer_key`, `explanation`, `materials`, `topic_ids`, `word_sense_ids`), độ sâu JSON vượt quá 10, chứa HTML, `category` không hợp lệ, `position` không thuộc 1..6 hoặc bị trùng, `id` lựa chọn không phải UUID hoặc bị trùng, nguồn trích dẫn không tồn tại.
  - `401 Unauthorized`: Chưa đăng nhập.
  - `403 Forbidden`: Không đủ quyền (yêu cầu `CONTENT_ADMIN` hoặc `SYSTEM_ADMIN`) hoặc thiếu token CSRF.

#### `POST /api/v1/admin/content/question-groups`
- **Mô tả:** Tạo bản thảo nhóm câu hỏi mới (`QUESTION_GROUP`) kèm revision DRAFT (versionNo = 1). Hỗ trợ partial draft. Hỗ trợ tạo inline các câu hỏi con (`children`) hoặc liên kết câu hỏi đã có qua `question_id`.
- **Request Body:**
```json
{
  "data": {
    "category": "TOEIC",
    "part_number": 7,
    "group_type": "PASSAGE",
    "title": "Email Memo Announcement",
    "materials": [
      {
        "position": 1,
        "material_type": "TEXT",
        "usage_role": "STIMULUS",
        "body": "Office will be closed on Friday for building maintenance."
      }
    ],
    "children": [
      {
        "prompt": "When will the office be closed?",
        "category": "TOEIC",
        "part_number": 7,
        "options": [
          { "id": "7fa85f64-5717-4562-b3fc-2c963f66afa1", "position": 1, "body": "On Friday" },
          { "id": "7fa85f64-5717-4562-b3fc-2c963f66afa2", "position": 2, "body": "On Monday" }
        ],
        "answer_key": {
          "correctOptionId": "7fa85f64-5717-4562-b3fc-2c963f66afa1"
        }
      }
    ]
  },
  "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "changeSummary": "Khởi tạo nhóm câu hỏi đọc hiểu thông báo"
}
```
- **Response:** `201 Created` (`AdminContentItemDto`).
- **Lỗi:**
  - `400 Bad Request`: Trường dữ liệu không thuộc allowlist (`category`, `part_number`, `group_type`, `title`, `materials`, `children`), độ sâu JSON vượt trần, chứa HTML, `materials` không hợp lệ (`material_type` phải là `TEXT`, `IMAGE`, `AUDIO`), hoặc câu hỏi con vi phạm ràng buộc.
  - `401 Unauthorized`: Chưa đăng nhập.
  - `403 Forbidden`: Không đủ quyền hoặc thiếu token CSRF.

#### `POST /api/v1/admin/content/practice-sets`
- **Mô tả:** Tạo bản thảo bộ đề luyện tập mới (`PRACTICE_SET`) kèm revision DRAFT (versionNo = 1). Hỗ trợ partial draft.
- **Request Body:**
```json
{
  "data": {
    "category": "TOEIC",
    "title": "TOEIC Mini Test 01",
    "selection_mode": "FIXED",
    "selection_config": {},
    "entries": [
      {
        "item_id": "18000000-0000-0000-0000-000000000001",
        "position": 1
      }
    ],
    "part_number": 5,
    "time_limit_seconds": 900
  },
  "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "changeSummary": "Khởi tạo bộ đề thi thử TOEIC Part 5"
}
```
- **Response:** `201 Created` (`AdminContentItemDto`).
- **Lỗi:**
  - `400 Bad Request`: Trường dữ liệu không thuộc allowlist, `selection_mode` không phải `FIXED` hoặc `DYNAMIC`, độ sâu vượt quá 10, nguồn không tồn tại.
  - `401 Unauthorized`: Chưa đăng nhập.
  - `403 Forbidden`: Không đủ quyền hoặc thiếu token CSRF.

#### `PUT /api/v1/admin/content/revisions/{id}`
- **Mô tả:** Chỉnh sửa bản thảo revision với cơ chế CAS (`expectedVersion`).
  - Cho phép sửa khi ở trạng thái `DRAFT`.
  - Nếu sửa khi ở trạng thái `REJECTED` hoặc `APPROVED`, hệ thống tự động reset trạng thái về `DRAFT` và xóa thông tin duyệt trước đó (`reviewedBy`, `reviewedAt`, `reviewNote`).
  - Bị chặn (`409 Conflict`) khi đang ở trạng thái `IN_REVIEW` hoặc `PUBLISHED`.
  - Kiểm tra tính hợp lệ của nguồn trích dẫn (`sourceIds` không được rỗng khi xuất bản; các ID nguồn phải tồn tại).
  - Kiểm tra quyền sở hữu sense và example (không được chuyển nghĩa/ví dụ sang từ khác).
  - Đối với DECK, tự động kiểm tra nghĩa từ thuộc từ đã xuất bản, ghim đúng revision đã xuất bản và chuẩn hóa vị trí (`position`).
- **Request Body:**
```json
{
  "expectedVersion": 0,
  "data": {
    "lemma": "persevere",
    "language_code": "en",
    "senses": [
      {
        "id": "c2000000-0000-0000-0000-000000000002",
        "sense_no": 1,
        "part_of_speech": "VERB",
        "cefr_level": "B2",
        "meaning_vi": "kiên trì, bền chí",
        "examples": []
      }
    ]
  },
  "sourceIds": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"],
  "changeSummary": "Cập nhật nghĩa tiếng Việt"
}
```
- **Response:** `200 OK` (`AdminRevisionDto`).
- **Lỗi:**
  - `400 Bad Request`: Payload sai cấu trúc, trùng sense/example, hoặc chuyển quyền sở hữu nghĩa từ.
  - `404 Not Found`: Revision không tồn tại hoặc nguồn không tồn tại.
  - `409 Conflict`: `expectedVersion` bị cũ (stale version CAS) hoặc revision đang ở trạng thái không cho phép sửa (`IN_REVIEW`, `PUBLISHED`).

#### `POST /api/v1/admin/content/items/{id}/revisions`
- **Mô tả:** Khởi tạo bản thảo revision mới (`DRAFT`) từ bản đã xuất bản (`PUBLISHED`). Kế thừa toàn bộ snapshot và nguồn trích dẫn từ bản xuất bản, tăng `versionNo = max(versionNo) + 1`. Đảm bảo invariant mỗi nội dung chỉ có tối đa một revision mở.
- **Request Body:**
```json
{
  "baseRevisionId": "28000000-0000-0000-0000-000000000001",
  "expectedItemVersion": 1,
  "changeSummary": "Khởi tạo revision mới để chỉnh sửa nội dung"
}
```
- **Response:** `201 Created` (`AdminRevisionDto`).
- **Lỗi:**
  - `404 Not Found`: Không tìm thấy nội dung hoặc baseRevisionId.
  - `409 Conflict`: Nội dung đã có một revision mở chưa xuất bản, hoặc `expectedItemVersion` bị cũ (stale CAS).

#### `POST /api/v1/admin/content/revisions/{id}/validate`
- **Mô tả:** Xem trước và kiểm tra tính hợp lệ cấp xuất bản (publish-level validation) của revision mà hoàn toàn **không gây đột biến (read-only)**: không đổi trạng thái revision, không tăng version, không ghi log và không chiếu dữ liệu vào bảng nghiệp vụ.
- **Ràng buộc nguồn trích dẫn:**
  - Bản thảo phải liên kết ít nhất một nguồn trích dẫn (`sources`).
  - Mọi nguồn liên kết phải ở trạng thái `ACTIVE` và có khai báo quyền hợp lệ (`licenseCode` hoặc `permissionNote`).
  - Với nguồn ngoài (`sourceType = EXTERNAL`), bắt buộc phải có `attribution` không rỗng/null. Nếu thiếu, trả về lỗi tại trường `sources` với thông điệp `"Active source and declared permission required."`.
  - Với nguồn tự biên soạn (`sourceType = SELF_AUTHORED`), trường `attribution` là tùy chọn.
- **Request Body:** Không có.
- **Response:** `200 OK` (`AdminValidationResponse`):
```json
{
  "valid": true,
  "errors": []
}
```
Hoặc khi có lỗi:
```json
{
  "valid": false,
  "errors": [
    {
      "field": "sources",
      "message": "Published content needs a declared source."
    }
  ]
}
```

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
- **Ràng buộc nguồn trích dẫn:** Nguồn liên kết phải `ACTIVE` và có giấy phép/ghi chú quyền hợp lệ; nguồn ngoài (`EXTERNAL`) bắt buộc phải có `attribution`. Nếu thiếu quyền hoặc attribution của nguồn ngoài, trả về `400 Bad Request` ("Active source and declared permission required.") và rollback nguyên tử toàn bộ giao dịch (không chiếu dữ liệu, không gán publishedRevisionId, không ghi source_snapshot và không tạo audit log giả).
- **Response:** `200 OK` (`AdminRevisionDto`).

#### `POST /api/v1/admin/content/items/{id}/unpublish`
- **Mô tả:** Ngừng xuất bản nội dung đã từng phát hành (`is_archived = true`, `archived_at = now`). Phân biệt rõ với thao tác lưu trữ/thu hồi quyền (`archive`):
  - **Cờ archive (`is_archived`):** Đặt thành `true` để ngừng cấp mới. Item bị loại khỏi các view phân phối (`catalog_visible_items`, `catalog_visible_senses`, `catalog_visible_deck_senses`), chặn xuất hiện trong catalog học tập, danh sách chọn bài học, hoặc làm nguồn dependency mới.
  - **Cờ revoke (`access_revoked_at`):** Giữ nguyên (không ghi nhận và không xóa bỏ). Dữ liệu thẻ SRS và attempt trong quá khứ không bị sửa đổi. Phân biệt rõ: Practice attempt replay và loại trừ câu unpublish khỏi lượt cấp mới đã được đo lường trực tiếp qua test thực tế learner; hành vi đọc/trả lịch sử SRS chưa được đo trực tiếp (chỉ xác nhận dữ liệu snapshot không bị sửa). Không suy diễn rằng audio lịch sử được cấp khi `PublishedContentOperations` vẫn loại bỏ item lưu trữ.
  - **Tính bất biến:** Không thay đổi con trỏ `published_revision_id`, không xóa/sửa dữ liệu chiếu (`words`, `word_senses`, `decks`, `grammar_lessons`), không sửa snapshot revision đã xuất bản.
  - **Tính lũy thừa (Idempotency):** Gọi lặp lại nhiều lần không tạo thêm audit log và không gia hạn thời điểm `archived_at`.
  - **Ghi log kiểm toán:** Ghi audit log với hành động `CONTENT_UNPUBLISHED` trong cùng transaction.
  - **Bảo toàn thu hồi:** Nếu item đã bị `archive` (đã thu hồi quyền), việc unpublish không khôi phục quyền. Nếu gọi `unpublish` trước rồi gọi `archive`, `archive` vẫn thiết lập `access_revoked_at` để thu hồi quyền lịch sử.
- **Yêu cầu quyền:** `CONTENT_ADMIN` hoặc `SYSTEM_ADMIN`.
- **Request Body (tùy chọn):**
```json
{
  "reason": "Tạm dừng phát hành để rà soát chương trình"
}
```
- **Response:** `200 OK`
```json
{
  "message": "Nội dung đã được ngừng xuất bản."
}
```
- **Lỗi:**
  - `401 Unauthorized`: Chưa xác thực.
  - `403 Forbidden`: Người dùng vai trò `LEARNER` hoặc thiếu CSRF token.
  - `404 Not Found`: Không tìm thấy nội dung với `id`.
  - `409 Conflict`: Nội dung chưa từng được xuất bản (`publishedRevisionId` null, ví dụ bản thảo DRAFT/IN_REVIEW/APPROVED).
- **Phạm vi kiểm tra lịch sử & Khoảng trống (Gap):**
  - Đã kiểm tra thực tế learner: Lượt khởi tạo bài luyện tập mới (Practice new attempt) loại trừ câu đã unpublish; replay/submit/grade của attempt đã lưu trước unpublish hoạt động đúng từ snapshot; view `catalog_visible_items` loại bỏ item unpublish.
  - Khoảng trống (Gap):
    - Chưa đo lường trực tiếp luồng đọc/trả lịch sử thẻ SRS (chỉ mới xác nhận tính bất biến của dữ liệu bảng `word_reviews` và snapshot).
    - Chưa có luồng riêng cho historical media direct stream (audio/ảnh lịch sử khi `PublishedContentOperations` vẫn loại bỏ archived item) hoặc candidate seal cạnh tranh (sẽ được mở trong chặng media/SRS chuyên biệt).

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
- **Mô tả:** Thêm nguồn trích dẫn mới. Soạn thảo bản nháp (`DRAFT`) cho phép tạo hoặc liên kết nguồn chưa hoàn chỉnh attribution, nhưng khi xem trước (`validate`) hoặc xuất bản (`publish`), nguồn ngoài (`EXTERNAL`) bắt buộc phải có `attribution`.
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

#### `PUT /api/v1/admin/content/sources/{id}`
- **Mô tả:** Cập nhật thông tin nguồn trích dẫn hoặc thay đổi trạng thái nguồn (`ACTIVE` / `ARCHIVED`). Việc sửa nguồn trích dẫn không làm thay đổi các snapshot nguồn (`source_snapshot`) đã được lưu trữ bất biến trong các revision đã xuất bản trước đây.
- **Request Body:**
```json
{
  "title": "Oxford Advanced Learner's Dictionary 10th - Updated",
  "sourceType": "EXTERNAL",
  "sourceUrl": "https://www.oxfordlearnersdictionaries.com",
  "author": "Oxford University Press",
  "licenseCode": "EDITORIAL_CITATION",
  "licenseUrl": null,
  "attribution": "Trích dẫn định nghĩa và ví dụ học thuật",
  "permissionNote": "Đã gia hạn cấp phép",
  "status": "ACTIVE"
}
```
- **Response:** `200 OK` (`ContentSourceDto`).
- **Lỗi:**
  - `400 Bad Request`: Thiếu trường bắt buộc hoặc trường không hợp lệ (status không thuộc ACTIVE|ARCHIVED, sourceType không thuộc SELF_AUTHORED|EXTERNAL, sourceUrl/licenseUrl không phải HTTP/HTTPS URL hợp lệ có host hoặc độ dài vượt quá giới hạn).
  - `404 Not Found`: Nguồn không tồn tại.

---

### 2.4. Phân hệ Hệ thống & Chính sách (System & Policy)
*Yêu cầu quyền:* `SYSTEM_ADMIN`

#### `GET /api/v1/admin/system/feature-flags`
- **Mô tả:** Lấy danh sách toàn bộ cờ tính năng hệ thống (chỉ xem; trạng thái lưu hiện chưa phản ánh hoạt động thực tế hoặc điều khiển runtime).
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
- **Mô tả:** Thao tác cập nhật trạng thái cờ tính năng hiện bị từ chối; hệ thống chỉ cho phép xem danh mục cờ và chưa hỗ trợ điều khiển tính năng runtime. Mọi yêu cầu PUT với key đã biết đều bị chặn với mã `409 Conflict`, hoàn toàn không mutation vào database và không ghi audit log.
- **Request Body:**
```json
{
  "enabled": false
}
```
- **Response:**
  - `409 Conflict` (`ProblemDetail`): Key cờ tồn tại nhưng thao tác cập nhật bị từ chối. Không thay đổi DB và không ghi audit log (`detail`: `"Chưa hỗ trợ điều khiển tính năng; trạng thái lưu chưa phản ánh hoạt động thực tế"`).
  - `404 Not Found` (`ProblemDetail`): Không tìm thấy key cờ tính năng (`detail`: `"Feature flag not found: ..."`).

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
- **Mô tả:** Thống kê hạn mức token AI trong ngày. Hiện chưa có nguồn đo thực tế nên trả về danh sách rỗng `[]` (không trả về hạn ngạch hoặc token sử dụng giả định).
- **Response:** `200 OK`
```json
[]
```

#### `GET /api/v1/admin/system/audit-logs`
- **Mô tả:** Tra cứu nhật ký kiểm toán hệ thống có phân trang và bộ lọc theo loại thực thể thực tế.
- **Query params:**
  - `search`: `string` (hành động, lý do).
  - `entityType`: `"ALL" | "USER" | "WORD" | "DECK" | "CONTENT_REVISION" | "FEATURE_FLAG"` (chỉ hỗ trợ các giá trị thực tế này; bỏ trống hoặc truyền `"ALL"` để lấy toàn bộ. Các thao tác phân quyền `ROLE` và phiên làm việc `SESSION` được lưu trữ dưới `USER`).
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

### 2.5. Phân hệ Quản trị Tệp Media & Quota (Media Ops)
*Yêu cầu quyền:* `CONTENT_ADMIN` (không phải `SYSTEM_ADMIN`)

#### `POST /api/v1/admin/media/upload-requests`
- **Mô tả:** Khởi tạo yêu cầu tải lên tệp media (hình ảnh, âm thanh) và giữ chỗ quota (`RESERVED`) trong bảng ledger `storage_allocations`.
- **Request Body:**
```json
{
  "fileName": "stimulus-diagram.png",
  "mimeType": "image/png",
  "declaredSizeBytes": 204800,
  "declaredDimensionBytes": 204800,
  "sourceId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```
- **Response:** `201 Created`
```json
{
  "uploadRequestId": "8fa85f64-5717-4562-b3fc-2c963f66afa1",
  "mediaId": "9fa85f64-5717-4562-b3fc-2c963f66afa2",
  "uploadUrl": "https://storage.masterenglish.com/temp/8fa85f64-5717-4562-b3fc-2c963f66afa1",
  "expiresAt": "2026-11-24T15:30:00Z",
  "maxSizeBytes": 5242880
}
```
- **Lỗi:**
  - `413 Payload Too Large`: Dung lượng vượt trần quota hoặc kích thước tối đa cho loại file (ảnh 5 MiB, âm thanh 20 MiB).
  - `400 Bad Request`: Định dạng MIME không được hỗ trợ (chỉ chấp nhận `image/jpeg`, `image/png`, `image/webp`, `audio/mpeg`, `audio/mp4`).

#### `PUT /api/v1/admin/media/upload-requests/{id}/bytes`
- **Mô tả:** Tiếp nhận nội dung nhị phân tải lên vào vùng nhớ đệm staging, ghi nhận biên nhận byte và SHA-256.
- **Request Body:** Binary stream.
- **Headers:** `Content-Type: <mime-type>`
- **Response:** `200 OK`
```json
{
  "uploadRequestId": "8fa85f64-5717-4562-b3fc-2c963f66afa1",
  "mediaId": "9fa85f64-5717-4562-b3fc-2c963f66afa2",
  "sizeBytes": 204800,
  "sha256Checksum": "556ec8450ed7e439aa4beabee80176cbf675348a4efb8a45640bc77af943bf3c",
  "status": "PENDING"
}
```

#### `POST /api/v1/admin/media/upload-requests/{id}/complete`
- **Mô tả:** Xác minh bytes thực qua `FfmpegMediaDecoder`, đọc lại bản cuối để kiểm kích thước và SHA-256, rồi commit media `READY` cùng allocation CONTENT `COMMITTED`. Complete không xóa tệp tạm và không giải phóng allocation TEMP. Token nội bộ `claimToken` không xuất hiện ở response.
- **Query params:** `sync=true|false` (mặc định: `true`).
- **Response:** `200 OK` (hoặc `202 Accepted` khi `sync=false` và đang ở trạng thái `VERIFYING`)
```json
{
  "uploadRequestId": "8fa85f64-5717-4562-b3fc-2c963f66afa1",
  "mediaId": "9fa85f64-5717-4562-b3fc-2c963f66afa2",
  "status": "COMPLETED",
  "verificationDeadlineAt": "2026-11-24T15:35:00Z",
  "leaseUntil": null,
  "leaseAcquired": false,
  "stagedSizeBytes": 204800,
  "stagedSha256": "556ec8450ed7e439aa4beabee80176cbf675348a4efb8a45640bc77af943bf3c"
}
```
- **Lỗi:**
  - `400 Bad Request`: File rỗng, hỏng hoặc không khớp định dạng khai báo. Nếu một lần xác minh trước đã ghi nhận đích PUT final, giữ `VERIFYING` và quota để cho phép retry trong deadline thay vì giải phóng quota khi bản cuối có thể vẫn tồn tại.
  - `404 Not Found`: Không tìm thấy yêu cầu tải lên.
  - `409 Conflict`: Lease đang được giữ bởi worker khác, yêu cầu đã hết hạn hoặc cả hai slot xác minh vật lý đều đang bận.
- **Cleanup staging bất định:** Grant có PUT claim nhưng chưa ghi nhận staged receipt vẫn giữ quota TEMP, kể cả đã hết hạn và qua 24 giờ. Không lấy thời gian retention hoặc object vừa absent làm bằng chứng PUT đã dừng; cần reconcile có bằng chứng dừng/cô lập writer trước thu hồi dung lượng.
- **Giới hạn worker và crash:** Hai slot PostgreSQL dùng chung giữa các instance. Lease hết hạn hoặc mất kết nối không tự nhả slot vật lý. Worker chỉ release token của mình sau khi decode/storage kết thúc. JVM crash có thể giữ slot, làm giảm công suất hoặc chặn xác minh mới; hiện không có API hay cơ chế tự thu hồi crash-held slot. Không xóa token theo TTL. Trước can thiệp vận hành phải chứng minh worker/process con và I/O liên quan đã dừng hoặc bị cô lập, rồi đối chiếu object/ledger; restart JVM riêng lẻ không đủ bằng chứng.

#### `GET /api/v1/admin/media/upload-requests/{id}`
- **Mô tả:** Tra cứu tiến độ và trạng thái hiện tại của yêu cầu tải lên media.
- **Response:** `200 OK` (`UploadStatusView`).

#### `GET /api/v1/admin/media/assets/{id}`
- **Mô tả:** Đọc chi tiết media asset kèm thông số kỹ thuật (chiều rộng, chiều cao, thời lượng phát, dung lượng, sha256 checksum) và trạng thái `PENDING`/`READY`/`FAILED`/`ARCHIVED`.
- **Response:** `200 OK` (`MediaAssetView`).

#### `POST /api/v1/admin/media/assets/{id}/archive`
- **Mô tả:** Đánh dấu media asset là `ARCHIVED`. Chỉ asset `READY` không còn được tham chiếu bởi nội dung đã xuất bản mới được archive.
- **Response:** `200 OK` (`MediaAssetView`).

---

### 2.6. Phân hệ Nhập Liệu Hàng Loạt (Bulk Content Import)
*Yêu cầu quyền:* `CONTENT_ADMIN` (chỉ `CONTENT_ADMIN`; `SYSTEM_ADMIN` không có quyền)`

#### `POST /api/v1/admin/import/jobs`
- **Mô tả:** Tải lên tệp CSV từ vựng (`VOCABULARY`) hoặc JSON ngân hàng câu hỏi (`QUESTION_BANK`), tự động kiểm tra cú pháp, giới hạn kích thước (tối đa 5 MiB, 5.000 dòng), nguồn trích dẫn và chạy DryRun ghi nhận từng dòng hợp lệ (`VALID`) hay không hợp lệ (`INVALID`). Giữ chỗ quota lưu trữ `RESERVED` trong suốt vòng đời import. Hỗ trợ cơ chế idempotency qua `requestId`: gửi lại cùng file và `requestId` trả về `200 OK` kèm thông tin job sẵn có; gửi khác file trả về `409 Conflict`.
- **Form-data params:**
  - `file`: `MultipartFile` (CSV UTF-8 hoặc JSON `schema_version: 1`).
  - `module`: `"VOCABULARY"` hoặc `"QUESTION_BANK"`.
  - `requestId`: `UUID` (Idempotency key).
  - `namespace`: `string` (Không gian định danh nguồn dữ liệu).
- **Response:** `201 Created` (hoặc `200 OK` khi replay idempotent)
```json
{
  "id": "9fa85f64-5717-4562-b3fc-2c963f66afa1",
  "createdBy": "1fa85f64-5717-4562-b3fc-2c963f66afa1",
  "requestId": "2fa85f64-5717-4562-b3fc-2c963f66afa1",
  "status": "READY",
  "importType": "QUESTIONS_JSON",
  "importNamespace": "ets-prep",
  "originalFilename": "questions.json",
  "totalRows": 100,
  "successRows": 0,
  "failedRows": 2,
  "skippedRows": 0,
  "createdAt": "2026-11-24T15:00:00Z"
}
```
- **Lỗi:**
  - `413 Payload Too Large`: Tệp vượt quá 5 MiB hoặc vượt quá 5.000 phần tử logic.
  - `400 Bad Request`: Tệp không hợp lệ cú pháp, thiếu header CSV bắt buộc, sai `schema_version` hoặc độ sâu JSON vượt quá 10.
  - `409 Conflict`: `requestId` đã được sử dụng với file có nội dung SHA-256 khác.
  - `503 Service Unavailable`: Chưa xác minh được kích thước và SHA-256 của input đọc lại từ kho. Job vẫn `UPLOADING`, quota vẫn `RESERVED`. Có thể gửi lại cùng `requestId` và input trong hạn upload.

#### `GET /api/v1/admin/import/jobs/{id}`
- **Mô tả:** Xem trạng thái, tiến độ và bộ đếm thành công/thất bại của Import Job. Chỉ người tạo job mới có quyền truy cập (bảo mật riêng tư chéo chủ sở hữu, trả về `404 Not Found` nếu người khác truy cập).
- **Response:** `200 OK` (`ImportJobView`).

#### `GET /api/v1/admin/import/jobs/{id}/rows`
- **Mô tả:** Tra cứu danh sách chi tiết từng dòng trong Import Job có phân trang, hỗ trợ lọc theo trạng thái dòng.
- **Query params:**
  - `status`: `"VALID" | "INVALID" | "SUCCESS" | "FAILED" | "SKIPPED"` (tùy chọn).
  - `page`, `size`: `integer`.
- **Response:** `200 OK` (`PageResponse<ImportJobRowView>`).

#### `POST /api/v1/admin/import/jobs/{id}/confirm`
- **Mô tả:** Xác nhận thực thi nhập liệu: nhận quyền xử lý độc quyền, duyệt qua từng dòng `VALID` và tạo `ContentItem` kèm revision `DRAFT` (tuyệt đối không tự động submit hay publish).
  - Đối với `VOCABULARY`: tạo các mục từ vựng `WORD`.
  - Đối với `QUESTION_BANK`: tạo câu hỏi độc lập `QUESTION` hoặc nhóm câu hỏi `QUESTION_GROUP` (tự động tạo atomic toàn bộ inline children và nhóm; nếu bất kỳ câu con nào lỗi thì rollback toàn bộ nhóm và các câu con, ghi nhận row `FAILED`).
  - Confirm không xóa tệp đầu vào và không giải phóng quota tạm. Chỉ được giải phóng sau khi xác nhận tệp đã được xóa.
- **Response:** `200 OK`
```json
{
  "jobId": "9fa85f64-5717-4562-b3fc-2c963f66afa1",
  "status": "COMPLETED",
  "totalRows": 100,
  "successRows": 98,
  "failedRows": 2,
  "skippedRows": 0
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
- `CONFLICT` (`409`): Sai trạng thái vòng đời (ví dụ: cố publish khi chưa approve, hoặc submit khi không phải DRAFT), hoặc thao tác cập nhật cờ tính năng chưa được hỗ trợ.
- `VALIDATION_ERROR` (`400`): Dữ liệu đầu vào sai quy chuẩn.
