# T4 — Implementation Brief: `results` phần 2 — Autosave CAS, chấm điểm server-side, kết quả và event

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026. O1–O4 theo đề xuất: khóa metadata `attemptStatus`; kết quả chỉ ở `/results`; submit trả tóm tắt kèm `resultsUrl`; event dùng `boolean correct`/`unanswered`. |
| Ngày | 26/11/2026 |
| Phụ thuộc | T0, T1, T3 (đã duyệt). T4 **thay** các mục 4.6, 4.7, 5.1 (PUT/submit), 5.3 của T3. Khi T3 và T4 khác nhau, T4 thắng. |
| Chế độ viết brief | READ-ONLY |

Nhãn: `[CREATE]`, `[MODIFY]`, `[DO NOT TOUCH]`.

**Cách triển khai.** T3 và T4 dùng chung bước commit "lưu đáp án và nộp bài" (T3 §10 bước 4). Bước đó hiện thực **theo T4**. T4 không tạo migration.

---

## 1. Metadata & Traceability

### 1.1. Sản phẩm

1. `PUT /api/v1/attempts/{attemptId}/answers/{snapshotId}`: CAS cộng idempotency, 4 câu SQL.
2. `AttemptGradingEngine`: một luồng chấm duy nhất cho nộp tay, cho tự nộp khi quá hạn (dọn dẹp lúc start của T3), cho GET lượt quá hạn và cho autosave sau hạn.
3. `POST /api/v1/attempts/{attemptId}/submit`: 3 câu SQL ghi và đọc, trả bản tóm tắt.
4. `GET /api/v1/attempts/{attemptId}/results`: chỉ khi `SUBMITTED`, đọc 100% từ snapshot.
5. Event `ResultsEvents.AttemptSubmitted`: bản chốt.
6. Mã lỗi mới `ATTEMPT_NOT_SUBMITTED`.

### 1.2. Traceability

| Mã | Nội dung |
|---|---|
| M05.06 | Autosave |
| M05.08, M05.09, M05.18 | Nộp, chấm `SINGLE_CHOICE_V1` |
| M05.10 | Kết quả và lời giải |
| R1, S6 | Ghép `public_snapshot`/`private_snapshot`, `question_snapshot`/`answer_key_snapshot` |
| R2 | Chuyển trạng thái `IN_PROGRESS → SUBMITTED` |
| R4 | Tự nộp khi quá hạn dùng chung engine |
| R8 | `VERSION_CONFLICT`, `ATTEMPT_NOT_OPEN`, `ATTEMPT_NOT_SUBMITTED` |
| S7 | Lựa chọn theo lượt; FK `(snapshot_id, selected_option_id)` |
| ADR-09, ADR-11, ADR-12 | CAS; số liệu gốc; event |

### 1.3. Explicitly Out-of-Scope

- Listener `learning` và `mistake_records` (D13).
- Lịch sử và thống kê M06 (ngoài port thống kê chủ điểm đã có ở T3).
- Quy đổi 990, IRT, Exam Mode, frontend.

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc

| Nhóm | File |
|---|---|
| Brief | T0, T1 (§3.M3, V14 4.3–4.4), T2, T3 (toàn văn, đã duyệt) |
| Chấm V13 | `PracticeServiceImpl.java:150-178` (`submit`, `topicStatistics`), `:365-373` (`percent`: `BigDecimal` chia, `RoundingMode.HALF_UP`, `whole == 0` → 0); `AttemptRepository.java:60-80` (thống kê: đúng khi lựa chọn được chọn có `is_correct`; `LEFT JOIN` nên câu bỏ trống là sai) |
| Event | `V1__create_modulith_event_publication.sql`; `application.properties:10` (`spring.modulith.events.completion-mode=update`), `:17` (`detection-strategy=explicitly-annotated`); `common/events/*`; `infrastructure/events/SpringModuleEvents.java` (`publish` → `ApplicationEventPublisher.publishEvent`) |
| Lỗi | `ErrorCode`, `BusinessException`, `GlobalExceptionHandler` (đã đọc ở T3) |

**Luật chấm V13 cần giữ:**
1. Một câu đúng khi lựa chọn được chọn là lựa chọn đúng.
2. Bỏ trống thì sai, không trừ điểm.
3. `score` (V13) = `percent(correct, total)`, làm tròn `HALF_UP` ra số nguyên.
4. `accuracyPercent` của thống kê dùng cùng `percent`, và trả `null` khi `questionCount = 0`.

Cả bốn luật được giữ ở 3.M2 và 3.M4.

### 2.2. Drift

