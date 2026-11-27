# T5 — Implementation Brief: Tích hợp liên module qua `common/events` và listener `learning` (`mistake_records`)

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026. O1–O4 theo đề xuất: không tạo `AttemptCreationFacade` (giữ T3 O2); câu bỏ trống, kể cả do hết giờ, tính là lỗi sai; giữ `completion-mode=update` và xóa publication `COMPLETED` sau 7 ngày; thêm `last_wrong_at`, chưa thêm 3 cột lifecycle D13, giữ FK sở hữu tới `attempts`. Test chạy lại event được sửa ở rà soát chéo (C-09). |
| Ngày | 26/11/2026 |
| Phụ thuộc | T0, T1, T2, T3, T4 (đã duyệt). T5 tiêu thụ `ResultsEvents.AttemptSubmitted` đúng như T4 §3.M3. |
| Chế độ viết brief | READ-ONLY. Không sửa Java, TypeScript, YAML, `.sql`. |

Nhãn: `[CREATE]`, `[MODIFY]`, `[DO NOT TOUCH]`.

**Cách triển khai.** T5 chạy sau khi code T3 và T4 đã vào nhánh. T5 là listener `@ApplicationModuleListener` **đầu tiên** của repo. Vì vậy đây cũng là lần đầu bảng `event_publication` có dữ liệu (T4-D08).

---

## 1. Metadata & Traceability

### 1.1. Sản phẩm

1. Migration `V18__create_mistake_records.sql`: tạo hai bảng `mistake_records` và `mistake_processed_attempts` (3.M2).
2. Trong module `learning`:
   - entity `MistakeRecord`;
   - repository `MistakeRecordRepository` với **một câu SQL** bulk upsert idempotent;
   - listener `MistakeRecordListener` (3.M3).
3. Hạ tầng event (3.M4):
   - executor riêng `moduleEventExecutor` (`core=1`, `max=2`, `queue=100`);
   - `@EnableScheduling`;
   - job `EventPublicationMaintenance`: gửi lại publication lỗi, dọn publication đã xong;
   - cấu hình `spring.modulith.events.staleness.*`.
4. Bốn rule ArchUnit mới và một test ranh giới SQL (3.M5).
5. Đồ thị phụ thuộc Unified Engine (3.M1), không tạo `AttemptCreationFacade` (O1).
6. Delta tài liệu (4.8).

### 1.2. Traceability

| Mã | Nội dung T5 |
|---|---|
| M03.12–M03.14 | Test từ vựng chạy qua `POST /api/v1/attempts` `PRACTICE_SET` (VOCABULARY). Không có lời gọi từ `vocabulary` (3.M1). |
| M03.15 | Câu `LISTEN_AND_CHOOSE`: `question_materials` AUDIO → `attempt_media_refs` → redirect media của T3. Không có code mới. |
| M03.16, M04.06, M04.10, M04.11 | Kết quả và chấm chung qua `results` (T4). T5 chỉ thêm hậu xử lý sau nộp. |
| M04.09 (phần "tập lỗi sai") | Chỉ **ghi dữ liệu** `OPEN`. Luồng luyện lại vẫn hoãn (D13). |
| M05.08, M05.09 | Event sau nộp: tiêu thụ idempotent |
| S3 | `mistake_records` thuộc `learning` |
| ADR-12 | `results` → `ResultsEvents.AttemptSubmitted` → `learning`; idempotent theo `(attempt_id, question_id)` |
| R2, S2 | Chỉ lượt `SUBMITTED` phát event (T4). Listener không đọc trạng thái lượt. |
| R5 | Không động tới 3 endpoint grammar cũ (T3 đã gỡ) |
| D13 | Phạm vi MVP: chỉ tạo mới hoặc làm mới về `OPEN` |

### 1.3. Explicitly Out-of-Scope

- `AttemptCreationFacade`, `ResultsEvents.startAttempt`. T3 O2 đã duyệt "không tạo". Xem câu hỏi O1 và Phụ lục A.
- Phần còn lại của D13:
  - chuyển `OPEN → IMPROVING → RESOLVED` khi làm đúng;
  - bảng `attempt_source_mistakes`, `source_type = MISTAKES`;
  - API `GET /api/v1/mistakes` (M06), index `ix_mistakes_user_state`;
  - cột `resolved_at`, `source_snapshot_id`, `last_evaluated_at` của DD.
- Cấu hình `spring.datasource.hikari.maximum-pool-size=5`. Đây là biến môi trường lúc deploy, chốt ở T7. T5 chỉ bảo đảm listener không vượt 2 kết nối.
- Mọi mục trong hàng rào YAGNI của PREAMBLE §4.
- Endpoint HTTP mới: **không có**.

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc

| Nhóm | File |
|---|---|
| Quy ước | `AGENTS.md`, `CLAUDE.md`, `docs/ARCHITECTURE.md:3-89` |
| Brief | `docs/m05/tasks/T0..T4`, `docs/m05/01-adrs-07-to-13.md:93-99` (ADR-12), `docs/practice/CONTRACT.md` §0 (D13), §1, §2.1, §8 |
| `common/events` | `ModuleEvents`, `EventReply`, `package-info` (`@NamedInterface("events")`), `ContentEvents`, `GrammarEvents`, `IdentityEvents`, `PracticeEvents`, `VocabularyEvents`; `infrastructure/events/SpringModuleEvents.java` |
| Module | `modules/{learning,vocabulary,grammar,practice,content,identity,admin}/package-info.java`; cây file `learning`, `vocabulary`, `grammar`; `grammar/service/impl/GrammarProjectionListener.java`; `learning/repository/{LessonProgressRepository,UserSavedWordRepository}.java` |
| Migration | `db/migration/V1..V13` (repo chưa có V14–V17; các bản này được T1–T3 đặc tả) |
| Schema thiết kế | `docs/database/proposals/approved-baseline.sql:848-873,1246-1252,1796-1802`, `docs/database/DATA_DICTIONARY.md:59-60` |
| Cấu hình | `application.properties`, khóa của `application-local.properties` (chỉ đọc tên khóa), `config/*.java`, `common/time/TimeConfiguration.java`, `build.gradle` |
| Kiến trúc | `ArchitectureTests.java`, `LayerArchitectureTests.java`, `META-INF/spring.factories` |
| Thư viện | Source jar Spring Modulith 2.1.1 trong Gradle cache (2.2) |
| Lệnh đã chạy | `./gradlew.bat dependencies --configuration testRuntimeClasspath --offline -q`: có `org.awaitility:awaitility:4.3.0`, `spring-modulith-test:2.1.1`, `tools.jackson.core:jackson-databind:3.1.7`, `com.fasterxml.jackson.core:jackson-databind:2.21.7` |

### 2.2. Sự thật đã kiểm chứng trong Spring Modulith 2.1.1 (đọc source jar)

| # | Sự thật | Hệ quả cho T5 |
|---|---|---|
| F1 | `@ApplicationModuleListener` = `@Async` + `@Transactional(propagation = REQUIRES_NEW)` + `@TransactionalEventListener` (mặc định `AFTER_COMMIT`). Có thuộc tính `id`, `condition`, `readOnlyTransaction`. | Không cần tự ghép annotation. Dùng `id` để cố định `listener_id`. |
| F2 | `EventPublicationAutoConfiguration.AsyncEnablingConfiguration` có `@EnableAsync`, điều kiện `@ConditionalOnMissingBean(AbstractAsyncConfiguration)`. | Repo **không** cần `@EnableAsync`. Chọn executor bằng `AsyncConfigurer`. |
| F3 | `StalenessMonitorConfiguration` là `SchedulingConfigurer`. Nó chỉ chạy khi có `@EnableScheduling`, và chỉ khi ít nhất một ngưỡng `spring.modulith.events.staleness.{published,processing,resubmitted}` khác 0. | Hiện monitor không chạy (repo không có `@EnableScheduling`). T5 bật. |
| F4 | `PersistentApplicationEventMulticaster` implement `FailedEventPublications.resubmit(ResubmissionOptions)` và `IncompleteEventPublications`. | Job gửi lại inject `FailedEventPublications`. |
| F5 | `DefaultEventPublicationRegistry` implement `CompletedEventPublications`. `deletePublicationsOlderThan(Duration)` gọi `deleteCompletedPublicationsBefore`, nên **chỉ xóa bản đã hoàn tất**. | Job dọn không đụng publication `FAILED`. |
| F6 | `EventPublication.Status` gồm `PUBLISHED`, `PROCESSING`, `COMPLETED`, `FAILED`, `RESUBMITTED`. Có `getCompletionAttempts()`. `ResubmissionOptions` có `withMaxInFlight`, `withBatchSize`, `withMinAge`, `withFilter`. `maxInFlight` tính theo số dòng `RESUBMITTED`. | Lọc publication "độc" theo số lần thử. |
| F7 | Khi gửi lại mà không tìm thấy listener có `listener_id` khớp, Modulith log `Listener … not found` và để publication ở `FAILED`. | Đổi tên class hoặc method listener sẽ bỏ rơi event đang chờ. Bắt buộc dùng `id` cố định. |
| F8 | `JacksonEventSerializationConfiguration` (Jackson 3, `JsonMapper`) có `@AutoConfigureBefore` bản Jackson 2. Cả hai có `@ConditionalOnMissingBean(EventSerializer)`. Classpath có `tools.jackson` 3.1.7. | Event được serialize bằng `JsonMapper` của Boot (Jackson 3). Bean `ObjectMapper` Jackson 2 trong `JacksonConfiguration` **không** được dùng cho event. |
| F9 | Thuộc tính có sẵn: `spring.modulith.events.completion-mode` (mặc định `update`), `republish-outstanding-events-on-restart` (mặc định `false`), `staleness.{published,processing,resubmitted,check-interval}`. | 4.7 |

