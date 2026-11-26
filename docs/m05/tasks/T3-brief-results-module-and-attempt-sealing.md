# T3 — Implementation Brief: Module `results`, R8 `BusinessException`, sealing bulk insert và anti-leak

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026. O1–O4 theo đề xuất: giữ tên mã lỗi đã duyệt (thêm `activeAttemptIds`); không tạo facade mở lượt; gỡ hẳn 3 endpoint grammar; lượt V13 giữ `score` theo %. Phần lưu đáp án, nộp bài, event và kết quả được chi tiết hóa và **sửa** ở [brief T4](T4-brief-autosave-grading-and-events.md). Khi hai brief khác nhau, T4 thắng. Riêng 3.M2 cũng bị T4 sửa (O1 của T4): khóa metadata của `ATTEMPT_NOT_OPEN` là `attemptStatus`, không phải `status`. **Rà soát chéo T0–T7:**<br>• §11 `AttemptGetTests.submittedReturnsResultShape` bị thay bằng T4 `AttemptGetTests.submittedReturnsActiveShapeOnly` (T4 O2): `GET /attempts/{id}` không trả kết quả ở mọi trạng thái.<br>• §5.1 thêm DTO `RefResponse(materialKey, anchor)` cho `refs[]` và `review.evidence[]` (AD-2).<br>• Bảng round-trip §7 cho GET kết quả và submit được thay bởi T4 (`/results` ≤ 5, submit ≤ 3).<br>• Bật nén gzip cho JSON ≥ 2 KB (AD-3, §7). |
| Ngày | 26/11/2026 |
| Phụ thuộc | T0, T1 (V14/V15, seed), T2 (`PracticeEvents.prepareSeal`, `ContentEvents.describeMedia`/`mediaPlaybackLocation`, V16). Tất cả đã duyệt. |
| Chế độ viết brief | READ-ONLY. Mọi file đích do task triển khai T3 tạo/sửa. |

Nhãn: `[CREATE]`, `[MODIFY]`, `[DELETE]`, `[DO NOT TOUCH]`.

---

## 1. Metadata & Traceability

### 1.1. Sản phẩm

1. `@ApplicationModule` mới `results`, nhận toàn bộ `/api/v1/attempts/**`.
2. Gỡ code attempt V13 khỏi `practice` và gỡ 3 endpoint grammar cũ (R5).
3. R8: metadata có kiểu trong `BusinessException`/`ErrorCode`, tương thích ngược.
4. Start (`POST /api/v1/attempts`): lazy cleanup, trần 5 lượt, idempotency chống race, sealing ≤ 7 câu ghi.
5. `GET /api/v1/attempts/{attemptId}` theo hai kiểu response tách biệt, chỉ `SELECT` cột public khi chưa nộp.
6. Vì gỡ code V13 cũng gỡ luôn đường lưu đáp án và nộp bài, T3 phải hiện thực lại theo schema mới:
   - `PUT …/answers/{snapshotId}` (CAS + idempotency);
   - `POST …/submit` (chấm + event);
   - `GET …/media/{mediaAssetId}` (redirect, hợp đồng T2);
   - `GET /api/v1/attempts/statistics/topics` (port thống kê grammar).
7. Migration `V17__results_finalize_snapshots.sql`, thực hiện hợp đồng bàn giao T1 §3.M1.

### 1.2. Traceability

| Mã | Nội dung |
|---|---|
| M05.03 | Start, sealing |
| M05.04, M05.07 | GET lượt đang làm, `savedAnswers` |
| M05.06 | Lưu đáp án CAS |
| M05.08, M05.09, M05.18 | Nộp, chấm `SINGLE_CHOICE_V1` |
| M05.10 | Kết quả sau nộp |
| R1, S6, S7 | Cột public/private; lựa chọn theo lượt |
| R2, S2 | Ba trạng thái |
| R3 | `attempt_mode` (MVP chỉ `PRACTICE`) |
| R4 | Cleanup 7 ngày, trần 5 |
| R5 | URI về `results`; gỡ endpoint cũ |
| R8 | Metadata có kiểu |
| ADR-07..13 | Toàn bộ |

### 1.3. Explicitly Out-of-Scope

- Listener `learning` cho `AttemptSubmitted` và `mistake_records` (D13).
- Thống kê theo Part, lịch sử `GET /api/v1/attempts` (M06).
- Frontend (T6). Benchmark và chốt SLA start (T7).
- `AttemptCreationFacade` (3.M1, câu hỏi O2).
- Exam Mode, endpoint `abandon`, các mục YAGNI.

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc

| Nhóm | File |
|---|---|
| `common/error` | `BusinessException.java` (toàn văn), `ErrorCode.java` (toàn văn), `GlobalExceptionHandler.java` (dòng 30, 74–100, 103–170, 195–207) |
| `practice` (attempt V13) | `controller/AttemptController.java`, `controller/GrammarPracticeController.java`, `service/PracticeService.java`, `service/impl/PracticeServiceImpl.java` (dòng 48–49, 135–160, 197–242, 279–280, 357–365), `entity/{Attempt,AttemptAnswer,AttemptQuestionSnapshot,AttemptSnapshotOption,AttemptSourceTopic}.java`, `repository/AttemptRepository.java:76` |
| Test V13 | `PracticeIntegrationTests.java` (endpoint dòng 85–383), `GrammarPracticeControllerIntegrationTests.java:107` (chỉ `exercises`) |
| Kiến trúc | `ArchitectureTests.java`, `LayerArchitectureTests.java`, `META-INF/spring.factories`, `docs/ARCHITECTURE.md:30-40`, `infrastructure/persistence/{content,vocabulary}/*` |
| Frontend | `masterenglish-web@616e217` nhánh `dev`: 0 tham chiếu tới `/attempts`, `practice-sets`, `/grammar/(topics/*/attempts\|attempts/mixed\|statistics)` |
| Tài liệu | Brief T0, T1 (đã sửa §4.7), T2; `01`, `02`, `CONTRACT.md` |

### 2.2. Drift

| ID | Đề T3 | Repo / hợp đồng đã duyệt | Giải quyết |
|---|---|---|---|
| T3-D01 | `BulkSnapshotJdbcRepository`, `JdbcTemplate.batchUpdate`, `reWriteBatchedInserts` | `LayerArchitectureTests:117-124` cấm `org.springframework.jdbc`, `java.sql`, `javax.sql` ở code main (T2-D01). Repo đã có pattern fragment + `EntityManager` (`spring.factories`). | Fragment `AttemptSnapshotWriteOperations`, implementation `infrastructure.persistence.results.JpaAttemptSnapshotWriter`, `INSERT … SELECT FROM unnest(...)`: **một câu lệnh mỗi bảng** (3.M5). `reWriteBatchedInserts` không cần. |
| T3-D02 | `MAX_ACTIVE_ATTEMPTS_REACHED (maxAllowed, activeCount, activeAttemptIds)` | CONTRACT §4.3 (đã duyệt T0): `OPEN_ATTEMPT_LIMIT_REACHED (openAttemptCount, maxOpenAttempts)` | Giữ tên và khóa đã duyệt, **thêm** `activeAttemptIds` (UUID_LIST) theo đề T3. Câu hỏi O1. |
| T3-D03 | `INSUFFICIENT_QUESTIONS (requested, available)` | `ErrorCode.java:22` và `SelectionPlanner` dùng `requestedCount`, `availableCount`, có test hiện hành | Giữ khóa cũ. Đổi tên sẽ phá yêu cầu "tương thích ngược 100%" của chính đề T3. |
| T3-D04 | `IDEMPOTENCY_KEY_CONFLICT` | CONTRACT §4.3: `IDEMPOTENCY_CONFLICT` | Giữ `IDEMPOTENCY_CONFLICT` (O1) |
| T3-D05 | `404 ATTEMPT_NOT_FOUND` | Repo dùng `RESOURCE_NOT_FOUND` cho mọi 404 (`ErrorCode.java:10`, CONTRACT §4.3) | `RESOURCE_NOT_FOUND`, `detail = "Attempt not found."` |
| T3-D06 | `submit_reason = 'IDLE_7D_ABANDONED'`, `'DEADLINE_EXPIRED'` | V14 `attempts_c24`: `submit_reason IN ('MANUAL','TIME_LIMIT')`. `attempts_c28`/DD: lượt ABANDONED có `submit_reason` NULL, dùng `abandoned_at`. | ABANDONED: `abandoned_at = now`, `submit_reason` NULL. Tự nộp khi quá hạn: `TIME_LIMIT`. |
| T3-D07 | `UNIQUE (user_id, idempotency_key)` | V13 `attempts_c2 UNIQUE (user_id, request_id)`; T1-D03 đã chốt `request_id` là nơi lưu `Idempotency-Key` | Dùng `request_id` |
| T3-D08 | `attempt_snapshot_options.option_text`, `ordinal` | V13: `body`, `position` (T1-D04) | Dùng `body`, `position`. API trả `body`. |
| T3-D09 | `AttemptCreationFacade` cho `vocabulary`, `grammar` | Không module nào khởi tạo lượt làm. Frontend gọi `POST /api/v1/attempts` với `source.type`. T2 O4 đã duyệt nguyên tắc "không để API chết trong `common`". | Không tạo ở T3. Chữ ký dự phòng ở 5.3. Câu hỏi O2. |
| T3-D10 | Gọi facade media trước khi mở transaction ghi | T2 (đã duyệt): `prepareSeal` chạy `MANDATORY` trong transaction start, khóa `FOR SHARE`, và đọc metadata media bên trong | Giữ T2. Đọc media ngoài transaction rồi kiểm lại bên trong sẽ là TOCTOU, và tốn thêm một lần đọc (3.M10). |
| T3-D11 | Xáo lựa chọn có kiểm soát khi `SHUFFLE` | V14: `option_order IN ('FIXED','SHUFFLED')`; TOEIC luôn `FIXED`; seed và Grammar hiện đều `FIXED` | Hiện thực xáo xác định cho `SHUFFLED` (3.M5). Tên giá trị là `SHUFFLED`. |
| T3-D12 | Tách `ActiveAttemptResponse` / `AttemptResultResponse`; `savedAnswers` dạng map | 02 §8: một `AttemptView`, `selectedOptionId`/`answerVersion` nằm trong từng câu | Theo đề T3. Cập nhật 02 §8 (4.10). |
| T3-D13 | — | Test V13 `PracticeIntegrationTests` gọi `/api/v1/grammar/topics/{id}/attempts`, `/grammar/attempts/mixed`, `/grammar/statistics/topics`, `PUT …/answers/{position}`. T1 `M05SeedIntegrationTests.grammarSeedWorksWithExistingEndpoint` gọi endpoint cũ. | Viết lại test theo API mới (11). Frontend không phụ thuộc (2.1). |
| T3-D14 | — | `MissingRequestHeaderException` được `ResponseEntityExceptionHandler` xử lý, rồi `handleExceptionInternal` đổi 4xx thành `VALIDATION_ERROR` (dòng 151–168) | Header `Idempotency-Key` khai báo `required = false`. Service kiểm và ném `BusinessException(VALIDATION_ERROR, "Idempotency-Key header must be a UUID.")`, để `detail` rõ ràng. |
| T3-D15 | — | Hợp đồng bàn giao T1 §3.M1 (re-backfill, slot rỗng, `NOT NULL`, trigger) | V17 (4.1) |