| ID | Đề T4 | Repo / hợp đồng | Giải quyết |
|---|---|---|---|
| T4-D01 | Autosave `expectedVersion == 0` → `INSERT … ON CONFLICT (attempt_id, snapshot_id) DO NOTHING` | T3 W6 và V17 tạo **slot rỗng cho mọi câu** lúc start hoặc migrate (`version = 0`). Không có code nào xóa `attempt_answers`. | Một nhánh duy nhất `UPDATE … WHERE version = :expected`. `expectedVersion = 0` hoạt động tự nhiên trên slot rỗng. Không có nhánh `INSERT` (3.M1). |
| T4-D02 | Replay khi "cùng `Idempotency-Key` + cùng payload hash" | T1 §3.M3 (đã duyệt): chỉ lưu `last_request_id`. Replay xác định bằng trạng thái: lựa chọn trùng body và `version = expectedVersion + 1`. | Giữ T1. Kết quả tương đương với so hash, và không cần cột hash. |
| T4-D03 | `submit_reason = 'USER_SUBMIT' \| 'DEADLINE_EXPIRED'` | V14 `attempts_c24`: `('MANUAL','TIME_LIMIT')`; `c25` ràng buộc `TIME_LIMIT` với `deadline_at` | Enum Java `SubmitReason { MANUAL, TIME_LIMIT }`. Giá trị DB không đổi. |
| T4-D04 | Cột `source_question_id` | Cột thật là `attempt_question_snapshots.question_id` (V13) | Dùng `question_id` |
| T4-D05 | `wrong_count`, `unanswered_count` | `attempts` có `correct_count`, `answered_count`, `total_count` | Tính khi đọc: `wrong = answered − correct`, `unanswered = total − answered`. Không thêm cột. |
| T4-D06 | Chấm bằng 2 câu tải, so trong bộ nhớ, rồi bulk `UPDATE … FROM UNNEST` | T3 4.6: `UPDATE … FROM` theo tập hợp | `UPDATE … FROM … RETURNING`: **một câu** vừa ghi `is_correct` vừa trả về đủ dữ liệu cho event và tổng hợp (3.M2). Ít câu hơn đề, không đưa dữ liệu đáp án lên RAM. |
| T4-D07 | Event có `PracticeCategory category`, `isCorrect`, `isUnanswered` | `common/events` dùng `String` cho loại nội dung (`ContentEvents.RevisionPublished.type`). Không có enum `PracticeCategory`. T3 5.3 dùng `outcome` dạng chuỗi và có `snapshotId`. | Theo đề T4: hai trường `boolean correct`, `boolean unanswered`, có ràng buộc trong compact constructor; bỏ `snapshotId`. `category` là `String`, theo pattern repo (3.M3). |
| T4-D08 | Event vào `event_publication` | Với `explicitly-annotated` và chưa có `@ApplicationModuleListener` nào, Spring Modulith **không** ghi dòng nào. | Phát event bằng `ApplicationEventPublisher`. Dòng `event_publication` chỉ xuất hiện khi listener D13 ra đời. Test dùng `@RecordApplicationEvents` (3.M3). |
| T4-D09 | `GET /api/v1/attempts/{id}/results` tách riêng | T3 3.M7: `GET /attempts/{id}` trả `AttemptResultResponse` khi `SUBMITTED` | **Sửa T3:**<br>• `GET /attempts/{id}` **không bao giờ** trả dữ liệu private, ở mọi trạng thái;<br>• lượt `SUBMITTED` trả `ActiveAttemptResponse` với `status = SUBMITTED` (chỉ đọc);<br>• kết quả đầy đủ ở `/results` (3.M4). |
| T4-D10 | — | T3 4.6: submit trả `AttemptResultResponse` đầy đủ, tức 8 câu đọc sau khi ghi | Submit trả `AttemptSubmitResponse` (tóm tắt, dựng từ dữ liệu `RETURNING`), giữ SLA < 500 ms. Lời giải chi tiết lấy qua `/results`. |
| T4-D11 | `409 ATTEMPT_NOT_SUBMITTED` | Chưa có trong `ErrorCode` | `[CREATE]` `ATTEMPT_NOT_SUBMITTED(HttpStatus.CONFLICT, DetailKey.enumValue("status"))` |
| T4-D12 | — | T3 4.8 thống kê ghi `round(…)` | Chốt: `percent` `HALF_UP`, `null` khi `questionCount = 0` (giữ luật V13) |
| T4-D13 | Đề T4: `ATTEMPT_NOT_OPEN` kèm `status` | `GlobalExceptionHandler.problem(...)` dùng `ProblemDetail.forStatusAndDetail`, nên body đã có trường chuẩn `status` (số HTTP). Metadata cũng đặt bằng `setProperty`, nên khóa `status` sẽ va chạm. | Khóa metadata là `attemptStatus` (O1) |

---

## 3. Mandatory Design Resolutions

### M1. Autosave: CAS và Idempotency

Đầu vào:
- `userId`, `attemptId`, `snapshotId`;
- header `Idempotency-Key` (UUID, bắt buộc; thiếu hoặc sai → 400 `VALIDATION_ERROR`, `detail = "Idempotency-Key header must be a UUID."`);
- body `{selectedOptionId: UUID|null, expectedVersion: int ≥ 0}`.

Một transaction (`@Transactional` trên method `AttemptServiceImpl.saveAnswer`), theo thứ tự:

| Bước | SQL | Kết quả |
|---|---|---|
| A1 | `SELECT status, deadline_at FROM attempts WHERE id = :a AND user_id = :u FOR UPDATE` | Không có → 404 `RESOURCE_NOT_FOUND`. `deadline_at <= now` và `IN_PROGRESS` → `AttemptGradingEngine.grade(…, TIME_LIMIT)` rồi 409 `ATTEMPT_NOT_OPEN` (`attemptStatus = SUBMITTED`). `status ≠ IN_PROGRESS` → 409 `ATTEMPT_NOT_OPEN` (`attemptStatus`). |
| A2 | Xem dưới bảng | Không có dòng → 404 (snapshot không thuộc lượt). `option_ok = false` → 400 `OPTION_NOT_IN_SNAPSHOT`. |
| A3 | (bộ nhớ) Phân nhánh idempotency/CAS | Bảng tiếp theo |
| A4 | `UPDATE attempt_answers SET selected_option_id = :o, answered_at = CASE WHEN CAST(:o AS uuid) IS NULL THEN NULL ELSE :now END, version = version + 1, last_request_id = :key, updated_at = :now WHERE attempt_id = :a AND snapshot_id = :s AND version = :expected` | Phải ra 1 dòng (đã khóa ở A2). 0 dòng → `IllegalStateException` (không thể xảy ra khi đã giữ khóa). |
| A5 | `UPDATE attempts SET last_active_at = :now WHERE id = :a` | — |

Câu A2:

```sql
SELECT aa.selected_option_id, aa.version, aa.last_request_id,
       (CAST(:o AS uuid) IS NULL
        OR EXISTS (SELECT 1 FROM attempt_snapshot_options o
                   WHERE o.snapshot_id = aa.snapshot_id AND o.id = CAST(:o AS uuid))) AS option_ok
FROM attempt_answers aa
WHERE aa.attempt_id = :a AND aa.snapshot_id = :s
FOR UPDATE OF aa
```

Phân nhánh A3 (`cur*` là giá trị đọc ở A2, `exp = expectedVersion`):

| Điều kiện | Kết quả |
|---|---|
| `key = curRequestId` **và** `curSelected = o` **và** `curVersion = exp + 1` | **Replay:** 200 với trạng thái hiện tại, không ghi gì (bỏ A4, A5) |
| `key = curRequestId` và điều kiện trên sai | 409 `IDEMPOTENCY_CONFLICT` |
| `curVersion ≠ exp` | 409 `VERSION_CONFLICT`, `currentVersion = curVersion`, `currentSelectedOptionId = curSelected` (có thể `null`, dùng `HashMap`) |
| Còn lại | A4, A5, rồi 200 |

- Tổng **4 câu SQL** (A1, A2, A4, A5); replay chỉ tốn 2.
- `is_correct` không bao giờ được ghi ở autosave. A4 không có cột này.
- Test `AutosaveTests.isCorrectStaysNull` kiểm sau mỗi lần ghi.
- **Bảo vệ kép lựa chọn sai câu:**
  - tầng service: `option_ok`;
  - tầng DB: FK `fk_attempt_answers_snapshot_option (snapshot_id, selected_option_id)` (T1) và `attempt_answers_c3` (V13).
  - Nếu service lọt, A4 ném `DataIntegrityViolationException` và client nhận 500. Test `optionOfOtherSnapshot400` chứng minh service chặn trước.

