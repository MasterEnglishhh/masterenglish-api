# Hợp đồng API và Dữ liệu: Vocabulary & Flashcard SRS

Tài liệu đặc tả hợp đồng kỹ thuật cho tính năng Học từ vựng và Thẻ ghi nhớ SRS (Spaced Repetition System) - Slice M03.  
Ngày ban hành: 05/11/2026.

Cập nhật sau review: xem [bản sửa và cách chạy](FIXES_2026-11-05.md).
V7 bảo vệ lịch sử/bản Published; V6 giữ nguyên checksum. Null progress version
chỉ dùng cho nghĩa chưa học; review trước hạn bị từ chối. Replay dùng after-state
bất biến và version đã ghi trong event, không dùng state hiện tại. Với event V6
cũ thiếu version, trả conflict yêu cầu tải lại tiến độ, không suy đoán kết quả.

---

## 1. Mục tiêu và Giới hạn triển khai

### 1.1. Mục tiêu
Cung cấp luồng học từ vựng và thẻ ghi nhớ hoàn chỉnh trên cơ sở dữ liệu thật:
1. Đăng nhập với quyền `LEARNER`.
2. Duyệt danh sách từ vựng và bộ thẻ (Deck).
3. Xem chi tiết từ vựng theo từng nghĩa (Sense), phiên âm, ví dụ.
4. Lật thẻ flashcard, tự đánh giá mức độ nhớ theo 3 nút: **QUÊN (AGAIN)**, **KHÓ (HARD)**, **ĐÃ NHỚ (GOOD)**.
5. Ghi nhận kết quả ôn tập nguyên tử và bất biến (Append-only review log), cập nhật tiến độ SRS chuẩn `SM2_3BTN_V1`.
6. Lưu và bỏ lưu từ vựng vào danh sách cá nhân (`user_saved_words`).
7. Lấy danh sách các thẻ đến hạn ôn (`/vocabulary/reviews/due`).
8. Bảo toàn tiến độ học khi reload trang hoặc đăng nhập lại.

### 1.2. Giới hạn (Out of Scope của slice hiện tại)
- Chưa làm Practice Engine TOEIC/Vocabulary Test (M03.12–M03.16).
- Đã có phát âm MP3 tạo local bằng Piper, lưu R2 và gắn vào đúng word revision qua V8;
  xem [hướng dẫn phát âm](PRONUNCIATION.md). Chưa có màn hình upload audio quản trị
  hoặc audio cho câu ví dụ; trường `audio_media_id` trong snapshot vẫn để dành cho
  luồng biên tập media đầy đủ.
- Chưa có CMS UI quản trị nội dung; nội dung được nạp qua content authoring/seed service chuẩn.
- Chưa làm thống kê toàn hệ thống (Progress dashboard aggregate M03.12, Study sessions, Heartbeat).

---

## 2. Xử lý các điểm lệch tài liệu (Discrepancy Resolution)