---

## 3. Mandatory Design Resolutions

### M1. Module `results` và dọn URI (R5)

- **Package** `com.masterenglish.api.modules.results`:

  ```java
  @org.springframework.modulith.ApplicationModule(
          id = "results",
          displayName = "Results (attempts, snapshots, grading)",
          allowedDependencies = {"common::events", "common::error", "common::security", "common::api", "common::pagination"})
  package com.masterenglish.api.modules.results;
  ```

  Các package con chỉ gồm `controller`, `dto.request`, `dto.response`, `entity`, `repository`, `repository.fragment`, `service`, `service.impl` (rule `businessModulesUseOnlyMvcPackages`).

- **Gỡ khỏi `practice`** `[DELETE]`:
  - `controller/AttemptController.java`;
  - các method attempt trong `GrammarPracticeController` (giữ `GET /topics/{topicId}/exercises`);
  - `entity/{Attempt,AttemptAnswer,AttemptQuestionSnapshot,AttemptSnapshotOption,AttemptSourceTopic}.java`;
  - `repository/{AttemptRepository,AttemptAnswerRepository,AttemptQuestionSnapshotRepository,AttemptSnapshotOptionRepository,AttemptSourceTopicRepository}.java`;
  - `dto/request/{AnswerRequest,StartAttemptRequest,MixedAttemptRequest}.java`;
  - `dto/response/{AttemptResponse,StartAttemptResult,TopicStatisticsResponse}.java`.

  `PracticeService` chỉ còn `exercises(UUID topicId)`. `PracticeServiceImpl` chỉ còn phần đó.

- **3 endpoint grammar cũ** (`POST /api/v1/grammar/topics/{topicId}/attempts`, `POST /api/v1/grammar/attempts/mixed`, `GET /api/v1/grammar/statistics/topics`): **gỡ hẳn**, không để `@Deprecated`. Lý do: frontend không gọi (2.1), và các test gọi chúng được viết lại trong T3. Thay thế:
  - `POST /api/v1/attempts` với `source.type` = `GRAMMAR_TOPIC` hoặc `MIXED_GRAMMAR`;
  - `GET /api/v1/attempts/statistics/topics?category=GRAMMAR&view=allocation`.

- **`AttemptCreationFacade`: không tạo** (T3-D09). Lý do:
  - không module nào cần khởi tạo lượt làm;
  - bài test từ vựng M03.12 và quiz ngữ pháp M04.10 đều do frontend mở qua `POST /api/v1/attempts` (`PRACTICE_SET` hoặc `GRAMMAR_TOPIC`).
  Khi có module cần, thêm `ResultsEvents.startAttempt` theo chữ ký dự phòng ở 5.3.

### M2. R8: metadata có kiểu

`common/error/DetailType.java` `[CREATE]`:

```java
public enum DetailType { COUNT, UUID_OR_NULL, ENUM, UUID_LIST }
```

`common/error/DetailKey.java` `[CREATE]`:

```java
public record DetailKey(String name, DetailType type) {
    public static DetailKey count(String name) { return new DetailKey(name, DetailType.COUNT); }
    public static DetailKey uuidOrNull(String name) { return new DetailKey(name, DetailType.UUID_OR_NULL); }
    public static DetailKey enumValue(String name) { return new DetailKey(name, DetailType.ENUM); }
    public static DetailKey uuidList(String name) { return new DetailKey(name, DetailType.UUID_LIST); }
}
```

`ErrorCode` `[MODIFY]`:
- Constructor đổi thành `ErrorCode(HttpStatus httpStatus, DetailKey... detailKeys)`, lưu `Map<String, DetailType>`.
- Giữ `allowsDetail(String)`. Thêm `DetailType detailType(String key)` (ném `IllegalArgumentException` khi khóa không đăng ký).
- Hằng (thêm vào cuối, giữ nguyên các hằng cũ):

```java
NO_ELIGIBLE_QUESTIONS(HttpStatus.CONFLICT, DetailKey.count("requestedCount"), DetailKey.count("availableCount")),
INSUFFICIENT_QUESTIONS(HttpStatus.CONFLICT, DetailKey.count("requestedCount"), DetailKey.count("availableCount")),
IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
OPEN_ATTEMPT_LIMIT_REACHED(HttpStatus.CONFLICT, DetailKey.count("openAttemptCount"), DetailKey.count("maxOpenAttempts"),
        DetailKey.uuidList("activeAttemptIds")),
VERSION_CONFLICT(HttpStatus.CONFLICT, DetailKey.count("currentVersion"), DetailKey.uuidOrNull("currentSelectedOptionId")),
ATTEMPT_NOT_OPEN(HttpStatus.CONFLICT, DetailKey.enumValue("status")),
OPTION_NOT_IN_SNAPSHOT(HttpStatus.BAD_REQUEST);
```

`BusinessException.copyDetails` `[MODIFY]`. Mỗi khóa phải nằm trong allowlist, rồi kiểm theo kiểu:

| `DetailType` | Giá trị hợp lệ | Lưu |
|---|---|---|
| `COUNT` | `Integer` hoặc `Long` ≥ 0 | nguyên |
| `UUID_OR_NULL` | `null` hoặc `java.util.UUID` | nguyên |
| `ENUM` | `Enum<?>` (lưu `name()`), hoặc `String` khớp `^[A-Z][A-Z_]{0,31}$` | `String` |
| `UUID_LIST` | `List<?>` có 0–10 phần tử, mọi phần tử là `UUID` khác null | `List.copyOf` |

- Sai kiểu hoặc khóa lạ → `IllegalArgumentException`. Đây là lỗi lập trình, không bao giờ tới client.
- **Không có kiểu chuỗi tự do**, để chặn rò rỉ thông điệp nội bộ.
- Map được copy vào `LinkedHashMap` (cho phép value null) rồi bọc `Collections.unmodifiableMap`.
- Caller cần value null phải dùng `HashMap`, vì `Map.of` không nhận null.

Tương thích ngược:
- Hai constructor của `BusinessException` giữ nguyên chữ ký.
- Mọi chỗ gọi cũ (`VALIDATION_ERROR` không có detail; `NO_ELIGIBLE`/`INSUFFICIENT` với `Integer`) cho cùng body.
- `GlobalExceptionHandler.handleBusiness` **không đổi** (`exception.getDetails().forEach(problem::setProperty)`).

Rủi ro chưa kiểm chứng: khi `currentSelectedOptionId = null`, mixin Jackson của `ProblemDetail` có thể bỏ khóa có giá trị null.
- Test `GlobalErrorDetailsTests.nullUuidSerializesAsJsonNull` quyết định.
- Nếu khóa bị bỏ, `handleBusiness` thay `null` bằng `com.fasterxml.jackson.databind.node.NullNode.getInstance()` cho khóa `UUID_OR_NULL`.

### M3. R4: cleanup và trần 5 lượt

Thứ tự trong transaction start (sau khi khóa user và kiểm replay, 3.M4):

1. **Abandon lazy**, một câu lệnh:

   ```sql
   UPDATE attempts
   SET status = 'ABANDONED', abandoned_at = :now, updated_at = :now
   WHERE user_id = :userId AND status = 'IN_PROGRESS' AND deadline_at IS NULL
     AND last_active_at <= :now - INTERVAL '7 days'
   ```

2. **Lượt mở**, một câu lệnh:

   ```sql
   SELECT id, deadline_at FROM attempts WHERE user_id = :userId AND status = 'IN_PROGRESS' ORDER BY id FOR UPDATE
   ```

3. Với mỗi lượt có `deadline_at <= :now`: chấm theo 4.6 với `submit_reason = 'TIME_LIMIT'`, `effective_finished_at = deadline_at`, rồi phát event.
   - MVP không tạo lượt có giờ (R3), nên nhánh này chỉ chạy qua fixture test.
   - Trên đường start, nhánh này **không** ném `ATTEMPT_NOT_OPEN`.
4. `openIds` = các lượt còn lại. Nếu `openIds.size() >= 5`, ném:

   ```java
   new BusinessException(ErrorCode.OPEN_ATTEMPT_LIMIT_REACHED,
       "Too many attempts in progress. Submit an open attempt to start a new one.",
       Map.of("openAttemptCount", openIds.size(), "maxOpenAttempts", 5,
              "activeAttemptIds", openIds.subList(0, Math.min(10, openIds.size()))))
   ```

   `activeAttemptIds` sắp theo `last_active_at DESC` (bài vừa làm nằm đầu), tối đa 10. Vì trần là 5, danh sách luôn ≤ 5.

- Chống race: mọi start của cùng user tuần tự hóa nhờ `IdentityEvents.verifyAndLockUser` (khóa dòng `users`), nên đếm và chèn không thể chen nhau.
- Hằng: `ResultsLimits.MAX_OPEN_ATTEMPTS = 5`, `ABANDON_AFTER = Duration.ofDays(7)`, kèm comment `shortcut: hằng trong code, chuyển sang system_settings khi M09 có màn hình cấu hình`.

### M4. Idempotency cho `POST /api/v1/attempts`