### M2. `AttemptGradingEngine`: một luồng chấm

`modules/results/service/impl/AttemptGradingEngine.java` `[CREATE]`. Bean `@Component` trong `service.impl`, không có hậu tố `Impl`, không có `@Transactional` ở class.

```java
public final class AttemptGradingEngine {
    /** Caller must hold the attempts row FOR UPDATE and be inside a transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public GradingOutcome grade(LockedAttempt attempt, SubmitReason reason, Instant now) { … }
}
public enum SubmitReason { MANUAL, TIME_LIMIT }       // modules/results/service/SubmitReason.java
public record LockedAttempt(UUID id, UUID userId, String category, String sourceType, int scoringVersion,
                            int totalCount, Instant startedAt, Instant deadlineAt) {}   // service package
public record GradingOutcome(int totalCount, int answeredCount, int correctCount, Integer listeningCorrect,
                             Integer readingCorrect, List<PartTally> parts, Instant submittedAt,
                             Instant effectiveFinishedAt, SubmitReason reason, int durationSeconds) {}
public record PartTally(int partNumber, int total, int correct, int answered) {}
```

Người gọi engine:

| Caller | Khóa trước | `reason` |
|---|---|---|
| `submit` (3.M4) | A1 của submit | `MANUAL` |
| Dọn dẹp lúc start (T3 §3.M3 bước 3) | `SELECT … FOR UPDATE` lượt mở | `TIME_LIMIT` |
| `GET /attempts/{id}` khi `deadline_at <= now` (T3 §3.M7) | `SELECT … FOR UPDATE` | `TIME_LIMIT` |
| Autosave A1 khi quá hạn | A1 | `TIME_LIMIT` |

Engine chạy 2 câu SQL.

**G1 — chấm và trả về theo tập hợp:**

```sql
UPDATE attempt_answers aa
SET is_correct = (aa.selected_option_id IS NOT NULL
                  AND aa.selected_option_id = (s.answer_key_snapshot->>'correct_option_snapshot_id')::uuid),
    updated_at = :now
FROM attempt_question_snapshots s
WHERE s.attempt_id = aa.attempt_id AND s.position = aa.question_position AND aa.attempt_id = :a
RETURNING s.question_id AS questionId, s.part_number AS partNumber,
          aa.is_correct AS correct, (aa.selected_option_id IS NULL) AS unanswered
```

Sau G1:
- Kiểm số dòng trả về = `totalCount`. Khác → `IllegalStateException("Answer slots incomplete")` → 500 và log. Bất biến slot của T3 bảo đảm điều này không xảy ra.
- Tổng hợp trong bộ nhớ:
  - `correct` = số dòng `correct`;
  - `answered` = số dòng `!unanswered`;
  - `listening` = số dòng `correct` có `partNumber ∈ 1..4`;
  - `reading` = số dòng `correct` có `partNumber ∈ 5..7`;
  - `parts` gom theo `partNumber` (chỉ TOEIC).
- `listeningCorrect`/`readingCorrect` = `null` khi `category ≠ TOEIC` (V14 `c29`).

**G2 — chốt lượt:**

```sql
UPDATE attempts
SET status = 'SUBMITTED', submitted_at = :now, effective_finished_at = :effective, submit_reason = :reason,
    correct_count = :correct, answered_count = :answered,
    score = CASE WHEN scoring_version = 1 THEN :legacyPercent ELSE :correct END,
    listening_correct = :listening, reading_correct = :reading,
    duration_seconds = :duration, last_active_at = :now, updated_at = :now, version = version + 1
WHERE id = :a AND status = 'IN_PROGRESS'
```

- `effective = (reason == TIME_LIMIT) ? deadlineAt : now`. V14 `c25` ép `effective_finished_at = deadline_at` với `TIME_LIMIT`.
- `duration = max(0, floor(seconds(effective − startedAt)))`.
- `legacyPercent = percent(correct, total)`: cùng công thức V13 (`BigDecimal`, `HALF_UP`; `total = 0` → 0). Chỉ áp cho lượt `scoring_version = 1`. Đây là O4 đã duyệt ở T3.
- Kết quả phải là 1 dòng. 0 dòng → `IllegalStateException` (người gọi đã khóa và đã kiểm `IN_PROGRESS`).

**Phát event:**
- Sau G2: `events.publish(new ResultsEvents.AttemptSubmitted(…))` qua `ModuleEvents.publish`, trong cùng transaction.
- Event chỉ phát trên đường chuyển `IN_PROGRESS → SUBMITTED`, nên không bao giờ phát hai lần cho một lượt.

Luật chấm (giữ V13, tổng quát cho mọi category):
- Đúng ⇔ `selected_option_id = answer_key.correct_option_snapshot_id`.
- Bỏ trống (`selected_option_id IS NULL`) là sai, không trừ điểm (00 §3).
- Với dòng V13 đã backfill, `correct_option_snapshot_id` được dựng từ lựa chọn `is_correct` duy nhất (V15 khối 0 và 2), nên tương đương luật cũ.

### M3. Event `ResultsEvents.AttemptSubmitted`

`common/events/ResultsEvents.java`: thay nội dung T3 5.3.

```java
/** Events published by the results module (ADR-12). Payloads carry ids and counts only, never content. */
public final class ResultsEvents {
    private ResultsEvents() {}

    /**
     * Published once, when an attempt moves from IN_PROGRESS to SUBMITTED. Consumers must be idempotent
     * per (attemptId, questionId) because the publication registry delivers at least once.
     */
    public record AttemptSubmitted(
            UUID eventId,                 // UUID.randomUUID() at publish; for logs and tracing
            UUID attemptId,
            UUID userId,
            String category,              // VOCABULARY | GRAMMAR | TOEIC
            String sourceType,            // PRACTICE_SET | GRAMMAR_TOPIC | MIXED_GRAMMAR
            String submitReason,          // MANUAL | TIME_LIMIT
            Instant submittedAt,
            Instant effectiveFinishedAt,  // order key for mistake lifecycle (CONTRACT §8.2)
            List<QuestionOutcome> outcomes) {
        public AttemptSubmitted {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record QuestionOutcome(UUID questionId, Integer partNumber, boolean correct, boolean unanswered) {
        public QuestionOutcome {
            if (correct && unanswered) {
                throw new IllegalArgumentException("An unanswered question cannot be correct.");
            }
        }
    }
}
```

- **Không có nội dung câu hay JSON snapshot** trong payload.
- Kích thước khi Jackson serialize vào `event_publication.serialized_event`: khoảng 200 × 90 byte ≈ 18 KB mỗi Full Test.
- Ghi bền: Spring Modulith (JPA registry, `completion-mode=update`) chèn một dòng `event_publication` cho **mỗi** `@ApplicationModuleListener` nghe event, trong cùng transaction nộp. Hiện chưa có listener nào, nên chưa có dòng nào (T4-D08). Listener D13 trong `learning` sẽ kích hoạt việc ghi.