| Vấn đề | Tài liệu cũ (SRS/SDD v5.0) | Tài liệu chuẩn (Approved Baseline & API Catalog) | Quyết định triển khai (Binding) |
|---|---|---|---|
| **Số nút SRS và khoảng cách AGAIN** | 4 nút (AGAIN, HARD, GOOD, EASY); AGAIN = 1 ngày. | 3 nút (AGAIN, HARD, GOOD); AGAIN = 10 phút. | **SM2_3BTN_V1**: 3 nút, AGAIN đưa `due_at = reviewed_at + 10 phút`, không có nút EASY. |
| **Đường dẫn API Review** | `/api/v1/learning/words/...` | `/api/v1/vocabulary/reviews` và `/reviews/due` | Dùng chuẩn **API Catalog**: `/api/v1/vocabulary/reviews` và `/api/v1/vocabulary/reviews/due`. Trách nhiệm xử lý logic thuộc module `learning`. |
| **Tổ chức chủ đề từ vựng** | Có nhắc đến topic độc lập. | Không có bảng `vocabulary_topics` riêng; tổ chức qua `decks` (title, description, level). | Dùng **Deck** làm đơn vị phân loại/tập hợp từ vựng; không tự thêm taxonomy bảng topic. |
| **Nguồn dữ liệu Migration** | SQL tham chiếu 62 bảng (`approved-baseline.sql`). | Migration đang ở V1–V5. | Viết migration nâng cấp `V6__...` tương thích cơ sở dữ liệu hiện có, không chạy đè file tham chiếu. |
| **Nội dung thẻ trong Deck** | Suy đoán từ từ vựng mới nhất. | Deck ghim `pinned_word_revision_id` cụ thể của từ. | **Ghim cứng revision**: Deck hiển thị thẻ dựa trên `pinned_word_revision_id` tại thời điểm Deck được xuất bản; không tự đổi thẻ khi từ có bản mới chưa xuất bản vào deck. |

---

## 3. Ánh xạ Yêu cầu M03 và Danh mục API

| Mã Yêu cầu | Mã API Catalog | Method & Path | Chức năng | Trạng thái slice |
|---|---|---|---|---|
| M03.01 | M03-API-003 | `GET /api/v1/vocabulary/decks` | Danh sách bộ từ vựng (Decks) | Hoàn thành |
| M03.02 | M03-API-001 | `GET /api/v1/vocabulary/words` | Tìm kiếm / lọc từ vựng | Hoàn thành |
| M03.03 | M03-API-002 | `GET /api/v1/vocabulary/words/{wordId}` | Chi tiết từ vựng theo nghĩa/ví dụ | Hoàn thành |
| M03.04 | M03-API-005 | `GET /api/v1/vocabulary/decks/{deckId}/words` | Lấy danh sách thẻ Flashcard của Deck | Hoàn thành |
| M03.05 | M03-API-010 | `POST /api/v1/vocabulary/reviews` | Đánh giá thẻ ghi nhớ (AGAIN/HARD/GOOD) | Hoàn thành |
| M03.06 | Client-side | N/A | Tổng hợp kết quả phiên học từ các review thành công | Hoàn thành |
| M03.07 | M03-API-010 | N/A (Internal SRS Engine) | Tính hạn ôn tiếp theo (`SM2_3BTN_V1`) | Hoàn thành |
| M03.08 | M03-API-009 | `GET /api/v1/vocabulary/reviews/due` | Danh sách các thẻ đến hạn ôn | Hoàn thành |
| M03.10 | M03-API-007 | `PUT /api/v1/vocabulary/saved-words/{senseId}` | Lưu nghĩa từ vào sổ tay cá nhân | Hoàn thành |
| M03.11 | M03-API-008 | `DELETE /api/v1/vocabulary/saved-words/{senseId}` | Bỏ lưu nghĩa từ khỏi sổ tay | Hoàn thành |
| M03.09 | M03-API-011, 012 | `/vocabulary/reviews/history`, `/vocabulary/progress` | Lịch sử chi tiết & dashboard tổng hợp | P1 (Giai đoạn sau) |

---

## 4. Kiến trúc và Ranh giới Phân hệ (Spring Modulith Boundaries)

Hệ thống tuân thủ nghiêm ngặt mô hình Modular Monolith:
```
           ┌────────────────┐
           │    identity    │
           └───────┬────────┘
                   │ (named-interface "user-lock")
                   ▼
┌──────────────┐◄──┴───────────┐
│  vocabulary  │    learning   │
└──────┬───────┘───────────────┘
       │ (public API)
       ▼
┌──────────────┐
│   content    │
└──────────────┘
```

