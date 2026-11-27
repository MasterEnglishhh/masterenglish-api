# Hợp đồng Practice và Results (bản chốt Giai đoạn 1)

Trạng thái: **đã triển khai cho Giai đoạn 1 (Slice 2, migration V18; các số V14/V15 trong thân tài liệu nay là V18/V19)** trên nhánh `feat/m04-grammar-complete`. Bản thiết kế bên dưới giữ nguyên làm căn cứ; các điểm đã đổi khi triển khai được ghi ở mục 18. Chưa triển khai: thống kê điểm yếu và lỗi sai (Slice 3). Frontend chưa dùng các endpoint này.
Nhánh: `feat/m04-grammar`. Ngày: 2026-11-21.

Nguồn chuẩn (đã duyệt, đọc trực tiếp trong repository):
- `docs/database/APPROVED_BASELINE.md`, `DATA_DICTIONARY.md`, `SCHEMA_CONTRACTS.md` (mục 4–7), `ERD.md`, `MIGRATION_PLAN.md`
- `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/vocabulary/CONTRACT.md`, `docs/LOCAL_SECURITY_SETUP.md`, `docs/database/checks/README.md`

Tham chiếu SQL (không phải migration dự án): `docs/database/proposals/approved-baseline.sql`. Bộ kiểm thử SQL tham chiếu (`SCHEMA_VALIDATION.md`) chỉ chứng minh ràng buộc và giao thức của SQL. **Không chứng minh API start/resume của backend.** Theo `checks/README.md`, các kiểm thử API, scheduler và migration dữ liệu thật phải làm khi viết module tương ứng.

Tài liệu này không phải nguồn chuẩn độc lập. Mọi chỗ lệch khỏi approved baseline được ghi ở mục 14.

## Lịch sử thay đổi

