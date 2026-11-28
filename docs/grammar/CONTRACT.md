# Hợp đồng Grammar (Giai đoạn 1)

Trạng thái: phần đọc và luồng publish đã triển khai trên nhánh `feat/grammar-m04`. Phần tiến độ học đã triển khai (migration V12); bản nháp quản trị topic/lesson và bộ lọc `domain=GRAMMAR` đã triển khai trên nhánh `feat/m08-content-ops`.

Quy tắc nền lấy từ `docs/database/SCHEMA_CONTRACTS.md` (mục 2, mục 5, mục 7) và `docs/database/DATA_DICTIONARY.md` (`grammar_topics`, `grammar_lessons`, `lesson_progress`).

## 1. Phạm vi

- Chủ điểm (topic) và bài học (lesson) ngữ pháp, soạn và xuất bản qua quy trình revision chung của module `content`.
- Học viên xem danh sách chủ điểm và bài học.
- Ngoài phạm vi giai đoạn này: luyện đề TOEIC, bài tập có chấm điểm (giai đoạn 2), AI Tutor, tệp đính kèm.

## 2. Ranh giới module

- `grammar` sở hữu bảng đọc `grammar_topics` và `grammar_lessons`, và toàn bộ API đọc `/api/v1/grammar/...`.
- `content` sở hữu vòng đời nội dung, revision và xuất bản. `content` phát `SnapshotValidationRequested` khi duyệt và `RevisionPublished` khi xuất bản.
- `grammar` tham gia qua `common/events`: validate snapshot và chiếu bản xuất bản vào bảng đọc, trong cùng transaction xuất bản.
- `learning` sở hữu tiến độ học (`lesson_progress`). Theo `docs/ARCHITECTURE.md`, đây là quyết định đã chốt trên `dev`.
- Không JOIN trực tiếp sang bảng của `content`. Lọc hiển thị dùng view `catalog_visible_items`.

## 3. Trạng thái triển khai

| Thành phần | Trạng thái |
|---|---|
| `GET /api/v1/grammar/topics` | Đã triển khai |
| `GET /api/v1/grammar/topics/{topicId}/lessons` | Đã triển khai |
| `GET /api/v1/grammar/lessons/{lessonId}` | Đã triển khai |
| `PUT /api/v1/grammar/lessons/{lessonId}/progress` | Đã triển khai (module `learning`, xem mục 4.4) |
| `GET /api/v1/grammar/progress` | Đã triển khai (mở rộng, chờ FE duyệt) |
| Validate snapshot khi duyệt và xuất bản | Đã triển khai |
| Chiếu bản xuất bản vào `grammar_topics` và `grammar_lessons` | Đã triển khai |
| Bảng `lesson_progress` | Đã triển khai (migration V12) |
| API quản trị tạo và sửa bản nháp grammar | Đã triển khai (module `admin`/`content`, xem mục 7) |
| Bộ lọc `domain=GRAMMAR` ở danh sách quản trị | Đã triển khai (module `admin`/`content`, xem mục 8.6) |

## 4. API học viên (`hasRole('LEARNER')`)

Không có token: `401`. Có token nhưng không có vai trò học viên: `403`.

### 4.1. Danh sách chủ điểm

`GET /api/v1/grammar/topics`

- Chỉ trả chủ điểm đang hiển thị (đã xuất bản, chưa lưu trữ, chưa bị thu hồi quyền truy cập).
- Mặc định sắp theo `sortOrder` tăng dần, rồi theo `id`.
- Trả `PageResponse<T>` (`items`, `page`, `size`, `totalItems`, `totalPages`, `hasNext`). Mặc định `page` 0 và `size` 20, tối đa 100. `sortBy` nhận `sortOrder`, `name`, `id` (mặc định `sortOrder`). `sortDirection` nhận `asc` hoặc `desc` (mặc định `asc`). `id` luôn là tie-breaker. **Đã triển khai.** Hình dạng phản hồi đổi từ mảng sang trang, FE cần đồng bộ. Trường kích thước trang là `size`, khớp `common/api/PageResponse`. Tài liệu SRS v5.0 ghi `pageSize`; code giữ `size` và không đổi module `common` để khớp SRS. FE dùng `size`.
- Phân trang hiện làm trong bộ nhớ trên toàn bộ chủ điểm hiển thị (shortcut trong `GrammarQueryServiceImpl`, nâng cấp sang SQL khi số chủ điểm lớn).