- **Header** `Idempotency-Key` bắt buộc, là UUID (T3-D14). Giá trị lưu ở `attempts.request_id` (đổi về chữ thường).
- **`request_hash`** = SHA-256 hex của JSON chuẩn hóa:
  - Khóa sắp tăng dần, UUID chữ thường, `topicIds` sắp tăng dần, `mode` luôn có (áp mặc định `PRACTICE`).
  - Ví dụ: `{"mode":"PRACTICE","source":{"practiceSetId":"…","type":"PRACTICE_SET"}}`, hoặc `{"mode":"PRACTICE","questionCount":10,"source":{"topicId":"…","type":"GRAMMAR_TOPIC"}}`.
  - Dựng bằng `TreeMap` lồng nhau, serialize bằng `ObjectMapper` với `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`.

| Nhánh | Điều kiện | Kết quả |
|---|---|---|
| Tạo mới | Không có dòng `(user_id, request_id)` | 201, header `Location: /api/v1/attempts/{id}`, body `ActiveAttemptResponse` |
| Replay | Có dòng, cùng `request_hash` | 200, `ActiveAttemptResponse`, `AttemptResultResponse` hoặc lượt ABANDONED (theo trạng thái hiện tại). Kiểm **trước** cleanup và trần, để retry không bị 409 giả. |
| Xung đột | Có dòng, khác `request_hash` | 409 `IDEMPOTENCY_CONFLICT` |
| Đồng thời | Hai request cùng key | Request sau chờ khóa user, rồi rơi vào nhánh replay |

Phòng thủ khi khóa user bị bỏ qua do bug:
- `AttemptServiceImpl.start` **không** có `@Transactional`. Nó chạy `transactions.execute(s -> doStart(...))`, dùng `TransactionTemplate` theo tiền lệ đăng ký trong `ARCHITECTURE.md:32-33`.
- Bắt `DataIntegrityViolationException` khi `NestedExceptionUtils.getMostSpecificCause(e).getMessage()` chứa `attempts_c2`.
- Khi đó chạy `transactions.execute(s -> replay(userId, key, hash))` trong transaction mới, trả 200 hoặc `IDEMPOTENCY_CONFLICT`. **Không bao giờ trả 500** cho trường hợp này.

### M5. Bulk insert ≤ 7 round-trip ghi

Fragment `results/repository/fragment/AttemptSnapshotWriteOperations.java` `[CREATE]`:

```java
public interface AttemptSnapshotWriteOperations {
    void insertAttempt(NewAttemptRow attempt);
    void insertSourceTopics(UUID attemptId, List<UUID> topicIds);           // chỉ MIXED_GRAMMAR
    void insertGroupSnapshots(UUID attemptId, List<GroupSnapshotRow> rows);
    void insertQuestionSnapshots(UUID attemptId, List<QuestionSnapshotRow> rows);
    void insertOptions(UUID attemptId, List<OptionRow> rows);
    void insertEmptyAnswerSlots(UUID attemptId, Instant now);
    void insertMediaRefs(UUID attemptId, Set<UUID> mediaAssetIds);
}
```

Các record `*Row` nằm trong cùng file fragment, bất biến.

- Implementation `infrastructure/persistence/results/JpaAttemptSnapshotWriter.java` `[CREATE]`, dùng `EntityManager.createNativeQuery(...).setParameter(...)` với tham số mảng (`UUID[]`, `Integer[]`, `String[]`).
- Đăng ký trong `META-INF/spring.factories`:

  ```
  com.masterenglish.api.modules.results.repository.fragment.AttemptSnapshotWriteOperations=com.masterenglish.api.infrastructure.persistence.results.JpaAttemptSnapshotWriter
  ```

- `AttemptRepository extends JpaRepository<Attempt, UUID>, AttemptSnapshotWriteOperations`.

| # | Câu lệnh | Dòng (Full Test) |
|---|---|---|
| W1 | `INSERT INTO attempts (…) VALUES (…)` | 1 |
| W2 | `INSERT INTO attempt_source_topics SELECT :a, t FROM unnest(CAST(:topicIds AS uuid[])) t` (chỉ MIXED) | 0 |
| W3 | `INSERT INTO attempt_group_snapshots … FROM unnest(…)` | 42 |
| W4 | `INSERT INTO attempt_question_snapshots … FROM unnest(…)` | 200 |
| W5 | `INSERT INTO attempt_snapshot_options … FROM unnest(…)` | 775 |
| W6 | `INSERT INTO attempt_answers … SELECT … FROM attempt_question_snapshots WHERE attempt_id = :a` | 200 |
| W7 | `INSERT INTO attempt_media_refs … FROM unnest(CAST(:ids AS uuid[])) ON CONFLICT DO NOTHING` | ≈ 60 |

Tổng: **6 câu ghi** (7 với MIXED_GRAMMAR), thỏa yêu cầu ≤ 10. SQL đầy đủ ở 4.4.

Xáo lựa chọn (`option_order = SHUFFLED`, không áp dụng cho TOEIC):
- Sắp lựa chọn theo `SHA-256(requestId ‖ questionId ‖ sourceOptionId)` tăng dần, rồi đánh lại `position` 1..n và `label`.
- `correct_option_ordinal` lấy theo vị trí mới.
- Kết quả xác định: replay cho cùng thứ tự.

### M6. Chống rò rỉ ở tầng SQL

- Đọc lượt `IN_PROGRESS`/`ABANDONED` qua 5 truy vấn projection (4.5 R1–R5). **Không** truy vấn nào có `private_snapshot`, `answer_key_snapshot`, `attempt_snapshot_options.is_correct`, `source_option_id`, hay `attempt_answers.is_correct`.
- `question_snapshot` được bóc ngay trong SQL: `question_snapshot - 'meta' - 'legacy_backfill'`.
- Truy vấn có cột private (4.5 R6–R7) nằm trong method riêng, tên có hậu tố `ForResult`, và chỉ được gọi khi `status = 'SUBMITTED'`.
- **Không đọc entity snapshot.** `results` không map entity cho các bảng snapshot, nên không có đường `findById` nạp cột private vào bộ nhớ. Entity duy nhất là `Attempt`, map các cột header không có dữ liệu đáp án.
- **Hai kiểu response tách biệt:**
  - `ActiveAttemptResponse` không có trường nào chứa đáp án hay lời giải.
  - `AttemptResultResponse` có thêm `result`, `review`, `reviewMaterials`.
  - Cả hai implement `sealed interface AttemptView` (trong `dto.response`); controller trả `AttemptView`.
  - Hai kiểu được dựng bởi hai mapper khác nhau (`ActiveAttemptMapper`, `AttemptResultMapper`). Mapper active không nhận tham số chứa dữ liệu private.
- `body` của lựa chọn P1/P2 là `NULL` từ lúc seal (T2: `optionDisplay = LABEL_ONLY`). Không có dữ liệu thì không thể lộ.

### M7. `GET /api/v1/attempts/{attemptId}`

| Trạng thái lượt | Response |
|---|---|
| Không tồn tại hoặc không phải chủ | 404 `RESOURCE_NOT_FOUND` (truy vấn header lọc `user_id`, không tiết lộ sự tồn tại) |
| `IN_PROGRESS` | 200 `ActiveAttemptResponse` (`status = IN_PROGRESS`) |
| `ABANDONED` | 200 `ActiveAttemptResponse` (`status = ABANDONED`, chỉ đọc). Lưu đáp án → 409 `ATTEMPT_NOT_OPEN`. Không có kết quả hay đáp án. |
| `SUBMITTED` | 200 `AttemptResultResponse` |
| Lượt có giờ quá hạn, chưa được dọn | Tự nộp `TIME_LIMIT` trong cùng transaction (MVP không có), rồi trả `AttemptResultResponse` |

`savedAnswers`: `Map<UUID snapshotId, SavedAnswer(UUID selectedOptionId, int version)>`.
- Có **mọi** snapshot, kể cả câu chưa chọn (`selectedOptionId = null`, `version = 0`).
- Nhờ vậy frontend khôi phục `expectedVersion` sau F5 mà không phải đoán.

### M8. V17: hoàn tất bàn giao T1

Xem 4.1. Thực hiện đủ 6 điểm của T1 §3.M1. V17 chạy cùng đợt deploy với code T3. Sau T3 không còn code ghi dòng định dạng V13.

### M9. Lưu đáp án, nộp bài, media, thống kê

Đây là hệ quả của M1: gỡ code V13 cũng gỡ các đường này. Chi tiết theo T0 §5.1/4.5 (đã duyệt), chuyển sang schema thật ở 4.6–4.8.

### M10. Media đọc trong transaction start

- `prepareSeal` (T2) gọi `ContentEvents.describeMedia` một lần (1 SELECT) trong transaction start, sau khi đã khóa `FOR SHARE` nguồn.
- Đọc trước ở transaction khác không giảm thời gian giữ kết nối đáng kể (1 câu lệnh). Ngược lại, nó tạo khe TOCTOU (media bị đổi trạng thái giữa hai lần đọc), nên phải kiểm lại bên trong.
- **Chọn:** một lần đọc bên trong (T3-D10).

---

## 4. Thiết kế chi tiết

### 4.1. `V17__results_finalize_snapshots.sql` `[CREATE]`