### 2.3. Drift

| Mã | Đề T5 / tài liệu | Repo / quyết định đã duyệt | Xử lý |
|---|---|---|---|
| T5-D01 | Đề B.1: `vocabulary` và `grammar` gọi `AttemptCreationFacade` (trong `common/events`, `results` implement). | T3 O2 **đã duyệt "không tạo"** (T3 §3.M1, T3-D09). Frontend mở mọi lượt qua `POST /api/v1/attempts` với `source.type`. Không module nào cần mở lượt. Nếu `grammar` gọi `results`, ta có vòng lúc chạy `grammar → results → practice → grammar` (T2: `practice` gọi `GrammarEvents.isVisibleTopic`). | Giữ quyết định T3. Đồ thị ở 3.M1. Chữ ký dự phòng ở Phụ lục A. **Câu hỏi O1.** |
| T5-D02 | Đề B.1: `grammar` ghi `attempt_source_topics` cho Mixed Grammar. | T3 W2: `results` ghi `attempt_source_topics` trong transaction start. `grammar` không sở hữu bảng này (CONTRACT §1). | Không đổi. `grammar` chỉ trả lời `isVisibleTopic`. |
| T5-D03 | Đề gọi event là `AttemptSubmittedEvent` với `isCorrect`. | T4 §3.M3 (đã duyệt O4): `ResultsEvents.AttemptSubmitted`, `QuestionOutcome(questionId, partNumber, boolean correct, boolean unanswered)`. Bỏ trống thì `correct = false`. | Dùng đúng T4. Điều kiện "`!isCorrect`" chính là `!correct`, đã gồm câu bỏ trống. |
| T5-D04 | Cột `mistake_records` theo đề: `status`, `consecutive_correct_count`, `last_processed_attempt_id`, `created_at`. | DD `approved-baseline.sql:848-866` dùng `state`, `consecutive_correct`, có thêm `source_snapshot_id`, `last_wrong_at`, `last_evaluated_at`, `resolved_at`, FK tới `attempt_question_snapshots`. | Theo tên của đề. **Thêm `last_wrong_at`** của DD để `source_attempt_id` không phụ thuộc thứ tự giao event (3.M3). Bỏ `source_snapshot_id`: event T4 không mang `snapshotId`, và listener không được đọc bảng của `results`. Ba cột lifecycle còn lại đợi D13 (additive). Ghi lệch vào CONTRACT §14.4. **Câu hỏi O4.** |
| T5-D05 | Đề B.3 cho chọn giữa bảng guard và `last_processed_attempt_id IS DISTINCT FROM EXCLUDED…`. | Cách thứ hai sai trong 2 trường hợp: (a) event của lượt A được giao lại **sau** khi lượt B đã ghi đè `last_processed_attempt_id`; (b) lượt toàn câu đúng không để lại dấu vết nào. | Dùng bảng guard `mistake_processed_attempts`, gộp vào **cùng một câu SQL** (3.M3). |
| T5-D06 | `DATA_DICTIONARY.md:59-60` xếp `mistake_records` vào nhóm "Results". | S3 (T0, đã duyệt): thuộc `learning`. `DATA_DICTIONARY` được dựng từ `approved-baseline.sql` bằng `docs/database/tools/`. | `[DO NOT TOUCH]` `docs/database/**`. Ghi lệch ở CONTRACT §14.4 (4.8). |
| T5-D07 | CONTRACT §2.1 dòng 83 mô tả event cũ (`snapshotId`, `outcome`). | T4 đã chốt hình dạng mới. Delta T4 §4.4 có thể chưa được áp dụng. | Bước docs của T5 kiểm tra và sửa nếu T4 chưa sửa (4.8). |
| T5-D08 | Đề A.2: kiểm tra `@EnableAsync` và `TaskExecutor`. | `grep` trong `src/main/java`: 0 `@EnableAsync`, 0 `@EnableScheduling`, 0 `@Scheduled`, 0 bean `Executor`. Có 40+ `@EventListener` đồng bộ, 0 `@ApplicationModuleListener` hay `@TransactionalEventListener`. | Nếu không cấu hình, `@Async` rơi vào `applicationTaskExecutor` mặc định của Boot (mặc định `core-size=8`, queue không giới hạn). Khi đó tối đa 8 listener có thể chạy song song và chiếm hết pool 5 kết nối. T5 thêm executor riêng (3.M4). |
| T5-D09 | ADR-12 (01:99): listener "ra đời cùng D13". | T5 làm **một phần** D13 (chỉ ghi `OPEN`). | Sửa câu ở 01 (4.8) |
| T5-D10 | `application.properties`: `spring.jackson.deserialization.fail-on-unknown-properties=true`. | Boot 4 áp thuộc tính `spring.jackson.*` cho `JsonMapper` (Jackson 3), cũng là mapper serialize event (F8). | Hệ quả: **xóa** hoặc **đổi tên** field của `AttemptSubmitted` sẽ làm publication cũ không deserialize được. Luật tiến hóa ở 5.3. Có test 11.2 khóa luật này. |
| T5-D11 | ARCHITECTURE.md:58: "Mỗi listener gọi service của chính module mình." | `GrammarProjectionListener` (service.impl) gọi thẳng repository. | Theo tiền lệ: listener đặt ở `service.impl` và chính nó là bước xử lý. Không tách thêm interface vì không có caller thứ hai. |
| T5-D12 | `docs/database/proposals/*` có `mistake_records` với FK `(user_id, source_attempt_id) → attempts(user_id, id)`. V13 có `attempts_c1 UNIQUE (user_id, id)`. | — | Giữ FK sở hữu này. Đây là kiểm tra toàn vẹn ở tầng DB, không phải truy cập ứng dụng (6.3). |

---

## 3. Mandatory Design Resolutions

### M1. Unified Engine và đồ thị phụ thuộc (DAG)

**Nguyên tắc:**
- Một engine duy nhất ở `results`.
- Một cửa vào HTTP duy nhất là `POST /api/v1/attempts`, theo `source.type` (T3).
- `vocabulary` và `grammar` **không** gọi `results` và không biết lượt làm tồn tại.
- Chúng chỉ đóng góp **nội dung**:
  - câu hỏi do `practice` sở hữu, gắn từ qua `question_words` và gắn chủ điểm qua `question_topics`;
  - câu trả lời cho các truy vấn đồng bộ có sẵn.

**Đồ thị phụ thuộc lúc chạy** (mũi tên = "gọi facade trong `common/events`", ↝ = "event bất đồng bộ qua `event_publication`"):

```
                    ┌──────────────┐
  HTTP /attempts ──▶│   results    │──── ResultsEvents.AttemptSubmitted ────↝ learning
                    └──┬───┬───┬───┘                                            (MistakeRecordListener)
        PracticeEvents │   │   │ IdentityEvents
                       ▼   │   ▼
                 ┌─────────┐│ ┌──────────┐
                 │practice ││ │ identity │
                 └─┬─────┬─┘│ └──────────┘
   GrammarEvents   │     │  │ ContentEvents (describeMedia, mediaPlaybackLocation)
                   ▼     ▼  ▼
              ┌───────┐ ┌─────────┐
              │grammar│ │ content │
              └───────┘ └─────────┘
   vocabulary: không có cạnh tới results/practice ở runtime. practice đọc view
   catalog_visible_deck_senses (T2) để chọn câu VOCABULARY theo deck.
   learning: không gọi module nào trong luồng này. Chỉ nhận event.
```

Kiểm tra không có vòng:
- `grammar`, `content`, `identity`, `vocabulary` là lá: không gọi `results` hay `practice`.
- `learning` chỉ là nơi nhận.

**Ánh xạ chức năng → luồng** (không có code mới ngoài listener):

| Chức năng | `source.type` (T3) | Vai trò của `vocabulary`/`grammar` |
|---|---|---|
| M03.12 EN→VI, M03.13 VI→EN, M03.14 điền từ | `PRACTICE_SET` (VOCABULARY; `FIXED` hoặc `DYNAMIC` theo `deck_id`/`wordSenseIds`, T2 §3) | Không gọi. Deck đọc qua view `catalog_visible_deck_senses`. |
| M03.15 nghe MP3 chọn từ | `PRACTICE_SET`, câu `LISTEN_AND_CHOOSE` có `question_materials` AUDIO (seed T1 câu 31) | Không gọi. Media qua `content` → `attempt_media_refs` (T3 W7) → `GET /api/v1/attempts/{id}/media/{mediaAssetId}` 302 (TTL 5 phút). |
| M03.16 kết quả | `GET /api/v1/attempts/{id}/results` (T4) | — |
| M04.06, M04.11 quiz theo chủ điểm | `GRAMMAR_TOPIC` | `GrammarEvents.isVisibleTopic` (do `practice` gọi) |
| M04.10 Mixed Grammar | `MIXED_GRAMMAR`; `results` ghi `attempt_source_topics` (T3 W2) | Như trên |
| Hậu xử lý lỗi sai | — | `learning` nhận `AttemptSubmitted` |