### M4. `GET /api/v1/attempts/{attemptId}/results`

| Trạng thái | Response |
|---|---|
| Không phải chủ hoặc không tồn tại | 404 `RESOURCE_NOT_FOUND` |
| `IN_PROGRESS`, `ABANDONED` | 409 `ATTEMPT_NOT_SUBMITTED` (`attemptStatus`) |
| `IN_PROGRESS` có `deadline_at <= now` | Chấm `TIME_LIMIT` trước (engine), rồi trả 200 |
| `SUBMITTED` | 200 `AttemptResultResponse` |

Đọc 100% từ bảng của `results`, **không** join `questions`, `question_options` hay `*_materials`:

| # | SQL | Nội dung |
|---|---|---|
| Q1 | `SELECT id, category, source_type, source_set_id, source_topic_id, status, attempt_mode, total_count, correct_count, answered_count, listening_correct, reading_correct, started_at, submitted_at, effective_finished_at, submit_reason, duration_seconds FROM attempts WHERE id = :a AND user_id = :u` | Header + số liệu |
| Q2 | `SELECT id, ordinal, part_number, public_snapshot::text, private_snapshot::text FROM attempt_group_snapshots WHERE attempt_id = :a ORDER BY ordinal` | Nhóm (public + private) |
| Q3 | `SELECT id, position, display_number, group_snapshot_id, part_number, (question_snapshot - 'meta' - 'legacy_backfill')::text, answer_key_snapshot::text FROM attempt_question_snapshots WHERE attempt_id = :a ORDER BY position` | Câu + đáp án |
| Q4 | `SELECT snapshot_id, id, position, label, body FROM attempt_snapshot_options WHERE attempt_id = :a ORDER BY question_position, position` | Lựa chọn |
| Q5 | `SELECT snapshot_id, selected_option_id, is_correct FROM attempt_answers WHERE attempt_id = :a` | Bài làm |

- Tổng 5 câu. `partBreakdown` tính trong bộ nhớ từ Q3 `part_number` và Q5.
- Tên method trong `AttemptRepository` có hậu tố `ForResult` (T3 §3.M6).
- **Sửa T3 §3.M7:** `GET /api/v1/attempts/{attemptId}` với lượt `SUBMITTED` trả `ActiveAttemptResponse` (`status = SUBMITTED`, chỉ đọc). Endpoint này **không bao giờ** đọc cột private. Đây là lớp chống rò rỉ đơn giản hơn: chỉ `/results` chạm cột private, và chỉ khi `SUBMITTED`.

### M5. `POST /api/v1/attempts/{attemptId}/submit`

Một transaction:

| Bước | SQL | Ghi chú |
|---|---|---|
| S1 | `SELECT id, user_id, category, source_type, scoring_version, total_count, started_at, deadline_at, status FROM attempts WHERE id = :a AND user_id = :u FOR UPDATE` | Không có → 404. `ABANDONED` → 409 `ATTEMPT_NOT_OPEN` (`attemptStatus = ABANDONED`). |
| S1' | `SUBMITTED` → Q1 và `SELECT s.part_number, count(*), count(*) FILTER (WHERE aa.is_correct), count(aa.selected_option_id) FROM attempt_answers aa JOIN attempt_question_snapshots s ON s.attempt_id = aa.attempt_id AND s.position = aa.question_position WHERE aa.attempt_id = :a AND s.part_number IS NOT NULL GROUP BY s.part_number` | 200 với kết quả đã lưu. **Không chấm lại, không phát event.** |
| G1, G2 | Engine (3.M2) với `MANUAL`, hoặc `TIME_LIMIT` khi `deadline_at <= now` | — |
| — | Dựng `AttemptSubmitResponse` từ `GradingOutcome` | Không đọc lại |

- **Lần nộp đầu:** 3 câu SQL (S1, G1, G2), cộng 1 INSERT `event_publication` cho mỗi listener (hiện là 0). Đề yêu cầu ≤ 6.
- **Gọi lại khi đã nộp:** 3 câu.
- Không khóa `users` (T3 §6, thứ tự khóa `attempts → attempt_answers`).

### M6. Số liệu trả về (ADR-11)

| Trường | Công thức |
|---|---|
| `totalCount` | `attempts.total_count` |
| `correctCount` | `correct_count` |
| `answeredCount` | `answered_count` |
| `wrongCount` | `answered_count − correct_count` |
| `unansweredCount` | `total_count − answered_count` |
| `accuracyPercent` | `percent(correct_count, total_count)`: `HALF_UP`, số nguyên 0–100. Tên trường không phải "score". |
| `listeningCorrect`, `readingCorrect` | Cột; `null` khi không phải TOEIC |
| `partBreakdown[]` | `{partNumber, totalCount, correctCount, wrongCount, unansweredCount}`. Chỉ TOEIC, sắp theo Part; category khác trả `[]`. |

**Cấm** các trường `score`, `scaledScore`, `toeicScore`, `listeningScore`, `readingScore`, `estimated*`. Test 11.3 kiểm.

---

## 4. Thiết kế chi tiết

### 4.1. File

| Đường dẫn (`modules/results/…`) | Loại | Nội dung |
|---|---|---|
| `service/SubmitReason.java` | CREATE | Enum |
| `service/LockedAttempt.java`, `GradingOutcome.java`, `PartTally.java` | CREATE | Record (3.M2) |
| `service/AttemptService.java` | MODIFY (so với T3 4.9) | `submit` trả `AttemptSubmitResponse`; thêm `AttemptResultResponse results(UUID userId, UUID attemptId)` |
| `service/impl/AttemptGradingEngine.java` | CREATE | 3.M2 |
| `service/impl/AttemptServiceImpl.java` | MODIFY (T3) | `saveAnswer` theo 3.M1; `submit` theo 3.M5; `results` theo 3.M4; `get` theo 3.M4 (bỏ nhánh result) |
| `service/impl/AttemptResultMapper.java` | MODIFY (T3) | Dựng từ Q1–Q5 |
| `repository/AttemptRepository.java` | MODIFY (T3) | Thêm A1, A2, A4, A5, G1, G2, S1, S1' (breakdown), Q1–Q5 |
| `controller/AttemptController.java` | MODIFY (T3) | Thêm `GET /{attemptId}/results` |
| `dto/response/AttemptSubmitResponse.java` | CREATE | 5.1 |
| `dto/response/AttemptResultResponse.java` | MODIFY (T3) | Theo 5.1 |
| `dto/response/ResultSummaryResponse.java`, `PartBreakdownResultResponse.java` | CREATE | 5.1 |
| `common/error/ErrorCode.java` | MODIFY | Thêm `ATTEMPT_NOT_SUBMITTED` |
| `common/events/ResultsEvents.java` | CREATE (thay bản T3) | 3.M3 |