```sql
-- V17: Hoàn tất hợp đồng bàn giao T1 §3.M1. Chạy cùng đợt deploy với code T3.

-- 1. Chạy lại NGUYÊN VĂN khối 0–5 của V15 (sao chép từ V15__practice_backfill_v13_attempts.sql),
--    để bắt dòng do code V13 ghi trong cửa sổ T1→T3.

-- 2. Slot rỗng cho câu V13 chưa có attempt_answers
INSERT INTO attempt_answers (attempt_id, question_position, snapshot_id, selected_option_id, answered_at,
                             version, is_correct, last_request_id, updated_at)
SELECT s.attempt_id, s.position, s.id, NULL, NULL, 0,
       CASE WHEN a.status = 'SUBMITTED' THEN FALSE ELSE NULL END, NULL, CURRENT_TIMESTAMP
FROM attempt_question_snapshots s
JOIN attempts a ON a.id = s.attempt_id
WHERE NOT EXISTS (SELECT 1 FROM attempt_answers aa
                  WHERE aa.attempt_id = s.attempt_id AND aa.question_position = s.position);

-- 3. Đáp án chỉ còn trong answer_key_snapshot
UPDATE attempt_snapshot_options SET is_correct = NULL WHERE is_correct IS NOT NULL;

-- 4. NOT NULL
ALTER TABLE attempt_question_snapshots
    ALTER COLUMN schema_version SET NOT NULL,
    ALTER COLUMN question_snapshot SET NOT NULL,
    ALTER COLUMN answer_key_snapshot SET NOT NULL,
    ALTER COLUMN display_number SET NOT NULL,
    ALTER COLUMN category SET NOT NULL,
    ALTER COLUMN grading_policy_version SET NOT NULL,
    ALTER COLUMN option_order SET NOT NULL;
ALTER TABLE attempt_snapshot_options
    ALTER COLUMN snapshot_id SET NOT NULL,
    ALTER COLUMN label SET NOT NULL;
ALTER TABLE attempt_answers
    ALTER COLUMN snapshot_id SET NOT NULL;

-- 5. Bỏ vế "dòng V13"; khóa is_correct của lựa chọn
ALTER TABLE attempt_question_snapshots
    DROP CONSTRAINT attempt_question_snapshots_c10,
    ADD CONSTRAINT attempt_question_snapshots_c14 CHECK (schema_version = 1
        AND ((prompt IS NULL AND explanation IS NULL AND source_revision_id IS NOT NULL)
             OR (question_snapshot->>'legacy_backfill') = 'true'));
ALTER TABLE attempt_snapshot_options
    ADD CONSTRAINT attempt_snapshot_options_c9 CHECK (is_correct IS NULL);

-- 6. Snapshot append-only (hàm của V7)
CREATE TRIGGER attempt_group_snapshots_append_only BEFORE UPDATE OR DELETE ON attempt_group_snapshots
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();
CREATE TRIGGER attempt_question_snapshots_append_only BEFORE UPDATE OR DELETE ON attempt_question_snapshots
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();
CREATE TRIGGER attempt_snapshot_options_append_only BEFORE UPDATE OR DELETE ON attempt_snapshot_options
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();
```

Thứ tự bắt buộc: bước 1 (dựng `answer_key_snapshot` từ `is_correct`) phải chạy **trước** bước 3 (xóa `is_correct`).

### 4.2. Cấu trúc `results`

| Đường dẫn (`modules/results/…`) | Loại | Nội dung |
|---|---|---|
| `package-info.java` | CREATE | 3.M1 |
| `entity/Attempt.java` | CREATE | Map `attempts`: `id`, `userId`, `category`, `sourceType`, `status`, `totalCount`, `attemptMode`, `startedAt`, `lastActiveAt`, `deadlineAt`, `submittedAt`, `correctCount`, `version` (`@Version long`), `updatedAt`. Chỉ đọc (`@Immutable` của Hibernate); mọi thay đổi trạng thái đi qua `@Modifying` native query. |
| `repository/AttemptRepository.java` | CREATE | `@Repository`, `extends JpaRepository<Attempt, UUID>, AttemptSnapshotWriteOperations`. Mọi truy vấn 4.3–4.8. |
| `repository/fragment/AttemptSnapshotWriteOperations.java` | CREATE | 3.M5 |
| `service/AttemptService.java` | CREATE | Chữ ký ở 4.9 |
| `service/AttemptStatisticsService.java` | CREATE | `List<TopicStatisticsResponse> topics(UUID userId, String category, String view)` |
| `service/impl/AttemptServiceImpl.java` | CREATE | Điều phối start/get/save/submit/media |
| `service/impl/AttemptStatisticsServiceImpl.java` | CREATE | `@Transactional(readOnly = true)` |
| `service/impl/RequestHasher.java` | CREATE | `final class`, SHA-256 JSON chuẩn hóa |
| `service/impl/SnapshotBuilder.java` | CREATE | `final class`, `SealSource` → `*Row` + JSON theo 02 §6 |
| `service/impl/Grader.java` | CREATE | `final class`, tổng hợp kết quả từ projection R8/R9 |
| `service/impl/ActiveAttemptMapper.java`, `AttemptResultMapper.java` | CREATE | 3.M6 |
| `service/impl/ResultsLimits.java` | CREATE | Hằng 3.M3 |
| `controller/AttemptController.java` | CREATE | 5.1 |
| `controller/AttemptStatisticsController.java` | CREATE | 5.1 |
| `dto/request/StartAttemptRequest.java` | CREATE | `record StartAttemptRequest(@NotNull @Valid SourceRequest source, Integer questionCount, String mode)` |
| `dto/request/SourceRequest.java` | CREATE | `record SourceRequest(@NotNull String type, UUID practiceSetId, UUID topicId, List<UUID> topicIds)` |
| `dto/request/SaveAnswerRequest.java` | CREATE | `record SaveAnswerRequest(UUID selectedOptionId, @NotNull @PositiveOrZero Integer expectedVersion)` |
| `dto/response/*Response.java` | CREATE | 5.1 |

Hạ tầng và `common`:

| Đường dẫn | Loại | Nội dung |
|---|---|---|
| `infrastructure/persistence/results/JpaAttemptSnapshotWriter.java` | CREATE | 4.4 |
| `src/main/resources/META-INF/spring.factories` | MODIFY | Thêm một dòng (3.M5) |
| `common/error/{DetailType,DetailKey}.java` | CREATE | 3.M2 |
| `common/error/{ErrorCode,BusinessException}.java` | MODIFY | 3.M2 |
| `common/events/ResultsEvents.java` | CREATE | 5.3 |

### 4.3. Luồng start (một transaction qua `TransactionTemplate`)

| Bước | Hành động | Câu SQL |
|---|---|---|
| 1 | Kiểm body và header ngoài transaction (400) | 0 |
| 2 | `IdentityEvents.verifyAndLockUser(userId)` | 1 |
| 3 | `findByUserIdAndRequestId` (`SELECT id, request_hash, status FROM attempts WHERE user_id = ? AND request_id = ?`) → replay hoặc 409 | 1 |
| 4 | Abandon lazy (3.M3 bước 1) | 1 |
| 5 | Lượt mở `FOR UPDATE` (3.M3 bước 2); tự nộp lượt quá hạn (MVP: 0); kiểm trần | 1 |
| 6 | `PracticeEvents.prepareSeal(requestId, request)` (T2) | 5 (FIXED) / 3 (`TopicSeal`) |
| 7 | `SnapshotBuilder` dựng các `Row` trong bộ nhớ | 0 |
| 8 | W1–W7 | 6–7 |
| 9 | Dựng `ActiveAttemptResponse` từ bộ nhớ (không đọc lại DB) | 0 |

Tổng Full Test FIXED: 1 + 1 + 1 + 1 + 5 + 6 = **15 câu SQL**, trong đó ghi 6. Đây là ước lượng; T7 đo.

### 4.4. SQL ghi (`JpaAttemptSnapshotWriter`)

```sql
-- W1
INSERT INTO attempts (id, user_id, category, source_type, source_topic_id, source_set_id, source_revision_id,
    source_config, request_id, request_hash, status, total_count, attempt_mode, scoring_version,
    snapshot_sealed_at, started_at, last_active_at, deadline_at, time_limit_seconds, version, updated_at)
VALUES (:id, :userId, :category, :sourceType, :sourceTopicId, :sourceSetId, :sourceRevisionId,
    CAST(:sourceConfig AS jsonb), :requestId, :requestHash, 'IN_PROGRESS', :totalCount, 'PRACTICE', 2,
    :now, :now, :now, NULL, NULL, 0, :now);

-- W3
INSERT INTO attempt_group_snapshots (id, attempt_id, source_group_id, source_revision_id, ordinal, schema_version,
    category, part_number, expected_question_count, public_snapshot, private_snapshot, created_at)
SELECT u.id, :attemptId, u.group_id, u.revision_id, u.ordinal, 1, 'TOEIC', u.part_number, u.expected,
       CAST(u.public_json AS jsonb), CAST(u.private_json AS jsonb), :now
FROM unnest(CAST(:ids AS uuid[]), CAST(:groupIds AS uuid[]), CAST(:revisionIds AS uuid[]), CAST(:ordinals AS int[]),
            CAST(:partNumbers AS int[]), CAST(:expected AS int[]), CAST(:publicJson AS text[]), CAST(:privateJson AS text[]))
     AS u(id, group_id, revision_id, ordinal, part_number, expected, public_json, private_json);

-- W4
INSERT INTO attempt_question_snapshots (attempt_id, position, id, question_id, topic_id, question_type_code, practice_mode,
    prompt, explanation, group_snapshot_id, source_revision_id, category, part_number, display_number, option_order,
    schema_version, grading_policy_version, question_snapshot, answer_key_snapshot)
SELECT :attemptId, u.position, u.id, u.question_id, u.topic_id, 'SINGLE_CHOICE', u.practice_mode,
       NULL, NULL, u.group_snapshot_id, u.revision_id, u.category, u.part_number, u.position, u.option_order,
       1, 'SINGLE_CHOICE_V1', CAST(u.question_json AS jsonb), CAST(u.key_json AS jsonb)
FROM unnest(CAST(:positions AS int[]), CAST(:ids AS uuid[]), CAST(:questionIds AS uuid[]), CAST(:topicIds AS uuid[]),
            CAST(:practiceModes AS text[]), CAST(:groupSnapshotIds AS uuid[]), CAST(:revisionIds AS uuid[]),
            CAST(:categories AS text[]), CAST(:partNumbers AS int[]), CAST(:optionOrders AS text[]),
            CAST(:questionJson AS text[]), CAST(:keyJson AS text[]))
     AS u(position, id, question_id, topic_id, practice_mode, group_snapshot_id, revision_id, category, part_number,
          option_order, question_json, key_json);

-- W5
INSERT INTO attempt_snapshot_options (id, attempt_id, question_position, position, body, is_correct, snapshot_id, label,
    source_option_id)
SELECT u.id, :attemptId, u.question_position, u.position, u.body, NULL, u.snapshot_id, chr(64 + u.position),
       u.source_option_id
FROM unnest(CAST(:ids AS uuid[]), CAST(:questionPositions AS int[]), CAST(:positions AS int[]), CAST(:bodies AS text[]),
            CAST(:snapshotIds AS uuid[]), CAST(:sourceOptionIds AS uuid[]))
     AS u(id, question_position, position, body, snapshot_id, source_option_id);

-- W6
INSERT INTO attempt_answers (attempt_id, question_position, snapshot_id, selected_option_id, answered_at, version,
    is_correct, last_request_id, updated_at)
SELECT s.attempt_id, s.position, s.id, NULL, NULL, 0, NULL, NULL, :now
FROM attempt_question_snapshots s WHERE s.attempt_id = :attemptId;

-- W7
INSERT INTO attempt_media_refs (attempt_id, media_asset_id)
SELECT :attemptId, m FROM unnest(CAST(:mediaIds AS uuid[])) m
ON CONFLICT DO NOTHING;
```