**Luật payload facade và event** (đã có, T5 khóa bằng ArchUnit A1):
- Record trong `common/events` chỉ mang `UUID`, enum hoặc `String` mã, số, `Instant`, và `List` của các record đó.
- Không import kiểu nào từ `..modules..` hay `jakarta.persistence..`.

**Quyết định:** không tạo `AttemptCreationFacade` (giữ T3 O2). Lý do:
1. Không có caller. Mọi lượt đều mở từ HTTP.
2. Facade từ `grammar` sẽ tạo vòng lúc chạy (T5-D01). Modulith `verify()` **không** bắt được vòng này vì mọi phụ thuộc đều trỏ vào `common::events`.
3. Một endpoint trùng chức năng `POST /attempts` sẽ phải nhân đôi Idempotency, giới hạn 5 lượt và mã lỗi.

### M2. `mistake_records` thuộc `learning` (S3): V18

Repo chưa có bảng này (`grep mistake` trong `db/migration`: chỉ một chú thích ở V13 dòng 3). T3 dùng V17, T4 không tạo migration, nên số tiếp theo là **V18**. Nếu lúc rebase số này đã bị dùng, đổi sang số trống kế tiếp (AGENTS.md).

Hai bảng, toàn bộ DDL ở 4.1:

**`mistake_records`**: mỗi dòng ứng với một cặp (người học, câu hỏi).
- `UNIQUE (user_id, question_id)`.
- `status` ∈ `OPEN`, `IMPROVING`, `RESOLVED`. MVP chỉ ghi `OPEN`. `IMPROVING`/`RESOLVED` chỉ có trong CHECK để D13 dùng sau.
- `wrong_count > 0`.
- `consecutive_correct_count ≥ 0`; khi `OPEN` thì bằng 0.
- `source_attempt_id`: lượt sai **gần nhất** theo `(last_wrong_at, attempt_id)`.
- `last_processed_attempt_id`: lượt **xử lý** gần nhất có chạm vào dòng này.
- `last_wrong_at`: `effective_finished_at` của lượt sai gần nhất.

**`mistake_processed_attempts`**: sổ guard idempotent, một dòng cho mỗi lượt đã xử lý. Dòng vẫn được ghi khi lượt không có câu sai.

**FK** (theo tiền lệ V12 và DD):
- `user_id → users`;
- `question_id → questions`;
- `(user_id, source_attempt_id) → attempts(user_id, id)` (dùng `attempts_c1`);
- `(user_id, attempt_id)` của guard `→ attempts(user_id, id)`.

Tất cả `ON DELETE RESTRICT`. Entity JPA chỉ giữ `UUID`, không có `@ManyToOne`.

### M3. Listener idempotent tuyệt đối

**Luồng:** `MistakeRecordListener.on(ResultsEvents.AttemptSubmitted)`, `@ApplicationModuleListener(id = "learning.mistake-records")`:
1. Lọc `outcomes` lấy `!correct()`. Tập này gồm cả câu bỏ trống. Lấy `questionId`, khử trùng, đổi sang `UUID[]`.
2. Gọi `MistakeRecordRepository.recordSubmittedAttempt(attemptId, userId, effectiveFinishedAt, wrongIds, clock.instant())`. Đây là **1 câu SQL** (4.3).
3. Không đọc bảng nào của `results`. Không đọc `attempts`.

**Câu SQL** (một câu, data-modifying CTE):
- CTE `guard`: `INSERT INTO mistake_processed_attempts … ON CONFLICT (attempt_id) DO NOTHING RETURNING attempt_id`.
- CTE `wrong`: `SELECT DISTINCT … FROM unnest(CAST(:wrongQuestionIds AS uuid[])) … WHERE EXISTS (SELECT 1 FROM guard)`.
- Thân: `INSERT INTO mistake_records … SELECT … FROM wrong ORDER BY question_id ON CONFLICT (user_id, question_id) DO UPDATE SET …`.

**Chứng minh không đếm đúp** (at-least-once):

| Tình huống | Hành vi |
|---|---|
| Lần đầu | `guard` trả 1 dòng → upsert chạy. Câu sai mới được `INSERT` với `wrong_count = 1`; câu đã có được `wrong_count + 1`. |
| Giao lại sau khi lần đầu đã commit | Xung đột PK của guard → `DO NOTHING` → `guard` rỗng → `wrong` rỗng → 0 dòng bị chạm. |
| Giao lại **đồng thời** (2 thread cùng event) | Thread 2 chờ khóa unique của guard tới khi thread 1 commit. Sau đó `DO NOTHING` → 0 dòng. Nếu thread 1 rollback, thread 2 chèn guard và xử lý đầy đủ. |
| Lần đầu lỗi giữa chừng | Cả câu lệnh (guard và upsert) nằm trong transaction `REQUIRES_NEW` nên rollback cùng nhau. Lần gửi lại xử lý như lần đầu. |
| Lượt toàn câu đúng | Guard vẫn được ghi. Giao lại vẫn là no-op. |

**Không phụ thuộc thứ tự giao event:**
- `wrong_count + 1` và `status = 'OPEN'`, `consecutive_correct_count = 0` đều giao hoán được.
- `source_attempt_id` chỉ đổi khi `(EXCLUDED.last_wrong_at, EXCLUDED.source_attempt_id) > (m.last_wrong_at, m.source_attempt_id)`.
- `last_wrong_at = GREATEST(...)`.
- Vì vậy kết quả cuối giống nhau với mọi thứ tự giao. Khóa thứ tự này đúng như CONTRACT §8.2 (`effective_finished_at`, `attempt_id`).

**Thứ tự khóa:** `ORDER BY w.question_id` theo thứ tự `uuid` của PostgreSQL. Không sắp xếp bằng Java: `UUID.compareTo` so sánh có dấu, khác thứ tự của PostgreSQL. Hai lượt khác nhau của cùng người học khóa các dòng chung theo cùng thứ tự, nên giảm nguy cơ deadlock. Nếu vẫn deadlock (`40P01`), publication chuyển `FAILED` và được gửi lại (M4). Guard bảo đảm không đếm đúp.

### M4. Executor riêng, transaction và vòng đời `event_publication`

**Executor** `moduleEventExecutor` (`ThreadPoolTaskExecutor`, 4.5):

| Tham số | Giá trị | Lý do |
|---|---|---|
| `corePoolSize` | 1 | Lúc bình thường chỉ 1 listener chiếm kết nối |
| `maxPoolSize` | 2 | `ThreadPoolExecutor` chỉ tạo thread thứ 2 khi queue đầy |
| `queueCapacity` | 100 | 100 task × ~10 ms ≈ 1 s tồn đọng. Bộ nhớ ≤ 100 × 18 KB ≈ 1,8 MB (Full Test). |
| `keepAliveSeconds` | 60 | Thu hồi thread thứ 2 |
| `threadNamePrefix` | `module-event-` | Dễ lọc log và test |
| `waitForTasksToCompleteOnShutdown` / `awaitTerminationSeconds` | `true` / 10 | Deploy không cắt ngang. Task chưa chạy vẫn còn trong registry. |
| `RejectedExecutionHandler` | Log WARN rồi bỏ task | Publication ở `PUBLISHED`. Monitor staleness đổi sang `FAILED` sau 10 phút, rồi job gửi lại. **Không** ném lỗi về thread HTTP. |

Gắn vào `@Async` qua `AsyncConfigurer.getAsyncExecutor()` (F2). Class `@Configuration` dùng `proxyBeanMethods` mặc định (`true`), để `getAsyncExecutor()` trả đúng singleton.

**Transaction:**
- `results` commit (gồm `INSERT event_publication`, trạng thái `PUBLISHED`) rồi mới giao event (`AFTER_COMMIT`).
- Thread HTTP chỉ đưa task vào queue.
- Listener chạy trên `module-event-*` trong transaction `REQUIRES_NEW` mới.
- Lỗi ở `learning` chỉ làm publication `FAILED`. Lượt làm, điểm và response nộp bài **không** bị rollback.
- Điều kiện tiên quyết đã có ở T4: `AttemptGradingEngine.grade` chạy `Propagation.MANDATORY`, nên event luôn được phát trong transaction đọc-ghi. Phát event ngoài transaction thì `@TransactionalEventListener` sẽ bỏ qua listener.

**Vòng đời `event_publication`** (`completion-mode=update`, giữ nguyên):

| Cơ chế | Cấu hình | Hiệu quả |
|---|---|---|
| Staleness monitor (F3) | `published=PT10M`, `processing=PT10M`, `resubmitted=PT10M`, `check-interval=PT1M` | Dòng kẹt (process chết, task bị từ chối) chuyển `FAILED` |
| Gửi lại | `EventPublicationMaintenance.resubmitFailed`, mỗi 5 phút (initial 2 phút); `batchSize=20`, `maxInFlight=10`, `minAge=1 phút`, `completion_attempts < 10` | Lỗi tạm tự phục hồi |
| Event độc | `completion_attempts ≥ 10` | Không gửi lại tự động. Giữ lại để điều tra (truy vấn ở 6.5). |
| Dọn | `purgeCompleted`, cron `0 30 20 * * *` UTC (03:30 giờ VN); xóa `COMPLETED` cũ hơn 7 ngày (F5) | Bảng không phình |
| Restart | `republish-outstanding-events-on-restart=false` (mặc định, ghi tường minh) | Tránh chạy trùng lúc khởi động. Staleness và job gửi lại lo phần còn tồn đọng. |