### 4.2. Khai báo repository

`@Modifying` dùng cho A4, A5, G2. G1 dùng `@Query(nativeQuery = true)` **không** `@Modifying`, trả `List<GradedRow>`, vì có `RETURNING`.

```java
interface GradedRow { UUID getQuestionId(); Integer getPartNumber(); boolean getCorrect(); boolean getUnanswered(); }

@Query(value = "<G1>", nativeQuery = true)
List<GradedRow> gradeAndReturn(@Param("a") UUID attemptId, @Param("now") Instant now);
```

- **Chưa chạy trong phiên.** Giả định Hibernate chạy được native `UPDATE … RETURNING` qua `getResultList`. Test `GradingEngineIntegrationTests.updateReturningWorks` xác nhận.
- Fallback nếu không chạy được: tách thành `@Modifying UPDATE` (G1a) và `SELECT … FROM attempt_answers JOIN attempt_question_snapshots` (G1b). Submit thành 4 câu, vẫn ≤ 6.
- Trước G1 cần `entityManager`-level flush? Không: không có entity `AttemptAnswer` nào được sửa qua JPA (T3 không map entity cho bảng này).

### 4.3. Luồng (sơ đồ)

```
PUT answers   : A1 lock attempt ─► [overdue? engine(TIME_LIMIT) → 409] ─► A2 lock slot + option_ok ─► A3 ─► A4 ─► A5
POST submit   : S1 lock attempt ─► SUBMITTED? S1' → 200 │ ABANDONED → 409 │ IN_PROGRESS → G1 ─► G2 ─► publish ─► 200
GET results   : Q1 (owner) ─► status? 409 │ overdue → lock + engine │ SUBMITTED → Q2..Q5 ─► 200
GET attempt   : (T3 R1–R5, không đổi) mọi trạng thái trả ActiveAttemptResponse
```

### 4.4. Delta tài liệu (thực hiện trong T4)

| File | Thay đổi |
|---|---|
| `docs/m05/tasks/T3-…md` | Đã ghi ở trạng thái: T4 thắng ở 4.6, 4.7, 5.1 (PUT/submit/GET khi `SUBMITTED`), 5.3 |
| `docs/m05/02-…md` §8 | `GET /attempts/{id}` không trả kết quả ở mọi trạng thái; thêm `AttemptSubmitResponse`, `AttemptResultResponse` theo 5.1 |
| `docs/m05/01-…md` ADR-12 | Payload event theo 3.M3 (`eventId`, `submitReason`, `submittedAt`, `QuestionOutcome(questionId, partNumber, correct, unanswered)`) |
| `docs/practice/CONTRACT.md` §2.1, §3.2, §4.3 | Event theo 3.M3; thêm `GET /attempts/{id}/results`; thêm `ATTEMPT_NOT_SUBMITTED` |

---

## 5. Hợp đồng giao tiếp