- `display_number = position` (T2 đánh số 1..N).
- `topic_id` NULL cho TOEIC/VOCABULARY; với GRAMMAR là `attributedTopicId`.
- `private_json` là `NULL` khi nhóm không có học liệu `AFTER_SUBMIT` (V14 `c10`).
- Mảng truyền dạng `UUID[]`, `Integer[]`, `String[]` của Java. Hibernate 6+ bind thành mảng PostgreSQL.
- Chưa chạy trong phiên. `SnapshotWriterTests.fullTestUsesSixStatements` xác nhận. Nếu bind mảng thất bại, fallback là `CAST(:x AS text)::uuid[]` với chuỗi `{…}`.

### 4.5. SQL đọc (`AttemptRepository`, native, projection interface)

| # | Method | SQL (rút gọn đúng cột) | Dùng khi |
|---|---|---|---|
| R1 | `findHeader(attemptId, userId)` | `SELECT id, category, source_type, source_set_id, source_topic_id, status, attempt_mode, total_count, started_at, last_active_at, deadline_at, submitted_at FROM attempts WHERE id = :attemptId AND user_id = :userId` | Mọi trạng thái |
| R2 | `findPublicGroups(attemptId)` | `SELECT id, ordinal, part_number, public_snapshot::text AS public_json FROM attempt_group_snapshots WHERE attempt_id = :a ORDER BY ordinal` | Mọi trạng thái |
| R3 | `findPublicQuestions(attemptId)` | `SELECT id, position, display_number, group_snapshot_id, part_number, (question_snapshot - 'meta' - 'legacy_backfill')::text AS question_json FROM attempt_question_snapshots WHERE attempt_id = :a ORDER BY position` | Mọi trạng thái |
| R4 | `findPublicOptions(attemptId)` | `SELECT snapshot_id, id, position, label, body FROM attempt_snapshot_options WHERE attempt_id = :a ORDER BY question_position, position` | Mọi trạng thái |
| R5 | `findSavedAnswers(attemptId)` | `SELECT snapshot_id, selected_option_id, version FROM attempt_answers WHERE attempt_id = :a` | Mọi trạng thái |
| R6 | `findPrivateGroupsForResult(attemptId)` | `SELECT id, private_snapshot::text FROM attempt_group_snapshots WHERE attempt_id = :a AND private_snapshot IS NOT NULL` | Chỉ `SUBMITTED` |
| R7 | `findAnswerKeysForResult(attemptId)` | `SELECT s.id, s.answer_key_snapshot::text, a.is_correct FROM attempt_question_snapshots s JOIN attempt_answers a ON a.attempt_id = s.attempt_id AND a.question_position = s.position WHERE s.attempt_id = :a` | Chỉ `SUBMITTED` |
| R8 | `summarizeForGrading(attemptId)` | Truy vấn tổng hợp ở T0 §4.5 (`correct_count`, `answered_count`, `listening_correct`, `reading_correct`) | Nộp |
| R9 | `partBreakdownForResult(attemptId)` | Truy vấn "theo Part" ở T0 §4.5 | Chỉ `SUBMITTED` |

- GET lượt chưa nộp: R1–R5 = **5 câu**.
- GET lượt đã nộp: R1–R7 + R9 = 8 câu (`result` lấy từ cột `attempts`).
- Index dùng: `attempts` PK; PK và unique của bảng snapshot theo `attempt_id` (T1 §3.M5). Không full scan.

### 4.6. Nộp bài

Trong một transaction `@Transactional`:

1. `SELECT … FROM attempts WHERE id = :a AND user_id = :u FOR UPDATE`.
   - Không có → 404.
   - `SUBMITTED` → trả kết quả đã có.
   - `ABANDONED` → 409 `ATTEMPT_NOT_OPEN` (`status = "ABANDONED"`).
2. Ghi `is_correct`, theo SQL Bước 1 ở T0 §4.5.
3. Chạy R8.
4. Cập nhật `attempts`:

   ```sql
   UPDATE attempts
   SET status = 'SUBMITTED', submitted_at = :now, effective_finished_at = :effective,
       submit_reason = :reason, correct_count = :correct, answered_count = :answered, score = :correct,
       listening_correct = :listening, reading_correct = :reading,
       duration_seconds = GREATEST(0, floor(extract(epoch FROM :effective - started_at)))::int,
       updated_at = :now, version = version + 1
   WHERE id = :a AND status = 'IN_PROGRESS'
   ```

   - `listening`/`reading` là NULL khi không phải TOEIC (V14 `c29`).
   - `score = correct_count` (`scoring_version = 2`).
   - Với lượt `scoring_version = 1` còn từ V13: `score = round(correct * 100 / total)` để giữ ngữ nghĩa cũ (V14 `c23`).

5. Phát `ResultsEvents.AttemptSubmitted` (5.3) qua `ApplicationEventPublisher`.
6. Đọc R1–R7 + R9 rồi trả `AttemptResultResponse`.

Tổng khoảng 4 câu ghi/đọc trước khi dựng response, cộng 8 câu đọc.

### 4.7. Lưu đáp án (CAS)

Trong một transaction:

1. `SELECT status FROM attempts WHERE id = :a AND user_id = :u FOR UPDATE`. Không có → 404. Khác `IN_PROGRESS` → 409 `ATTEMPT_NOT_OPEN`.
2. Khóa slot:

   ```sql
   SELECT selected_option_id, version, last_request_id
   FROM attempt_answers
   WHERE attempt_id = :a AND snapshot_id = :s
   FOR UPDATE
   ```

   Không có → 404.
3. Nếu `selectedOptionId != null`: `SELECT 1 FROM attempt_snapshot_options WHERE snapshot_id = :s AND id = :o`. Không có → 400 `OPTION_NOT_IN_SNAPSHOT`.
4. Replay và xung đột theo bảng T0 §5.1 (key ↔ `last_request_id`).
5. Ghi:

   ```sql
   UPDATE attempt_answers
   SET selected_option_id = :o,
       answered_at = CASE WHEN CAST(:o AS uuid) IS NULL THEN NULL ELSE :now END,
       version = version + 1, last_request_id = :key, updated_at = :now
   WHERE attempt_id = :a AND snapshot_id = :s AND version = :expected
   ```

   0 dòng → 409 `VERSION_CONFLICT` với `currentVersion` và `currentSelectedOptionId` lấy từ bước 2.
6. `UPDATE attempts SET last_active_at = :now WHERE id = :a`. Không tăng `version` của attempt, vì đây là native query.

Tổng 4–5 câu.

### 4.8. Media redirect và thống kê

`GET /api/v1/attempts/{attemptId}/media/{mediaAssetId}`:

1. R1 (chủ lượt). Sai → 404.
2. `SELECT 1 FROM attempt_media_refs WHERE attempt_id = :a AND media_asset_id = :m`. Không có → 404.
   - Mọi media v1 đều `BEFORE_SUBMIT`: AUDIO/IMAGE bắt buộc `STIMULUS` (V14 `group_materials_c9`, 02 §3).
   - Vì vậy kiểm tham chiếu là đủ, kể cả với lượt `ABANDONED`.
3. `ContentEvents.mediaPlaybackLocation(m)`. Rỗng → 503 `SERVICE_UNAVAILABLE` (`detail = "Media is unavailable."`).
4. `302 Location`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`.

`GET /api/v1/attempts/statistics/topics?category=GRAMMAR&view=allocation`:

```sql
SELECT s.topic_id AS topicId, count(DISTINCT a.id) AS attemptCount, count(*) AS questionCount,
       count(*) FILTER (WHERE ans.is_correct) AS correctCount
FROM attempts a
JOIN attempt_question_snapshots s ON s.attempt_id = a.id
JOIN attempt_answers ans ON ans.attempt_id = s.attempt_id AND ans.question_position = s.position
WHERE a.user_id = :userId AND a.status = 'SUBMITTED' AND a.category = 'GRAMMAR'
GROUP BY s.topic_id
ORDER BY s.topic_id
```

- `accuracyPercent = round(correctCount * 100 / questionCount)`.
- `category` khác `GRAMMAR`, hoặc `view` khác `allocation` → 400 (CONTRACT §3.2).

### 4.9. `AttemptService`

```java
public interface AttemptService {
    StartResult start(UUID userId, String idempotencyKey, StartAttemptRequest request);  // created=true → 201
    AttemptView get(UUID userId, UUID attemptId);
    SavedAnswerResponse saveAnswer(UUID userId, UUID attemptId, UUID snapshotId, String idempotencyKey,
                                   SaveAnswerRequest request);
    AttemptResultResponse submit(UUID userId, UUID attemptId);
    URI mediaLocation(UUID userId, UUID attemptId, UUID mediaAssetId);
}
record StartResult(AttemptView view, boolean created) {}   // service package, không phải DTO HTTP
```

### 4.10. Delta tài liệu (thực hiện trong T3)

| File | Thay đổi |
|---|---|
| `docs/m05/02-…md` §8 | `ActiveAttemptResponse` và `AttemptResultResponse` theo 5.1; `savedAnswers` dạng map; bỏ `version` của attempt khỏi DTO |
| `docs/practice/CONTRACT.md` §4.3 | `OPEN_ATTEMPT_LIMIT_REACHED` thêm `activeAttemptIds` (UUID_LIST) |
| `docs/practice/CONTRACT.md` §3.2 | Gỡ dòng endpoint DEPRECATED; thêm endpoint media; ghi `results` là owner |
| `docs/m05/tasks/T1-…md` 11.2 | `grammarSeedWorksWithExistingEndpoint` → `POST /api/v1/attempts` `GRAMMAR_TOPIC` |

---

## 5. Hợp đồng giao tiếp

### 5.1. HTTP (`results`)

Chung:
- Role `LEARNER`. Thiếu đăng nhập → 401. Thiếu role → 403.
- `POST`/`PUT` cần `X-XSRF-TOKEN` (thiếu → 403 `ACCESS_DENIED`, theo `SecurityConfiguration` hiện có).
- Lỗi là `application/problem+json` (`type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, `errors`, cùng metadata 3.M2).

#### `POST /api/v1/attempts`