Ước lượng kích thước: giả sử 500 lượt nộp/ngày, payload trung bình ~3 KB (Full Test ~18 KB, quiz 10–20 câu ~1,5 KB). Khoảng 1,5 MB/ngày, tức **≈ 10 MB** với 7 ngày giữ lại. Dung lượng này nằm trên đĩa và ít ảnh hưởng `shared_buffers=64MB`.

**Ngân sách kết nối** (pool mục tiêu 5):

| Nguồn | Tối đa đồng thời |
|---|---|
| Listener (`module-event-*`) | 2 (bình thường 1) |
| Scheduler (`scheduling-1`, Boot mặc định 1 thread): staleness, gửi lại, dọn | 1, mỗi lần vài ms |
| HTTP | ≥ 2 trong cửa sổ bảo trì; ≥ 3 lúc thường |

Gửi lại không chạy listener trên thread scheduler. Multicaster gọi bean method qua proxy `@Async`, nên listener vẫn chạy trên executor (F4).

### M5. ArchUnit và kiểm tra ranh giới

**Giữ nguyên:**
- `ArchitectureTests`: `modules.verify()` và các assert module (T3 đã thêm `results`).
- `LayerArchitectureTests`: 13 rule hiện có (đếm `@Test` trong file), cộng `sealRecordsNeverReachHttpLayer` (T2), tổng 14 như T3 §11.5. Rule slices `businessModulesHaveNoDirectDependenciesOnEachOther` đã cấm `learning` import kiểu của `results`.

**Thêm `[CREATE]`** (code ở 4.6):

| Mã | Rule | Khóa điều gì |
|---|---|---|
| A1 | `eventContractsCarryOnlyPlainTypes` | `..common.events..` không phụ thuộc `..modules..` hay `jakarta.persistence..`. Facade và event chỉ mang `UUID`/enum/giá trị. |
| A2 | `onlyResultsCreatesAttemptSubmitted` | Ngoài `..modules.results..` và `..common.events..`, không class nào gọi constructor `ResultsEvents.AttemptSubmitted`. `learning` không tự dựng event. |
| A3 | `moduleListenersLiveInServiceImplAndTakeEventContracts` | Method có `@ApplicationModuleListener` nằm trong `..modules.*.service.impl..`. Tham số duy nhất của nó thuộc `..common.events..`. |
| A4 | `LearningSqlBoundaryTests` (unit, reflection) | Mọi `@Query` trong `modules.learning.repository` không nhắc tới bảng của `results`: regex `\b(attempts\|attempt_answers\|attempt_question_snapshots\|attempt_group_snapshots\|attempt_snapshot_options\|attempt_media_refs\|attempt_source_topics)\b`. `mistake_processed_attempts` không khớp vì `_` là ký tự từ. |

---

## 4. Thiết kế chi tiết

### 4.1. `src/main/resources/db/migration/V18__create_mistake_records.sql` `[CREATE]`

```sql
-- V18: Learning mistake records (S3, ADR-12, D13 MVP scope).
-- The learning module records wrong and unanswered questions from ResultsEvents.AttemptSubmitted.
-- Only OPEN is written for now; IMPROVING/RESOLVED, attempt_source_mistakes and the MISTAKES
-- source arrive with the rest of D13 through later additive migrations.
CREATE TABLE mistake_records (
    id                        UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id                   UUID        NOT NULL,
    question_id               UUID        NOT NULL,
    source_attempt_id         UUID        NOT NULL,
    last_processed_attempt_id UUID        NOT NULL,
    status                    VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    wrong_count               INTEGER     NOT NULL,
    consecutive_correct_count INTEGER     NOT NULL DEFAULT 0,
    last_wrong_at             TIMESTAMPTZ NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT mistake_records_c1 PRIMARY KEY (id),
    CONSTRAINT mistake_records_c2 UNIQUE (user_id, question_id),
    CONSTRAINT mistake_records_c3 CHECK (status IN ('OPEN', 'IMPROVING', 'RESOLVED')),
    CONSTRAINT mistake_records_c4 CHECK (wrong_count > 0 AND consecutive_correct_count >= 0),
    CONSTRAINT mistake_records_c5 CHECK (status <> 'OPEN' OR consecutive_correct_count = 0)
);

ALTER TABLE mistake_records
    ADD CONSTRAINT fk_mistake_records_user_id_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_mistake_records_question_id_questions
        FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_mistake_records_user_id_source_attempt_id_attempts
        FOREIGN KEY (user_id, source_attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT;

-- One row per attempt already applied; makes at-least-once redelivery a no-op.
CREATE TABLE mistake_processed_attempts (
    attempt_id   UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT mistake_processed_attempts_c1 PRIMARY KEY (attempt_id),
    CONSTRAINT fk_mistake_processed_attempts_user_id_attempt_id_attempts
        FOREIGN KEY (user_id, attempt_id) REFERENCES attempts (user_id, id) ON DELETE RESTRICT
);

COMMENT ON TABLE mistake_records IS 'Lỗi theo user/câu (learning, S3). MVP chỉ ghi OPEN từ ResultsEvents.AttemptSubmitted.';
COMMENT ON COLUMN mistake_records.source_attempt_id IS 'Lượt sai gần nhất theo (last_wrong_at, attempt_id)';
COMMENT ON COLUMN mistake_records.last_processed_attempt_id IS 'Lượt được xử lý gần nhất đã chạm dòng này';
COMMENT ON COLUMN mistake_records.last_wrong_at IS 'effective_finished_at của lượt sai gần nhất';
COMMENT ON TABLE mistake_processed_attempts IS 'Sổ idempotent của listener learning.mistake-records, một dòng mỗi lượt';
```

Ghi chú:
- `gen_random_uuid()` có sẵn trong PostgreSQL 13+, V14 (T1) cũng dùng.
- Không thêm index. `mistake_records_c2` phục vụ `ON CONFLICT` và truy vấn theo `user_id`.
- Không có FK cho `last_processed_attempt_id`. Guard của cùng lượt đã kiểm FK sở hữu trong cùng câu lệnh.
- Không sửa V13–V17.

### 4.2. Entity `modules/learning/entity/MistakeRecord.java` `[CREATE]`

Chỉ để `ddl-auto=validate` và để `JpaRepository` có kiểu. Không có setter; mọi ghi đi qua 4.3.

```java
@Entity
@Table(name = "mistake_records")
public class MistakeRecord {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "question_id", nullable = false) private UUID questionId;
    @Column(name = "source_attempt_id", nullable = false) private UUID sourceAttemptId;
    @Column(name = "last_processed_attempt_id", nullable = false) private UUID lastProcessedAttemptId;
    @Column(name = "status", nullable = false, length = 16) private String status;
    @Column(name = "wrong_count", nullable = false) private int wrongCount;
    @Column(name = "consecutive_correct_count", nullable = false) private int consecutiveCorrectCount;
    @Column(name = "last_wrong_at", nullable = false) private Instant lastWrongAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected MistakeRecord() {}
    // getters only
}
```

`mistake_processed_attempts` **không** có entity. Native query ghi trực tiếp, và `validate` chỉ kiểm bảng đã map.

### 4.3. `modules/learning/repository/MistakeRecordRepository.java` `[CREATE]`

Native `@Modifying @Query` trong interface JpaRepository, theo tiền lệ `LessonProgressRepository:23-44`. Không cần fragment hay `spring.factories`.

```java
@Repository
public interface MistakeRecordRepository extends JpaRepository<MistakeRecord, UUID> {

    /**
     * Applies one submitted attempt exactly once. The guard row turns redelivery into a no-op.
     * Returns the number of mistake rows inserted or updated (0 on redelivery).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    WITH guard AS (
                        INSERT INTO mistake_processed_attempts (attempt_id, user_id, processed_at)
                        VALUES (:attemptId, :userId, :now)
                        ON CONFLICT (attempt_id) DO NOTHING
                        RETURNING attempt_id
                    ), wrong AS (
                        SELECT DISTINCT w.question_id
                        FROM unnest(CAST(:wrongQuestionIds AS uuid[])) AS w(question_id)
                        WHERE EXISTS (SELECT 1 FROM guard)
                    )
                    INSERT INTO mistake_records AS m
                        (user_id, question_id, source_attempt_id, last_processed_attempt_id,
                         status, wrong_count, consecutive_correct_count, last_wrong_at,
                         created_at, updated_at)
                    SELECT :userId, w.question_id, :attemptId, :attemptId,
                           'OPEN', 1, 0, :finishedAt, :now, :now
                    FROM wrong w
                    ORDER BY w.question_id
                    ON CONFLICT (user_id, question_id) DO UPDATE SET
                        wrong_count = m.wrong_count + 1,
                        consecutive_correct_count = 0,
                        status = 'OPEN',
                        last_processed_attempt_id = EXCLUDED.last_processed_attempt_id,
                        source_attempt_id = CASE
                            WHEN (EXCLUDED.last_wrong_at, EXCLUDED.source_attempt_id)
                                 > (m.last_wrong_at, m.source_attempt_id)
                            THEN EXCLUDED.source_attempt_id
                            ELSE m.source_attempt_id END,
                        last_wrong_at = GREATEST(m.last_wrong_at, EXCLUDED.last_wrong_at),
                        updated_at = EXCLUDED.updated_at
                    """,
            nativeQuery = true)
    int recordSubmittedAttempt(
            @Param("attemptId") UUID attemptId,
            @Param("userId") UUID userId,
            @Param("finishedAt") Instant finishedAt,
            @Param("wrongQuestionIds") UUID[] wrongQuestionIds,
            @Param("now") Instant now);
}
```