Phản hồi `PageResponse<GrammarTopicResponse>`:

```json
{ "items": [{ "id": "uuid", "slug": "articles", "name": "Articles", "description": "A và an", "difficulty": "BASIC", "sortOrder": 1 }], "page": 0, "size": 20, "totalItems": 1, "totalPages": 1, "hasNext": false }
```

### 4.2. Bài học của chủ điểm

`GET /api/v1/grammar/topics/{topicId}/lessons`

- `404 RESOURCE_NOT_FOUND` nếu chủ điểm không hiển thị.
- Chỉ trả bài học đang hiển thị. Bài học chỉ hiển thị khi chính nó và chủ điểm của nó đều hiển thị.
- Sắp theo `sortOrder`, rồi theo `title`.

Phản hồi `GrammarLessonSummaryResponse[]`:

```json
[{ "id": "uuid", "title": "Definite article", "sortOrder": 1, "estimatedMinutes": 5 }]
```

### 4.3. Chi tiết bài học

`GET /api/v1/grammar/lessons/{lessonId}`

- `404 RESOURCE_NOT_FOUND` nếu bài học hoặc chủ điểm của nó không hiển thị.
- Trả nguyên `body` đã lưu dưới dạng JSON object.

Phản hồi `GrammarLessonResponse`:

```json
{ "id": "uuid", "topicId": "uuid", "title": "Definite article", "body": { "blocks": [] }, "estimatedMinutes": 5 }
```

### 4.4. Tiến độ bài học

`PUT /api/v1/grammar/lessons/{lessonId}/progress`, body `{"state": "IN_PROGRESS" | "COMPLETED"}`.

- Chỉ ghi khi bài học đang hiển thị. `404 RESOURCE_NOT_FOUND` nếu không hiển thị; `400 VALIDATION_ERROR` nếu `state` khác hai giá trị trên.
- `COMPLETED` ghi `completed_at` và ghim `lastCompletedRevisionId` là bản đang xuất bản tại thời điểm đó.
- `IN_PROGRESS` tạo dòng nếu chưa có. Một dòng đã `COMPLETED` không bao giờ bị hạ về `IN_PROGRESS`.
- Gọi lại an toàn: lần gọi sau không đổi bản ghi đã hoàn thành, kể cả khi bài học đã có revision mới.
- Phản hồi `200` là `LessonProgressResponse`: `{ "lessonId": "uuid", "state": "COMPLETED", "completedAt": "2026-11-21T08:00:00Z", "lastCompletedRevisionId": "uuid" }`. `completedAt` và `lastCompletedRevisionId` là `null` khi `IN_PROGRESS`.

`GET /api/v1/grammar/progress` trả mảng `LessonProgressResponse` của chính người học, sắp theo `updatedAt` giảm dần. Endpoint này không có trong bản nháp cũ; **[cần FE duyệt]**.

Chưa triển khai: trường `hasNewerRevision` trong phản hồi bài học và trường `state` trong `GET /lessons/{lessonId}`. Thêm các trường này sẽ làm module `grammar` phụ thuộc `learning`, nên cần duyệt riêng.

**Đã chốt:** không tự tạo `IN_PROGRESS` khi học viên chỉ mở bài. Chỉ PUT mới tạo dòng.

## 5. Quy tắc nội dung

Các quy tắc dưới đây đã được kiểm tra khi duyệt và xuất bản.

### 5.1. Chủ điểm (`GRAMMAR_TOPIC`)