| Ngày | Phiên bản | Thay đổi | Căn cứ |
|---|---|---|---|
| 2026-11-21 | Giai đoạn 1 | Bản chốt ban đầu | — |
| 2026-11-26 | T0 | Mở rộng cho TOEIC (M05). Nội dung cũ bị thay được gạch bỏ tại chỗ và ghi "(thay bởi T0)", không xóa. Tóm tắt thay đổi:<br>• D8 → D8 v2: V13 đã lên `main` (`9ea157d`, PR #15), không sửa V13, schema mới đi qua V14+ kiểu additive.<br>• D6 mở lại cho TOEIC (`practice_sets`, S9).<br>• Không có `EXPIRED` (S2).<br>• `mistake_records` thuộc `learning` (S3).<br>• `STALE_ANSWER_VERSION` → `VERSION_CONFLICT` (S8).<br>• Autosave có `Idempotency-Key`.<br>• Autosave và submit không khóa `users`.<br>• JSON snapshot theo `docs/m05/02`.<br>• Schema đích theo brief T0 §4.1. | [brief T0](../m05/tasks/T0-brief-contract-freeze.md), [ADR-07..13](../m05/01-adrs-07-to-13.md), [JSON Schema v1](../m05/02-snapshot-json-schemas-v1.md) |
| 2026-11-26 | Rà soát chéo T0–T7 | Áp delta tài liệu của các brief đã duyệt, gạch nội dung cũ tại chỗ:<br>• §2.1: `PracticeEvents` chỉ còn `prepareSeal`; facade media của `content`; event `AttemptSubmitted` theo T4.<br>• §3.2: GET luôn trả `ActiveAttemptResponse`; submit trả `AttemptSubmitResponse`; thêm `/results` và endpoint media; 3 endpoint grammar cũ gỡ hẳn.<br>• §3.4: DTO theo 02 §8.<br>• §4.3: `activeAttemptIds`; `attemptStatus` thay `status`; thêm `ATTEMPT_NOT_SUBMITTED`; kiểu `UUID_LIST`.<br>• §5.1–5.4: `prepareSeal`, response PUT, kết quả chỉ ở `/results`.<br>• §14.4: lệch DD của T1. | Brief [T2](../m05/tasks/T2-brief-practice-module-and-selection.md) §4.9, [T3](../m05/tasks/T3-brief-results-module-and-attempt-sealing.md) §4.10, [T4](../m05/tasks/T4-brief-autosave-grading-and-events.md) §4.4, [T1](../m05/tasks/T1-brief-flyway-migration-and-seed.md) §4.8 |
| 2026-11-26 | Duyệt toàn bộ câu hỏi mở T5–T7 và AD-1..AD-3 | • §0 D13, §1, §8.5: phần ghi `OPEN` của `learning` (T5, thiết kế, chưa triển khai).<br>• §14.4: lệch DD của `mistake_records`; bỏ cột ước tính 990 (AD-1).<br>• Brief T3: bật nén gzip cho JSON ≥ 2 KB (AD-3). AD-2 (`refs` camelCase) đã ghi ở 02 §8. | Brief [T5](../m05/tasks/T5-brief-cross-module-integration-and-listener.md), [T6](../m05/tasks/T6-brief-frontend-toeic-practice-ui.md), [T7](../m05/tasks/T7-brief-verification-security-and-benchmark.md) |

## 0. Trạng thái quyết định

| Mã | Quyết định | Trạng thái | Căn cứ |
|---|---|---|---|
| D1 | Tách `results` và `practice` | Đã chốt | DD: ownership |
| D2 | Căn khóa, PK, FK theo approved baseline | Đã chốt | DD, `approved-baseline.sql`, ERD |
| D3 | Tài nguyên attempt dưới `/api/v1/attempts` | Đã chốt. Endpoint **PENDING FE REVIEW** | Mục 3 |
| D4 | Thiếu câu: báo số hợp lệ, không tự cắt | Đã chốt | SCHEMA §5 |
| D5 | Không để 5 bài IN_PROGRESS khóa vĩnh viễn | Đã chốt: lazy cleanup khi start | SCHEMA §6, mục 5.1 |
| D6 | ~~Hoãn `practice_sets`~~ (thay bởi T0). Grammar vẫn dùng DYNAMIC. TOEIC dùng `practice_sets` FIXED, cho phép `part_number IS NULL` (S9) | Đã chốt lại 2026-11-26 | DD; brief T0 §4.1.8 |
| D7 | Thiết kế cho VOCABULARY, GRAMMAR, TOEIC. Không bật publish/chấm khi thiếu validator, renderer, grader | Đã chốt | SCHEMA §5, DD `question_types` |
| D8 | ~~**Không** đổi V12/V13, **không** tạo V14, **không** chạy migration. Không mặc định V13 chưa áp dụng~~ (thay bởi D8 v2) | Thay thế | Mục 11 |
| D8 v3 | Hợp nhất `main` (27/11/2026): V13 giữ nguyên; V18 nâng cấp dữ liệu attempt từ V13 sang dạng baseline (sao lưu vào `legacy_v13_*`, không xóa dữ liệu); V19 là mistake lifecycle. Phá quy tắc additive của D8 v2 (dựng lại bảng `attempts`, `attempt_*`). Các endpoint grammar của `main` được giữ bằng adapter; shape phản hồi bổ sung các tên của `main` (additive). | Chờ duyệt | Nhánh `feat/m04-grammar-complete` |
| D8 v2 | V13 đã merge vào `main` nên bất biến. Mọi thay đổi schema đi qua V14+ kiểu additive (ADD COLUMN, DROP NOT NULL, thay CHECK, CREATE TABLE). Không xóa cột. | Đã chốt 2026-11-26 | AGENTS.md; brief T0 R7 |
| D16 | Trạng thái lượt làm chỉ gồm `IN_PROGRESS`, `SUBMITTED`, `ABANDONED`. Không có `EXPIRED`. | Đã chốt 2026-11-26 (S2) | ADR-10 |
| D9 | Phân bổ topic Mixed: thuật toán xác định theo hash request, ưu tiên topic ít câu hơn, UUID phá hòa. Lưu `topicIds` và `attributedTopicId` trong snapshot. Thống kê allocation không đếm trùng. Hai loại thống kê tách bạch | Đã chốt (thay quy tắc `min(C)`) | Mục 7.2 |
| D10 | Thiếu câu: HTTP 409 `INSUFFICIENT_QUESTIONS` kèm `requestedCount`, `availableCount`. 0 câu dùng mã riêng | Đã chấp thuận | Mục 4 |
| D12 | Grammar hiện tại untimed, `deadline_at = NULL`. Giữ cơ chế deadline/TIME_LIMIT trong thiết kế dùng chung | Đã duyệt | Mục 5.5 |
| D13 | Hoãn mistake lifecycle. Bảo toàn dữ liệu để làm sau | Đã chấp thuận hoãn. **Chưa hoàn thành.** Phần ghi `OPEN` từ event đã chốt thiết kế ở T5 (26/11/2026), chưa triển khai. | Mục 8, 8.5 |
| D14 | Lazy cleanup cho bài untimed 7 ngày. Không áp dụng cho timed. Không đổi SUBMITTED | Đã chấp thuận | Mục 5.5 |
| D15 | Endpoint dưới `/api/v1/attempts` | Đã chấp thuận nguyên tắc. **PENDING FE REVIEW** | Mục 3 |
| C-ERR | Mở rộng `BusinessException`, `ErrorCode`, `GlobalExceptionHandler` khi Giai đoạn 2 | Đã duyệt. Tương thích ngược, allowlist metadata, regression test | Mục 4 |

## 1. Ranh giới module và ownership

| Module | Sở hữu (bảng) | Không sở hữu |
|---|---|---|
| `practice` | `questions`, `question_options`, `question_topics`, `question_types`, `practice_sets` (sau) | Lượt làm, đáp án, chấm điểm, thống kê |
| `results` | `attempts`, `attempt_source_topics`, `attempt_group_snapshots`, `attempt_question_snapshots`, `attempt_snapshot_options`, `attempt_answers`, `attempt_media_refs`, ~~`mistake_records` (sau)~~ (thay bởi T0: thuộc `learning`, S3). Start, lưu, nộp, xem, chấm, thống kê | Nội dung câu hỏi |
| `practice` (bổ sung T0) | `question_groups`, `group_materials`, `question_materials`, `practice_sets`, `set_items`, `question_words` | — |
| `learning` (bổ sung T0) | `mistake_records`, `mistake_processed_attempts` (T5), `attempt_source_mistakes` (D13, sau). Nhận `ResultsEvents.AttemptSubmitted` | Không đọc bảng nào của `results` |
| `grammar` | `grammar_topics`, `grammar_lessons` | Lượt làm |
| `learning` | `lesson_progress` (không đổi) | — |
| `identity` | `users` (khóa qua `IdentityEvents`) | — |

Ownership này khớp DATA_DICTIONARY (mục "Practice" và "Results").

Quy tắc phụ thuộc:
- Module chỉ phụ thuộc `common` và các facade trong `common/events`. Không import entity hay repository của module khác.
- `results` → `practice`: `PracticeEvents` (đếm và chọn câu).
- `results` → `grammar`: `GrammarEvents.isVisibleTopic`.
- `practice` → `grammar`: `GrammarEvents.isVisibleTopic` khi validate publish.
- `results` → `identity`: `IdentityEvents.verifyAndLockUser`.
- Không có vòng: `grammar` không phụ thuộc `practice` hay `results`.
- Lời gọi cần kết quả ngay dùng event đồng bộ có `EventReply` (ARCHITECTURE).
- **Chỉ có một engine chấm trong `results`.** Grader là registry theo `grading_policy_version` (hiện chỉ `SINGLE_CHOICE_V1`). Không tạo engine riêng cho từng môn.

## 2. Hợp đồng sự kiện

### 2.1. `PracticeEvents` (mới, `common/events`)

> **Bản hiện hành (T2, O4):** `PracticeEvents` chỉ có **một** method `prepareSeal(UUID requestId, SealRequest request) -> SealSource`. `SealRequest` là sealed interface (`PracticeSetSeal`, `TopicSeal`). Method chạy với `Propagation.MANDATORY` trong transaction start của `results`, khóa nguồn `FOR SHARE`. Record seal (`SealSource`, `SealGroup`, `SealMaterial`, `SealQuestion`, `SealOption`, `SealRef`, `SealSpoken`) theo brief T2 §4.3. Media do `content` cung cấp: `ContentEvents.describeMedia(Set<UUID>)` và `ContentEvents.mediaPlaybackLocation(UUID)`. Các mục gạch bên dưới chỉ giữ để truy vết.

- ~~`countAvailable(category, topicIds) -> long`: số câu đủ điều kiện chọn (mục 2.2).~~ (thay bởi T2)
- ~~`selectQuestions(requestId, category, topicIds, limit) -> List<SelectedQuestion>`.~~ (thay bởi T2) Mỗi câu một lần. Có `requestId` (đã sửa ở A6): D9 mục 7.2 bước 2 cần nó để sắp thứ tự, thiếu thì không tái lập được kết quả. Thiếu câu thì ném `BusinessException` (NO_ELIGIBLE_QUESTIONS hoặc INSUFFICIENT_QUESTIONS). `SelectedQuestion` gồm:
  - `questionId`, `sourceRevisionId`
  - `topicIds`: toàn bộ `topic_ids` của revision đã ghim
  - `attributedTopicId`: topic gán cho câu trong bài (mục 7.2)
  - `questionTypeCode`, `practiceMode`, `schemaVersion`, `gradingPolicyVersion`
  - `prompt`, `explanation`
  - `options[{optionId, position, body}]`: `optionId` lấy từ envelope của revision (ổn định)
  - `correctOptionId`: chỉ dùng phía server để ghi `answer_key_snapshot`

**Bổ sung T0 (TOEIC):** ~~`loadSetForSealing(practiceSetId) -> SealSource`~~ (thay bởi T2: `prepareSeal`) cùng các record `SealSource`, `SealGroup`, `SealMaterial`, `SealQuestion`, `SealOption`, `SealRef`, `SealSpoken`. Chữ ký đầy đủ ở brief T0 §5.3. Ánh xạ seal ở [02 §6](../m05/02-snapshot-json-schemas-v1.md).

**Bổ sung T0 (event async):** ~~`common/events/ResultsEvents.AttemptSubmitted(attemptId, userId, category, sourceType, effectiveFinishedAt, outcomes[{questionId, snapshotId, partNumber, outcome}])`~~ (thay bởi T4 §3.M3) `ResultsEvents.AttemptSubmitted(eventId, attemptId, userId, category, sourceType, submitReason, submittedAt, effectiveFinishedAt, outcomes[QuestionOutcome(questionId, partNumber, correct, unanswered)])`. Event được phát khi lượt chuyển `IN_PROGRESS → SUBMITTED` và được ghi vào `event_publication` (ADR-12).

### 2.2. Điều kiện câu đủ điều kiện

- Bản **Published**, projection khớp `published_revision_id` (join `content_items`).
- Không archive (`is_archived = false`), không thu hồi (`access_revoked_at IS NULL`).
- `question_types.enabled = true` cho `question_type_code`.
- Category được bật (D7).
- Thuộc ít nhất một chủ điểm trong danh sách yêu cầu (qua `question_topics`).
- Nguồn được khóa theo thứ tự `question_id` (`FOR SHARE`) trước khi chọn (SCHEMA §6, mục 5.1).

### 2.3. Sự kiện đã có

- `GrammarEvents.isVisibleTopic(topicId)`.
- `GrammarEvents.lockPublishedTopics(topicIds)`: bước 3 của start (mục 5.1).
- `IdentityEvents.verifyAndLockUser(userId)`.

## 3. API Contract (PENDING FE REVIEW)

### 3.1. Quy ước chung

- Mọi endpoint: role `LEARNER`. Chưa đăng nhập: 401. Không đủ role: 403.
- `POST`, `PUT`, `DELETE` cần header `X-XSRF-TOKEN` khớp cookie `XSRF-TOKEN` (`LOCAL_SECURITY_SETUP.md`).
- `Idempotency-Key` (UUID) là header của `POST /api/v1/attempts`. Header này đã nằm trong danh sách CORS (`SecurityConfiguration.java`, dòng 134), và DD ghi "Idempotency-Key khi bắt đầu".
- `X-Request-Id` là traceId. Client gửi lên, server trả lại (`RequestIdFilter`).
- Lỗi theo ProblemDetail, có `code` (mục 4).
- Chưa có FE nào gọi các endpoint này. Toàn bộ endpoint ở trạng thái **PROPOSED — PENDING FE REVIEW**.

### 3.2. Bảng endpoint

| Method | Path | Owner | Headers / Body | Thành công | Lỗi |
|---|---|---|---|---|---|
| POST | `/api/v1/attempts` | results | `Idempotency-Key`; body `{source:{type:"GRAMMAR_TOPIC", topicId}, questionCount}` hoặc `{source:{type:"MIXED_GRAMMAR", topicIds[]}, questionCount}` | 201 tạo mới; 200 replay cùng nội dung | Mục 4.3 |
| GET | `/api/v1/attempts/{attemptId}` | results | — | ~~200 `AttemptView`~~ (thay bởi T4 O2) 200 `ActiveAttemptResponse` ở **mọi** trạng thái, không bao giờ có dữ liệu sau nộp | 404 |
| PUT | `/api/v1/attempts/{attemptId}/answers/{snapshotId}` | results | body `{selectedOptionId: UUID \| null, expectedVersion: long}` | ~~200 `AnswerView`~~ (thay bởi T4) 200 `{snapshotId, selectedOptionId, version, answeredAt}` | Mục 4.3 |
| POST | `/api/v1/attempts/{attemptId}/submit` | results | — | ~~200 `AttemptView` (có kết quả)~~ (thay bởi T4 O3) 200 `AttemptSubmitResponse` `{attemptId, status, result, resultsUrl}`; gọi lại khi đã nộp trả kết quả đã lưu | 404; 409 `ATTEMPT_NOT_OPEN` (`attemptStatus`) nếu ABANDONED |
| GET | `/api/v1/attempts/{attemptId}/results` (T4) | results | — | 200 `AttemptResultResponse` | 404; 409 `ATTEMPT_NOT_SUBMITTED` (`attemptStatus`) |
| GET | `/api/v1/attempts/{attemptId}/media/{mediaAssetId}` (T2, T3) | results | — | 302 tới URL R2 ký 5 phút | 404; 503 `SERVICE_UNAVAILABLE` |
| GET | `/api/v1/grammar/topics/{topicId}/exercises` | practice | — | 200 `{topicId, availableQuestions}` | 404 |
| GET | `/api/v1/attempts/statistics/topics?category=GRAMMAR&view=allocation` | results | — | 200 danh sách theo chủ điểm, `view` cố định `allocation` | 400 nếu category hoặc view không hợp lệ |
| POST | `/api/v1/attempts` (bổ sung T0) | results | Ngoài hai nguồn Grammar còn có `{source:{type:"PRACTICE_SET", practiceSetId}, mode?}`. `questionCount` bị cấm với `PRACTICE_SET`. `mode` mặc định `PRACTICE`; `EXAM` → 400 ở MVP. | 201 / 200 replay | Brief T0 §5.1 |
| PUT | `/api/v1/attempts/{attemptId}/answers/{snapshotId}` (bổ sung T0) | results | Thêm header `Idempotency-Key` (UUID, bắt buộc) | 200 / 200 replay | Brief T0 §5.1 |
| GET | `/api/v1/practice-sets?category=&partNumber=&page=&size=` (T0) | practice | `partNumber=0` là bộ nhiều Part. Tham số đầy đủ (`category`, `partNumber`, `difficulty`, `q`, `page`, `size`) theo brief T2 §5.1. | 200 `PageResponse<PracticeSetSummary>` | 400, 404 |
| GET | `/api/v1/practice-sets/{practiceSetId}` (T0) | practice | — | 200 `PracticeSetSummary` + `description`, `partBreakdown` | 404 |

~~Endpoint cũ trong code (`POST /api/v1/grammar/topics/{topicId}/attempts`, `POST /api/v1/grammar/attempts/mixed`, `GET /api/v1/grammar/statistics/topics`, `PUT /api/v1/attempts/{id}/answers/{position}`) bị **DEPRECATED** từ T0 và được gỡ trong task phát hành API v1.~~ (thay bởi T3 O3) Các endpoint cũ này được **gỡ hẳn** ở T3, không giữ `@Deprecated`. Frontend không gọi chúng. Thay thế: `POST /api/v1/attempts` với `GRAMMAR_TOPIC`/`MIXED_GRAMMAR`, và `GET /api/v1/attempts/statistics/topics?category=GRAMMAR&view=allocation`.

Thay đổi so với bản trước:
- `questionCount` là **bắt buộc**, không có giá trị mặc định. Lý do: hash idempotency phải phụ thuộc đúng giá trị client gửi.
- `view` của thống kê chỉ nhận `allocation` trong Giai đoạn 2. `multi_label` được giữ để dùng sau (mục 7.3), không mở trong M04.

### 3.3. Lý do chọn URL

- **Tài nguyên attempt** dưới `/api/v1/attempts`. Cùng một tài nguyên dùng cho VOCABULARY (DECK), GRAMMAR, TOEIC về sau.
- **Thống kê** dưới `/api/v1/attempts/statistics/...` với tham số `category`. Thống kê thuộc `results` và dùng cho mọi category.
- **Exercises theo chủ điểm** giữ dưới `/api/v1/grammar/topics/...`. Đây là danh mục theo chủ điểm của Grammar, owner là `practice`. AGENTS cho phép URL công khai khác module sở hữu.
- **Idempotency bằng header.** Lệch với `vocabulary/CONTRACT.md` (dùng `reviewRequestId` trong body). Xem mục 14.2.
- **`snapshotId` trong URL**, không dùng vị trí câu (DD: `attempt_answers.snapshot_id UNIQUE`).
- **Resume** là `GET /api/v1/attempts/{id}`. Resume chỉ đọc snapshot, không bốc lại đề (SCHEMA §6). **Retry** start là `POST` với cùng `Idempotency-Key`.

### 3.4. DTO

> **Bản hiện hành (T3, T4):** `ActiveAttemptResponse`, `AttemptSubmitResponse`, `AttemptResultResponse` theo [02 §8](../m05/02-snapshot-json-schemas-v1.md), brief T3 §5.1 và brief T4 §5.1. `AttemptView` một hình dạng cho mọi trạng thái đã bị thay (T4 O2): kết quả và lời giải chỉ có ở `/results`.
>
> ~~T0: hình dạng `AttemptView` và `AnswerView` cho mọi category được chốt ở 02 §8 và brief T0 §5.1.~~ (thay bởi T3/T4) Hai điểm thay đổi so với đoạn dưới:
> - Không trả `score` (ADR-11).
> - `AnswerView` bỏ `attemptVersion`.
>
> Đoạn dưới giữ lại để truy vết.

**`AttemptView` trước nộp** (`status = IN_PROGRESS`):
- `id`, `category`, `source {type, topicIds[]}`, `status`, `totalCount`, `version`, `deadlineAt` (null với Grammar), `startedAt`, `lastActiveAt`.
- `questions[]`: `snapshotId`, `ordinal`, `topicId` (= `attributedTopicId`), `questionTypeCode`, `practiceMode`, `prompt`, `options[]` (`optionId`, `position`, `body`), `selectedOptionId`, `answerVersion`.

**Không có** trước nộp: `correctOptionId`, `isCorrect`, `explanation`, phần nào của `answer_key_snapshot` hay `explanation_snapshot`. Không trả nguyên cột JSON. Chỉ trả các trường đã whitelist (mục 6.2).

**`AttemptView` sau nộp** (`status = SUBMITTED`): thêm `score`, `correctCount`, `answeredCount`, `submittedAt`, `effectiveFinishedAt`, `submitReason`, `durationSeconds`. Với từng câu: `isCorrect`, `correctOptionId`, `explanation`. Chỉ chủ bài nhận được.

**`AttemptView` khi ABANDONED:** giống trước nộp, không có điểm (`score = null`). Không cho lưu đáp án.

**`AnswerView`:** `snapshotId`, `selectedOptionId` (null khi bỏ chọn), `answerVersion`, `answeredAt`, `attemptVersion`.

**`TopicStatisticsItem`:** `topicId`, `attemptCount`, `questionCount`, `correctCount`, `accuracyPercent` (null khi `questionCount = 0`), `view = "allocation"`.

## 4. Lỗi và ProblemDetail (common/error)

### 4.1. Hiện trạng (đã kiểm tra trong code)

- `ErrorCode` có: `VALIDATION_ERROR` (400), `UNAUTHENTICATED` (401), `ACCESS_DENIED` (403), `RESOURCE_NOT_FOUND` (404), `CONFLICT` (409), và các mã Identity/Media.
- `BusinessException(ErrorCode, message)` không mang dữ liệu thêm. Từ A1 (21/11/2026) có thêm constructor `(ErrorCode, message, details)` với allowlist theo mã (mục 4.2).
- `GlobalExceptionHandler.handleBusiness` gọi `problem(...)`, gắn `code` (tên `ErrorCode`), `traceId` (từ request attribute do `RequestIdFilter` gán), và `errors` (list rỗng).
- Có sẵn `ErrorHandlingTests` (10 ca, không phải 6 như trước: 6 ca gốc gồm `returnsProblemDetailsWithMatchingRequestId`, `validationDoesNotExposeTheRejectedPassword`, `malformedJsonDoesNotEchoRequestContents`, `unexpectedExceptionsDoNotExposeInternalMessages`, `replacesInvalidIncomingRequestIds`, `pageResponseMatchesTheApiContract`; 4 ca bổ sung: `authorizationFailuresInsideMvcRemainForbidden`, `authenticationFailuresInsideMvcRemainUnauthorized`, `staleVersionReturnsConflictWithoutExposingPersistenceDetails`, `rateLimitExceededReturnsTooManyRequestsWithRetryAfterHeader`). Đây là bộ hồi quy bắt buộc giữ xanh.

### 4.2. Thiết kế mở rộng (Giai đoạn 2)

Điều kiện chấp thuận: tương thích ngược, không đổi cấu trúc ProblemDetail chung, chỉ trả metadata nghiệp vụ an toàn, có regression test cho lỗi cũ và mới.

**Tương thích ngược:**
- Constructor `BusinessException(ErrorCode, message)` giữ nguyên. Constructor mới `(ErrorCode, message, details)` có thêm.
- Không đổi giá trị hoặc HTTP status của `ErrorCode` hiện có. Chỉ thêm mã mới.
- Khi `details` rỗng, body lỗi **không thêm khóa nào** so với hiện tại.
- Mọi trường chuẩn ProblemDetail (`type`, `title`, `status`, `detail`) và `code`, `traceId`, `errors` giữ nguyên tên và ý nghĩa.

**Metadata an toàn:**
- `details` là `Map<String, Object>` bất biến, chỉ chứa khóa **đã đăng ký** cho từng `ErrorCode` (allowlist trong code).
- Giá trị chỉ là số nguyên, UUID của tài nguyên của chính người gọi, hoặc chuỗi enum (ví dụ `status`).
- Không đưa vào: stack trace, SQL, tên bảng, thông điệp exception nội bộ, dữ liệu của người dùng khác, email, token.
- Khóa không có trong allowlist: lỗi lập trình, ném `IllegalArgumentException` ở constructor (không đi tới client).

**Cách ghi vào response:** `handleBusiness` gọi `problem.setProperty(key, value)` cho từng khóa trong `details`. Đây là extension member của ProblemDetail, không đổi cấu trúc chung.

### 4.3. Bảng mã lỗi nghiệp vụ

| Mã | HTTP | Khi nào | Metadata (allowlist) |
|---|---|---|---|
| `VALIDATION_ERROR` (có sẵn) | 400 | Body sai định dạng; `questionCount` ngoài 1–100 hoặc thiếu; MIXED với ít hơn 2 hoặc hơn 10 topic; topic trùng; thiếu `Idempotency-Key` | `errors` |
| `RESOURCE_NOT_FOUND` (có sẵn) | 404 | Chủ điểm không hiển thị; attempt không tồn tại hoặc không thuộc người gọi; snapshot không thuộc attempt | — |
| `NO_ELIGIBLE_QUESTIONS` (mới) | 409 | Chủ điểm hiển thị nhưng không có câu đủ điều kiện (`availableCount = 0`) | `requestedCount`, `availableCount` |
| `INSUFFICIENT_QUESTIONS` (mới) | 409 | `0 < availableCount < requestedCount` | `requestedCount`, `availableCount` |
| `IDEMPOTENCY_CONFLICT` (mới) | 409 | Cùng `Idempotency-Key` nhưng khác hash | — |
| `OPEN_ATTEMPT_LIMIT_REACHED` (mới) | 409 | Đã có 5 bài IN_PROGRESS sau lazy cleanup | `openAttemptCount` (COUNT), `maxOpenAttempts` (COUNT), `activeAttemptIds` (UUID_LIST, T3 O1) |
| ~~`STALE_ANSWER_VERSION` (mới)~~ (thay bởi T0, S8) | 409 | `expectedVersion` không khớp | `currentVersion`, `currentSelectedOptionId` |
| `VERSION_CONFLICT` (mới, T0) | 409 | `expectedVersion` không khớp | `currentVersion` (COUNT), `currentSelectedOptionId` (UUID_OR_NULL) |
| `ATTEMPT_NOT_OPEN` (mới) | 409 | Lưu đáp án khi bài đã SUBMITTED hoặc ABANDONED; nộp bài đã ABANDONED | ~~`status`~~ (thay bởi T4 O1) `attemptStatus` (ENUM) |
| `ATTEMPT_NOT_SUBMITTED` (mới, T4) | 409 | `GET /results` khi bài còn IN_PROGRESS hoặc đã ABANDONED | `attemptStatus` (ENUM) |
| `OPTION_NOT_IN_SNAPSHOT` (mới) | 400 | `selectedOptionId` không thuộc snapshot | — |

Bổ sung T0 (R8):
- Metadata có kiểu: `COUNT` (số nguyên ≥ 0), `UUID_OR_NULL`, `ENUM` (`^[A-Z][A-Z_]{0,31}$`), `UUID_LIST` (thêm ở T3).
- ~~`ATTEMPT_NOT_OPEN.status` có kiểu ENUM.~~ (thay bởi T4 O1) Khóa metadata là `attemptStatus`, kiểu ENUM. Tên `status` trùng với trường chuẩn `status` (số HTTP) của ProblemDetail nên không dùng.
- `IDEMPOTENCY_CONFLICT` còn áp dụng cho `PUT /answers` khi trùng `Idempotency-Key` nhưng trạng thái không khớp lần ghi trước.
- `CONFLICT` có sẵn vẫn dùng cho lỗi optimistic lock chung.

Phân biệt rõ:
- **Request không hợp lệ:** `VALIDATION_ERROR` (400). Lỗi do client gửi sai.
- **Không có câu phù hợp (0 câu):** `NO_ELIGIBLE_QUESTIONS` (409). Chủ điểm có, nhưng ngân hàng không có câu nào đủ điều kiện.
- **Có câu nhưng thiếu số lượng:** `INSUFFICIENT_QUESTIONS` (409). Có `availableCount` dương nhưng nhỏ hơn `requestedCount`. Không tự cắt (D4).
- **Xung đột idempotency:** `IDEMPOTENCY_CONFLICT` (409). Cùng key, khác nội dung.
- **Giới hạn bài đang mở:** `OPEN_ATTEMPT_LIMIT_REACHED` (409). Không phải lỗi dữ liệu. Người dùng cần nộp hoặc chờ bài bỏ dở được xử lý.
- **Thứ tự kiểm tra start:** mục 5.1. Thứ tự này quyết định mã nào được trả khi nhiều điều kiện cùng sai.

### 4.4. Ví dụ response thiếu câu

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Not enough eligible questions.",
  "code": "INSUFFICIENT_QUESTIONS",
  "requestedCount": 20,
  "availableCount": 12,
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
}
```

### 4.5. Hồi quy bắt buộc

1. `ErrorHandlingTests` (6 ca hiện có) vẫn xanh, không sửa.
2. Test tương thích mã: tên và HTTP status của mọi `ErrorCode` cũ không đổi (snapshot test).
3. Test `BusinessException` cũ: constructor 2 tham số vẫn hoạt động, body không có khóa thêm.
4. Test metadata: `INSUFFICIENT_QUESTIONS` có đủ `requestedCount` và `availableCount`, đúng kiểu số.
5. Test allowlist: khóa ngoài allowlist bị từ chối ở constructor.
6. Test không rò rỉ: exception không mong đợi không lộ message nội bộ (đã có ở `unexpectedExceptionsDoNotExposeInternalMessages`, phải giữ).
7. Test `traceId` vẫn có trong mọi lỗi nghiệp vụ mới.

## 5. Giao thức transaction

### 5.1. Start (SCHEMA §6)

Trong một transaction, theo thứ tự:
1. `verifyAndLockUser`. Điểm đồng bộ hóa mọi start của cùng một người dùng (SCHEMA §6: khóa user trước).
2. Replay theo `Idempotency-Key` `(user_id, request_id)`:
   - Cùng hash: trả bài cũ (200), **ngay cả khi đã đủ 5 bài**. Client retry sau lỗi mạng không bị báo sai.
   - Khác hash: `IDEMPOTENCY_CONFLICT`.
3. Khóa các chủ điểm đang hiển thị `FOR SHARE` qua `GrammarEvents.lockPublishedTopics`, theo thứ tự id của PostgreSQL. Revision được ghim lấy từ kết quả khóa này. Thiếu chủ điểm nào thì 404. Bước này bỏ qua khi nguồn là MISTAKES. Khóa giữ tới hết transaction, nên lưu trữ hoặc publish mới của chủ điểm phải chờ.
4. **Lazy cleanup** (trước khi đếm), chỉ trên bài của chính người dùng, sau khi đã khóa user:
   - Bài `IN_PROGRESS` có `deadline_at IS NULL` và `last_active_at <= now - practice.abandon_days` (7 ngày): `status = ABANDONED`, `abandoned_at = now`. Không tính điểm.
   - Bài `IN_PROGRESS` có `deadline_at <= now`: **tự nộp** theo giao thức TIME_LIMIT (`submit_reason = TIME_LIMIT`, `effective_finished_at = deadline_at`, `submitted_at = now`). Không dùng quy tắc 7 ngày cho bài này.
   - Điều kiện `status = 'IN_PROGRESS'` trong mọi câu cập nhật. **Không bao giờ đổi bài SUBMITTED.**
   - Khóa các attempt ứng viên `FOR UPDATE`, theo `id` tăng dần, **sau** khi đã khóa user. Không khóa attempt trước user (SCHEMA §6).
5. Đếm bài `IN_PROGRESS` còn lại. Nếu `>= practice.max_open` (5): `OPEN_ATTEMPT_LIMIT_REACHED`.
6. ~~`countAvailable`:~~ (thay bởi T2: kiểm số câu nằm trong `PracticeEvents.prepareSeal`, cùng mã lỗi)
   - `availableCount = 0`: `NO_ELIGIBLE_QUESTIONS`.
   - `0 < availableCount < requestedCount`: `INSUFFICIENT_QUESTIONS`.
7. Khóa nguồn theo thứ tự `question_id` (`FOR SHARE`), rồi chạy thuật toán phân bổ (mục 7.2): lập tập đủ điều kiện, kiểm tra thiếu câu, sắp theo hash, lấy N câu, phân bổ topic. (T2/T3: bước 6–7 là một lời gọi `prepareSeal`; thứ tự đầy đủ của transaction start ở brief T3 §3.M3.)
8. Ghi trong cùng transaction:
   - `attempts`: `status = IN_PROGRESS`, `deadline_at = NULL` (Grammar), `last_active_at = now`, `snapshot_sealed_at = now`, `total_count` (1–100).
   - `attempt_source_topics` (chỉ MIXED): mỗi topic kèm `source_revision_id`.
   - `attempt_question_snapshots` và `attempt_snapshot_options` (mục 6). `option_id` lấy từ envelope.
   - Answer slot rỗng cho mỗi snapshot: `selected_option_id = NULL`, `answered_at = NULL`, `version = 0`.
9. Trả 201.

Nếu thiếu câu, vượt giới hạn, hoặc lỗi giữa chừng: transaction rollback, không ghi gì. Bài đã bị ABANDONED ở bước 4 cũng rollback (nếu không thì giới hạn 5 không đúng).

### 5.2. Lưu đáp án (autosave, CAS)

~~Thứ tự khóa: user → attempt → answer slot (SCHEMA §6).~~ (thay bởi T0) Thứ tự khóa: attempt → answer slot.
- Autosave và submit không khóa `users`.
- Không có deadlock vì không luồng nào khóa `users` sau khi đã giữ `attempts`; start khóa `users` trước (brief T0 §6.4).

1. ~~`verifyAndLockUser`.~~ Khóa attempt `FOR UPDATE`. Khóa answer slot `FOR UPDATE`.
1a. (T0) Kiểm `Idempotency-Key` so với `last_request_id` theo bảng ở brief T0 §5.1 trước bước 5.
2. Không phải chủ bài: 404. Trạng thái không `IN_PROGRESS`: `ATTEMPT_NOT_OPEN`.
3. `snapshotId` thuộc attempt (FK kép): 404 nếu không.
4. `selectedOptionId` (nếu khác null) thuộc đúng snapshot (FK kép `(snapshot_id, option_id)`): `OPTION_NOT_IN_SNAPSHOT`.
5. `UPDATE attempt_answers SET selected_option_id = ?, answered_at = ?, version = version + 1 WHERE attempt_id = ? AND snapshot_id = ? AND version = :expectedVersion`.
   - Không có dòng nào được cập nhật: ~~`STALE_ANSWER_VERSION`~~ `VERSION_CONFLICT` (T0, S8) kèm `currentVersion` và `currentSelectedOptionId`. **Không ghi đè.**
   - Được cập nhật: `attempts.last_active_at = now`.
6. Trả 200 ~~`AnswerView`~~ (thay bởi T4) `{snapshotId, selectedOptionId, version, answeredAt}`. Giao thức đầy đủ (A1–A5, replay idempotent) ở brief T4 §3.M1.

Bỏ chọn là một lần lưu hợp lệ, có tăng `version` (DD). `answered_at` về null khi bỏ chọn.

### 5.3. Nộp bài (idempotent)

Cùng thứ tự khóa.
- Đã SUBMITTED: trả đúng kết quả đã ghi. Không chấm lại.
- ABANDONED: `ATTEMPT_NOT_OPEN`.
- Chấm bằng `answer_key_snapshot.correctOptionId`, không đọc bảng lựa chọn hiện hành:
  - `selected_option_id = correctOptionId`: `is_correct = true`, `score = 1`.
  - Sai hoặc bỏ trống: `is_correct = false`, `score = 0`.
- Ghi attempt trong cùng transaction (DD và `me_submit_attempt`):
  - `correct_count` = số câu `is_correct`
  - `answered_count` = số câu có `selected_option_id`
  - `score` = tổng `score` = `correct_count`
  - `status = SUBMITTED`, `submitted_at = now`, `effective_finished_at = submitted_at`, `submit_reason = 'MANUAL'`
  - `duration_seconds = floor(submitted_at - started_at)`
  - `local_day = (effective_finished_at AT TIME ZONE timezone)::date`, `timezone` mặc định `Asia/Ho_Chi_Minh`
- Sau nộp, `attempt_answers`, snapshot và option thành bất biến (trigger `me_guard_attempt`, `me_guard_attempt_child`).

### 5.4. Resume và xem bài

- Chỉ chủ bài. Người khác nhận 404 (không để lộ sự tồn tại).
- Resume không tạo bài mới và không bốc lại đề. Đọc từ snapshot đã seal (SCHEMA §6).
- Trước nộp: mục 3.4. ~~Sau nộp: thêm kết quả và lời giải, chỉ cho chủ bài.~~ (thay bởi T4 O2) `GET /attempts/{id}` không bao giờ trả kết quả hay lời giải. Sau nộp, kết quả và lời giải chỉ có ở `GET /attempts/{id}/results`, chỉ cho chủ bài.

### 5.5. Abandoned, deadline, scheduler (D12, D14)

**Đã có trong thiết kế và sẽ triển khai ở Giai đoạn 2:**
- Lazy cleanup cho bài untimed không hoạt động 7 ngày (bước 4, mục 5.1). Đây là cơ chế duy nhất chuyển sang ABANDONED.
- Auto-submit lazy cho bài có `deadline_at` đã qua (bước 4, mục 5.1). Code phải tồn tại để không chặn timed về sau, dù Grammar hiện không có bài timed.
- `deadline_at` nullable. Với Grammar, luôn `NULL`.
- `submit_reason` có `MANUAL` và `TIME_LIMIT`.

**Quy tắc phân biệt:**
- Bài **untimed** không hoạt động 7 ngày: ABANDONED.
- Bài **timed**: không bao giờ ABANDONED vì không hoạt động. Chỉ tự nộp khi quá deadline (SCHEMA §6: "timed luôn ưu tiên auto-submit").
- Lazy abandon 7 ngày **không thay** giao thức auto-submit của bài timed.
- Không đổi SUBMITTED thành ABANDONED. Bài ABANDONED không được tiếp tục (trigger `me_guard_attempt`).

**Chưa triển khai (hoãn có chủ đích):**
- Scheduler định kỳ tự nộp bài timed và đánh dấu ABANDONED theo lịch (SCHEMA §6: claim theo deadline index, batch nhỏ, khóa user trước).
- Tạo bài timed (đặt `deadline_at` khi start).

**Tiêu chí chưa đạt khi hoãn scheduler:**
- Bài bỏ dở vẫn hiện `IN_PROGRESS` đến khi người dùng start lại. Giao diện phải dựa trên `lastActiveAt`.
- Bài timed quá hạn mà không ai start lại vẫn nằm `IN_PROGRESS` đến khi có lần start hoặc truy cập tiếp theo. Không đạt yêu cầu "tự động nộp đúng hạn" của bài timed.
- Không ảnh hưởng thống kê (chỉ tính SUBMITTED) và không ảnh hưởng giới hạn 5 bài (đã xử lý lazy).
- **Không được coi việc hoãn scheduler là đáp ứng đầy đủ nghiệp vụ timed.**

**Giới hạn cấu hình:** `practice.max_open = 5`, `practice.abandon_days = 7`, `practice.max_questions = 100` có seed trong `approved-baseline.sql` (dòng 2879–2881). Hiện là hằng trong code, có comment `shortcut:`, nâng cấp sang `system_settings` sau.

## 6. Snapshot, đáp án chuẩn và bảo mật

### 6.1. Envelope publish (SCHEMA §4)

Đã theo envelope này từ A5 (21/11/2026): validator nhận `data` và kiểm theo đúng các trường trên, không trả thêm trường lạ; projection đọc envelope đã lưu, bóc `data`, giữ `options[].id`, lấy đáp án từ `answer_key`. Test: `QuestionSnapshotValidatorTests`, `PracticeIntegrationTests` (fixture envelope, và `republishingTheSameSnapshotKeepsOptionIds`). Bước 2 (21/11/2026): `QUESTION` đã vào `VALIDATED_TYPES`, nên publish đi qua pipeline thật. `question_type_code`, `practice_mode`, `difficulty` bắt buộc khi publish; draft có thể thiếu. Không còn mặc định ngầm. Test: `QuestionPublicationIntegrationTests` (publish hợp lệ, dữ liệu sai, nguồn sai, topic và option id sai, projection lỗi làm rollback).

```json
{
  "schema_version": 1,
  "content_type": "QUESTION",
  "data": {
    "category": "GRAMMAR",
    "part_number": null,
    "question_type_code": "SINGLE_CHOICE",
    "practice_mode": "STANDARD",
    "prompt": "...",
    "group": null,
    "options": [{"id": "<uuid>", "position": 1, "body": "..."}],
    "answer_key": {"correctOptionId": "<uuid>"},
    "explanation": "...",
    "materials": [],
    "topic_ids": ["<uuid>"],
    "word_sense_ids": []
  }
}
```

- `options[].id` là ID ổn định trong revision. Projection giữ ID này, không sinh UUID mới như hiện tại.
- `schema_version` ngoài cột và trong envelope phải khớp. `content_type` phải khớp item (SCHEMA §4).

### 6.2. Cột snapshot

> T0: thay bằng [02 §2–§3](../m05/02-snapshot-json-schemas-v1.md). Các điểm thay đổi so với bảng dưới:
> - Không có `explanation_snapshot` (S6). Lời giải nằm trong `answer_key_snapshot`.
> - `answer_key_snapshot` dùng `correct_option_snapshot_id` và `correct_option_ordinal`.
> - `meta` chỉ giữ `topic_ids` và `word_sense_ids`. Chủ điểm được gán nằm ở cột `topic_id`.
> - Nhóm TOEIC có `public_snapshot` và `private_snapshot`.
> - `attempt_snapshot_options.id` là UUID theo lượt làm (S7).
>
> Bảng dưới giữ lại để truy vết.

| Cột | Nội dung | Hiển thị |
|---|---|---|
| `question_snapshot` (JSONB, object) | `{"schema_version": 1, "public": {"prompt", "questionTypeCode", "practiceMode", "options[{optionId, position, body}]"}, "meta": {"topicIds": [...], "attributedTopicId": "..."}}` | `public` được trả, theo whitelist. `meta` chỉ dùng cho thống kê và không trả ra API. |
| `answer_key_snapshot` (JSONB) | `{"correctOptionId": "..."}` | Riêng tư, chỉ server |
| `explanation_snapshot` (JSONB) | `{"text": "..."}` | Riêng tư, chỉ sau nộp |

- `attempt_snapshot_options` không có `is_correct`.
- Serializer không trả nguyên cột JSON. Chỉ trả trường đã whitelist.

### 6.3. Bất biến

- `me_guard_attempt_child` chặn `INSERT`, `UPDATE`, `DELETE` trên snapshot và option sau khi seal. Ngoại lệ duy nhất là xóa bài ABANDONED quá 30 ngày theo chính sách lưu trữ.
- Sửa câu hỏi sau khi start không ảnh hưởng bài đang làm.
- Start lại với cùng `Idempotency-Key` phải trả đúng bài cũ, kể cả khi nguồn đã đổi hoặc bị archive (SCHEMA §6). Quyền đọc tài liệu được kiểm tra riêng.

## 7. Mixed Grammar và hai loại thống kê (D9)

### 7.1. Ràng buộc từ tài liệu

- SCHEMA §6: `MIXED_GRAMMAR` có ít nhất 2 topic ghim bằng bảng liên kết có FK (`attempt_source_topics`), không dùng JSON.
- SCHEMA §7: không phân bổ một câu nhiều topic thành nhiều câu khi tính tổng toàn hệ thống.
- DD `question_topics` là quan hệ **hiện hành**. Không dùng để thống kê lượt làm cũ, vì chủ điểm của câu có thể đổi sau khi làm bài.

### 7.2. Thuật toán phân bổ topic cho Mixed Grammar (D9)

Thay thế hoàn toàn quy tắc `min(C)` theo UUID. Thuật toán xác định, chạy trong transaction start (mục 5.1), sau khi đã khóa user.

Đầu vào:
- `R`: `requestId` (UUID, viết chữ thường).
- `T`: các chủ điểm yêu cầu, đã khử trùng và sắp theo chuỗi UUID chữ thường tăng dần. Với `GRAMMAR_TOPIC`, `T` chỉ gồm một topic.
- `N`: `questionCount` (bắt buộc, 1–100).
- `topics(q)`: `topic_ids` của revision đã ghim của câu `q`.

Bước 1. Tập đủ điều kiện: `E = { q đủ điều kiện (mục 2.2) : topics(q) ∩ T ≠ ∅ }`. `availableCount = |E|`, đếm số câu phân biệt, không cộng theo topic.
- `|E| = 0`: `NO_ELIGIBLE_QUESTIONS`.
- `0 < |E| < N`: `INSUFFICIENT_QUESTIONS` với `availableCount = |E|`. Không tự giảm `N` (D4).

Bước 2. Thứ tự xác định: `key(q) = SHA-256(R ‖ questionId)` (hex chữ thường). Sắp `E` tăng dần theo `key`. Cùng `R` cho cùng thứ tự. Mỗi request có thứ tự hoán vị riêng.

Bước 3. Chọn: `S` gồm N câu đầu của `E` theo thứ tự bước 2. Mỗi câu một lần.

Bước 4. Phân bổ: khởi tạo `alloc[t] = 0` với mọi `t ∈ T`. Duyệt `S` theo đúng thứ tự bước 2. Với mỗi `q`, đặt `C_q = topics(q) ∩ T` và chọn:
`attributedTopicId(q) = argmin over t in C_q of (alloc[t], t)`. Ưu tiên topic có ít câu đã phân bổ hơn. Hòa thì lấy UUID chuỗi nhỏ hơn. Sau đó `alloc[t] += 1`.

Bất biến phải có (kiểm bằng unit test ở Giai đoạn 2):
- Mỗi câu trong `S` xuất hiện đúng một lần.
- `attributedTopicId(q) ∈ C_q`, nghĩa là thuộc cả `T` lẫn `topics(q)`.
- `Σ alloc[t] = |S| = N`.
- Cùng đầu vào cho cùng kết quả. Không phụ thuộc thứ tự chèn dữ liệu hay thứ tự liệt kê `T`.

Không có đảm bảo cân bằng tuyệt đối. Thuật toán chỉ ưu tiên topic ít câu hơn trong phạm vi câu có thể gán. Mô phỏng ngoài repo, 3000 kịch bản ngẫu nhiên (2411 kế hoạch thành công): không vi phạm bất biến nào. Độ lệch lớn nhất giữa topic nhiều và ít nhất là 5, trung bình 1.42. Độ lệch này do câu chỉ thuộc một topic, không phải lỗi thuật toán. Giao diện phải hiển thị số câu thực tế theo topic.

Snapshot ghi tại thời điểm start: `meta.topicIds = topics(q)` đầy đủ, `meta.attributedTopicId` là kết quả bước 4. Sau seal, bất biến (mục 6.3).

Server kiểm tra trước khi ghi: `attributedTopicId ∈ meta.topicIds` và `attributedTopicId ∈ T`. Vi phạm thì rollback.

Tính nhất quán với revision đã ghim: `topics(q)` lấy từ bản Published hiện hành (join `content_items.published_revision_id = questions.published_revision_id`). Projection thay toàn bộ `question_topics` trong cùng transaction publish, nên khớp với revision vừa xuất bản. Snapshot ghi `source_revision_id` để truy vết. Cần test ở Giai đoạn 2: publish R1 với `{A}`, rồi R2 với `{A, B}`, thì start sau đó dùng `{A, B}`.

### 7.3. Hai loại thống kê, tách bạch

| | **Phân bổ (allocation)** | **Đa nhãn (multi_label, sau này)** |
|---|---|---|
| Mục đích | Kết quả bài mixed theo chủ điểm, đúng với số câu thực tế | Năng lực theo mọi chủ điểm liên quan |
| Đếm câu | Mỗi câu đếm **đúng một lần**, theo `attributedTopicId` | Mỗi câu đếm ở **mọi** topic trong `meta.topicIds` |
| Tổng | Cộng các topic = tổng câu thực tế của bài (và của toàn hệ thống) | **Không cộng được** thành tổng hệ thống. Tổng có thể lớn hơn số câu thực tế |
| Điểm/correct | Mỗi câu một lần, không nhân đôi | Có thể nhân đôi trên các topic. Phải ghi rõ |
| Trạng thái M04 | **Triển khai ở Giai đoạn 2** (`view=allocation`) | **Chưa triển khai.** Chỉ giữ dữ liệu (`meta.topicIds`) |

Quy tắc bắt buộc:
- Mỗi response thống kê có trường `view`. Không bao giờ trả hai loại không nhãn.
- Tổng `questionCount` theo topic của một bài `allocation` bằng `total_count` của bài đó.
- Tổng toàn hệ thống dùng `allocation`. `multi_label` không dùng cho tổng.

### 7.4. Hậu quả chấp nhận được

- Một topic có thể có `questionCount = 0` trong một bài mixed nếu mọi câu của nó được gán sang topic khác. Đây là kết quả đúng theo quy tắc, không phải lỗi. Giao diện phải hiển thị rõ.
- Điểm của bài không đổi. `score = correct_count` (mục 5.3). Tổng `correctCount` theo topic (allocation) bằng `correct_count` của bài.

### 7.5. Kiểm tra khả thi

| Yêu cầu | Đáp ứng bằng snapshot? | Lý do |
|---|---|---|
| Ghim revision | Có | `meta` lấy từ revision đã ghim, không từ `question_topics` hiện hành |
| Không đổi khi admin sửa | Có | Snapshot bất biến sau seal (trigger) |
| JSON hợp lệ | Có | DD: `question_snapshot` là object, tối đa 1 MB |
| Truy vấn theo topic | Có | `question_snapshot->'meta'->>'attributedTopicId'` |
| Không nhân đôi tổng | Có | Mỗi câu một `attributedTopicId` |
| Thống kê đa nhãn sau này | Có | `meta.topicIds` giữ đầy đủ |
| Không cần bảng/cột mới | Có | Không đổi DD |

Trade-off: `topicIds` và `attributedTopicId` không có FK. Bù bằng: server kiểm tra trước khi ghi (mục 7.2), và topic đã được khóa `FOR SHARE` qua `lockPublishedTopics` trước khi chọn câu (mục 5.1, bước 3).

### 7.6. Truy vấn allocation

```sql
SELECT s.question_snapshot->'meta'->>'attributedTopicId' AS topic_id,
       count(DISTINCT a.id)                            AS attempt_count,
       count(*)                                        AS question_count,
       count(*) FILTER (WHERE ans.is_correct)          AS correct_count