Bind mảng:
- Dùng chung giả định đã nêu ở T3 §4.4 dòng 494–495: Hibernate 6+ bind `UUID[]` thành mảng PostgreSQL.
- Mảng rỗng `new UUID[0]` hợp lệ.
- Nếu test 11.2 `emptyWrongListStillWritesGuard` thất bại do bind, dùng fallback của T3: truyền `String` dạng `{uuid,uuid}` và `CAST(:wrongQuestionIds AS uuid[])` giữ nguyên.

### 4.4. Listener `modules/learning/service/impl/MistakeRecordListener.java` `[CREATE]`

```java
/** Records wrong and unanswered questions of each submitted attempt (D13 MVP: OPEN only). */
@Component
public class MistakeRecordListener {

    /** Stored in event_publication.listener_id; renaming it orphans pending publications. */
    static final String LISTENER_ID = "learning.mistake-records";

    private static final Logger LOG = LoggerFactory.getLogger(MistakeRecordListener.class);

    private final MistakeRecordRepository mistakes;
    private final Clock clock;

    public MistakeRecordListener(MistakeRecordRepository mistakes, Clock clock) {
        this.mistakes = mistakes;
        this.clock = clock;
    }

    @ApplicationModuleListener(id = LISTENER_ID)
    public void on(ResultsEvents.AttemptSubmitted event) {
        UUID[] wrong =
                event.outcomes().stream()
                        .filter(outcome -> !outcome.correct())
                        .map(ResultsEvents.QuestionOutcome::questionId)
                        .distinct()
                        .toArray(UUID[]::new);
        int rows =
                mistakes.recordSubmittedAttempt(
                        event.attemptId(), event.userId(), event.effectiveFinishedAt(), wrong, clock.instant());
        LOG.debug("Attempt {} applied to {} mistake records.", event.attemptId(), rows);
    }
}
```

- `Clock` là bean `applicationClock` (`common/time/TimeConfiguration.java:11`). `learning` đã được phép dùng `common::time`.
- `package-info` của `learning` **không đổi**, vì `common::events` đã có trong `allowedDependencies`.
- Không có `@Transactional` ở class (rule `servicesDeclareTransactionsAtMethodLevel`). Transaction đến từ meta-annotation ở method (F1).
- Log chỉ ghi `attemptId` và số dòng, không ghi `userId` hay `questionId`.

### 4.5. `config/ModuleEventConfiguration.java` `[CREATE]`

```java
/** Bounded executor for asynchronous module listeners, so they never take most of the JDBC pool. */
@Configuration
@EnableScheduling
public class ModuleEventConfiguration implements AsyncConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleEventConfiguration.class);

    @Bean
    public ThreadPoolTaskExecutor moduleEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("module-event-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        // The publication stays incomplete; the staleness monitor fails it and the maintenance job resubmits it.
        executor.setRejectedExecutionHandler(
                (task, pool) -> LOG.warn("Module event queue is full; the publication will be resubmitted."));
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return moduleEventExecutor(); // proxied @Configuration: returns the singleton bean
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (error, method, params) ->
                LOG.warn("Asynchronous listener {} failed; its publication is marked FAILED.", method.getName(), error);
    }
}
```

Ghi chú:
- Boot có thể tắt `applicationTaskExecutor` khi thấy bean `Executor` này. Repo không dùng nó: 0 `@Async` khác, 0 MVC async. Đây là hành vi chấp nhận được.
- `@EnableScheduling` dùng scheduler mặc định của Boot (1 thread).

### 4.6. Hạ tầng và test kiến trúc

**`infrastructure/events/EventPublicationMaintenance.java` `[CREATE]`** (cạnh `SpringModuleEvents`):

```java
/** Retries failed module-event publications and removes old completed ones (ADR-12, T5 §3.M4). */
@Component
class EventPublicationMaintenance {

    static final int MAX_COMPLETION_ATTEMPTS = 10;
    static final Duration COMPLETED_RETENTION = Duration.ofDays(7);
    static final ResubmissionOptions RESUBMISSION =
            ResubmissionOptions.defaults()
                    .withBatchSize(20)
                    .withMaxInFlight(10)
                    .withMinAge(Duration.ofMinutes(1))
                    .withFilter(publication -> publication.getCompletionAttempts() < MAX_COMPLETION_ATTEMPTS);

    private final FailedEventPublications failed;
    private final CompletedEventPublications completed;

    EventPublicationMaintenance(FailedEventPublications failed, CompletedEventPublications completed) {
        this.failed = failed;
        this.completed = completed;
    }

    @Scheduled(initialDelay = 2, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void resubmitFailed() {
        failed.resubmit(RESUBMISSION);
    }

    @Scheduled(cron = "0 30 20 * * *", zone = "UTC") // 03:30 Asia/Ho_Chi_Minh
    void purgeCompleted() {
        completed.deletePublicationsOlderThan(COMPLETED_RETENTION);
    }
}
```

**`src/test/java/com/masterenglish/api/LayerArchitectureTests.java` `[MODIFY]`**: thêm A1–A3. Các rule này import **toàn bộ** `com.masterenglish.api` (biến `application` như rule `databaseAccessGoesThroughSpringDataRepositories`), vì `MODULES` không chứa `common`.

```java
@Test
void eventContractsCarryOnlyPlainTypes() {
    noClasses().that().resideInAPackage("..common.events..")
            .should().dependOnClassesThat().resideInAnyPackage("..modules..", "jakarta.persistence..")
            .check(IMPORTER.importPackages("com.masterenglish.api"));
}

@Test
void onlyResultsCreatesAttemptSubmitted() {
    noClasses().that().resideOutsideOfPackages("..modules.results..", "..common.events..")
            .should().callConstructor(ResultsEvents.AttemptSubmitted.class,
                    UUID.class, UUID.class, UUID.class, String.class, String.class, String.class,
                    Instant.class, Instant.class, List.class)
            .check(IMPORTER.importPackages("com.masterenglish.api"));
}

@Test
void moduleListenersLiveInServiceImplAndTakeEventContracts() {
    methods().that().areAnnotatedWith(ApplicationModuleListener.class)
            .should().beDeclaredInClassesThat().resideInAPackage("..modules.*.service.impl..")
            .andShould(haveSingleParameterFromCommonEvents())   // ArchCondition<JavaMethod>, tham số [0] thuộc ..common.events..
            .check(MODULES);
}
```

Nếu sau này `AttemptSubmitted` thêm field, chữ ký constructor ở A2 phải được cập nhật cùng. Test sẽ báo lỗi biên dịch, nên không thể quên.

**`src/test/java/com/masterenglish/api/modules/learning/repository/LearningSqlBoundaryTests.java` `[CREATE]`** (A4): duyệt `MistakeRecordRepository`, `LessonProgressRepository`, `UserSavedWordRepository`, `UserWordProgressRepository`, `WordReviewRepository` qua reflection. Lấy `Query.value()` của mọi method rồi assert không khớp regex ở 3.M5.

### 4.7. `src/main/resources/application.properties` `[MODIFY]`

Thêm ngay sau dòng `spring.modulith.events.completion-mode=update`:

```properties
# Async module listeners (T5): stale publications become FAILED and are resubmitted by EventPublicationMaintenance.
spring.modulith.events.republish-outstanding-events-on-restart=false
spring.modulith.events.staleness.published=PT10M
spring.modulith.events.staleness.processing=PT10M
spring.modulith.events.staleness.resubmitted=PT10M
spring.modulith.events.staleness.check-interval=PT1M
```

Không thêm thuộc tính `spring.task.execution.*`, vì executor được cấu hình trong Java (4.5).

### 4.8. Delta tài liệu (làm trong bước commit 5)

> **Đã áp phần thiết kế ngày 26/11/2026, khi duyệt:** CONTRACT §0 (D13), §1, §2.1, §8.5, §14.4, "Lịch sử thay đổi"; `01` ADR-12. Các mục này ghi "chưa triển khai".
>
> **Còn lại cho bước commit 5:** đổi D13 thành "Một phần (T5)" khi code đã merge, và `docs/ARCHITECTURE.md:67-69` (mô tả code thật).

| File | Thay đổi |
|---|---|
| `docs/practice/CONTRACT.md` §0, dòng D13 | "Một phần (T5): ghi `OPEN` từ event. Vòng đời và luyện lại vẫn hoãn." |
| CONTRACT §1, dòng `learning` (bổ sung T0) | Thêm `mistake_processed_attempts`. |
| CONTRACT §2.1, dòng 83 | Nếu T4 chưa sửa, thay bằng hình dạng T4 §3.M3 và ghi "(thay bởi T4)". |
| CONTRACT §8 | Thêm mục 8.5 "Đã làm ở T5": bảng, listener id, guard, phạm vi `OPEN`. |
| CONTRACT §14.4 | Thêm 3 dòng lệch DD: (a) `state` → `status`, `consecutive_correct` → `consecutive_correct_count`; (b) chưa có `source_snapshot_id`, `last_evaluated_at`, `resolved_at`, `UNIQUE (user_id, id)`, `ix_mistakes_user_state` (đợi D13); (c) `DATA_DICTIONARY` vẫn xếp bảng vào nhóm Results (S3). |
| CONTRACT, "Lịch sử thay đổi" | Một dòng T5. |
| `docs/m05/01-adrs-07-to-13.md:99` | "Listener … ra đời cùng D13" → "Listener `learning.mistake-records` có từ T5 (chỉ ghi `OPEN`). Phần còn lại của D13 vẫn hoãn." Ghi thêm executor riêng và giữ 7 ngày. |
| `docs/ARCHITECTURE.md:67-69` | Thêm một câu: listener bất đồng bộ chạy trên `moduleEventExecutor` (1–2 thread). Phải đặt `id` cố định. |