Chung: role `LEARNER`; `PUT`/`POST` cần `X-XSRF-TOKEN`; lỗi là `application/problem+json` (`type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, `errors` + metadata R8).

### 5.1. HTTP

#### `PUT /api/v1/attempts/{attemptId}/answers/{snapshotId}`

Header: `Idempotency-Key: 3f8a…` (UUID), `X-XSRF-TOKEN`.

```json
{ "selectedOptionId": "5b6c7d8e-2222-4c3d-9e4f-000000000424", "expectedVersion": 2 }
```

200 (ghi mới hoặc replay):

```json
{ "snapshotId": "9a0b1c2d-6666-4a71-8c83-000000000042",
  "selectedOptionId": "5b6c7d8e-2222-4c3d-9e4f-000000000424", "version": 3, "answeredAt": "2026-11-26T02:15:02Z" }
```

| Lỗi | HTTP | `code` / metadata |
|---|---|---|
| Header hoặc body sai (`expectedVersion` thiếu hoặc âm) | 400 | `VALIDATION_ERROR` |
| Không phải chủ; snapshot không thuộc lượt | 404 | `RESOURCE_NOT_FOUND` |
| Lượt `SUBMITTED`/`ABANDONED` (kể cả vừa bị tự nộp) | 409 | `ATTEMPT_NOT_OPEN` (`attemptStatus`) |
| Lựa chọn không thuộc snapshot | 400 | `OPTION_NOT_IN_SNAPSHOT` |
| Trùng key, trạng thái khác | 409 | `IDEMPOTENCY_CONFLICT` |
| `expectedVersion` lệch | 409 | `VERSION_CONFLICT` (`currentVersion`, `currentSelectedOptionId`) |

```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "The answer was changed by another request.",
  "instance": "/api/v1/attempts/7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50/answers/9a0b1c2d-6666-4a71-8c83-000000000042",
  "code": "VERSION_CONFLICT", "currentVersion": 3, "currentSelectedOptionId": null,
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", "errors": [] }
```

**Va chạm tên `status`.**
- Khóa metadata `status` của `ATTEMPT_NOT_OPEN` (CONTRACT §4.3, T3 3.M2) trùng tên với trường chuẩn `status` (HTTP status) của ProblemDetail. Body lỗi sẽ có hai khóa `status`, hoặc giá trị `"SUBMITTED"` đè lên `409`.
- Brief dùng tên metadata **`attemptStatus`** cho cả `ATTEMPT_NOT_OPEN` và `ATTEMPT_NOT_SUBMITTED` (T4-D13).

Ví dụ:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "The attempt is no longer open.",
  "instance": "/api/v1/attempts/7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50/answers/9a0b1c2d-6666-4a71-8c83-000000000042",
  "code": "ATTEMPT_NOT_OPEN", "attemptStatus": "SUBMITTED",
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", "errors": [] }
```

`ErrorCode`:
- `ATTEMPT_NOT_OPEN(HttpStatus.CONFLICT, DetailKey.enumValue("attemptStatus"))`;
- `ATTEMPT_NOT_SUBMITTED(HttpStatus.CONFLICT, DetailKey.enumValue("attemptStatus"))`.

#### `POST /api/v1/attempts/{attemptId}/submit`

Header `X-XSRF-TOKEN`. Không có body.

200 `AttemptSubmitResponse`:

```json
{ "attemptId": "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50", "status": "SUBMITTED",
  "result": { "totalCount": 24, "correctCount": 17, "wrongCount": 5, "unansweredCount": 2, "accuracyPercent": 71,
              "listeningCorrect": 8, "readingCorrect": 9,
              "partBreakdown": [ { "partNumber": 1, "totalCount": 2, "correctCount": 2, "wrongCount": 0, "unansweredCount": 0 },
                                 { "partNumber": 7, "totalCount": 7, "correctCount": 4, "wrongCount": 2, "unansweredCount": 1 } ],
              "submittedAt": "2026-11-26T02:40:00Z", "effectiveFinishedAt": "2026-11-26T02:40:00Z",
              "submitReason": "MANUAL", "durationSeconds": 1800 },
  "resultsUrl": "/api/v1/attempts/7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50/results" }
```

- Ví dụ trên rút gọn `partBreakdown`; với TOEIC, mảng có mọi Part xuất hiện trong lượt.
- Kiểm số: `accuracyPercent = HALF_UP(17 × 100 / 24) = 71` (70,83).

| Lỗi | HTTP | `code` |
|---|---|---|
| Không phải chủ | 404 | `RESOURCE_NOT_FOUND` |
| `ABANDONED` | 409 | `ATTEMPT_NOT_OPEN` (`attemptStatus`) |

#### `GET /api/v1/attempts/{attemptId}/results`

200 `AttemptResultResponse`:

```json
{ "id": "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50", "category": "TOEIC", "attemptMode": "PRACTICE", "status": "SUBMITTED",
  "source": { "type": "PRACTICE_SET", "practiceSetId": "e5000000-0030-4000-8000-000000000001", "topicIds": [] },
  "result": { "totalCount": 24, "correctCount": 17, "wrongCount": 5, "unansweredCount": 2, "accuracyPercent": 71,
              "listeningCorrect": 8, "readingCorrect": 9, "partBreakdown": [],
              "submittedAt": "2026-11-26T02:40:00Z", "effectiveFinishedAt": "2026-11-26T02:40:00Z",
              "submitReason": "MANUAL", "durationSeconds": 1800 },
  "groups": [
    { "groupSnapshotId": "0a1b2c3d-1111-4a2b-8c3d-000000000041", "ordinal": 1, "partNumber": 3,
      "groupType": "CONVERSATION", "title": null, "speakerCount": 3,
      "materials": [ { "materialKey": "graphic", "materialType": "TABLE", "position": 2, "mediaUrl": null,
                       "body": { "headers": ["Room", "Available from"], "rows": [["Room D", "2:00 P.M."]] } } ],
      "reviewMaterials": [ { "materialKey": "transcript", "materialType": "TRANSCRIPT", "position": 1, "mediaUrl": null,
                             "body": { "segments": [ { "type": "speech", "speaker": "W-Br",
                                                       "text": "Room D is free on Thursday, but only in the afternoon." } ] } } ] } ],
  "questions": [
    { "snapshotId": "9a0b1c2d-6666-4a71-8c83-000000000001", "ordinal": 1, "displayNumber": 1, "groupSnapshotId": null,
      "partNumber": 1, "questionKind": "STANDARD", "optionDisplay": "LABEL_ONLY", "prompt": null, "blankKey": null,
      "insertSentence": null, "refs": [], "materials": [],
      "options": [ { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000011", "label": "A", "body": null } ],
      "answer": { "selectedOptionId": null, "isCorrect": false },
      "review": { "correctOptionId": "8f9a0b1c-5555-4f60-8b72-000000000013", "correctOptionLabel": "C",
                  "explanation": "Only statement C describes two people moving a table.", "evidence": [],
                  "optionTranscripts": [ { "label": "C", "speaker": "M-Au", "text": "They're carrying a table." } ],
                  "promptTranscript": null } } ] }
```

- Ví dụ trên rút gọn mảng và nội dung (`partBreakdown`, `materials`, `options`, transcript); response thật đầy đủ.
- `result.partBreakdown` thật có mọi Part với TOEIC.
- `review.optionTranscripts` thật có đủ 4 (P1) hoặc 3 (P2) phần tử.

| Lỗi | HTTP | `code` |
|---|---|---|
| Không phải chủ | 404 | `RESOURCE_NOT_FOUND` |
| Chưa nộp hoặc bị bỏ | 409 | `ATTEMPT_NOT_SUBMITTED` (`attemptStatus`) |

### 5.2. Facade dùng

- `ModuleEvents.publish` (phát event).
- Không gọi facade đồng bộ nào khác. Chấm điểm chỉ dùng dữ liệu của `results`.

### 5.3. Event

`ResultsEvents.AttemptSubmitted` (3.M3). Ví dụ đã serialize:

```json
{ "eventId": "c1d2e3f4-0000-4a1b-8c2d-000000000001", "attemptId": "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50",
  "userId": "11111111-2222-4333-8444-555555555555", "category": "TOEIC", "sourceType": "PRACTICE_SET",
  "submitReason": "MANUAL", "submittedAt": "2026-11-26T02:40:00Z", "effectiveFinishedAt": "2026-11-26T02:40:00Z",
  "outcomes": [ { "questionId": "e5000000-0010-4000-8000-000000000001", "partNumber": 1, "correct": false, "unanswered": true },
                { "questionId": "e5000000-0010-4000-8000-000000000006", "partNumber": 3, "correct": true, "unanswered": false } ] }
```

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn

| Rủi ro | Biện pháp |
|---|---|
| Đáp án lộ trước khi nộp | Chỉ `/results` đọc cột private, và chỉ khi `SUBMITTED`. `GET /attempts/{id}` không bao giờ đọc (3.M4). Autosave không trả gì ngoài trạng thái slot của chính người gọi. |
| `is_correct` lộ qua autosave | Autosave không ghi và không đọc `is_correct` |
| IDOR | A1, S1, Q1 lọc `user_id`; không phải chủ → 404 |
| CSRF | `PUT`/`POST` qua cấu hình hiện có |
| Request đến trễ ghi đè đáp án mới | CAS `version = expectedVersion` |
| Retry mạng gây 409 giả | Replay theo `last_request_id` |
| Autosave chen vào lúc nộp | Cả hai khóa `attempts FOR UPDATE` trước; bên đến sau thấy trạng thái mới |
| Nộp hai lần | Lần hai chờ khóa, thấy `SUBMITTED`, trả kết quả đã lưu, không phát event |
| Thứ tự khóa | `attempts` → `attempt_answers` (A1→A2, S1→G1). Không luồng nào khóa ngược. Start (T3) khóa `users` → `attempts` và không khóa `attempt_answers` khi đang giữ `users` → `attempts` theo thứ tự ngược. Không có vòng. |
| Event mang nội dung đề | Payload chỉ có ID và cờ (3.M3) |
| Metadata `status` đè trường chuẩn | Đổi tên thành `attemptStatus` (5.1) |

---

## 7. Ranh giới Spring Modulith và tài nguyên

- **DAG không đổi so với T3.** `results` phát event trong `common/events`. Chưa module nào nghe.
- **Lớp mới:** `AttemptGradingEngine` và các record ở `service`/`service.impl`. Rule `servicesDeclareTransactionsAtMethodLevel`: `@Transactional` chỉ đặt ở method.

**Round-trip:**

| Thao tác | SQL |
|---|---|
| Autosave | 4 (replay 2) |
| Submit lần đầu | 3 (+1 cho mỗi listener sau này) |
| Submit lại | 3 |
| `/results` | 5 |

**Pool 5 kết nối:**
- Mỗi request giữ một kết nối trong thời gian transaction ngắn.
- G1 cập nhật tối đa 200 dòng `attempt_answers` theo PK. Đây là HOT update: `is_correct`, `updated_at` không có index, và `fillfactor = 90` (T1).

**RAM:**
- G1 `RETURNING` 200 dòng, khoảng 20 KB.
- `/results` Full Test khoảng 300–500 KB JSON.
- Không cache.

---

## 8. Edge Cases & Failure Modes Matrix

| # | Tình huống | Nguyên nhân | Hành vi | Kết quả |
|---|---|---|---|---|
| 1 | Câu chưa chọn, `expectedVersion = 0` | Lần chọn đầu | A4 khớp `version = 0` | 200, `version = 1` |
| 2 | Hai tab cùng `expectedVersion` | Song song | Bên sau chờ khóa slot, thấy `version` đã tăng | 409 `VERSION_CONFLICT` kèm trạng thái mới |
| 3 | Retry sau khi đã ghi | Mất response | Khớp key, lựa chọn, `version` | 200, không ghi thêm |
| 4 | Dùng lại key cũ cho thao tác khác | Bug client | Key trùng, trạng thái khác | 409 `IDEMPOTENCY_CONFLICT` |
| 5 | Bỏ chọn | Người học | `selected = NULL`, `answered_at = NULL` | 200, `version + 1` |
| 6 | Bỏ chọn khi đang trống | Người học | Ghi vẫn hợp lệ, `version + 1` | 200 |
| 7 | Lựa chọn của câu khác | Bug client | `option_ok = false` | 400 `OPTION_NOT_IN_SNAPSHOT` |
| 8 | Autosave sau khi nộp | Race hoặc tab cũ | A1 | 409 `ATTEMPT_NOT_OPEN` (`attemptStatus = SUBMITTED`) |
| 9 | Autosave lượt `ABANDONED` | Lượt bị dọn | A1 | 409 (`ABANDONED`) |
| 10 | Nộp hai lần đồng thời | Double click | Bên sau thấy `SUBMITTED` | 200 cả hai, 1 event |
| 11 | Nộp lượt `ABANDONED` | — | — | 409 `ATTEMPT_NOT_OPEN` |
| 12 | Nộp khi mọi câu bỏ trống | — | `correct = 0`, `answered = 0` | 200, `accuracyPercent = 0` |
| 13 | Lượt V13 (`scoring_version = 1`) nộp sau T4 | Dữ liệu cũ | `score = percent HALF_UP` | Hợp lệ với `c23` |
| 14 | Lượt có giờ quá hạn, người học gọi `/results` | Exam sau này | Engine `TIME_LIMIT` trước | 200 |
| 15 | Số slot ≠ `total_count` | Dữ liệu hỏng | G1 kiểm số dòng | 500 + log, rollback |
| 16 | `/results` của lượt đang làm | Frontend gọi sớm | — | 409 `ATTEMPT_NOT_SUBMITTED` |
| 17 | `UPDATE … RETURNING` không chạy qua Hibernate | Môi trường | Test 11.2 phát hiện | Fallback 4.2 |
| 18 | Listener tương lai chạy lại event (at-least-once) | Sự cố | Listener idempotent theo `(attemptId, questionId)` | Không đếm đúp |

---

## 9. Kế hoạch chỉnh sửa file

| Đường dẫn | Hành động |
|---|---|
| `src/main/java/com/masterenglish/api/modules/results/service/{SubmitReason,LockedAttempt,GradingOutcome,PartTally}.java` | CREATE |
| `src/main/java/com/masterenglish/api/modules/results/service/impl/AttemptGradingEngine.java` | CREATE |
| `src/main/java/com/masterenglish/api/modules/results/{service/AttemptService, service/impl/AttemptServiceImpl, service/impl/AttemptResultMapper, repository/AttemptRepository, controller/AttemptController}.java` | MODIFY (so với T3) |
| `src/main/java/com/masterenglish/api/modules/results/dto/response/{AttemptSubmitResponse,ResultSummaryResponse,PartBreakdownResultResponse}.java` | CREATE |
| `src/main/java/com/masterenglish/api/modules/results/dto/response/AttemptResultResponse.java` | MODIFY |
| `src/main/java/com/masterenglish/api/common/error/ErrorCode.java` | MODIFY (`ATTEMPT_NOT_SUBMITTED`; khóa `attemptStatus`) |
| `src/main/java/com/masterenglish/api/common/events/ResultsEvents.java` | CREATE |
| `src/test/java/com/masterenglish/api/modules/results/**` | CREATE |
| `docs/m05/02-…md`, `docs/m05/01-…md`, `docs/practice/CONTRACT.md` | MODIFY (4.4) |
| `src/main/resources/db/migration/**` | DO NOT TOUCH (T4 không có migration) |
| `modules/practice/**`, `modules/content/**` | DO NOT TOUCH |

---

## 10. Lộ trình commit

Thay bước 4 của T3. Mỗi bước: test được chọn xanh và `.\gradlew.bat spotlessCheck`.

| Bước | Nội dung | Lệnh | Commit đề xuất |
|---|---|---|---|
| 1 | `ErrorCode.ATTEMPT_NOT_SUBMITTED`, khóa `attemptStatus`, `ResultsEvents` | `--tests 'com.masterenglish.api.common.*' --tests '*ErrorHandlingTests'` | `feat(common): mã lỗi kết quả và event nộp bài` |
| 2 | `AttemptGradingEngine` + unit test | `--tests '*GradingEngine*'` | `feat(results): động cơ chấm điểm dùng chung` |
| 3 | Autosave CAS | `--tests '*Autosave*'` | `feat(results): lưu đáp án với CAS và idempotency` |
| 4 | Submit, `/results`, GET không còn trả kết quả | `--tests 'com.masterenglish.api.modules.results.*'` | `feat(results): nộp bài, xem kết quả và phát sự kiện` |
| 5 | Delta tài liệu | Script C-01/C-02 | `docs(m05): cập nhật hợp đồng chấm điểm và kết quả` |
| 6 | Cổng cuối | `.\gradlew.bat check bootJar` | — |

---

## 11. Test Matrix

### 11.1. Unit

| Test | Assert |
|---|---|
| `PercentTests.halfUpLikeV13` | `percent(2,3) = 67`, `percent(1,8) = 13`, `percent(17,24) = 71`, `percent(0,0) = 0` |
| `QuestionOutcomeTests.unansweredCannotBeCorrect` | Constructor ném `IllegalArgumentException` |
| `ResultSummaryTests.wrongAndUnansweredDerived` | `wrong = answered − correct`, `unanswered = total − answered` |
| `ResultSummaryTests.nonToeicHasNullSectionsAndEmptyParts` | — |

### 11.2. Integration (Testcontainers PostgreSQL 17, seed T1, start qua API T3)

| Test | Assert |
|---|---|
| `AutosaveTests.firstSelectFromZero` | Edge #1 |
| `AutosaveTests.deselect` / `deselectEmpty` | Edge #5, #6 |
| `AutosaveTests.replaySameKeyNoWrite` | `version` và `updated_at` không đổi |
| `AutosaveTests.reuseKeyDifferentState409` | Edge #4 |
| `AutosaveTests.versionConflictCarriesNullSelection` | JSON có `"currentSelectedOptionId": null` |
| `AutosaveTests.optionOfOtherSnapshot400` | — |
| `AutosaveTests.isCorrectStaysNull` | Sau mỗi bước, mọi `is_correct` của lượt là NULL |
| `AutosaveTests.submittedAndAbandoned409` | `attemptStatus` đúng |
| `AutosaveTests.fourStatements` | Hibernate statistics: 4 câu |
| `GradingEngineIntegrationTests.updateReturningWorks` | G1 trả 24 dòng cho S01 |
| `GradingEngineIntegrationTests.toeicSectionsFromSeed` | Chọn đúng Q01–Q10, sai Q11, bỏ trống còn lại → `correct = 10`, `listening = 10`, `reading = 0`, `answered = 11` |
| `GradingEngineIntegrationTests.grammarHasNullSections` | — |
| `GradingEngineIntegrationTests.legacyScoringVersion1KeepsPercent` | Fixture V13 (backfill): `score = percent` |
| `GradingEngineIntegrationTests.timeLimitUsesDeadline` | `effective_finished_at = deadline_at`, `submit_reason = 'TIME_LIMIT'` |
| `SubmitTests.firstSubmitThreeStatements` | — |
| `SubmitTests.secondSubmitReturnsStoredNoEvent` | `@RecordApplicationEvents`: đúng 1 `AttemptSubmitted` |
| `SubmitTests.abandoned409` | — |
| `SubmitTests.eventPayloadHasNoContent` | Event serialize thành JSON không chứa `prompt`, `explanation`, `body`, `snapshot` |
| `ResultsTests.inProgress409NotSubmitted` / `submittedReturnsReview` / `otherUser404` / `fiveStatements` | 3.M4 |
| `ResultsTests.readsOnlyResultsTables` | Kiểm chuỗi `@Query` của Q1–Q5 (reflection) không chứa `questions `, `question_options`, `group_materials`, `question_materials` |
| `AttemptGetTests.submittedReturnsActiveShapeOnly` | `GET /attempts/{id}` sau nộp không có `review`, `reviewMaterials`, `correctOptionId` |

### 11.3. Security / Anti-leak

| Test | Assert |
|---|---|
| `ResultFieldNamingTests.noScoreFields` | JSON `/results` và submit không có khóa nào khớp `(?i)score\|scaled\|estimated` |
| `PreSubmitLeakTests.*` (T3) | Giữ xanh; thêm ca cho lượt `SUBMITTED` |

### 11.4. Concurrency (không `@Transactional`)

| Test | Assert |
|---|---|
| `AutosaveConcurrencyTests.sameVersionOneWins` | {200, 409} |
| `SubmitConcurrencyTests.doubleSubmitOneEvent` | {200, 200}, 1 event, `correct_count` không đổi |
| `SubmitConcurrencyTests.autosaveRacingSubmit` | Không slot nào có `updated_at > submitted_at`, ngoại trừ cập nhật `is_correct` của chính G1 |

### 11.5. ArchUnit

`ArchitectureTests`, `LayerArchitectureTests` (14 rule) xanh.

---

## 12. Definition of Done & Open Decisions

### 12.1. DoD

| # | Tiêu chí | Pass/Fail |
|---|---|---|
| 1 | Autosave ≤ 4 câu SQL; submit lần đầu ≤ 3 (+ listener); `/results` ≤ 5 | ☐ |
| 2 | `is_correct` NULL suốt quá trình làm bài; chỉ G1 ghi | ☐ |
| 3 | Một `AttemptSubmitted` mỗi lượt, payload không có nội dung | ☐ |
| 4 | `GET /attempts/{id}` không trả dữ liệu private ở mọi trạng thái | ☐ |
| 5 | Luật chấm V13 giữ nguyên (`PercentTests`, `legacyScoringVersion1KeepsPercent`) | ☐ |
| 6 | Không có trường tên điểm hay 990 | ☐ |
| 7 | Test 11.1–11.5 xanh; `.\gradlew.bat spotlessCheck check bootJar` xanh | ☐ |
| 8 | Delta tài liệu 4.4 đã ghi | ☐ |

### 12.2. Câu hỏi cần duyệt

| # | Câu hỏi | Đề xuất |
|---|---|---|
| O1 | Đổi khóa metadata `status` → `attemptStatus` để không đè trường `status` (HTTP) của ProblemDetail (CONTRACT và T3 đang dùng `status`)? | Đổi. Giữ `status` sẽ làm body lỗi có `"status": "SUBMITTED"` thay cho `409`, phá RFC 7807. |
| O2 | `GET /attempts/{id}` với lượt `SUBMITTED` trả `ActiveAttemptResponse` chỉ đọc, kết quả chỉ ở `/results` (sửa T3 §3.M7)? | Đồng ý |
| O3 | Submit trả bản tóm tắt cộng `resultsUrl`, không trả toàn bộ lời giải (sửa T3 4.6)? | Đồng ý, để giữ SLA < 500 ms |
| O4 | Event dùng `boolean correct` và `boolean unanswered` (theo đề T4), có kiểm hợp lệ ở constructor, thay `outcome` chuỗi của T3? | Đồng ý |