| Trường | Quy tắc |
|---|---|
| `slug` | Bắt buộc. Chỉ gồm chữ thường, số và dấu gạch ngang; không bắt đầu hoặc kết thúc bằng gạch ngang, và không có hai gạch ngang liền nhau. Tối đa 120 ký tự. Phải duy nhất trong toàn bộ chủ điểm. |
| `name` | Bắt buộc, không rỗng, tối đa 200 ký tự, không chứa HTML. |
| `description` | Tùy chọn, là chuỗi, không chứa HTML. |
| `difficulty` | Tùy chọn. Chỉ nhận `BASIC`, `INTERMEDIATE`, `ADVANCED`. |
| `sort_order` | Tùy chọn. Số nguyên từ 0 trở lên. |

Trường khác bị từ chối với thông báo `Unsupported snapshot field: <tên>`.

### 5.2. Bài học (`GRAMMAR_LESSON`)

| Trường | Quy tắc |
|---|---|
| `topic_id` | Bắt buộc. Phải là chủ điểm đang hiển thị. |
| `pinned_topic_revision_id` | Bắt buộc. Phải là một revision **đã xuất bản** của chính chủ điểm đó. Revision mới hơn của chủ điểm không làm bài học mất hiệu lực. |
| `title` | Bắt buộc, không rỗng, tối đa 200 ký tự, không chứa HTML. |
| `slug` | Bắt buộc, cùng quy tắc với chủ điểm. Không kiểm tra trùng. |
| `position` | Bắt buộc. Số nguyên từ 0 đến 100000. Lưu vào `sort_order`. |
| `body_schema_version` | Tùy chọn. Chỉ nhận `1`. |
| `estimated_minutes` | Tùy chọn. Từ 1 đến 600. |
| `body` | Bắt buộc. Chỉ có khóa `blocks`. Chuỗi JSON của `body` tối đa 100.000 ký tự và 1 MiB theo UTF-8. Độ sâu lồng tối đa 16 mức, tính `body` là mức 1. |

### 5.3. Khối nội dung (`body.blocks`)

`blocks` là mảng từ 1 đến 100 phần tử. Mỗi phần tử có `type`, và chỉ được có các trường của loại đó.

| `type` | Trường | Giới hạn |
|---|---|---|
| `heading` | `text` | Bắt buộc, tối đa 200 ký tự |
| `paragraph` | `text` | Bắt buộc, tối đa 50000 ký tự |
| `note` | `text` | Bắt buộc, tối đa 50000 ký tự |
| `example` | `example_en` (bắt buộc), `example_vi` (tùy chọn) | Tối đa 10000 ký tự mỗi trường |
| `table` | `rows`: từ 1 đến 100 hàng, mỗi hàng từ 1 đến 20 ô | Mỗi ô là chuỗi tối đa 500 ký tự |

Mọi chuỗi đều không được chứa thẻ HTML: thẻ mở, thẻ đóng, thẻ tự đóng, comment `<!--` và `<!DOCTYPE`. Ký hiệu so sánh như `x < y` được phép. **Đã chốt:** giữ nguyên 5 loại khối trên làm allowlist của `body_schema_version = 1`. Chưa có khối `media` vì module Media chưa có API. Khi xuất bản `GRAMMAR_LESSON` v1, tập `revision_media_refs` của bài học phải rỗng. Ràng buộc này không kiểm tra được trong `modules/grammar`: sự kiện `SnapshotValidationRequested` không mang thông tin media của revision, nên kiểm tra thuộc module `content`, ngoài phạm vi này.

Guard đã triển khai: tổng JSON `body` tối đa 100.000 ký tự và 1 MiB theo UTF-8. Giới hạn 1 MiB không bao giờ kích hoạt trước giới hạn ký tự, vì 100.000 ký tự UTF-8 tối đa 400 KB, nên giữ làm chốt chặn phòng thủ. Độ sâu lồng tối đa 16. Chỉ nhận field theo allowlist. Từ chối HTML thô.

### 5.4. Lỗi

Mọi lỗi kiểm tra dữ liệu trả `400 VALIDATION_ERROR` theo ProblemDetail (RFC 7807), cụ thể theo trường. Thông báo lỗi hiện dùng tiếng Anh, theo quy ước code hiện có. Đổi sang tiếng Việt là quyết định chung của dự án, chưa áp dụng cho module này. Loại nội dung không có validator (ví dụ `QUESTION`) cũng trả `400`.