### 4.1. Phân hệ `content`
- **Trách nhiệm**: Quản lý định danh nội dung (`content_items`), các phiên bản soạn thảo/duyệt (`content_revisions`), bản quyền/nguồn (`content_sources`, `content_source_links`), phụ thuộc giữa các phiên bản (`revision_dependencies`), tham chiếu media (`revision_media_refs`) và nhật ký kiểm toán (`audit_logs`).
- **Nguyên tắc bất biến**: Bản ghi `PUBLISHED` là bất biến. Mọi thay đổi phải tạo `content_revisions` mới (DRAFT).
- **Phụ thuộc**: Độc lập với `vocabulary` và `learning`. Không import repository hay entity của phân hệ khác.

### 4.2. Phân hệ `vocabulary`
- **Trách nhiệm**: Cung cấp mô hình dữ liệu chiếu (Projection) cho từ vựng và bộ thẻ: `words`, `word_senses`, `word_sense_examples`, `decks`, `deck_words`.
- **Dịch vụ công khai**:
  - `VocabularyQueryService`: Tra cứu catalog từ vựng, chi tiết từ, danh sách deck, chi tiết deck, và thẻ thuộc deck.
  - `VocabularyProjectionService`: Được gọi khi `content` xuất bản phiên bản WORD hoặc DECK để đồng bộ dữ liệu vào bảng chiếu trong cùng transaction.
- **Phụ thuộc**: Gọi API công khai của `content` và dùng các khối dùng chung (`common`).

### 4.3. Phân hệ `learning`
- **Trách nhiệm**: Quản lý trạng thái cá nhân người học: từ đã lưu (`user_saved_words`), tiến độ SRS theo nghĩa từ (`user_word_progress`), lịch sử ôn tập bất biến (`word_reviews`), thuật toán tính toán SRS (`SM2_3BTN_V1`), và hàng đợi thẻ đến hạn (`due queue`).
- **Bộ điều khiển (Controllers)**: Phục vụ các endpoint:
  - `PUT /api/v1/vocabulary/saved-words/{senseId}`
  - `DELETE /api/v1/vocabulary/saved-words/{senseId}`
  - `GET /api/v1/vocabulary/saved-words`
  - `GET /api/v1/vocabulary/reviews/due`
  - `POST /api/v1/vocabulary/reviews`
- **Phụ thuộc**: Gọi `VocabularyQueryService` để kiểm tra tính hợp lệ của nghĩa từ/deck, gọi `UserLockService` của phân hệ `identity` để khóa user khi tạo tiến trình mới một cách an toàn. Tuyệt đối không để `vocabulary` phụ thuộc ngược vào `learning`.

---

## 5. Đặc tả Chi tiết các Endpoint API

### 5.1. Quy ước chung
- **Prefix**: `/api/v1`
- **Xác thực**: Yêu cầu Cookie `access_token` hợp lệ và vai trò `LEARNER` (hoặc `SYSTEM_ADMIN` có gán `LEARNER`). Không nhận `userId` từ query params hoặc request body.
- **CSRF**: Các request thay đổi trạng thái (`POST`, `PUT`, `DELETE`) bắt buộc có header `X-XSRF-TOKEN` hoặc header tương ứng lấy từ `/api/v1/auth/csrf`.
- **Phân trang**: Mặc định `page=0`, `size=20`, tối đa `size=100`. Phản hồi dạng `PageResponse<T>`.

---

### 5.2. Danh sách Từ vựng
- **Method & Path**: `GET /api/v1/vocabulary/words`
- **Query Parameters**:
  - `q`: chuỗi tìm kiếm theo tiền tố hoặc lemma (tùy chọn, tối đa 100 ký tự).
  - `cefrLevel`: `A1`, `A2`, `B1`, `B2`, `C1`, `C2` (tùy chọn).
  - `partOfSpeech`: loại từ `NOUN`, `VERB`, `ADJECTIVE`, `ADVERB`, ... (tùy chọn).
  - `page`: int >= 0 (mặc định 0).
  - `size`: int từ 1 đến 100 (mặc định 20).
  - `sort`: `lemma,asc` (mặc định), `lemma,desc`, `createdAt,desc`.