FROM attempts a
JOIN attempt_question_snapshots s ON s.attempt_id = a.id
LEFT JOIN attempt_answers ans ON ans.snapshot_id = s.id
WHERE a.user_id = :userId AND a.status = 'SUBMITTED' AND a.category = 'GRAMMAR'
GROUP BY 1;
```

Câu bỏ trống có `is_correct = false` nên tính là sai.

## 8. Mistake lifecycle (D13): HOÃN, CHƯA HOÀN THÀNH

**Trạng thái:** đã triển khai (Slice 3, migration V15): `mistake_records`, `attempt_source_mistakes`, projection lại từ lịch sử đã chấm, nguồn `MISTAKES`. Quyết định D13 đã được đảo: lỗi sai nằm trong phạm vi M04 theo yêu cầu của người dùng. Mục 8.1 bên dưới là lịch sử thiết kế.

### 8.1. Đã hoãn

- `mistake_records`, `attempt_source_mistakes`, `me_refresh_mistakes`, source type `MISTAKES`.
- Vòng đời `OPEN → IMPROVING → RESOLVED` (SCHEMA §6).
- M04.09 phần "tập lỗi sai". Phần "luyện lại toàn bộ chủ điểm" đã có (tạo attempt mới).

### 8.2. Bảo toàn dữ liệu bắt buộc từ Giai đoạn 2

Theo SCHEMA §6, lỗi được tính lại từ lịch sử đã chấm, theo `(effective_finished_at, attempt_id)`. Vì vậy phải giữ:
1. Mỗi `attempt_answers`: `is_correct` và `score` ghi lúc nộp, không tính lại về sau.
2. Snapshot có `question_id`, `source_revision_id`, `answer_key_snapshot`, bất biến.
3. Attempt có `effective_finished_at`, `attempt_id`, `status = SUBMITTED`, `submitted_at`.
4. Không xóa dòng lịch sử. `attempt_answers` chỉ đổi trước nộp.
5. Không tạo bảng hay logic ghi lỗi riêng trong `grammar`.

### 8.3. Phân biệt hai kiểu luyện lại

- **Luyện lại toàn bộ chủ điểm:** attempt mới `GRAMMAR_TOPIC`, `Idempotency-Key` mới. Đã có trong thiết kế.
- **Luyện riêng các câu đã sai:** attempt `MISTAKES` lấy từ `mistake_records`. Hoãn.

### 8.4. Điều kiện để làm sau

- Mục 8.2 đúng trong DB.
- Có migration thêm `mistake_records` và `attempt_source_mistakes`. Không sửa migration cũ.
- Có chính sách với bài tự nộp muộn (`effective_finished_at`) khi bật bài timed.

### 8.5. Phần ghi lỗi sai đã chốt thiết kế ở T5 (chưa triển khai)

Nguồn: [brief T5](../m05/tasks/T5-brief-cross-module-integration-and-listener.md) (duyệt 26/11/2026).
- Migration `V18__create_mistake_records.sql`:
  - `mistake_records` có `UNIQUE (user_id, question_id)`, `status ∈ {OPEN, IMPROVING, RESOLVED}`, `wrong_count > 0`, `last_wrong_at`;
  - `mistake_processed_attempts(attempt_id PK)` làm sổ idempotent;
  - FK sở hữu `(user_id, source_attempt_id) → attempts(user_id, id)`.
- Listener `learning.mistake-records` (`@ApplicationModuleListener`, `id` cố định). Mỗi event một câu SQL: guard `ON CONFLICT DO NOTHING` + bulk upsert. Giao lại không tăng `wrong_count`; kết quả không phụ thuộc thứ tự giao event.
- Chỉ ghi `OPEN` cho câu sai hoặc bỏ trống (kể cả bỏ trống do hết giờ, T5 O2). Chưa có `IMPROVING`/`RESOLVED`, `attempt_source_mistakes`, nguồn `MISTAKES`, API `GET /mistakes`.
- Listener chạy trên executor riêng `moduleEventExecutor` (1–2 thread). Publication `FAILED` được gửi lại mỗi 5 phút, tối đa 10 lần; `COMPLETED` bị xóa sau 7 ngày (`completion-mode=update`, T5 O3).
- Không tạo `AttemptCreationFacade` (T5 O1, giữ T3 O2).

## 9. Idempotency và hash

- Khóa: `(user_id, request_id)`. `request_id` lấy từ `Idempotency-Key` (UUID).
- Hash: SHA-256 hex (64 ký tự) của payload **đã chuẩn hóa**: khóa JSON sắp xếp, UUID chữ thường, mảng topic sắp xếp tăng dần, `questionCount` là số nguyên client gửi (bắt buộc, không có mặc định).
- Hash không gồm `Idempotency-Key` và không gồm thời điểm.
- Cùng key, cùng hash: trả đúng bài cũ, kể cả khi nguồn đã đổi hoặc archive.
- Cùng key, khác hash: `IDEMPOTENCY_CONFLICT`.
- Submit không cần key vì đã idempotent theo trạng thái.
- ~~Autosave không dùng `Idempotency-Key`. Dùng `expectedVersion`.~~ (thay bởi T0, ADR-09)
- Autosave dùng `expectedVersion` (CAS) **và** `Idempotency-Key` lưu ở `attempt_answers.last_request_id`:
  - Trùng key, trùng lựa chọn, `version = expectedVersion + 1`: replay 200.
  - Trùng key nhưng sai trạng thái: `IDEMPOTENCY_CONFLICT`.

## 10. Schema đích theo DD

> T0: schema đích thực thi được thay bằng brief T0 §4.1 (V14+ additive, D8 v2). Các điểm lệch DD đã duyệt nằm ở mục 14.4. Bảng dưới giữ lại để truy vết.

Chỉ liệt kê khác biệt so với V13 hiện tại. Bảng và cột không nêu thì khớp DD.

| Bảng | Đích (theo DD, cộng phần đã chốt) |
|---|---|
| `question_types` | Thêm mới. Chỉ bật `SINGLE_CHOICE`. |
| `questions` | Giữ. `category` cho `VOCABULARY`, `GRAMMAR`, `TOEIC` (cổng D7). |
| `question_options` | `id` ổn định từ envelope. Thêm `UNIQUE (question_id, id)`. |
| `question_topics` | Giữ PK `(question_id, topic_id)`, FK đến `grammar_topics`. Chỉ dùng để chọn câu hiện hành. |
| `attempts` | Thêm `answered_count`, `duration_seconds`, `effective_finished_at`, `submit_reason`, `local_day`, `timezone`, `source_config` (JSONB, allowlist: `category`, `topicIds`, `questionCount`), `source_revision_id`, `snapshot_sealed_at`, `last_active_at`, `deadline_at`, `abandoned_at`. `score = correct_count`. `total_count` 1–100. Status gồm `ABANDONED`. |
| `attempt_source_topics` | Thêm `source_revision_id NOT NULL`. PK `(attempt_id, topic_id)`. |
| `attempt_question_snapshots` | `id` (PK), `attempt_id`, `question_id`, `source_revision_id`, `group_snapshot_id` (nullable, **để trống trong M04**), `category`, `ordinal` (1–100), `question_type_code`, `schema_version`, `grading_policy_version`, `question_snapshot` (JSONB có `meta`), `answer_key_snapshot` (JSONB), `explanation_snapshot` (JSONB). Bỏ cột text `prompt`, `explanation`. |
| `attempt_snapshot_options` | PK `(snapshot_id, option_id)`, `UNIQUE (snapshot_id, position)`, `public_body`. **Bỏ `is_correct`.** |
| `attempt_answers` | `id`, `attempt_id`, `snapshot_id UNIQUE`, `selected_option_id` (nullable), `response_payload` (nullable, để trống), `version BIGINT`, `answered_at` (nullable), `is_correct`, `score`. FK kép `(attempt_id, snapshot_id)` và `(snapshot_id, selected_option_id)`. Quan hệ 0..1 với snapshot (ERD). |
| `lesson_progress` | **Không đổi.** V12 đã khớp DD. |

Ràng buộc theo DD (viết trong SQL ở Giai đoạn 2):
- `CHECK (status='SUBMITTED' AND submitted_at IS NOT NULL AND effective_finished_at IS NOT NULL AND submit_reason IS NOT NULL AND correct_count IS NOT NULL AND answered_count IS NOT NULL AND score IS NOT NULL AND duration_seconds IS NOT NULL AND local_day IS NOT NULL AND abandoned_at IS NULL)`
- `CHECK (status='IN_PROGRESS' AND num_nonnulls(submitted_at, effective_finished_at, submit_reason, correct_count, answered_count, score, duration_seconds, abandoned_at, local_day) = 0)`
- `CHECK (status='ABANDONED' AND abandoned_at IS NOT NULL AND num_nonnulls(submitted_at, effective_finished_at, submit_reason, correct_count, answered_count, score, duration_seconds, local_day) = 0)`
- `CHECK ((selected_option_id IS NULL) = (answered_at IS NULL))` cho `attempt_answers`.

## 11. Migration (D8): không giả định, không sửa khi chưa có bằng chứng

### 11.1. Trạng thái đã biết

- ~~V12 và V13 **chưa có trên `origin/main`** (đã kiểm tra bằng git). Đây là dữ kiện về **nhánh**, không phải về môi trường. Không dùng nó làm bằng chứng chưa áp dụng.~~ (thay bởi T0)
- (T0, 2026-11-26) V12 và V13 **đã có trên `origin/main`**: `git log origin/main -- …/V13__create_practice_engine.sql` trả về `9ea157d`, được merge qua `4afb631` (PR #15). Theo AGENTS.md, migration đã merge vào `main` thì không được sửa. Cây quyết định 11.4 kết thúc ở **Nhánh B** (D8 v2). Q7 vẫn chạy trước khi deploy để ước lượng backfill (S12).
- Trạng thái áp dụng ở **từng** môi trường: **chưa có bằng chứng.**
  - Container PostgreSQL dev trên máy này (`masterenglish-local-postgres-1`) đang dừng.
  - Không có quyền vào DB dùng chung từ phiên này.
- `spring.flyway.out-of-order=true` là cấu hình có sẵn. Không dùng để kết luận tương thích.
- Chưa có danh sách môi trường được xác nhận. Cần người có quyền cung cấp.
- Kiểm kê môi trường (chỉ đọc, không kết nối):
  - Container `masterenglish-local-postgres-1` (postgres:17) và `masterenglish-local-mailpit-1` đều đã dừng (Exited, 2 ngày trước). Không chạy lại.
  - Không có tiến trình nào nghe các cổng PostgreSQL thường gặp (5432, 15432, 5433, 5434, 6543) trên máy này.
  - Worktree này không có `.env`, chỉ có `.env.example`. Repo Infra (chứa compose và `.env.prod`) không có ở `D:/MasterEnglish`.
  - Môi trường được tài liệu nhắc tới: local (profile `local`), production trên EC2 chạy từ `/srv/masterenglish` (`README.md`, mục triển khai), và CI dùng Testcontainers (tạm thời, không phải DB dùng chung).
  - Kết quả: không có môi trường nào truy cập được từ phiên này.
- Line ending của migration:
  - Blob SQL trong git lưu LF (đếm bằng `tr`, 0 ký tự CR).
  - `core.autocrlf=true`. `.gitattributes` không có quy tắc cho `*.sql`.
  - Working copy: V10 và V12 đang có CRLF, V13 hiện là LF.
  - **Đã kiểm (A4, 21/11/2026):** checksum Flyway **không** phụ thuộc line ending. Hai DB Testcontainers, một áp dụng V1–V13 bản LF và một bản CRLF, có cùng checksum từng version. `validate` đều OK khi file là LF, CRLF hoặc nguyên trạng working copy (lẫn CRLF và LF). Đối chứng dương: đổi một dòng trong V13 làm `validate` báo `Migration checksum mismatch for migration version 13`. Thí nghiệm là file test tạm, đã xóa, không commit.
- Phạm vi V12/V13: nằm ở commit `9ea157d` trên nhánh local `feat/m04-grammar`. Không có trên remote branch nào và không có trên `origin/main`.

### 11.2. Quy tắc cố định cho D8

- Không sửa V12, V13. Không tạo V14. Không chạy `flyway migrate`, `repair`, `baseline`. Không đổi cấu hình Flyway.
- Chỉ sau khi có kết quả truy vấn ở mục 11.3 của **mọi** môi trường mới chọn phương án.
- Một file migration đã áp dụng không được sửa. Kiểm tra `checksum` trước khi kết luận.

### 11.3. Truy vấn read-only cần chạy (ở từng môi trường)

Chỉ dùng tài khoản chỉ đọc. Không ghi.

```sql
-- Q1: lịch sử Flyway đầy đủ
SELECT installed_rank, version, description, type, script, checksum,
       installed_on, execution_time, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- Q2: có bản ghi thất bại nào không