---

## 5. Hợp đồng giao tiếp

### 5.1. HTTP

Không thêm, không đổi endpoint nào. `POST /api/v1/attempts/{id}/submit` giữ nguyên response và status (T4 §3.M5). Lỗi của listener **không bao giờ** đi vào response.

### 5.2. Event tiêu thụ

`ResultsEvents.AttemptSubmitted` đúng như T4 §3.M3. Dạng lưu trong `event_publication`:
- `event_type = com.masterenglish.api.common.events.ResultsEvents$AttemptSubmitted`;
- `listener_id = learning.mistake-records`;
- `serialized_event` (Jackson 3, Instant dạng ISO-8601):

```json
{
  "eventId": "4b8f0c1e-7a51-4f0e-9c1d-2f3a4b5c6d7e",
  "attemptId": "0f6f3a52-2b1e-4c0a-8d55-6f1b2c3d4e5f",
  "userId": "8a1d2c3b-4e5f-4a6b-9c7d-0e1f2a3b4c5d",
  "category": "TOEIC",
  "sourceType": "PRACTICE_SET",
  "submitReason": "MANUAL",
  "submittedAt": "2026-11-26T03:15:42.120Z",
  "effectiveFinishedAt": "2026-11-26T03:15:42.120Z",
  "outcomes": [
    {"questionId": "e5000000-0001-4000-8000-000000000101", "partNumber": 5, "correct": true,  "unanswered": false},
    {"questionId": "e5000000-0001-4000-8000-000000000102", "partNumber": 5, "correct": false, "unanswered": false},
    {"questionId": "e5000000-0001-4000-8000-000000000103", "partNumber": 5, "correct": false, "unanswered": true}
  ]
}
```

Với ví dụ trên, listener ghi:
- guard cho `attemptId`;
- 2 dòng `mistake_records` (`…102`, `…103`), mỗi dòng `status = OPEN`, `wrong_count = 1`, `source_attempt_id = last_processed_attempt_id = attemptId`, `last_wrong_at = 2026-11-26T03:15:42.120Z`.

### 5.3. Luật tiến hóa event (do T5-D10)

| Thay đổi `AttemptSubmitted`/`QuestionOutcome` | Cho phép? | Điều kiện |
|---|---|---|
| Thêm field kiểu đối tượng (nullable) | Có | Listener chịu được `null` từ JSON cũ |
| Thêm field `boolean`/`int` | Có | JSON cũ deserialize thành `false`/`0`. Listener phải hiểu đúng giá trị đó. |
| Xóa hoặc đổi tên field | **Không**, khi vẫn còn publication chưa `COMPLETED` | Với `fail-on-unknown-properties=true`, JSON cũ sẽ bị từ chối → `FAILED` vĩnh viễn |
| Đổi tên hoặc chuyển package của record | **Không** | `event_type` lưu tên class đầy đủ |
| Đổi `LISTENER_ID` | **Không** | F7 |

### 5.4. Facade

Không thêm facade (O1). `common/events` không đổi ở T5.

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn đồng thời

### 6.1. Không có bề mặt HTTP mới

- Không có IDOR, CSRF hay ProblemDetail mới.
- `mistake_records` chưa có API đọc (M06 hoãn).

### 6.2. Payload không mang nội dung

- Event chỉ chứa id, mã và boolean (T4).
- Listener không ghi nội dung câu hay đáp án.
- Log chỉ có `attemptId`.

### 6.3. Toàn vẹn sở hữu

- FK `(user_id, source_attempt_id) → attempts(user_id, id)` và `(user_id, attempt_id)` của guard bảo đảm lỗi được gắn đúng chủ lượt.
- Event có `userId` sai sẽ vi phạm FK. Publication thành `FAILED` và **không** ghi gì.
- FK kiểm tra bằng khóa `FOR KEY SHARE` trên dòng `attempts`. Khóa này chỉ xung đột với `FOR UPDATE`/`DELETE`.
- Sau khi lượt đã `SUBMITTED`, `results` chỉ khóa `FOR UPDATE` dòng đó khi nộp lại hoặc lưu đáp án muộn; cả hai trả nhanh (T4). Chờ tối đa vài ms, chấp nhận được.

### 6.4. Idempotency và đồng thời

Xem bảng chứng minh ở 3.M3. Tóm tắt:

| Rủi ro | Biện pháp |
|---|---|
| Giao lại (at-least-once) | Guard PK, cùng câu lệnh với upsert |
| Hai lượt cùng người học xử lý song song | `ON CONFLICT` khóa dòng; `ORDER BY question_id`; deadlock → `FAILED` → gửi lại |
| Thứ tự giao đảo | Phép cộng giao hoán; `source_attempt_id` theo `(last_wrong_at, attempt_id)` |
| `questionId` trùng trong `outcomes` | `distinct()` ở Java và `SELECT DISTINCT` ở SQL. Không thì lỗi PostgreSQL "ON CONFLICT DO UPDATE command cannot affect row a second time". |

### 6.5. Vận hành

Truy vấn kiểm tra (chỉ đọc, chạy bằng tay):

```sql
SELECT status, count(*), min(publication_date) FROM event_publication GROUP BY status;
SELECT id, listener_id, completion_attempts, publication_date
FROM event_publication WHERE status = 'FAILED' AND completion_attempts >= 10;
```

---

## 7. Ranh giới Spring Modulith và tài nguyên

### 7.1. DAG và dependency khai báo

| Module | `allowedDependencies` | Đổi ở T5 |
|---|---|---|
| `learning` | `common::{events,api,entity,error,security,pagination,time}` | Không |
| `results` | `common::{events,error,security,api,pagination}` (T3) | Không |
| `vocabulary`, `grammar`, `practice`, `content` | Như hiện tại | Không |

- Phụ thuộc kiểu duy nhất mới: `learning → common.events.ResultsEvents`.
- Không có `@ManyToOne` xuyên module. Mọi FK trong entity là `UUID`.

### 7.2. Số câu SQL

| Thao tác | Câu SQL |
|---|---|
| Submit (thread HTTP) | 3 của T4 + **1** `INSERT event_publication` (lần đầu tiên xuất hiện) |
| Listener, lần đầu | `UPDATE event_publication` → `PROCESSING`, **1** upsert, `UPDATE` → `COMPLETED` |
| Listener, giao lại | Như trên; upsert chạm 0 dòng |
| Gửi lại mỗi 5 phút | 1 `count` + 1 `select` theo `(status, publication_date)` (index V1 sẵn có) |
| Dọn mỗi ngày | 1 `DELETE … WHERE completion_date < ?` (index V1 `idx_event_publication_completion_date`) |

### 7.3. RAM và kết nối

- **Pool:** xem bảng ngân sách ở 3.M4. HTTP luôn còn ít nhất 2 trên 5 kết nối.
- **RAM API (`768m`):** 2 thread × stack 1 MB, queue ≤ 1,8 MB, và mảng `UUID` ≤ 200 phần tử mỗi event. Không đáng kể.
- **RAM PostgreSQL (`384m`):**
  - upsert ≤ 200 dòng mỗi Full Test, theo index `mistake_records_c2`;
  - `event_publication` ≈ 10 MB với 7 ngày;
  - `mistake_processed_attempts` ≈ 70 byte/lượt (khoảng 13 MB/năm với 500 lượt/ngày).
- **Độ trễ nộp bài:** serialize ~18 KB JSON và thêm 1 `INSERT` trong transaction nộp. Đo ở T7 với SLA < 500 ms.

---

## 8. Edge Cases & Failure Modes Matrix