Kiểm thử trong `modules/grammar` xác nhận mã lỗi `VALIDATION_ERROR` ở tầng validator. Chưa có ca HTTP publish trong `modules/grammar`, vì endpoint publish nằm ở `admin`/`content`.

## 6. Luồng xuất bản

1. Quản trị viên tạo bản nháp, gửi duyệt, duyệt, rồi xuất bản bằng các endpoint revision có sẵn của `content`.
2. Trong transaction xuất bản, `content` gửi `SnapshotValidationRequested` tới `grammar`. `grammar` trả lời cho `GRAMMAR_TOPIC` và `GRAMMAR_LESSON`. `vocabulary` bỏ qua các loại này.
3. Sau khi validate thành công, `content` phát `RevisionPublished`. `grammar` ghi bảng đọc trong cùng transaction.
4. Nếu bước nào lỗi, cả lượt xuất bản được rollback.

## 7. Bản nháp quản trị (Đã triển khai)

Đã triển khai trong module `admin` và `content` qua sự kiện `ContentEvents` trên nhánh `feat/m08-content-ops`:

- `POST /api/v1/admin/content/grammar/topics`: Tạo bản nháp chủ điểm ngữ pháp (`GRAMMAR_TOPIC`) kèm revision DRAFT đầu tiên, trả về 201 `AdminContentItemDto`. Hỗ trợ partial draft (bắt buộc `title`, `slug`, `data` là JSON object, tùy chọn `sourceIds`, `changeSummary`).
- `POST /api/v1/admin/content/grammar/lessons`: Tạo bản nháp bài học ngữ pháp (`GRAMMAR_LESSON`) kèm revision DRAFT đầu tiên, trả về 201 `AdminContentItemDto`. Bắt buộc `title`, `slug`, `data` là JSON object.
- Chỉnh sửa bản nháp: Tái sử dụng generic revision endpoint `PUT /api/v1/admin/content/revisions/{id}`. Khi sửa `REJECTED` hoặc `APPROVED`, revision được reset về `DRAFT` và xóa metadata duyệt.
- Giới hạn snapshot bài học: Allowlist known fields, chuỗi JSON `body` tối đa 100.000 ký tự và 1 MiB theo UTF-8, độ sâu lồng tối đa 16 mức (tính `body` là mức 1), chỉ gồm 5 loại khối (`heading`, `paragraph`, `note`, `example`, `table`), cấm hoàn toàn HTML thô.
- Xuất bản: Tái sử dụng generic endpoint `POST /api/v1/admin/content/revisions/{id}/publish`. Yêu cầu chủ điểm phải được xuất bản trước, bài học ghim revision đã xuất bản của chủ điểm qua `pinned_topic_revision_id`. Khóa phụ thuộc được lưu bất biến trong `revision_dependencies`. Khi xuất bản `GRAMMAR_LESSON` v1, tập `revision_media_refs` phải rỗng; nếu có media ref sẽ bị chặn với `400 VALIDATION_ERROR`.

## 8. Quyết định đã chốt và trạng thái triển khai

Người dùng đã chốt sáu quyết định dưới đây. Trạng thái ghi tình trạng của code tại thời điểm cập nhật.