SELECT version, description, success
FROM flyway_schema_history
WHERE success = false;

-- Q3: V11, V12, V13 có mặt không
SELECT version, success, checksum
FROM flyway_schema_history
WHERE version IN ('10', '11', '12', '13');

-- Q4: các bảng Practice/Results/lesson đã tồn tại chưa
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('lesson_progress', 'question_types', 'questions',
                     'question_options', 'question_topics', 'attempts',
                     'attempt_source_topics', 'attempt_question_snapshots',
                     'attempt_snapshot_options', 'attempt_answers',
                     'notifications', 'reminder_rules', 'system_settings')
ORDER BY table_name;

-- Q5: cột của attempts và attempt_answers (so với DD)
SELECT table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('attempts', 'attempt_answers')
ORDER BY table_name, ordinal_position;

-- Q6: ràng buộc của attempts và attempt_answers (so với DD)
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN ('attempts'::regclass, 'attempt_answers'::regclass)
ORDER BY 1, 2;

-- Q7: có dữ liệu thật không (quyết định rủi ro khi biến đổi)
SELECT 'attempts' AS t, count(*) FROM attempts
UNION ALL SELECT 'attempt_answers', count(*) FROM attempt_answers
UNION ALL SELECT 'questions', count(*) FROM questions
UNION ALL SELECT 'lesson_progress', count(*) FROM lesson_progress;

