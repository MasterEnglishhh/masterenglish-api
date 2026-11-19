# Hợp đồng Grammar (Giai đoạn 1)

Trạng thái: phần đọc và luồng publish đã triển khai trên nhánh `feat/grammar-m04`. Phần tiến độ học và bản nháp quản trị **chưa triển khai, chờ duyệt**. Các chỗ chưa được duyệt đánh dấu **[cần duyệt]**.

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
| `PUT /api/v1/grammar/lessons/{lessonId}/progress` | Chưa triển khai, chờ module `learning` |
| Validate snapshot khi duyệt và xuất bản | Đã triển khai |
| Chiếu bản xuất bản vào `grammar_topics` và `grammar_lessons` | Đã triển khai |
| Bảng `lesson_progress` | Chưa triển khai, chờ migration sau |
| API quản trị tạo và sửa bản nháp grammar | Chưa triển khai, chờ duyệt (xem mục 7) |
| Bộ lọc `domain=GRAMMAR` ở danh sách quản trị | Chưa triển khai, chờ duyệt |

## 4. API học viên (`hasRole('LEARNER')`)

Không có token: `401`. Có token nhưng không có vai trò học viên: `403`.

### 4.1. Danh sách chủ điểm

`GET /api/v1/grammar/topics`

- Chỉ trả chủ điểm đang hiển thị (đã xuất bản, chưa lưu trữ, chưa bị thu hồi quyền truy cập).
- Sắp theo `sortOrder` tăng dần, rồi theo `name`.
- Trả mảng, không phân trang. **[cần duyệt]** Dev dùng `PageResponse` cho danh sách lớn; danh sách chủ điểm có thể cần chuyển sang phân trang.

Phản hồi `GrammarTopicResponse[]`:

```json
[{ "id": "uuid", "slug": "articles", "name": "Articles", "description": "A và an", "difficulty": "BASIC", "sortOrder": 1 }]
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

### 4.4. Tiến độ (chưa triển khai)

`PUT /api/v1/grammar/lessons/{lessonId}/progress`, body `{"state": "IN_PROGRESS" | "COMPLETED"}`.

Chưa triển khai vì tiến độ thuộc module `learning`. Khi làm, các trường `state` và `hasNewerRevision` sẽ được thêm vào các phản hồi bài học.

**[cần duyệt]** Có tự tạo `IN_PROGRESS` khi học viên chỉ mở bài hay không. Bản nháp cũ chỉ tạo khi gọi PUT.

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
| `body` | Bắt buộc. Chỉ có khóa `blocks`. Độ dài chuỗi JSON của `body` phải dưới 100000 ký tự. |

### 5.3. Khối nội dung (`body.blocks`)

`blocks` là mảng từ 1 đến 100 phần tử. Mỗi phần tử có `type`, và chỉ được có các trường của loại đó.

| `type` | Trường | Giới hạn |
|---|---|---|
| `heading` | `text` | Bắt buộc, tối đa 200 ký tự |
| `paragraph` | `text` | Bắt buộc, tối đa 50000 ký tự |
| `note` | `text` | Bắt buộc, tối đa 50000 ký tự |
| `example` | `example_en` (bắt buộc), `example_vi` (tùy chọn) | Tối đa 10000 ký tự mỗi trường |
| `table` | `rows`: từ 1 đến 100 hàng, mỗi hàng từ 1 đến 20 ô | Mỗi ô là chuỗi tối đa 500 ký tự |

Mọi chuỗi đều không được chứa thẻ HTML. **[cần duyệt]** Danh mục khối trên là bộ tối thiểu đang triển khai. Chưa có khối audio hay ảnh vì module Media chưa có API.

### 5.4. Lỗi

Mọi lỗi kiểm tra dữ liệu trả `400 VALIDATION_ERROR` với thông báo tiếng Việt, cụ thể theo trường. Loại nội dung không có validator (ví dụ `QUESTION`) cũng trả `400`.

## 6. Luồng xuất bản

1. Quản trị viên tạo bản nháp, gửi duyệt, duyệt, rồi xuất bản bằng các endpoint revision có sẵn của `content`.
2. Trong transaction xuất bản, `content` gửi `SnapshotValidationRequested` tới `grammar`. `grammar` trả lời cho `GRAMMAR_TOPIC` và `GRAMMAR_LESSON`. `vocabulary` bỏ qua các loại này.
3. Sau khi validate thành công, `content` phát `RevisionPublished`. `grammar` ghi bảng đọc trong cùng transaction.
4. Nếu bước nào lỗi, cả lượt xuất bản được rollback.

## 7. Bản nháp quản trị (chưa triển khai, chờ duyệt)

Đề xuất: thêm sự kiện tạo bản nháp grammar trong `common/events`, xử lý trong `content`, và endpoint trong `admin`. Cần duyệt vì đụng tới ba module khác.

Đường dẫn dự kiến (theo bản nháp cũ):

- `POST /api/v1/admin/content/grammar/topics`, `PUT /api/v1/admin/content/grammar/topics/{id}`
- `POST /api/v1/admin/content/grammar/lessons`, `PUT /api/v1/admin/content/grammar/lessons/{id}`

Bản nháp chỉ cần đủ trường khi xuất bản. Duyệt và xuất bản dùng các endpoint revision có sẵn.

## 8. Câu hỏi còn lại [cần duyệt]

1. Danh mục khối nội dung (mục 5.3) và các trường của từng loại.
2. Có tự tạo `IN_PROGRESS` khi học viên chỉ mở bài hay không (mục 4.4).
3. Có cần tổng hợp tiến độ theo chủ điểm, ví dụ "3/8 bài", hay không.
4. Danh sách `difficulty` và các giới hạn số có đúng ý không.
5. Danh sách chủ điểm trả mảng hay `PageResponse`.
6. Danh sách quản trị trả `domain = GRAMMAR` cho mục ngữ pháp; giao diện quản trị phải mở rộng bộ lọc.