1. **Khối nội dung và giới hạn `body`.** Trạng thái: đã triển khai trong `modules/grammar`. Giữ 5 loại khối làm allowlist, không thêm `media`. Ràng buộc `revision_media_refs` rỗng cho lesson v1 không kiểm tra được ở `grammar`, xem mục 5.3.
2. **Không tự tạo `IN_PROGRESS` khi mở bài.** Trạng thái: khớp triển khai.
3. **Tổng hợp tiến độ theo chủ điểm.** Đã chốt: phụ thuộc một chiều `learning → grammar`, `grammar` không phụ thuộc `learning`. Module `grammar` chỉ trả dữ liệu nội dung, có thể kèm `lessonCount` đếm bài đã xuất bản. Tổng hợp tiến độ người học (`completedLessonCount`, `totalLessonCount` chỉ tính bài đã xuất bản, chưa lưu trữ hay thu hồi, và cờ `hasUpdate` khi `last_completed_revision_id` khác `published_revision_id`) đặt trong `GET /api/v1/grammar/progress` của module `learning`. Trạng thái: chưa triển khai.
4. **`difficulty`** chỉ nhận `BASIC`, `INTERMEDIATE`, `ADVANCED`. Trạng thái: khớp bảng `grammar_topics` (V10) và validator.
5. **Danh sách chủ điểm trả `PageResponse<T>`**, mặc định 20, tối đa 100, sắp `sort_order ASC, id ASC`. Trạng thái: đã triển khai. Đổi hình dạng phản hồi mục 4.1, FE cần đồng bộ.
6. **`domain=GRAMMAR`** trong danh sách quản trị chỉ lọc `GRAMMAR_TOPIC` và `GRAMMAR_LESSON`, không gộp `QUESTION`. Trạng thái: đã triển khai (`GET /api/v1/admin/content/items?domain=GRAMMAR` lọc đúng `GRAMMAR_TOPIC` và `GRAMMAR_LESSON`; `domain=VOCABULARY` lọc `WORD` và `DECK`; domain lạ trả về 400 `VALIDATION_ERROR`).

Phạm vi triển khai: tổng hợp tiến độ (quyết định 3) thuộc phạm vi module `learning`. Draft quản trị (§7) và bộ lọc `domain=GRAMMAR` (quyết định 6) thuộc phạm vi `admin`/`content`. Các phần này nằm ngoài phạm vi tự triển khai của `modules/grammar`.

Thứ tự khóa khi bắt đầu luyện tập có chủ điểm (`AttemptServiceImpl.start`) giữ nguyên code: khóa user, idempotency, khóa topic `FOR SHARE` theo id tăng dần qua `GrammarEvents.lockPublishedTopics`, settle bài quá hạn và giới hạn 5 bài mở, rồi chọn câu. Facade khóa nằm trong `grammar`, caller nằm ở cụm results chưa merge.

Ghi chú tham chiếu: quy tắc chọn câu cho mixed attempt (phương án A) được ghi ở mục 2.4 của tài liệu practice. Mục 2.4 hiện nằm ở nhánh `wip/m05-results-local`, chưa có trên nhánh này.

## 9. Bàn giao

- **`learning`:** tổng hợp tiến độ theo chủ điểm (`completedLessonCount`, `totalLessonCount`, `hasUpdate`) nằm trong `GET /api/v1/grammar/progress`. `grammar` cần cung cấp public facade đếm bài đã xuất bản theo chủ điểm, và `learning` đọc qua facade đó. Facade chưa có. Vì facade đặt trong `common/events`, cần duyệt riêng, ngoài phạm vi module `grammar`.
- **`admin`/`content`:** Đã triển khai API tạo bản nháp `POST /grammar/topics` và `POST /grammar/lessons`, chỉnh sửa bản nháp qua `PUT /revisions/{id}`, và bộ lọc `domain=GRAMMAR` / `domain=VOCABULARY` trên `GET /items`.
- **`content` (kiểm tra `revision_media_refs`):** Đã triển khai kiểm tra `revision_media_refs` rỗng cho bài học ngữ pháp v1 trước khi duyệt và xuất bản (`hasRevisionMediaRefs` ném `400 VALIDATION_ERROR` nếu tồn tại media ref).
- **Frontend:** `GET /api/v1/grammar/topics` trả `PageResponse<GrammarTopicResponse>` (`items`, `page`, `size`, `totalItems`, `totalPages`, `hasNext`). Phản hồi cũ là mảng, FE cần cập nhật. Trường kích thước là `size`, không phải `pageSize` như SRS v5.0.
- **Ghi nhận checkpoint:** `GrammarPracticeControllerIntegrationTests.java` nằm trong `modules/practice/controller` theo đường dẫn. `GrammarPracticeController` đã có trên nhánh từ `9ea157d`. Commit checkpoint `6d733df` giữ nguyên, không viết lại lịch sử.