-- Q8: giá trị cấu hình practice đã seed chưa (nếu bảng tồn tại)
SELECT key, value FROM system_settings WHERE key LIKE 'practice.%';
```

Nếu một bảng không tồn tại, Q5, Q6, Q7 sẽ báo lỗi. Đó là kết quả hợp lệ, không phải lỗi truy vấn.

### 11.4. Cây quyết định

| Kết quả đọc được | Hành động |
|---|---|
| Q2 có dòng `success = false` | **Dừng.** Cần quy trình sửa lỗi Flyway riêng. Không tự xử lý. |
| Q1 không có `12` và `13` ở mọi môi trường | Xem xét **Nhánh A** (mục 11.5). Cần người dùng duyệt. |
| Q1 có `13` `success = true` ở ít nhất một môi trường | **Nhánh B** (mục 11.6). Không sửa V13. |
| Q1 có `12`, không có `13` ở đâu | Không sửa V12. V13 chưa áp dụng ở đâu nên có thể xem xét sửa, **nhưng cần duyệt riêng** (nhánh A′). |
| `checksum` của `12` hoặc `13` khác file trong repo | **Dừng.** File đã áp dụng không được sửa. |
| Q3 có `11` ở môi trường | Ghi nhận thứ tự V11/V12/V13. Không suy ra tương thích từ out-of-order. |
| Q4 cho thấy `attempts` đã có nhưng không khớp DD | Dừng. Không suy đoán. Phân tích trước khi chọn nhánh. |

### 11.5. Nhánh A (chưa áp dụng ở đâu, cần duyệt)

- Giữ V12 (đã khớp DD).
- Viết lại V13 theo mục 10.
- Kiểm thử: DB trống chạy V1 đến V13 (đã có `contextLoads`).

### 11.6. Nhánh B (đã áp dụng ở ít nhất một môi trường)

- Không sửa V12, V13.
- Thêm `V14__practice_results_expand.sql`, theo kiểu expand:
  - Cột mới: `ADD COLUMN` với mặc định hoặc nullable.
  - Ràng buộc mới: `NOT VALID`, sau đó `VALIDATE` sau khi backfill.
  - Bảng mới (`question_types`, các bảng `attempt_*` đích) tạo song song, chưa xóa bảng cũ.
  - Backfill theo batch có checkpoint.
- Xóa cột cũ là giai đoạn contract, cần kế hoạch riêng (MIGRATION_PLAN).

### 11.7. Kiểm thử nâng cấp (bắt buộc, cả hai nhánh)

Theo mẫu `VocabularyUpgradeTests` (`Flyway.configure().target(...)`):
1. Migrate đến V10 (đã có `grammar_topics`, `grammar_lessons`).
2. **Chèn dữ liệu grammar sau khi bảng đã tồn tại.**
3. Migrate tiếp đến latest.
4. Kiểm tra dữ liệu không đổi và ràng buộc mới hợp lệ.

Bản sao có dữ liệu thật: không có quyền từ phiên này. Dùng bộ dữ liệu mẫu ẩn danh trong test, và ghi đây là giới hạn.

## 12. Tái sử dụng cho Vocabulary và TOEIC (D7, D16)

| Điểm mở rộng | Hiện tại | Điều kiện để bật |
|---|---|---|
| `category` | Schema cho phép 3 giá trị | Có validator, renderer, grader cho category đó |
| `question_types.enabled` | Chỉ `SINGLE_CHOICE` | Có renderer và grader cho loại đó |
| `grading_policy_version` | `SINGLE_CHOICE_V1` | Thêm policy vào registry, không thêm engine |
| `source_type` | `GRAMMAR_TOPIC`, `MIXED_GRAMMAR` | `DECK` cho VOCABULARY; `PRACTICE_SET`, `MISTAKES` sau |
| `practice_mode` | `STANDARD`, `FILL_OPTION` | Theo SCHEMA §5 |
| TOEIC group | Chưa làm | `attempt_group_snapshots` và `group_snapshot_id` đã có chỗ (nullable) |
| `attempt_media_refs` | Chưa làm | Theo SCHEMA §6 |

Nguyên tắc: không thêm grader riêng cho từng category. Chỉ thêm policy trong cùng registry.

## 13. Rà soát nhất quán

| # | Mục | Kết quả | Ghi chú |
|---|---|---|---|
| 1 | Ranh giới `practice`, `results` | Nhất quán với DD | Mục 1 |
| 2 | Ownership bảng | Nhất quán với DD và MIGRATION_PLAN ("Questions/Practice" và "Attempts/Results" là hai nhóm) | Mục 1 |
| 3 | API request và response | Nhất quán. Endpoint chờ FE | Mục 3 |
| 4 | Error contract | Nhất quán với ProblemDetail hiện có. Có điều kiện tương thích ngược và regression | Mục 4 |
| 5 | Start và resume | Nhất quán với SCHEMA §6. Resume không tạo bài mới | Mục 5.1, 5.4 |
| 6 | Idempotency | Nhất quán với DD về ngữ nghĩa. Lệch transport với vocabulary | Mục 9, 14.2 |
| 7 | CAS autosave | Nhất quán với SCHEMA §6 và `me_save_answer` | Mục 5.2 |
| 8 | Snapshot sealing | Nhất quán với trigger tham chiếu | Mục 6.3 |
| 9 | Answer key security | Nhất quán: không trả key trước nộp, whitelist | Mục 6.2 |
| 10 | Submit và scoring | Nhất quán với `me_submit_attempt` và DD | Mục 5.3 |
| 11 | Mixed Grammar | Nhất quán với SCHEMA §6 (FK). Thuật toán D9 xác định, đã mô phỏng, server kiểm tra trước khi ghi | Mục 7.2 |
| 12 | Abandoned và deadline | Lazy khớp SCHEMA §6. Timed không bị lazy 7 ngày. Scheduler hoãn có tiêu chí | Mục 5.5 |
| 13 | Statistics | Hai loại tách bạch, có `view` | Mục 7.3 |
| 14 | Flyway | Không giả định. Có truy vấn và cây quyết định | Mục 11 |
| 15 | Tái sử dụng Vocabulary, TOEIC | Có điểm mở rộng, không thêm engine | Mục 12 |
| 16 | AGENTS: không sửa migration đã áp dụng, `ddl-auto=validate`, ArchitectureTests khi đổi dependency | Nhất quán. Có trong checklist | Mục 15 |
| 17 | MIGRATION_PLAN: không mở endpoint khi thiếu validation/grader/quyền | Nhất quán. Endpoint chỉ mở khi có đủ | Mục 15, bước 4.8 |
| 18 | ERD: `ATTEMPT_QUESTION_SNAPSHOTS → ATTEMPT_ANSWERS` là 0..1 | Nhất quán với `snapshot_id UNIQUE` | Mục 10 |
| 19 | APPROVED: 1–100, tối đa 5, abandon 7 ngày, seed `practice.*` | Nhất quán | Mục 5.5 |

Không có mâu thuẫn nội bộ còn lại. Các điểm chờ là phụ thuộc dữ liệu (Flyway), xác nhận (FE, FE review, D12), hoặc quyết định của người dùng (quy tắc gán, duyệt `common/error`).

## 14. Sai lệch với approved baseline

### 14.1. Cố ý hoãn có tiêu chí

- Scheduler và bài timed (mục 5.5).
- Mistake lifecycle và luyện lỗi sai (mục 8).
- `practice_sets` (D6).
- TOEIC group và media (mục 12).
- `system_settings`: giá trị hiện là hằng trong code, có `shortcut:`.

### 14.2. Lệch cần quyết định

- **Idempotency transport:** DD ghi "Idempotency-Key" và contract này chọn header. `vocabulary/CONTRACT.md` dùng `reviewRequestId` trong body. Hai quy ước cùng tồn tại. Cần thống nhất (FE hoặc người phụ trách vocabulary). Không sửa vocabulary trong lượt này.
- **Thống kê mixed:** dùng `meta` trong JSON, không có cột mới (mục 7). Lệch về nơi lưu, không lệch về nghĩa.
- **Quy tắc phân bổ topic** (D9): thuật toán xác định theo hash request, ưu tiên topic ít câu hơn. Không có trong tài liệu chuẩn. Người dùng đã chấp thuận thay thế quy tắc `min(C)`.

### 14.4. Lệch DD đã duyệt ở T0 (2026-11-26)

- `attempt_snapshot_options` giữ `id` UUID theo lượt làm (V13) thay cho PK `(snapshot_id, option_id)`. ID gốc lưu ở `source_option_id` (S7).
- Không có `explanation_snapshot` (S6).
- `attempt_answers` giữ PK V13 `(attempt_id, question_position)`. Không có `score` theo câu, vì `is_correct` đã đủ.
- `attempts`:
  - Thêm `attempt_mode`, `time_limit_seconds`, `scoring_version`, `listening_correct`, `reading_correct`.
  - Chưa thêm `local_day`, `timezone`; hai cột này thêm cùng D13.
  - `total_count` 1–200.
- `group_materials`, `question_materials`:
  - `material_type` có 6 giá trị.
  - `body` là JSONB.
  - `visibility` là cột GENERATED từ `usage_role`.
  - `document_key NOT NULL`.
- `practice_sets`: TOEIC cho phép `part_number IS NULL` (S9).
- `attempt_group_snapshots` chỉ cho TOEIC ở MVP.
- Bổ sung T1 (§4.8):
  - `attempts.request_id` là nơi lưu `Idempotency-Key` của `POST /attempts`. `attempt_answers.last_request_id` lưu key của lần `PUT` gần nhất.
  - Cột `snapshot_id` trên `attempt_snapshot_options` và `attempt_answers`.
  - Cột `option_order` trên snapshot câu.
  - Cột P2 `prompt_speaker` và `prompt_transcript`; `question_options.speaker`.
  - **Không** có cột ước tính 990 (AD-1, duyệt 26/11/2026): quy đổi 990 thuộc hàng rào YAGNI. DD có thể chừa cột, nhưng V14 không thêm.
- Bổ sung T5 (`mistake_records`, so với DD `approved-baseline.sql:848-866`):
  - Đổi tên cột: `state` → `status`, `consecutive_correct` → `consecutive_correct_count`.
  - Thêm cột: `last_processed_attempt_id`, `created_at`.
  - Chưa có (đợi phần còn lại của D13, migration additive): `source_snapshot_id`, `last_evaluated_at`, `resolved_at`, `UNIQUE (user_id, id)`, `ix_mistakes_user_state`.
  - `DATA_DICTIONARY.md` vẫn xếp `mistake_records` vào nhóm Results. Theo S3, bảng thuộc `learning`. Không sửa `docs/database/**`.

### 14.3. Chưa đúng trong code hiện tại (không phải thiết kế)

Việc của Giai đoạn 2:
- `score` tính theo phần trăm. Phải là `correct_count`.
- Không có `version` và CAS ở lưu đáp án.
- Không có answer slot rỗng khi start.
- Không tách `answer_key_snapshot`. `is_correct` nằm trong `attempt_snapshot_options`.
- Không cho bỏ chọn.
- Không có giới hạn 5 bài, không có lazy cleanup, không có `ABANDONED`.
- Giới hạn 50 câu thay vì 100. `questionCount` có mặc định 10 (phải bỏ).
- Thiếu cột DD khi nộp (`answered_count`, `duration_seconds`, `effective_finished_at`, `submit_reason`, `local_day`, `source_config`).
- ~~Option id sinh ngẫu nhiên trong projection.~~ Đã sửa ở A5.
- ~~Envelope validator dùng tên khác envelope đã duyệt.~~ Đã sửa ở A5.
- `category` hard-code `GRAMMAR`.
- `attempts` nằm trong `practice`, không phải `results`.
- Không có test đồng thời. Đã có cho lesson progress (A3, `LessonProgressConcurrencyTests`). Chưa có cho practice (B4).
- Test hiện khóa cứng điểm theo phần trăm (`PracticeIntegrationTests`, các dòng assert `score` và `accuracyPercent`).
- Không có `attributedTopicId`, `meta` trong snapshot.

## 15. Checklist Giai đoạn 2 (thứ tự bắt buộc)

Thứ tự: xác minh migration → thiết kế schema thực thi → tách ownership → sửa nghiệp vụ → kiểm thử. **Không bắt đầu bước sau khi bước trước chưa đạt tiêu chí.**

### Bước 0. Cổng (chỉ chặn công việc phụ thuộc schema)
- [x] Quy tắc phân bổ D9 đã chốt và đã mô phỏng (mục 7.2).
- [x] D12 và common/error đã duyệt.
- [ ] Q1–Q8 từ mọi môi trường: chưa thực hiện được (mục 11.1).
- [ ] Quyết định nhánh A, A′ hoặc B: chờ Q1–Q8.
- [x] Thí nghiệm checksum với LF và CRLF trên DB tạm: đã làm (A4, mục 11.1).
- Tiêu chí: các ô trên được tick trước khi chạm schema. Công việc không phụ thuộc schema không bị cổng này chặn.

### Bước 1. Xác minh migration (không ghi DB)
- [ ] Đối chiếu checksum V10, V12, V13 trong repo với Q1 (nếu có).
- [ ] Xác định các bảng đã có (Q4) và các cột/ràng buộc khác DD (Q5, Q6).
- [ ] Ghi số dòng (Q7). Nếu có dữ liệu thật trong `attempts`, không được biến đổi trực tiếp: phải dùng nhánh B.
- Tiêu chí: có biên bản đối chiếu cho từng môi trường.

### Bước 2. Thiết kế schema thực thi
- [ ] Viết SQL đích theo mục 10 (nhánh A: sửa V13; nhánh B: V14). Chưa commit.
- [ ] Đối chiếu từng ràng buộc với DD và `approved-baseline.sql`.
- [ ] Đối chiếu ERD (mục 10) với SQL.
- [ ] Kiểm tra `ddl-auto=validate`: mọi entity khớp schema.
- Tiêu chí: bảng đối chiếu DD có 100% cột và ràng buộc được đánh dấu.

### Bước 3. Tách ownership
- [ ] Tạo `modules/results/` với `package-info.java` (`allowedDependencies`: `common::events`, `common::error`, `common::security`).
- [ ] Chuyển entity, repository, service, controller của attempt sang `results`.
- [ ] Tạo `common/events/PracticeEvents.java` (A6: đã có facade, chưa có handler) và handler trong `practice`.
- [ ] Sửa `QuestionProjectionListener` gọi `PracticeService`, không gọi repository (ARCHITECTURE).
- [ ] Cập nhật `ArchitectureTests` để có `results`, `practice`, `grammar`, `learning`.
- Tiêu chí: `ArchitectureTests` và `LayerArchitectureTests` xanh. Không có import chéo vào repository hay entity.

### Bước 4. Sửa nghiệp vụ
Theo thứ tự phụ thuộc:
- [x] 4.1 `common/error`: thêm `details`, mã mới, handler. Đáp ứng mục 4.2 và 4.5 (A1).
- [x] 4.2 Envelope validator và projection: theo SCHEMA §4. Option id ổn định (A5).
- [ ] 4.3 `attributedTopicId` và `meta` trong `selectQuestions` và snapshot (mục 7.2).
- [ ] 4.4 Start: idempotency, lazy cleanup, giới hạn 5, `NO_ELIGIBLE` và `INSUFFICIENT`, khóa theo thứ tự ID, answer slot rỗng (mục 5.1).
- [ ] 4.5 Autosave: CAS, bỏ chọn, `ATTEMPT_NOT_OPEN`, `OPTION_NOT_IN_SNAPSHOT` (mục 5.2).
- [ ] 4.6 Submit: `answer_key_snapshot.correctOptionId`, `score = correct_count`, các cột DD khi nộp (mục 5.3).
- [ ] 4.7 Thống kê allocation (mục 7.6), `view=allocation`.
- [ ] 4.8 Gate endpoint: chỉ mở khi có validator, grader, quyền (MIGRATION_PLAN).
- Tiêu chí: mỗi mục có test (bước 5) xanh.

### Bước 5. Kiểm thử
- [ ] Unit: chấm điểm, CAS, giới hạn, quy tắc gán, lazy cleanup theo nhánh untimed và timed.
- [ ] Integration **không** `@Transactional` cho đồng thời: autosave cùng `expectedVersion`; autosave cũ đến sau; autosave đồng thời với submit; hai start cùng key; start đồng thời khi có 4 bài đang mở (một thành công, một `OPEN_ATTEMPT_LIMIT_REACHED`).
- [ ] Integration: thiếu câu (0 và 0 < n < requested) trả đúng mã và metadata.
- [ ] Integration: bài untimed quá 7 ngày được dọn trước khi đếm; bài SUBMITTED không bao giờ bị đổi.
- [ ] Integration: bài timed quá hạn tự nộp (TIME_LIMIT), không bị lazy 7 ngày.
- [ ] Hồi quy `common/error` (mục 4.5), đặc biệt `ErrorHandlingTests`.
- [ ] Migration: `VocabularyUpgradeTests` theo mẫu (mục 11.7).
- [ ] Sửa các test đang khóa cứng điểm theo phần trăm.
- [ ] `spotlessCheck`, full suite.
- Tiêu chí: full suite xanh; không có test "PASS" giả (test chưa chạy phải ghi là chưa chạy).

### Bước 6. Tài liệu và cổng FE
- [ ] Cập nhật `docs/grammar/CONTRACT.md` (mục 3, 4.4, 8) để trỏ tới contract này. Không sửa nội dung nghiệp vụ đã chốt.
- [ ] Đối chiếu FE với mục 3. Gỡ "PENDING FE REVIEW" khi FE xác nhận.
- Tiêu chí: FE đã xác nhận bằng văn bản hoặc endpoint giữ trạng thái PENDING.

## 16. Điều kiện đang chặn việc sửa code

**Chặn công việc phụ thuộc schema (Nhóm B):**
1. Chưa có bằng chứng Flyway từ bất kỳ môi trường nào (Q1–Q8). Không có môi trường nào truy cập được từ phiên này (mục 11.1).
2. Đã giải quyết ở A4: checksum Flyway không phụ thuộc line ending (mục 11.1).

**Không chặn công việc độc lập với schema (Nhóm A):** xem báo cáo, mục 5.

**Không chặn nhưng cần theo dõi:**
3. FE review (D15, mục 3). Endpoint giữ PENDING.
4. Quy ước idempotency giữa attempt và vocabulary (mục 14.2) chưa thống nhất.
5. Chi phí lazy cleanup khi có nhiều bài bỏ dở. Cần đo trước khi đóng Giai đoạn 2.
6. Chưa có test API start và resume cho backend (`checks/README.md`). Phải có ở Giai đoạn 2.
7. ~~`QUESTION` chưa nằm trong `VALIDATED_TYPES`~~: đã xử lý ở bước 2 (21/11/2026), được người phụ trách duyệt. Publish câu hỏi đi qua pipeline thật và được kiểm bằng `QuestionPublicationIntegrationTests`.

## 17. Trạng thái thiết kế

~~**DESIGN READY WITH BLOCKERS.**~~ (thay bởi T0)

**DESIGN READY** (2026-11-26).
- Blocker Flyway đã được gỡ bằng D8 v2 (V13 đã lên `main`, nên đi theo Nhánh B).
- Còn theo dõi: FE review (D15), quy ước idempotency với vocabulary (14.2), benchmark start Full Test (ADR-13).

Đã chốt: ranh giới, ownership, sự kiện, API, lỗi, giao thức transaction, snapshot, mixed và thuật toán D9, hai loại thống kê, mistake (hoãn có điều kiện), lazy abandon và deadline, schema đích, cây quyết định migration, tái sử dụng, checklist.

Còn chặn: chỉ công việc phụ thuộc schema (Nhóm B), do thiếu bằng chứng Flyway và chưa kiểm chứng checksum với line ending. Không có lỗi thiết kế nào còn lại trong file.

Không sửa Java, migration, Frontend, baseline. Không chạy migration. Không commit, push, merge. Không bắt đầu Giai đoạn 2.

## 18. Ghi nhận triển khai (Slice 2)

Migration `V14__practice_engine_alignment.sql`: dựng lại các bảng attempt của V13 theo cấu trúc Approved Baseline v3.0 (phạm vi grammar); tạo `question_types` (SINGLE_CHOICE bật), và các bảng rỗng `question_groups`, `practice_sets`, `set_items`, `attempt_group_snapshots` để giữ FK nullable cho M05. V1..V13 không sửa, không có V11. Dữ liệu attempt của V13 bị bỏ (đã được duyệt cho môi trường local).

Endpoint đang dùng:
- `POST /api/v1/attempts` (header `Idempotency-Key`; body `source` và `questionCount` bắt buộc). Trả 201 khi tạo mới, 200 khi replay.
- `GET /api/v1/attempts/{id}`; `PUT /api/v1/attempts/{id}/answers/{snapshotId}` (`selectedOptionId` hoặc null, `expectedVersion`); `POST /api/v1/attempts/{id}/submit`.
- `GET /api/v1/attempts/statistics/topics?category=GRAMMAR&view=allocation` (chỉ allocation; cờ điểm yếu thuộc Slice 3).
- `GET /api/v1/grammar/topics/{id}/exercises`.
- Các đường dẫn cũ `POST /api/v1/grammar/topics/{id}/attempts`, `POST /api/v1/grammar/attempts/mixed`, `GET /api/v1/grammar/statistics/topics` và `PUT /api/v1/attempts/{id}/answers/{position}` được giữ lại như adapter theo hợp đồng của `main` (hợp nhất 27/11/2026). Adapter cắt số câu theo kho câu có sẵn và trả 404 khi kho rỗng; endpoint mới `POST /api/v1/attempts` vẫn trả 409 theo D9. Route `{snapshotId}` nhận UUID, route `{position}` nhận số.

Mã lỗi mới: `IDEMPOTENCY_CONFLICT` (409), `OPEN_ATTEMPT_LIMIT_REACHED` (409; `openAttemptCount`, `maxOpenAttempts`), `STALE_ANSWER_VERSION` (409; `currentVersion`, `currentSelectedOptionId`), `ATTEMPT_NOT_OPEN` (409; `attemptStatus`), `OPTION_NOT_IN_SNAPSHOT` (400). Khóa `attemptStatus` (không dùng `status`) vì `status` là thành viên dành riêng của ProblemDetail. Giá trị `details` nay được kiểm tra theo khóa: đếm không âm, `currentSelectedOptionId` là UUID hoặc null, `attemptStatus` là tên enum.

Điểm khác với bản thiết kế khi triển khai:
- `score = correct_count` (1 điểm mỗi câu đúng); `accuracyPercent` là trường dẫn xuất trong phản hồi sau nộp.
- Đáp án đúng và lời giải nằm trong `answer_key_snapshot` và `explanation_snapshot` (JSONB riêng); `attempt_snapshot_options` không có `is_correct`. Trước nộp, phản hồi có `correctOptionId`, `explanation`, `isCorrect` đều null.
- Topic được gán (`attributedTopicId`) và `topicIds` nằm trong `question_snapshot` (JSON công khai), vì baseline không có cột topic trên snapshot.
- Chọn câu dùng `SelectionPlanner` (D9): lock `FOR SHARE` theo id, thứ tự theo hash của requestId, thiếu câu thì báo `NO_ELIGIBLE_QUESTIONS` / `INSUFFICIENT_QUESTIONS`, không cắt.
- Trần 5 bài mở và thời gian abandon 7 ngày là hằng số trong code. Bảng `system_settings` chưa được tạo, nên chưa đọc cấu hình từ DB.
- Chưa áp dụng các trigger `guard_*`/`*_complete` và các hàm `me_*` của baseline; ràng buộc được service thực thi. Cần bổ sung ở M05.
- Mistake lifecycle (M04.09) và thống kê điểm yếu (M04.08) thuộc Slice 3.
- Facade mới trong `common/events`: `GrammarEvents.findPublishedTopic(topicId)`.

### 18.1. Ghi nhận triển khai (Slice 3)

Migration `V15__mistake_lifecycle.sql`: `mistake_records` và `attempt_source_mistakes` theo DATA_DICTIONARY (đủ UNIQUE, CHECK trạng thái, FK kép). V1..V14 không sửa.

Vòng đời lỗi sai: khi một lượt chuyển sang `SUBMITTED` (kể cả `TIME_LIMIT`), với mỗi câu của lượt đó, projection được tính lại từ toàn bộ lịch sử đã chấm của user với câu đó, sắp theo `(effective_finished_at, attempt_id)` (SQL). Hàm gập thuần `MistakeProjection` không phụ thuộc đồng hồ. Vì tính lại từ lịch sử, nộp muộn không làm sai kết quả và retry không cộng thêm gì. Blank tính là sai.

Endpoint mới:
- `GET /api/v1/mistakes?state=&topicId=&page=&size=` (sắp `lastWrongAt` giảm dần, tie-break `id`). Đường dẫn này không có trong contract gốc; cần FE duyệt.
- `POST /api/v1/attempts` với `source.type = MISTAKES` và `source.mistakeIds` (1..100 id, phải thuộc về learner, không thì 404). Chỉ chọn câu còn hợp lệ (đúng revision, loại câu bật, không archive/revoke). Thiếu thì `NO_ELIGIBLE_QUESTIONS` / `INSUFFICIENT_QUESTIONS` kèm số khả dụng. Các lỗi sai được chọn được ghim vào `attempt_source_mistakes`.
- `GET /api/v1/attempts/statistics/summary?category=GRAMMAR&view=allocation`: tổng toàn hệ thống, mỗi câu đếm đúng một lần. Đường dẫn này không có trong contract gốc.

Thống kê theo topic (`view=allocation`) thêm: `view`, `sampleSize`, `windowCorrectCount`, `windowAccuracyPercent`, `weak`, `windowDays`, `windowStart`. Cửa sổ là 30 ngày theo `effective_finished_at`. Điểm yếu: ít nhất 20 câu đã chấm trong cửa sổ và tỷ lệ đúng dưới 70% (so sánh số nguyên, `70.0%` không yếu). Các ngưỡng là hằng số trong `WeakTopicPolicy`, chưa đọc từ `system_settings`.

Điểm chưa làm, cần duyệt:
- View `multi_label` (đếm trùng câu đa topic theo từng topic) vẫn bị hoãn theo mục 7.3. Yêu cầu của Slice 3 muốn đếm trùng theo topic, nên đây là điểm xung đột. Phần đã làm theo `allocation`: mỗi câu đếm đúng một lần theo `attributedTopicId`, và tổng toàn hệ thống không cộng trùng.
- Hỗ trợ lọc thống kê theo khoảng thời gian: không có trong contract, chưa làm; cửa sổ cố định 30 ngày.