Header: `Idempotency-Key: <uuid>`, `X-XSRF-TOKEN`.

```json
{ "source": { "type": "PRACTICE_SET", "practiceSetId": "e5000000-0030-4000-8000-000000000001" }, "mode": "PRACTICE" }
```

| `source.type` | Trường bắt buộc | Trường cấm |
|---|---|---|
| `PRACTICE_SET` | `practiceSetId` | `topicId`, `topicIds`, `questionCount` |
| `GRAMMAR_TOPIC` | `topicId`, `questionCount` (1–100) | `practiceSetId`, `topicIds` |
| `MIXED_GRAMMAR` | `topicIds` (2–10, không trùng), `questionCount` (1–100) | `practiceSetId`, `topicId` |

`mode`: bỏ trống hoặc `"PRACTICE"`. `"EXAM"` → 400 (`errors[0].field = "mode"`).

201 / 200 `ActiveAttemptResponse`:

```json
{ "id": "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50", "category": "TOEIC", "attemptMode": "PRACTICE",
  "status": "IN_PROGRESS",
  "source": { "type": "PRACTICE_SET", "practiceSetId": "e5000000-0030-4000-8000-000000000001", "topicIds": [] },
  "totalCount": 24, "startedAt": "2026-11-26T02:10:00Z", "lastActiveAt": "2026-11-26T02:10:00Z", "deadlineAt": null,
  "groups": [
    { "groupSnapshotId": "0a1b2c3d-1111-4a2b-8c3d-000000000041", "ordinal": 1, "partNumber": 3,
      "groupType": "CONVERSATION", "title": null, "speakerCount": 3,
      "materials": [
        { "materialKey": "audio", "materialType": "AUDIO", "position": 1,
          "mediaUrl": "/api/v1/attempts/7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50/media/e5000000-0002-4000-8000-000000000007",
          "body": { "duration_seconds": 41 } } ] } ],
  "questions": [
    { "snapshotId": "9a0b1c2d-6666-4a71-8c83-000000000001", "ordinal": 1, "displayNumber": 1, "groupSnapshotId": null,
      "partNumber": 1, "questionKind": "STANDARD", "optionDisplay": "LABEL_ONLY", "prompt": null, "blankKey": null,
      "insertSentence": null, "refs": [],
      "materials": [
        { "materialKey": "photo", "materialType": "IMAGE", "position": 1,
          "mediaUrl": "/api/v1/attempts/7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50/media/e5000000-0002-4000-8000-000000000001",
          "body": { "alt_text": "Two workers are carrying a long table through an open doorway." } } ],
      "options": [ { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000011", "label": "A", "body": null },
                   { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000012", "label": "B", "body": null },
                   { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000013", "label": "C", "body": null },
                   { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000014", "label": "D", "body": null } ] } ],
  "savedAnswers": {
    "9a0b1c2d-6666-4a71-8c83-000000000001": { "selectedOptionId": null, "version": 0 } } }
```

Ví dụ trên rút gọn; response thật có đủ mọi nhóm và mọi câu.

**Phần tử `refs[]` (AD-2, rà soát chéo T0–T7):**
- DTO `record RefResponse(String materialKey, String anchor)` trong `results/dto/response`. `anchor` là `null` khi JSON không có.
- Mapper đọc `refs[].material_key` và `refs[].anchor` từ `question_snapshot`, đổi sang camelCase. Không trả nguyên node JSON.
- Cùng record này dùng cho `review.evidence[]` ở `/results` (T4).
- Ví dụ câu P7: `"refs": [ { "materialKey": "email", "anchor": null }, { "materialKey": "chat", "anchor": "m3" } ]`.
- Test `AttemptGetTests.refsAreCamelCase`: JSON có `materialKey`, không có `material_key`.

| Lỗi | HTTP | `code` / metadata |
|---|---|---|
| Thiếu hoặc sai `Idempotency-Key`; body sai | 400 | `VALIDATION_ERROR` |
| Set hoặc topic không hiển thị | 404 | `RESOURCE_NOT_FOUND` |
| Cùng key, khác payload | 409 | `IDEMPOTENCY_CONFLICT` |
| ≥ 5 lượt mở | 409 | `OPEN_ATTEMPT_LIMIT_REACHED` (`openAttemptCount`, `maxOpenAttempts`, `activeAttemptIds`) |
| Không đủ câu | 409 | `NO_ELIGIBLE_QUESTIONS` / `INSUFFICIENT_QUESTIONS` (`requestedCount`, `availableCount`) |

Ví dụ 409 trần:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "Too many attempts in progress. Submit an open attempt to start a new one.",
  "instance": "/api/v1/attempts", "code": "OPEN_ATTEMPT_LIMIT_REACHED",
  "openAttemptCount": 5, "maxOpenAttempts": 5,
  "activeAttemptIds": ["7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50", "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a51",
                       "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a52", "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a53",
                       "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a54"],
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", "errors": [] }
```

#### `GET /api/v1/attempts/{attemptId}`

> **Thay bởi T4 O2:** endpoint này trả `ActiveAttemptResponse` ở **mọi** trạng thái (kể cả `SUBMITTED`, chỉ đọc). `AttemptResultResponse` chỉ có ở `GET /api/v1/attempts/{attemptId}/results` với hình dạng theo T4 §5.1 (`answer`, `review.correctOptionLabel`, `result.partBreakdown`…). Đoạn gạch dưới chỉ giữ để truy vết.

~~200 `ActiveAttemptResponse` (`IN_PROGRESS`/`ABANDONED`) hoặc `AttemptResultResponse` (`SUBMITTED`); 404 `RESOURCE_NOT_FOUND`.~~

~~`AttemptResultResponse` = các trường của active **trừ** `savedAnswers`, cộng thêm:~~
- `answers`: map `snapshotId` → `{ selectedOptionId, isCorrect }`.
- `result`: `{ correctCount, answeredCount, totalCount, listeningCorrect, readingCorrect, byPart[{partNumber, correct, total}], submittedAt, effectiveFinishedAt, submitReason, durationSeconds }`.
- `questions[].review`: `{ correctOptionId, explanation, evidence, optionTranscripts, promptTranscript }`.
- `groups[].reviewMaterials`.
- Lựa chọn P1/P2 vẫn có `body = null`. Văn bản nằm ở `review.optionTranscripts`.

Không có trường `score` (ADR-11).

#### `PUT /api/v1/attempts/{attemptId}/answers/{snapshotId}`

Header `Idempotency-Key`, `X-XSRF-TOKEN`. Body `{"selectedOptionId": "<uuid>|null", "expectedVersion": 2}`.

200 `SavedAnswerResponse`:

```json
{ "snapshotId": "9a0b1c2d-6666-4a71-8c83-000000000042", "selectedOptionId": "5b6c7d8e-2222-4c3d-9e4f-000000000424",
  "version": 3, "answeredAt": "2026-11-26T02:15:02Z" }
```

| Lỗi | HTTP | `code` |
|---|---|---|
| Không phải chủ; snapshot không thuộc lượt | 404 | `RESOURCE_NOT_FOUND` |
| Lượt không `IN_PROGRESS` | 409 | `ATTEMPT_NOT_OPEN` (`status`) |
| Lựa chọn không thuộc câu | 400 | `OPTION_NOT_IN_SNAPSHOT` |
| Trùng key, sai trạng thái | 409 | `IDEMPOTENCY_CONFLICT` |
| Sai `expectedVersion` | 409 | `VERSION_CONFLICT` (`currentVersion`, `currentSelectedOptionId`) |

#### `POST /api/v1/attempts/{attemptId}/submit`

200 `AttemptResultResponse`. Lỗi: 404; 409 `ATTEMPT_NOT_OPEN` (`ABANDONED`).

#### `GET /api/v1/attempts/{attemptId}/media/{mediaAssetId}`

302, hoặc 404, hoặc 503 `SERVICE_UNAVAILABLE` (4.8).

#### `GET /api/v1/attempts/statistics/topics?category=GRAMMAR&view=allocation`

200 `[{ "topicId": "…", "attemptCount": 2, "questionCount": 20, "correctCount": 15, "accuracyPercent": 75, "view": "allocation" }]`. Lỗi 400.

### 5.2. Facade dùng (đã có từ T2)

- `PracticeEvents.prepareSeal`.
- `ContentEvents.mediaPlaybackLocation`.
- `IdentityEvents.verifyAndLockUser`.
- `GrammarEvents.isVisibleTopic` (qua T2).

### 5.3. `common/events/ResultsEvents.java` `[CREATE]`

```java
/** Events published by the results module (ADR-12). */
public final class ResultsEvents {
    private ResultsEvents() {}

    /** Published once per attempt, when it moves from IN_PROGRESS to SUBMITTED. */
    public record AttemptSubmitted(UUID attemptId, UUID userId, String category, String sourceType,
                                   Instant effectiveFinishedAt, List<QuestionOutcome> outcomes) {}

    /** outcome: CORRECT | INCORRECT | UNANSWERED */
    public record QuestionOutcome(UUID questionId, UUID snapshotId, Integer partNumber, String outcome) {}