| # | Tình huống | Loại | Xử lý | Kết quả mong đợi |
|---|---|---|---|---|
| 1 | Event giao lại sau khi đã `COMPLETED` (gửi lại bằng tay, restart) | At-least-once | Guard `DO NOTHING` | `wrong_count` không đổi; upsert chạm 0 dòng |
| 2 | Hai thread cùng xử lý một event | Đồng thời | Khóa unique của guard | Đúng một lần |
| 3 | Lượt toàn câu đúng | Dữ liệu | Guard vẫn ghi; `wrong` rỗng | 0 dòng `mistake_records`; giao lại vẫn no-op |
| 4 | Câu bỏ trống (`unanswered = true`) | Nghiệp vụ | Tính là sai (`correct = false`) | Dòng `OPEN` (O2) |
| 5 | Câu đã có dòng `OPEN`, sai lần nữa ở lượt mới | Nghiệp vụ | `ON CONFLICT DO UPDATE` | `wrong_count + 1`; `source_attempt_id` = lượt mới hơn |
| 6 | Event lượt cũ (A, t1) giao **sau** lượt mới (B, t2 > t1) | Thứ tự | So sánh `(last_wrong_at, attempt_id)` | `source_attempt_id = B`, `last_wrong_at = t2`, `wrong_count = 2` |
| 7 | DB lỗi tạm hoặc deadlock `40P01` trong listener | Sự cố | Rollback `REQUIRES_NEW`; `FAILED`; gửi lại sau ≥ 1 phút | Response nộp bài đã 200; lỗi sai được ghi sau |
| 8 | Process chết khi listener đang `PROCESSING` | Sự cố | Staleness 10 phút → `FAILED` → gửi lại | Ghi đúng một lần (guard) |
| 9 | Queue executor đầy (> 100 task) | Tải | Handler log WARN và bỏ task | Publication `PUBLISHED` → `FAILED` sau 10 phút → gửi lại; HTTP không lỗi |
| 10 | Event độc (FK sai `userId`, JSON hỏng) | Dữ liệu | Thử tối đa 10 lần rồi dừng | `FAILED`, `completion_attempts ≥ 10`; truy vấn 6.5 phát hiện |
| 11 | Đổi tên class hoặc method listener | Refactor | `id` cố định | `listener_id` không đổi; event cũ vẫn được xử lý (F7) |
| 12 | Xóa hoặc đổi tên field event khi còn publication chờ | Tiến hóa | Luật 5.3 | Bị cấm. Test `eventSerializationRoundTrip` bắt thay đổi phá vỡ. |
| 13 | Test `@Transactional` (rollback) gọi nộp bài | Test | `AFTER_COMMIT` không bao giờ chạy | Không có `mistake_records` |
| 14 | Test kế tiếp dọn DB khi listener của test trước còn chạy | Test | `@AfterEach` chờ `IncompleteEventPublications`/`event_publication` không còn dòng chưa hoàn tất (Awaitility 4.3.0) | Không flaky. FK `RESTRICT` tới `users` đã có từ V13 (`attempts`). |
| 15 | Lượt V13 cũ (`scoring_version = 1`) nộp sau deploy | Dữ liệu cũ | Event giống lượt mới (T4) | Ghi lỗi bình thường |
| 16 | `TIME_LIMIT`: lượt tự nộp khi `GET /results` | Luồng | Engine `MANDATORY` trong transaction đọc-ghi (T4) | Event phát; `last_wrong_at = deadline_at` |
| 17 | Câu hỏi bị archive hoặc thu hồi sau khi đã ghi lỗi | Nội dung | Không đổi `mistake_records` | Lọc lúc luyện lại thuộc D13 |
| 18 | Deploy dừng app khi queue còn task | Vận hành | Chờ tối đa 10 s; phần còn lại giữ `PUBLISHED` | Staleness và gửi lại sau restart |

---

## 9. Kế hoạch chỉnh sửa file

| File | Nhãn |
|---|---|
| `src/main/resources/db/migration/V18__create_mistake_records.sql` | CREATE |
| `src/main/java/com/masterenglish/api/modules/learning/entity/MistakeRecord.java` | CREATE |
| `src/main/java/com/masterenglish/api/modules/learning/repository/MistakeRecordRepository.java` | CREATE |
| `src/main/java/com/masterenglish/api/modules/learning/service/impl/MistakeRecordListener.java` | CREATE |
| `src/main/java/com/masterenglish/api/config/ModuleEventConfiguration.java` | CREATE |
| `src/main/java/com/masterenglish/api/infrastructure/events/EventPublicationMaintenance.java` | CREATE |
| `src/main/resources/application.properties` | MODIFY (4.7) |
| `src/test/java/com/masterenglish/api/LayerArchitectureTests.java` | MODIFY (A1–A3) |
| `src/test/java/com/masterenglish/api/modules/learning/repository/LearningSqlBoundaryTests.java` | CREATE (A4) |
| `src/test/java/com/masterenglish/api/modules/learning/MistakeRecordMigrationTests.java` | CREATE |
| `src/test/java/com/masterenglish/api/modules/learning/service/impl/MistakeRecordListenerTests.java` | CREATE |
| `src/test/java/com/masterenglish/api/modules/learning/MistakeRecordEventIntegrationTests.java` | CREATE |
| `src/test/java/com/masterenglish/api/config/ModuleEventConfigurationTests.java` | CREATE |
| `src/test/java/com/masterenglish/api/infrastructure/events/EventPublicationMaintenanceTests.java` | CREATE |
| `docs/practice/CONTRACT.md`, `docs/m05/01-adrs-07-to-13.md`, `docs/ARCHITECTURE.md` | MODIFY (4.8) |
| `src/main/java/com/masterenglish/api/modules/learning/package-info.java` | DO NOT TOUCH (đã đủ dependency) |
| `src/main/java/com/masterenglish/api/common/events/**` | DO NOT TOUCH (`ResultsEvents` thuộc T4) |
| `src/main/java/com/masterenglish/api/modules/{results,practice,vocabulary,grammar,content}/**` | DO NOT TOUCH |
| `src/main/resources/db/migration/V1..V17` | DO NOT TOUCH |
| `src/main/resources/META-INF/spring.factories` | DO NOT TOUCH (không dùng fragment) |
| `docs/database/**` | DO NOT TOUCH (T5-D06) |
| `src/test/java/com/masterenglish/api/ArchitectureTests.java` | DO NOT TOUCH (T3 đã thêm `results`) |

---

## 10. Lộ trình commit

Mỗi bước phải build xanh và qua `spotlessCheck`. Chỉ commit khi người dùng yêu cầu. Không thêm dòng ghi công.

| Bước | Nội dung | Lệnh kiểm tra | Commit đề xuất |
|---|---|---|---|
| 1 | V18, `MistakeRecord`, `MistakeRecordRepository`, `MistakeRecordMigrationTests` | `.\gradlew.bat spotlessCheck test --tests '*MistakeRecordMigrationTests' --tests '*LayerArchitectureTests'` | `feat(learning): thêm bảng ghi lỗi sai và sổ idempotent` |
| 2 | `ModuleEventConfiguration`, `EventPublicationMaintenance`, thuộc tính 4.7, test cấu hình | `--tests '*ModuleEventConfigurationTests' --tests '*EventPublicationMaintenanceTests'` | `feat(config): executor riêng và bảo trì event bất đồng bộ` |
| 3 | `MistakeRecordListener` cùng unit và integration test | `--tests 'com.masterenglish.api.modules.learning.*' --tests 'com.masterenglish.api.modules.results.*'` | `feat(learning): ghi lỗi sai từ sự kiện nộp bài` |
| 4 | A1–A4 | `--tests '*ArchitectureTests' --tests '*LearningSqlBoundaryTests'` | `test(arch): khóa ranh giới event giữa results và learning` |
| 5 | Delta tài liệu 4.8 | Soát diff | `docs(m05): ghi nhận listener lỗi sai và vòng đời event` |
| 6 | Cổng cuối | `.\gradlew.bat check bootJar` (cần Docker cho Testcontainers) | — |

---

## 11. Test Matrix

### 11.1. Unit

| Test | Assert |
|---|---|
| `MistakeRecordListenerTests.passesOnlyWrongAndUnanswered` | Mock repository nhận `UUID[]` gồm đúng các câu `correct = false` |
| `MistakeRecordListenerTests.deduplicatesQuestionIds` | `outcomes` có `questionId` trùng → mảng không trùng |
| `MistakeRecordListenerTests.usesEffectiveFinishedAtAndClock` | `finishedAt = event.effectiveFinishedAt()`, `now = Clock` cố định |
| `MistakeRecordListenerTests.listenerIdIsStable` | `@ApplicationModuleListener.id() == "learning.mistake-records"` |
| `ModuleEventConfigurationTests.executorIsBounded` | `core = 1`, `max = 2`, `queue = 100`, prefix `module-event-`, `getAsyncExecutor()` là cùng bean |
| `ModuleEventConfigurationTests.rejectionDoesNotThrow` | Pool đầy: `execute` không ném lỗi |
| `EventPublicationMaintenanceTests.resubmissionOptions` | `batchSize = 20`, `maxInFlight = 10`, `minAge = 1 phút`; filter loại `completionAttempts = 10`, giữ `9` |
| `EventPublicationMaintenanceTests.purgeUsesSevenDays` | Gọi `deletePublicationsOlderThan(Duration.ofDays(7))` |
| `LearningSqlBoundaryTests` (A4) | Không `@Query` nào của `learning` nhắc bảng `results` |

### 11.2. Integration (Testcontainers PostgreSQL 17, seed T1, start/submit qua API T3/T4)

Test async **không** dùng `@Transactional`. Chờ bằng Awaitility (`atMost(10s)`). `@AfterEach` chờ `event_publication` không còn dòng `completion_date IS NULL` có `status <> 'FAILED'`.