- **Logic lọc**:
  - Chỉ trả về các từ có bản ghi `content_items` đang `PUBLISHED` và chưa bị lưu trữ/thu hồi (`is_archived = false`, `access_revoked_at IS NULL`).
  - Lọc `cefrLevel` và `partOfSpeech` áp dụng trên **cùng một nghĩa** (`word_senses`) để tránh ghép sai nghĩa. Khi một từ có nhiều nghĩa cùng khớp, kết quả từ vựng không bị nhân bản (Deduplication).
- **Response 200 OK**:
```json
{
  "items": [
    {
      "id": "c1f7601b-9f9b-449e-b9b5-6a5cf808f972",
      "lemma": "approach",
      "languageCode": "en",
      "ipa": "/əˈprəʊtʃ/",
      "audioUrl": null,
      "senses": [
        {
          "id": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
          "senseNo": 1,
          "partOfSpeech": "NOUN",
          "cefrLevel": "B1",
          "meaningVi": "cách tiếp cận, phương pháp",
          "definitionEn": "a way of dealing with somebody/something"
        }
      ]
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

### 5.3. Chi tiết Từ vựng
- **Method & Path**: `GET /api/v1/vocabulary/words/{wordId}`
- **Path Parameter**: `wordId` (UUID hợp lệ).
- **Response 200 OK**:
```json
{
  "id": "c1f7601b-9f9b-449e-b9b5-6a5cf808f972",
  "lemma": "approach",
  "languageCode": "en",
  "ipa": "/əˈprəʊtʃ/",
  "audioUrl": null,
  "senses": [
    {
      "id": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
      "senseNo": 1,
      "partOfSpeech": "NOUN",
      "cefrLevel": "B1",
      "meaningVi": "cách tiếp cận, phương pháp",
      "definitionEn": "a way of dealing with somebody/something",
      "examples": [
        {
          "id": "f5195b05-5928-4ef7-bca8-22fca5665851",
          "position": 1,
          "exampleEn": "We need a fresh approach to this issue.",
          "exampleVi": "Chúng ta cần một cách tiếp cận mới cho vấn đề này."
        }
      ]
    }
  ]
}
```
- **Lỗi**:
  - `404 Not Found`: Từ không tồn tại hoặc không ở trạng thái Published hợp lệ.

---

### 5.4. Danh sách Bộ thẻ (Decks)
- **Method & Path**: `GET /api/v1/vocabulary/decks`
- **Query Parameters**:
  - `q`: tìm kiếm theo tiêu đề (tùy chọn).
  - `level`: `A1`..`C2` (tùy chọn).
  - `page`, `size`: phân trang.
- **Response 200 OK**:
```json
{
  "items": [
    {
      "id": "7a26f34e-0a06-4443-85b4-d73aa731215b",
      "title": "Business Communication Essentials",
      "description": "Từ vựng giao tiếp thương mại cơ bản trong công sở.",
      "level": "B1",
      "cardCount": 12,
      "createdAt": "2026-11-05T00:00:00Z"
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

### 5.5. Chi tiết Bộ thẻ (Deck Detail)
- **Method & Path**: `GET /api/v1/vocabulary/decks/{deckId}`
- **Path Parameter**: `deckId` (UUID).
- **Response 200 OK**:
```json
{
  "id": "7a26f34e-0a06-4443-85b4-d73aa731215b",
  "title": "Business Communication Essentials",
  "description": "Từ vựng giao tiếp thương mại cơ bản trong công sở.",
  "level": "B1",
  "cardCount": 12,
  "publishedRevisionId": "09ecbe21-d779-45e0-82a8-062e21e0655e",
  "createdAt": "2026-11-05T00:00:00Z"
}
```

---

### 5.6. Danh sách Thẻ của Bộ thẻ (Deck Cards)
- **Method & Path**: `GET /api/v1/vocabulary/decks/{deckId}/words`
- **Path Parameter**: `deckId` (UUID).
- **Query Parameters**: `page`, `size`, `cefrLevel`, `partOfSpeech`.
- **Nguyên tắc quan trọng**: `deck_words` là projection membership; `pinned_word_revision_id` nằm trong snapshot DECK và được ghi ở revision_dependencies. Thẻ render từ snapshot WORD đã ghim. DTO trả wordId/wordRevisionId/wordSenseId; client ánh xạ sang sourceWordId/sourceRevisionId khi review, kèm deckId/deckRevisionId.
- **Response 200 OK**:
```json
{
  "items": [
    {
      "deckId": "7a26f34e-0a06-4443-85b4-d73aa731215b",
      "deckRevisionId": "09ecbe21-d779-45e0-82a8-062e21e0655e",
      "position": 1,
      "wordSenseId": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
      "wordId": "c1f7601b-9f9b-449e-b9b5-6a5cf808f972",
      "wordRevisionId": "b16f6b02-e221-4d37-97d5-d0ff0c2b2a61",
      "lemma": "approach",
      "ipa": "/əˈprəʊtʃ/",
      "partOfSpeech": "NOUN",
      "cefrLevel": "B1",
      "meaningVi": "cách tiếp cận, phương pháp",
      "definitionEn": "a way of dealing with somebody/something",
      "examples": [
        {
          "id": "f5195b05-5928-4ef7-bca8-22fca5665851",
          "position": 1,
          "exampleEn": "We need a fresh approach to this issue.",
          "exampleVi": "Chúng ta cần một cách tiếp cận mới cho vấn đề này."
        }
      ],
      "audioUrl": null,
      "isSaved": false,
      "progress": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1,
  "hasNext": false
}
```
*Ghi chú: Trường `progress` chứa `{ repetitions: int, intervalDays: int, easeFactor: number, dueAt: string, version: int }` nếu người dùng đã có tiến độ, hoặc `null` nếu chưa học.*

---

### 5.7. Lưu và Bỏ lưu Nghĩa từ (Saved Words)
- **Lưu**: `PUT /api/v1/vocabulary/saved-words/{senseId}`
  - Response: `204 No Content`
  - Idempotent: Nếu đã lưu từ trước, trả về 204 mà không phát sinh lỗi.
- **Bỏ lưu**: `DELETE /api/v1/vocabulary/saved-words/{senseId}`
  - Response: `204 No Content`
  - Idempotent: Nếu chưa từng lưu hoặc đã bỏ lưu, trả về 204.
  - **Bảo toàn SRS**: Tuyệt đối không xóa hay thay đổi bản ghi `user_word_progress` và `word_reviews`.
- **Danh sách từ đã lưu**: `GET /api/v1/vocabulary/saved-words`
  - Query: `page`, `size`, `cefrLevel`.
  - Response 200: `PageResponse<SavedWordDto>` chứa thông tin nghĩa, từ gốc và thời điểm `savedAt`.

---

### 5.8. Danh sách Thẻ đến hạn ôn (Due Queue)
- **Method & Path**: `GET /api/v1/vocabulary/reviews/due`
- **Query Parameters**:
  - `deckId`: UUID (tùy chọn; nếu truyền thì chỉ lấy các nghĩa thuộc deck này mà người dùng đến hạn ôn).
  - `limit`: int từ 1 đến 100 (mặc định 20).
- **Tiêu chuẩn đến hạn**:
  - Bản ghi `user_word_progress` của chính user có `due_at <= server_now`.
  - Nghĩa từ chưa từng học (chưa có tiến độ) **không tự biến thành thẻ đến hạn**.
- **Response 200 OK**:
```json
{
  "totalDue": 5,
  "cards": [
    {
      "wordSenseId": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
      "wordId": "c1f7601b-9f9b-449e-b9b5-6a5cf808f972",
      "wordRevisionId": "b16f6b02-e221-4d37-97d5-d0ff0c2b2a61",
      "lemma": "approach",
      "ipa": "/əˈprəʊtʃ/",
      "partOfSpeech": "NOUN",
      "cefrLevel": "B1",
      "meaningVi": "cách tiếp cận, phương pháp",
      "definitionEn": "a way of dealing with somebody/something",
      "examples": [
        {
          "id": "f5195b05-5928-4ef7-bca8-22fca5665851",
          "position": 1,
          "exampleEn": "We need a fresh approach to this issue.",
          "exampleVi": "Chúng ta cần một cách tiếp cận mới cho vấn đề này."
        }
      ],
      "dueAt": "2026-11-04T15:30:00Z",
      "repetitions": 1,
      "intervalDays": 1,
      "easeFactor": 2.500,
      "version": 1
    }
  ]
}
```

---

### 5.9. Ghi nhận Đánh giá Thẻ (Review Transaction)
- **Method & Path**: `POST /api/v1/vocabulary/reviews`
- **Headers bắt buộc**: `Content-Type: application/json`, `X-XSRF-TOKEN: ...`
- **Request Body**:
```json
{
  "wordSenseId": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
  "sourceWordId": "c1f7601b-9f9b-449e-b9b5-6a5cf808f972",
  "sourceRevisionId": "b16f6b02-e221-4d37-97d5-d0ff0c2b2a61",
  "deckId": "7a26f34e-0a06-4443-85b4-d73aa731215b",
  "deckRevisionId": "09ecbe21-d779-45e0-82a8-062e21e0655e",
  "rating": "GOOD",
  "reviewRequestId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "expectedProgressVersion": null
}
```
*Lưu ý*: `expectedProgressVersion` là `null` khi học lần đầu, hoặc bằng số `version` hiện tại của `user_word_progress`.

- **Quy trình xử lý giao dịch Server-side**:
  1. **Xác thực User & Khóa User**: Xác nhận `AuthenticatedUser`. Khóa người dùng để chống race condition khi tạo tiến trình mới.
  2. **Kiểm tra Idempotency trước**: Tra cứu `word_reviews` theo `(user_id, reviewRequestId)`.
     - Nếu đã tồn tại bản ghi với cùng `request_hash`: Trả về kết quả đã lưu trước đó (Status `200 OK` hoặc `201 Created`), không tính lại SRS và không đổi `reviewed_at`.
     - Nếu đã tồn tại nhưng khác `request_hash`: Báo lỗi `409 Conflict` (Duplicate request ID with different payload).
  3. **Khóa bản ghi tiến độ (`user_word_progress`)**:
     - Khóa theo `(user_id, word_sense_id)` bằng pessimistic/optimistic lock.
     - So khớp `expectedProgressVersion`: Nếu không khớp, trả về `409 Conflict`.
  4. **Kiểm tra tính hợp lệ của ngữ cảnh**:
     - Nghĩa từ phải tồn tại, thuộc đúng `sourceWordId` và `sourceRevisionId`.
     - Nếu có `deckId` / `deckRevisionId`, nghĩa từ phải là thành viên hợp lệ trong snapshot của deck.
  5. **Tính toán thuật toán SM2_3BTN_V1**:
     - Lấy server clock hiện tại (`server_now`).
     - Xác định múi giờ người dùng (IANA timezone, mặc định `UTC` hoặc từ user profile) và tính `local_day`.
     - Tính toán `repetitions`, `interval_days`, `ease_factor`, `next_due_at`.
  6. **Ghi nhận nguyên tử (Atomic commit)**:
     - Lưu sự kiện vào `word_reviews` (chứa `before_state`, `after_state`, `rating`, `policy_version`, `local_day`, `timezone`).
     - Cập nhật/tạo mới `user_word_progress` (`version` tăng 1).

- **Response 201 Created**:
```json
{
  "reviewId": "f2d87e01-8395-46aa-b2b9-e478dc44f478",
  "wordSenseId": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
  "rating": "GOOD",
  "intervalDays": 1,
  "easeFactor": 2.600,
  "repetitions": 1,
  "nextDueAt": "2026-11-06T00:00:00Z",
  "progressVersion": 1
}
```

---

## 6. Đặc tả Thuật toán SM2_3BTN_V1

### 6.1. Tham số khởi tạo (Khi chưa có tiến độ)
- `repetitions = 0`
- `interval_days = 0`
- `ease_factor = 2.500`
- `policy_version = "SM2_3BTN_V1"`

### 6.2. Ánh xạ Đánh giá (Rating -> Quality `q`)
| Nút hiển thị | Mã Rating | Quality `q` | Ý nghĩa |
|---|---|---|---|
| **QUÊN** | `AGAIN` | 1 | Không nhớ được nghĩa hoặc sai hoàn toàn |
| **KHÓ** | `HARD` | 3 | Nhớ được nhưng rất khó khăn, ngập ngừng |
| **ĐÃ NHỚ** | `GOOD` | 5 | Nhớ tốt, phản xạ nhanh và chính xác |

### 6.3. Công thức cập nhật Ease Factor (EF)
$$\Delta EF = 0.1 - (5 - q) \times (0.08 + (5 - q) \times 0.02)$$
$$EF_{new} = \max\left(1.300, \text{round}(EF_{old} + \Delta EF, 3)\right)$$

Cụ thể:
- Với `GOOD` ($q=5$): $\Delta EF = +0.100$. $EF$ tăng $0.1$.
- Với `HARD` ($q=3$): $\Delta EF = 0.1 - 2 \times (0.08 + 0.04) = 0.1 - 0.24 = -0.140$. $EF$ giảm $0.14$.
- Với `AGAIN` ($q=1$): $\Delta EF = 0.1 - 4 \times (0.08 + 0.08) = 0.1 - 0.64 = -0.540$. $EF$ giảm $0.54$.
- **Sàn $EF$**: Không bao giờ giảm xuống dưới $1.300$.

### 6.4. Công thức Repetitions và Interval
- **Nếu `rating == AGAIN`**:
  - `repetitions = 0`
  - `interval_days = 0`
  - `due_at = server_now + 10 phút` (600 giây).
- **Nếu `rating IN (HARD, GOOD)`**:
  - `repetitions = repetitions_old + 1`
  - Khoảng cách ôn tập (`interval_days`):
    - Nếu `repetitions == 1`: `interval_days = 1` ngày.
    - Nếu `repetitions == 2`: `interval_days = 6` ngày.
    - Nếu `repetitions >= 3`:
      $$\text{interval\_days} = \max\left(1, \left\lfloor \text{interval\_old} \times EF_{old} + 0.5 \right\rfloor\right)$$
      *(Lưu ý bắt buộc: Sử dụng **$EF_{old}$** để tính khoảng cách mới, sau đó mới cập nhật $EF_{new}$ vào DB).*
  - `due_at = server_now + (interval_days * 24 giờ)`. Không cộng theo ngày lịch địa phương để tránh lỗi DST.

---

## 7. Envelope Schema cho Content WORD và DECK (Version 1)

### 7.1. WORD Envelope (`content_type = "WORD"`)
```json
{
  "schema_version": 1,
  "content_type": "WORD",
  "data": {
    "lemma": "approach",
    "language_code": "en",
    "ipa": "/əˈprəʊtʃ/",
    "audio_media_id": null,
    "senses": [
      {
        "id": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
        "sense_no": 1,
        "part_of_speech": "NOUN",
        "cefr_level": "B1",
        "meaning_vi": "cách tiếp cận, phương pháp",
        "definition_en": "a way of dealing with somebody/something",
        "audio_media_id": null,
        "examples": [
          {
            "id": "f5195b05-5928-4ef7-bca8-22fca5665851",
            "position": 1,
            "example_en": "We need a fresh approach to this issue.",
            "example_vi": "Chúng ta cần một cách tiếp cận mới cho vấn đề này."
          }
        ]
      }
    ]
  }
}
```

### 7.2. DECK Envelope (`content_type = "DECK"`)
```json
{
  "schema_version": 1,
  "content_type": "DECK",
  "data": {
    "title": "Business Communication Essentials",
    "description": "Từ vựng giao tiếp thương mại cơ bản trong công sở.",
    "level": "B1",
    "members": [
      {
        "word_id": "c1f7601b-9f9b-449e-b9b5-6a5cf808f972",
        "word_sense_id": "e6a213e4-3d02-4573-bf01-525b6ff4f221",
        "pinned_word_revision_id": "b16f6b02-e221-4d37-97d5-d0ff0c2b2a61",
        "position": 1
      }
    ]
  }
}
```

---

## 8. Hợp đồng Frontend và Cloudflare Pages
1. **Kiến trúc tĩnh**: Frontend được build với `output: export` (`npm run build:pages`). Không sử dụng Server-side Rendering phụ thuộc cơ sở dữ liệu thời điểm build.
2. **Định tuyến tĩnh**:
   - `/learn/vocabulary`: Chọn deck qua query parameter `?deck=<uuid>`.
   - `/learn/review`: Ôn tập theo query parameter `?deck=<uuid>` hoặc toàn bộ do server trả về tại `/api/v1/vocabulary/reviews/due`.
   - Bỏ `review` khỏi danh sách `generateStaticParams` của route `[section]` để tránh xung đột xuất tĩnh trùng lặp.
3. **Quản lý Request Idempotency**:
   - Client sinh `reviewRequestId` (UUID v4) cho mỗi lượt lật thẻ và đánh giá.
   - Vô hiệu hóa (disable) 3 nút đánh giá trong khi request đang gửi (Pending) để chống nhấp đúp (Double click).
   - Nếu xảy ra lỗi mạng hoặc refresh phiên tự động (401 -> refresh token), request thử lại **phải giữ nguyên `reviewRequestId`**.
   - Chỉ chuyển sang thẻ tiếp theo khi nhận được phản hồi HTTP thành công từ server.
4. **Xử lý phiên và xác thực**:
   - Tái sử dụng `auth-client` hiện có với cookie same-origin và cơ chế single-flight `refreshSession`.
   - Lỗi 403 (Không đủ quyền) không kích hoạt refresh loop.
5. **Đồng bộ trạng thái sau reload**:
   - Khi người dùng tải lại trang hoặc đăng nhập lại, hàng đợi và tiến độ luôn được nạp mới trực tiếp từ server.

---

## 9. Bảng Mã Lỗi (ProblemDetail)

| Mã lỗi (`code`) | HTTP Status | Trường hợp xảy ra |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Dữ liệu đầu vào không hợp lệ (rating không phải AGAIN/HARD/GOOD, UUID sai định dạng). |
| `UNAUTHENTICATED` | 401 | Chưa đăng nhập hoặc phiên hết hạn không thể refresh. |
| `ACCESS_DENIED` | 403 | Không có vai trò `LEARNER`. |
| `RESOURCE_NOT_FOUND` | 404 | Từ, bộ thẻ, hoặc nghĩa từ không tồn tại hoặc không ở trạng thái Published. |
| `CONFLICT` | 409 | Trùng `reviewRequestId` với payload khác, hoặc `expectedProgressVersion` không khớp (Stale data). |
| `RATE_LIMITED` | 429 | Gửi yêu cầu quá tần suất cho phép. |
| `INTERNAL_ERROR` | 500 | Lỗi xử lý giao dịch máy chủ. |