    // Dự phòng (O2), CHƯA tạo ở T3:
    // public UUID startAttempt(UUID userId, UUID idempotencyKey, StartSource source) — chỉ thêm khi có module gọi.
}
```

- Chưa có listener (D13). Spring Modulith chỉ ghi `event_publication` khi có `@ApplicationModuleListener`, nên hiện không phát sinh dòng nào.
- Test kiểm event được phát bằng `@RecordApplicationEvents`.

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn

| Rủi ro | Biện pháp |
|---|---|
| Đáp án hoặc transcript lộ trước khi nộp | Ba lớp:<br>(1) SQL R1–R5 không chọn cột private, và `question_snapshot` bị bóc `meta`;<br>(2) `ActiveAttemptResponse` không có trường private;<br>(3) `body` P1/P2 là NULL từ lúc seal. |
| Entity nạp cột private vào bộ nhớ | Không có entity snapshot trong `results` |
| IDOR | Mọi truy vấn có `user_id = :userId` hoặc đi qua R1 trước. Không phải chủ → 404 (không phân biệt với không tồn tại). |
| Chọn lựa chọn của câu khác | Bước 3 của 4.7, cộng FK `fk_attempt_answers_snapshot_option` và `attempt_answers_c3` |
| CSRF | `POST`/`PUT` qua cấu hình hiện có. `Idempotency-Key` đã nằm trong CORS allowlist (`SecurityConfiguration.java:134`). |
| Race start | Khóa user, rồi replay; fallback `DataIntegrityViolation` trên `attempts_c2` (3.M4) |
| Race autosave/submit | Khóa `attempts` `FOR UPDATE` rồi tới slot. Autosave sau submit → `ATTEMPT_NOT_OPEN`. |
| Thứ tự khóa | Start: `users` → `attempts` (mở, theo `id`) → nguồn `FOR SHARE` (T2). Autosave/submit: `attempts` → `attempt_answers`. Không luồng nào khóa `users` sau khi đã giữ `attempts`, nên không có deadlock. |
| Snapshot bị sửa sau khi seal | Trigger V17. Mọi `UPDATE`/`DELETE` ném `23514`. |
| Media sau nộp bị ký trước | Không có media `AFTER_SUBMIT` ở v1 (4.8). Nếu thêm về sau, phải mở lại 4.8. |
| Metadata lỗi rò rỉ | R8: chỉ 4 kiểu, không có chuỗi tự do |
| Lượt bị abandon nhưng frontend vẫn lưu | 409 `ATTEMPT_NOT_OPEN` (`status = ABANDONED`) |

---

## 7. Ranh giới Spring Modulith và tài nguyên

**DAG:**
- `results` → `common::{events,error,security,api,pagination}`.
- Gọi `practice` (`PracticeEvents`), `content` (`ContentEvents`), `identity` (`IdentityEvents`) qua facade.
- `practice` không gọi `results`. `learning` sẽ nghe `ResultsEvents` (sau). Không có vòng.

**`ArchitectureTests` `[MODIFY]`:** thêm `assertThat(modules.getModuleByName("results")).isPresent();`.

**`LayerArchitectureTests`:**
- Không đổi rule.
- `JpaAttemptSnapshotWriter` ở `infrastructure.persistence`, nên được dùng `EntityManager`.
- `RequestHasher`, `SnapshotBuilder`, `Grader`, mapper ở `service.impl`, không có hậu tố `Impl`.
- `StartResult` ở `service`, không kết thúc bằng `Request`/`Response`.

**UUID FK:** entity `Attempt` chỉ có `UUID` (`userId`). Không có `@ManyToOne`.

**Round-trip (ước lượng):**

| Thao tác | Câu SQL |
|---|---|
| Start Full Test | 15 (ghi 6) |
| GET đang làm | 5 |
| GET kết quả | 8 |
| Autosave | 4–5 |
| Submit | 4 + 8 đọc |

**Pool 5 kết nối:**
- Mỗi request một kết nối. Facade chạy cùng thread.
- Start giữ kết nối trong suốt transaction. Mục tiêu p95 < 800 ms với Full Test (ADR-13), đo ở T7.

**RAM API:**
- Start Full Test dựng khoảng 1.300 `Row` cộng JSON (~1–2 MB), giải phóng sau request.
- `ActiveAttemptResponse` của Full Test khoảng 200–400 KB JSON.

**Nén response (AD-3, duyệt 26/11/2026 ở rà soát chéo T0–T7):** `src/main/resources/application.properties` `[MODIFY]` thêm:

```properties
# Large attempt payloads (Full Test 200-400 KB JSON) are gzip-compressed for the read SLA (AD-3).
server.compression.enabled=true
server.compression.mime-types=application/json,application/problem+json
server.compression.min-response-size=2KB
```

- Nén ở connector của Tomcat, nên MockMvc **không** thấy. Test `ResponseCompressionTests.largeAttemptIsGzipped` dùng `@SpringBootTest(webEnvironment = RANDOM_PORT)` và `java.net.http.HttpClient` gửi `Accept-Encoding: gzip` tới `GET /api/v1/attempts/{id}` (seed S01): header `Content-Encoding: gzip`, giải nén ra đúng JSON. Không gửi `Accept-Encoding` thì không nén.
- `GET /api/v1/auth/csrf` nhỏ hơn 2 KB nên không bị nén. Không có response nào vừa lớn hơn 2 KB vừa chứa token bí mật cùng dữ liệu do người dùng nhập, nên rủi ro kiểu BREACH không áp.
- Đi qua rewrite của Next standalone và Pages Function thì header `Content-Encoding` giữ nguyên; T7 kiểm trên stack benchmark.

**RAM PostgreSQL:** mảng tham số của W5 (775 phần tử × 6 mảng) khoảng < 200 KB mỗi câu lệnh.

---

## 8. Edge Cases & Failure Modes Matrix

| # | Tình huống | Nguyên nhân | Hành vi | Kết quả |
|---|---|---|---|---|
| 1 | Hai `POST` cùng key, đồng thời | Retry mạng | Khóa user tuần tự hóa; request sau thấy dòng đã có | 201 và 200, 1 dòng `attempts` |
| 2 | Bỏ qua khóa user (bug), unique `attempts_c2` bị vi phạm | Lỗi lập trình | Bắt `DataIntegrityViolation`, replay ở transaction mới | 200 hoặc `IDEMPOTENCY_CONFLICT`, không 500 |
| 3 | Cùng key, đổi set | Bug client | Hash khác | 409 `IDEMPOTENCY_CONFLICT` |
| 4 | Đã có 5 lượt mở, retry start cùng key | Retry | Replay chạy trước kiểm trần | 200 |
| 5 | Lượt thứ 6 | Giới hạn | — | 409 `OPEN_ATTEMPT_LIMIT_REACHED`, 5 `activeAttemptIds` |
| 6 | 1 lượt mở đã 8 ngày không hoạt động, start mới | Bỏ dở | Abandon lazy trước khi đếm | Lượt cũ `ABANDONED`, start 201 |
| 7 | Start rollback giữa chừng (`INSUFFICIENT`) | Thiếu câu | Toàn bộ, kể cả abandon lazy, rollback | 409, không ghi gì |
| 8 | GET lượt người khác | IDOR | R1 lọc `user_id` | 404 |
| 9 | Autosave sau khi submit | Race | Khóa attempt | 409 `ATTEMPT_NOT_OPEN` (`SUBMITTED`) |
| 10 | Hai tab cùng `expectedVersion` | — | CAS | 200 và 409 `VERSION_CONFLICT` |
| 11 | Retry PUT sau khi đã ghi | Mất response | Khớp `last_request_id` | 200, `version` không tăng |
| 12 | `VERSION_CONFLICT` khi slot chưa chọn | — | `currentSelectedOptionId = null` | JSON có `"currentSelectedOptionId": null` (3.M2) |
| 13 | Submit hai lần | Double click | Lần hai đọc kết quả | 200, 1 event |
| 14 | Submit lượt `ABANDONED` | — | — | 409 `ATTEMPT_NOT_OPEN` |
| 15 | Lượt V13 đang làm dở khi deploy T3 | Dữ liệu cũ | V17 thêm slot rỗng; dùng API mới bình thường; `scoring_version = 1` | Nộp được |
| 16 | Code nào đó `UPDATE` snapshot | Bug | Trigger | `23514` → 500 + log |
| 17 | Media lượt có bucket khác cấu hình | Seed local | `mediaPlaybackLocation` rỗng | 503 |
| 18 | `Idempotency-Key` không phải UUID | Client | — | 400 `VALIDATION_ERROR` |
| 19 | `mode = EXAM` | Client | — | 400 |
| 20 | Grammar `option_order = SHUFFLED` | Nội dung | Xáo xác định | Replay cùng thứ tự; chấm đúng theo `correct_option_snapshot_id` |
| 21 | Bind mảng Hibernate thất bại | Môi trường | Test 11.2 phát hiện | Fallback 4.4 |

---

## 9. Kế hoạch chỉnh sửa file

| Đường dẫn | Hành động |
|---|---|
| `src/main/resources/db/migration/V17__results_finalize_snapshots.sql` | CREATE |
| `src/main/resources/application.properties` | MODIFY (3 dòng `server.compression.*`, AD-3) |
| `src/test/java/com/masterenglish/api/modules/results/ResponseCompressionTests.java` | CREATE (AD-3) |
| `src/main/java/com/masterenglish/api/modules/results/**` | CREATE (4.2) |
| `src/main/java/com/masterenglish/api/infrastructure/persistence/results/JpaAttemptSnapshotWriter.java` | CREATE |
| `src/main/resources/META-INF/spring.factories` | MODIFY |
| `src/main/java/com/masterenglish/api/common/error/{DetailType,DetailKey}.java` | CREATE |
| `src/main/java/com/masterenglish/api/common/error/{ErrorCode,BusinessException}.java` | MODIFY |
| `src/main/java/com/masterenglish/api/common/error/GlobalExceptionHandler.java` | MODIFY chỉ khi test 3.M2 cho thấy null bị bỏ |
| `src/main/java/com/masterenglish/api/common/events/ResultsEvents.java` | CREATE |
| `src/main/java/com/masterenglish/api/modules/practice/{controller/AttemptController, entity/Attempt*, repository/Attempt*, dto/request/{AnswerRequest,StartAttemptRequest,MixedAttemptRequest}, dto/response/{AttemptResponse,StartAttemptResult,TopicStatisticsResponse}}.java` | DELETE |
| `src/main/java/com/masterenglish/api/modules/practice/{controller/GrammarPracticeController, service/PracticeService, service/impl/PracticeServiceImpl}.java` | MODIFY (chỉ giữ `exercises`) |
| `src/test/java/com/masterenglish/api/ArchitectureTests.java` | MODIFY |
| `src/test/java/com/masterenglish/api/modules/practice/controller/PracticeIntegrationTests.java` | DELETE (thay bằng test `results`) |
| `src/test/java/com/masterenglish/api/modules/practice/controller/GrammarPracticeControllerIntegrationTests.java` | DO NOT TOUCH (chỉ test `exercises`) |
| `src/test/java/com/masterenglish/api/modules/practice/migration/M05SeedIntegrationTests.java` | MODIFY (T3-D13) |
| `src/test/java/com/masterenglish/api/modules/results/**`, `common/error/*Tests` | CREATE |
| `docs/m05/02-…md`, `docs/practice/CONTRACT.md`, `docs/m05/tasks/T1-…md` | MODIFY (4.10) |
| `V1`–`V16` | DO NOT TOUCH |
| `modules/practice/service/impl/{QuestionSelectionServiceImpl,…}` (T2) | DO NOT TOUCH |

---

## 10. Lộ trình commit

Commit chỉ khi người dùng yêu cầu. Mỗi bước: test được chọn xanh và `.\gradlew.bat spotlessCheck`.

| Bước | Nội dung | Lệnh | Commit |
|---|---|---|---|
| 1 | R8: `DetailType`, `DetailKey`, `ErrorCode`, `BusinessException` + test | `--tests 'com.masterenglish.api.common.*' --tests '*ErrorHandlingTests'` | `feat(common): metadata lỗi có kiểu cho BusinessException` |
| 2 | Khung `results` (`package-info`, entity `Attempt`, repository, writer, `spring.factories`) + `ArchitectureTests` | `--tests '*ArchitectureTests' --tests '*LayerArchitectureTests' --tests '*SnapshotWriterTests'` | `feat(results): khởi tạo module results và bộ ghi snapshot` |
| 3 | V17, start, GET, media, gỡ code attempt khỏi `practice`, viết lại test | `--tests 'com.masterenglish.api.modules.results.*' --tests 'com.masterenglish.api.modules.practice.*' --tests '*M05Seed*'` | `feat(results): mở lượt làm, đóng băng đề và xem lượt đang làm` |
| 4 | Autosave, submit, event, thống kê | Như bước 3, thêm `--tests '*Concurrency*'` | `feat(results): lưu đáp án CAS, nộp bài và thống kê chủ điểm` |
| 5 | Delta tài liệu | Script C-01/C-02 trên 02 | `docs(m05): cập nhật hợp đồng results theo T3` |
| 6 | Cổng cuối | `.\gradlew.bat check bootJar` | — |

Bước 3 phải gộp V17 với việc gỡ code V13 trong cùng một commit. Lý do: sau V17, code V13 không còn ghi được, vì `snapshot_id NOT NULL` và `is_correct` của lựa chọn bị đặt `NULL` trong khi entity cũ map `is_correct` bằng `boolean` nguyên thủy.

---

## 11. Test Matrix

### 11.1. Unit

| Test | Assert |
|---|---|
| `BusinessExceptionTypedDetailsTests.countRejectsNegative` / `uuidOrNullAcceptsNull` / `enumAcceptsEnumAndPattern` / `uuidListRejectsNullElementAndMoreThan10` / `unknownKeyRejected` / `legacyTwoArgConstructorHasNoDetails` | 3.M2 |
| `ErrorCodeCompatibilityTests.existingCodesKeepStatusAndName` | Snapshot tên và HTTP status của 16 mã cũ không đổi |
| `RequestHasherTests.modeDefaultHashesEqual` / `topicOrderIrrelevant` / `uuidCaseIrrelevant` | 3.M4 |
| `SnapshotBuilderTests.labelOnlyBodiesGoToOptionTranscripts` | P1/P2: `OptionRow.body = null`, `key_json.option_transcripts` có 4/3 phần tử |
| `SnapshotBuilderTests.afterSubmitMaterialsOnlyInPrivate` | — |
| `SnapshotBuilderTests.privateJsonNullWhenEmpty` | — |
| `SnapshotBuilderTests.shuffledIsDeterministicAndKeyFollows` | 3.M5 |
| `SnapshotBuilderTests.outputValidatesAgainstSchemaV1` | Mọi JSON sinh ra khớp các bất biến schema trong `02` mà validator Java kiểm |

### 11.2. Integration (Testcontainers PostgreSQL 17, seed T1)

| Test | Assert |
|---|---|
| `SnapshotWriterTests.fullTestUsesSixStatements` | Seal S01. Đếm câu lệnh ghi = 6 (Hibernate statistics). Số dòng `attempts` 1, nhóm 5, câu 24, lựa chọn 94 (8 + 6 + 80), slot 24, `attempt_media_refs` 8 (m1–m8). |
| `AttemptStartTests.createdReturns201WithLocation` | — |
| `AttemptStartTests.replaySameKey200` / `differentPayload409` / `missingKey400` | 3.M4 |
| `AttemptStartTests.limitFiveWithActiveIds` | Edge #5 |
| `AttemptStartTests.lazyAbandonBeforeCount` | Edge #6 |
| `AttemptStartTests.rollbackOnInsufficientKeepsAbandonUndone` | Edge #7 |
| `AttemptStartTests.grammarTopicAndMixed` | Nguồn grammar; `attempt_source_topics` cho MIXED |
| `AttemptStartTests.timedOverdueIsAutoSubmittedOnStart` | Fixture SQL `deadline_at` quá khứ → `SUBMITTED`, `TIME_LIMIT`, `effective_finished_at = deadline_at` |
| `AttemptGetTests.inProgressShape` | Có `savedAnswers` cho mọi câu; không có `result`/`review` |
| `AttemptGetTests.abandonedIsReadOnlyActiveShape` | — |
| ~~`AttemptGetTests.submittedReturnsResultShape`~~ (thay bởi T4 `AttemptGetTests.submittedReturnsActiveShapeOnly`) | — |
| `AttemptGetTests.otherUser404` | — |
| `AutosaveTests.casSuccess` / `deselect` / `replay` / `versionConflictCarriesCurrent` / `optionOfOtherSnapshot400` / `abandoned409` | 4.7 |
| `SubmitTests.gradesToeicSections` | S01: tự chọn đáp án đúng cho câu 1–10 → `listeningCorrect = 10`, `readingCorrect = 0`, `correctCount = 10` |
| `SubmitTests.secondSubmitNoSecondEvent` | `@RecordApplicationEvents`: đúng 1 `AttemptSubmitted` |
| `MediaRedirectTests.redirect302WhenStorageMocked` / `unknownMedia404` / `storageOff503` | 4.8 |
| `StatisticsTests.allocationMatchesV13Semantics` | — |
| `V17UpgradeTests.v13AttemptsFinalized` | Migrate tới V16, chèn dữ liệu V13 cùng dòng code V13 ghi sau T1, migrate V17. Kết quả: mọi slot có mặt, `is_correct` của lựa chọn NULL, cột NOT NULL, trigger chặn UPDATE. |
| `M05SeedIntegrationTests.grammarSeedWorksWithNewEndpoint` | Thay test cũ (T3-D13) |
| `GlobalErrorDetailsTests.nullUuidSerializesAsJsonNull` | 3.M2 |
| `ErrorHandlingTests` (10 ca hiện có) | Xanh, không sửa |

### 11.3. Security / Anti-leak

| Test | Assert |
|---|---|
| `PreSubmitLeakTests.activeJsonHasNoPrivateKeys` | JSON không chứa `correctOptionId`, `explanation`, `evidence`, `optionTranscripts`, `promptTranscript`, `reviewMaterials`, `meta`, `sourceOptionId`, `isCorrect`, `score` |
| `PreSubmitLeakTests.activeJsonHasNoPrivateText` | Không chứa chuỗi transcript hay lời giải nào của seed (P1–P4) |
| `PreSubmitLeakTests.repositoryActiveQueriesSelectNoPrivateColumns` | Kiểm chuỗi `@Query` của R1–R5 (reflection): không chứa `private_snapshot`, `answer_key_snapshot`, `is_correct`, `source_option_id` |
| `AttemptAccessTests.putWithoutCsrf403` | — |

### 11.4. Concurrency (không `@Transactional`)

| Test | Assert |
|---|---|
| `StartConcurrencyTests.sameKeyParallelCreatesOne` | 2 thread → 1 dòng; mã trả về {201, 200} |
| `StartConcurrencyTests.fifthAndSixthParallel` | 4 lượt mở sẵn → {201, 409} |
| `AutosaveConcurrencyTests.sameVersionOneWins` | {200, 409}, `version` tăng 1 |
| `SubmitConcurrencyTests.autosaveRacingSubmit` | Không slot nào có `updated_at > submitted_at` |

### 11.5. ArchUnit

| Test | Assert |
|---|---|
| `ArchitectureTests` | Có `results`, `verify()` xanh |
| `LayerArchitectureTests` | 14 rule (gồm rule T2) xanh |

---

## 12. Definition of Done & Open Decisions

### 12.1. DoD

| # | Tiêu chí | Pass/Fail |
|---|---|---|
| 1 | V17 chạy sạch trên DB trống và DB có dữ liệu V13 | ☐ |
| 2 | Không còn class attempt nào trong `practice`; 3 endpoint grammar cũ đã gỡ | ☐ |
| 3 | `BusinessException` cũ hoạt động như trước (`ErrorHandlingTests`, `ErrorCodeCompatibilityTests`) | ☐ |
| 4 | Start Full Test ghi bằng ≤ 7 câu lệnh (`SnapshotWriterTests`) | ☐ |
| 5 | Test anti-leak 11.3 xanh | ☐ |
| 6 | Test concurrency 11.4 xanh | ☐ |
| 7 | `ArchitectureTests`, `LayerArchitectureTests` xanh | ☐ |
| 8 | `.\gradlew.bat spotlessCheck check bootJar` xanh | ☐ |
| 9 | Delta tài liệu 4.10 đã ghi | ☐ |

### 12.2. Câu hỏi cần duyệt

| # | Câu hỏi | Đề xuất |
|---|---|---|
| O1 | Đề T3 đặt tên `MAX_ACTIVE_ATTEMPTS_REACHED (maxAllowed, activeCount)`, `IDEMPOTENCY_KEY_CONFLICT`, `INSUFFICIENT_QUESTIONS (requested, available)`. Giữ tên đã duyệt ở CONTRACT (`OPEN_ATTEMPT_LIMIT_REACHED`, `IDEMPOTENCY_CONFLICT`, `requestedCount`/`availableCount`) và chỉ thêm `activeAttemptIds`? | Giữ tên đã duyệt. Frontend chưa dùng nên đổi tên được, nhưng `INSUFFICIENT_QUESTIONS` đang có code và test hiện hành. |
| O2 | Không tạo `AttemptCreationFacade`/`ResultsEvents.startAttempt` cho tới khi có module gọi? | Không tạo |
| O3 | Gỡ hẳn 3 endpoint grammar cũ (không giữ `@Deprecated`)? | Gỡ. Frontend 0 lời gọi; test được viết lại. |
| O4 | Lượt `scoring_version = 1` (V13) nộp sau T3: tiếp tục ghi `score` theo % để giữ ngữ nghĩa cũ? | Có (4.6) |