| Test | Assert |
|---|---|
| `MistakeRecordMigrationTests.v18AppliesAndValidates` | Flyway tới V18; Hibernate `validate` xanh |
| `MistakeRecordMigrationTests.constraints` | `status = 'X'` → `23514` (c3); `wrong_count = 0` → c4; `OPEN` với `consecutive_correct_count = 1` → c5; trùng `(user_id, question_id)` → `23505` |
| `MistakeRecordMigrationTests.ownershipForeignKey` | Guard hoặc record với `user_id` khác chủ `attempt` → `23503` |
| `MistakeRecordEventIntegrationTests.submitCreatesOpenMistakes` | Seed S01: trả lời sai 2 câu, bỏ trống 1, đúng phần còn lại; nộp. Kết quả: 3 dòng `OPEN`, `wrong_count = 1`, `source_attempt_id = last_processed_attempt_id = attemptId`; 1 dòng guard; `event_publication.listener_id = 'learning.mistake-records'`, `status = 'COMPLETED'` |
| `…redeliveryIsNoOp` | Gọi thẳng `MistakeRecordRepository.recordSubmittedAttempt(...)` thêm 2 lần với tham số của cùng event, mỗi lần trong `TransactionTemplate` mới → mỗi lần trả 0, `wrong_count` vẫn 1. Lý do gọi thẳng repository (rà soát chéo, C-09): gọi `listener.on(event)` qua bean Spring sẽ đi qua proxy `@Async`, trả về ngay và chạy trên `module-event-*`, nên không đọc được kết quả và assert sẽ đua với luồng nền. |
| `…redeliveryThroughListenerIsNoOp` | Gọi `listener.on(event)` qua bean 2 lần, rồi chờ bằng Awaitility (tối đa 10 s) tới khi đủ 2 lần gọi spy `recordSubmittedAttempt` → `wrong_count` vẫn 1 |
| `…concurrentRedeliveryIsNoOp` | `ConcurrentRequests.runAtOnce(2, …)`: mỗi luồng gọi `recordSubmittedAttempt(...)` trong `TransactionTemplate` riêng với cùng tham số → tổng giá trị trả về = số câu sai của lần đầu (một luồng làm hết, luồng kia trả 0); đúng 1 guard; `wrong_count = 1` |
| `…secondAttemptIncrementsAndMovesSource` | Lượt 2 sai cùng câu → `wrong_count = 2`, `source_attempt_id = lượt 2` |
| `…outOfOrderDeliveryKeepsLatestSource` | Gọi `recordSubmittedAttempt` cho B rồi cho A (gọi thẳng repository, cùng lý do trên) với `A.effectiveFinishedAt < B` → `source_attempt_id = B`, `last_wrong_at = B.effectiveFinishedAt`, `wrong_count = 2` |
| `…allCorrectWritesGuardOnly` | 0 `mistake_records`, 1 guard |
| `…emptyWrongListStillWritesGuard` | `UUID[0]` bind thành công (kiểm giả định 4.3) |
| `…listenerFailureDoesNotRollbackSubmit` | `@MockitoSpyBean MistakeRecordRepository` ném `DataAccessResourceFailureException` lần đầu. Kết quả: submit 200; `attempts.status = 'SUBMITTED'`; publication `FAILED`. Sau đó gọi trực tiếp `FailedEventPublications.resubmit(ResubmissionOptions.defaults())` (`minAge = 0`; option thật của job được kiểm ở 11.1) → `COMPLETED`, dòng `OPEN` xuất hiện, `wrong_count = 1`. |
| `…runsOnModuleEventThread` | Spy ghi `Thread.currentThread().getName()` → bắt đầu bằng `module-event-` |
| `…eventSerializationRoundTrip` | `EventSerializer` bean: serialize rồi deserialize `AttemptSubmitted` 200 câu → `equals`. JSON thiếu `unanswered` → `false`. JSON có field lạ → lỗi (khóa T5-D10). |
| `…timeLimitUsesDeadline` | Lượt hết giờ tự nộp → `last_wrong_at = deadline_at` |

### 11.3. Security và anti-leak

| Test | Assert |
|---|---|
| `…serializedEventHasNoContent` | `serialized_event` không chứa chuỗi `prompt`, `transcript`, `explanation`, `body` của seed |
| `…logsHaveNoUserId` | `OutputCaptureExtension`: log DEBUG của listener không chứa `userId` |

### 11.4. Concurrency (không `@Transactional`)

| Test | Assert |
|---|---|
| `MistakeRecordConcurrencyTests.twoAttemptsSameUserOverlap` | 2 lượt cùng người học, 50 câu sai chung, xử lý song song 20 vòng → `wrong_count = 2` mỗi câu; nếu có deadlock thì publication `FAILED` và được gửi lại thành công |
| `…listenersNeverExceedTwoConcurrent` | Test đặt `spring.datasource.hikari.maximum-pool-size=5`. Spy `recordSubmittedAttempt` giữ `AtomicInteger` đang chạy và giá trị lớn nhất, chèn trễ 50 ms. Nộp 30 lượt liên tiếp → giá trị lớn nhất ≤ 2. 10 request `GET /api/v1/practice-sets` gửi song song trong lúc đó vẫn 200. |

### 11.5. ArchUnit

| Test | Assert |
|---|---|
| `ArchitectureTests` | `modules.verify()` xanh; không thêm dependency |
| `LayerArchitectureTests` | 14 rule (13 hiện có + T2) và A1–A3 xanh |
| `LearningSqlBoundaryTests` | A4 xanh |

---

## 12. Definition of Done và Open Decisions

### 12.1. DoD

| # | Tiêu chí | ☐ |
|---|---|---|
| 1 | V18 additive; không sửa V1–V17; `ddl-auto=validate` xanh | ☐ |
| 2 | Listener chỉ phụ thuộc `common.events`; không đọc bảng của `results` (A4) | ☐ |
| 3 | Một câu SQL cho mỗi event; giao lại không tăng `wrong_count` (11.2, 3 test) | ☐ |
| 4 | Kết quả không phụ thuộc thứ tự giao event | ☐ |
| 5 | Lỗi listener không làm hỏng submit (`listenerFailureDoesNotRollbackSubmit`) | ☐ |
| 6 | Listener chạy trên `module-event-*`, tối đa 2 thread | ☐ |
| 7 | `FAILED` tự gửi lại; `COMPLETED` > 7 ngày bị xóa; event độc dừng sau 10 lần | ☐ |
| 8 | `listener_id = learning.mistake-records` trong `event_publication` | ☐ |
| 9 | A1–A4 và `modules.verify()` xanh | ☐ |
| 10 | Không có endpoint mới; không có `AttemptCreationFacade` (trừ khi O1 = B) | ☐ |
| 11 | `.\gradlew.bat check bootJar` xanh, có Docker. Ghi rõ nếu không chạy được. | ☐ |
| 12 | Delta tài liệu 4.8 đã áp dụng | ☐ |

### 12.2. Câu hỏi cần duyệt

| # | Câu hỏi | Đề xuất |
|---|---|---|
| O1 | Đề T5 yêu cầu `vocabulary`/`grammar` gọi `AttemptCreationFacade`. T3 O2 đã duyệt "không tạo". (A) giữ T3: không tạo, Unified Engine qua `POST /attempts`; hay (B) tạo theo Phụ lục A? | **A.** Không có caller. Phương án B tạo vòng `grammar → results → practice → grammar` mà `verify()` không phát hiện. |
| O2 | Câu bỏ trống có tạo `mistake_records` không, kể cả bỏ trống vì hết giờ (`TIME_LIMIT`)? | **Có**, đúng đề (`!correct`) và đúng luật chấm (bỏ trống là sai). Phân biệt `unanswered` để lọc ở D13 nếu cần (event đã mang cờ này). |
| O3 | Giữ `completion-mode=update` và xóa `COMPLETED` > 7 ngày; hay đổi sang `completion-mode=delete`? | **Giữ `update` + 7 ngày.** Có dấu vết điều tra; khoảng 10 MB. |
| O4 | Lệch so với đề và DD: thêm `last_wrong_at` (DD có, đề không); bỏ `source_snapshot_id`/`last_evaluated_at`/`resolved_at` (đợi D13); giữ FK sở hữu tới `attempts(user_id, id)`. | **Đồng ý cả ba.** `last_wrong_at` cần để `source_attempt_id` đúng khi event đảo thứ tự. |

---

## Phụ lục A. `AttemptCreationFacade` (chỉ dùng khi O1 = B)

Đặt ở `common/events/ResultsEvents.java`, cùng pattern `ModuleEvents.request` + `EventReply` như `VocabularyEvents`:

```java
public AttemptStarted startAttempt(UUID userId, UUID idempotencyKey, StartAttemptCommand command) {
    return events.request(reply -> new StartAttemptRequested(userId, idempotencyKey, command, reply));
}

public record StartAttemptCommand(
        String mode,            // PRACTICE | EXAM
        String sourceType,      // PRACTICE_SET | GRAMMAR_TOPIC | MIXED_GRAMMAR
        UUID practiceSetId,     // PRACTICE_SET
        UUID topicId,           // GRAMMAR_TOPIC
        List<UUID> topicIds,    // MIXED_GRAMMAR
        Integer questionCount) {}

public record AttemptStarted(UUID attemptId, boolean created) {}

public record StartAttemptRequested(
        UUID userId, UUID idempotencyKey, StartAttemptCommand command, EventReply<AttemptStarted> reply) {}
```

- Handler: `results/service/impl/ResultsEventHandler.java`, `@EventListener` đồng bộ, `@Transactional(propagation = MANDATORY)`. Nó gọi đúng service start của T3. Validation và mã lỗi giống `POST /attempts`.
- Nhánh này cần thêm:
  - endpoint của module gọi (ví dụ `POST /api/v1/vocabulary/decks/{deckId}/tests`);
  - guard chống vòng: handler của `grammar` không được gọi lại `ResultsEvents`;
  - test tái nhập `grammar → results → practice → grammar`.
- Không đặc tả sâu hơn khi chưa có quyết định O1 = B.
