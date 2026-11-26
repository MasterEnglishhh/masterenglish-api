# T7 — Implementation Brief: Verification suite, ArchUnit, security audit và benchmark trên mục tiêu `t3.small`

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026. O1–O5 theo đề xuất: stack benchmark build web từ source; đếm SQL bằng `pg_stat_statements` và Hibernate statistics; SLA start chốt bằng p95 làm tròn lên bội số 100 ms, phải ≤ 800 ms; commit fixture SQL 200 câu; số đo chính thức ưu tiên EC2 `t3.small`. Thêm theo AD-3: kiểm response JSON lớn có `Content-Encoding: gzip`. |
| Ngày | 26/11/2026 |
| Phụ thuộc | T0–T6 (đã duyệt). T5 O1–O4 và T6 O1–O5 được duyệt theo đề xuất, nên các mục đánh dấu ⟂ bên dưới giữ nguyên. |
| Repo | `masterenglish-api` (test, compose, k6, báo cáo); `masterenglish-web` (test frontend) |
| Chế độ viết brief | READ-ONLY. Chưa tạo file test, compose hay báo cáo nào. |

Nhãn: `[CREATE]`, `[MODIFY]`, `[DO NOT TOUCH]`.

**Cách triển khai.**
- T7 không thêm tính năng. T7 thêm: bộ test nghiệm thu `com.masterenglish.api.m05`, fixture Full Test 200 câu, stack benchmark và báo cáo `docs/m05/03-benchmark-and-signoff.md`.
- Báo cáo chỉ được viết **sau khi chạy thật**. Brief này chỉ đưa khuôn mẫu ở 4.6.
- Phiên viết brief không chạy được Docker (`docker info` lỗi ở các task trước), nên chưa có số đo nào.

---

## 1. Metadata & Traceability

### 1.1. Sản phẩm

1. **Architecture và migration:** `TableOwnershipTests`, `CrossModuleRelationTests`, `MigrationImmutabilityTests`, `M05UpgradeFromV13Tests`, cùng một bước CI `git diff` cho V1–V13 (3.M1).
2. **Integration (PostgreSQL 17 thật):** 9 lớp test nghiệm thu (3.M2).
3. **Ngân sách SQL:** `RoundTripBudgetTests` (Hibernate statistics) trên fixture Full Test 200 câu (3.M3).
4. **Stack benchmark:** `compose.benchmark.yaml`, `scripts/benchmark/*` (seed user, sinh fixture, k6, lấy mẫu RAM), báo cáo `03` (3.M3).
5. **Frontend:** bổ sung test cho T6, gồm hồi phục sau F5 và smoke e2e trên stack benchmark (3.M4).
6. **Security audit:** checklist có bằng chứng, và ma trận DoD T0–T6 → kiểm chứng (3.M5).

### 1.2. Traceability

| Mã | T7 kiểm chứng |
|---|---|
| R1, S6, S7, ADR-07 | `SnapshotImmutabilityTests`, `DeepAntiLeakTests` |
| R2, S2 | `AttemptLifecycleGuardrailTests.noExpiredAnywhere` |
| R3 | `IdempotencyAndCasTests.examModeRejected` |
| R4 | `AttemptLifecycleGuardrailTests` |
| R5 | `UnifiedEngineRegressionTests.legacyGrammarEndpointsGone` |
| R6 | Test frontend 3.M4, `DeepAntiLeakTests.noTemplateStrings` |
| R8 | `IdempotencyAndCasTests`, `SecurityAndOwnershipTests` (metadata có kiểu) |
| S3, ADR-12 | `SubmitConcurrencyAndListenerTests`, `TableOwnershipTests` |
| S9 | `UnifiedEngineRegressionTests.multiPartSetHasNullPart` |
| ADR-08 | `FixedOptionOrderTests`, `DeepAntiLeakTests.labelOnlyBodiesNull` |
| ADR-09, ADR-11 | `IdempotencyAndCasTests`, `UnifiedEngineRegressionTests.noScoreFields` |
| ADR-13 | `RoundTripBudgetTests`, benchmark k6 |
| D8 v2 | `MigrationImmutabilityTests`, `M05UpgradeFromV13Tests` |

### 1.3. Explicitly Out-of-Scope

- Sửa code tính năng. Test đỏ thì mở lỗi cho task gốc (T1–T6), không vá trong T7.
- Đo độ trễ R2 và Range/206 trên R2. Đã là kiểm tra tay của T2 11.5. Benchmark không gọi `/media/*`.
- Benchmark AI service, Redis rate limit, mail.
- Tải dài (soak) quá 5 phút; tải cố ý vượt ngưỡng (stress, DoS).
- Thêm dependency vào build chính (p6spy…): O2.

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc và lệnh đã chạy

| Nhóm | File / lệnh |
|---|---|
| Test hiện có | `ArchitectureTests.java` (assert 8 module + `verify()`), `LayerArchitectureTests.java` (13 `@Test`), `TestcontainersConfiguration.java` (`postgres:17`, `@ServiceConnection`), `support/ConcurrentRequests.java` (`CyclicBarrier`), `VocabularyUpgradeTests.java` (Flyway `.target("5")` rồi `latest`), `LessonProgressControllerIntegrationTests.java` (MockMvc + `springSecurity()`, `csrf()`, `user()`), `GrammarPracticeControllerIntegrationTests.java:113-138` (publish qua `ContentAuthoringService.createItemWithDraft → submitForReview → approve → publish`) |
| Build | `build.gradle`: Spotless 8.10.4 (`googleJavaFormat('1.28.0').aosp()`, `removeUnusedImports`), task `test` cấp secret giả; Modulith 2.1.1; Boot 4.1.2 |
| CI | `.github/workflows/ci.yml`: `docker info`, `python3 -B -m unittest discover -s scripts/pronunciation`, `./gradlew spotlessCheck check bootJar --no-daemon`, Docker build |
| Hạ tầng | `compose.local.yaml` (chỉ `postgres:17`, `redis`, `mailpit`; **không** có API, web, giới hạn RAM hay `shared_buffers`); `Dockerfile` (`eclipse-temurin:21-jre-noble`, `ENTRYPOINT java -Duser.timezone=UTC -jar`, **không** có `MaxRAMPercentage`); `grep -i hikari` trong `src/main/resources`: **0 kết quả** (Hikari mặc định 10 kết nối) |
| Identity | V2 `users`, `user_roles`, `roles` (1 = `LEARNER`); V3 thêm `first_name`, `last_name`, `email_verified_at`, status `PENDING_VERIFICATION`; `BCryptPasswordEncoder(12)`; `POST /api/v1/auth/login` (`email`, `password`); `GET /api/v1/auth/csrf` → `{headerName, token}` |
| Migration | `git rev-parse origin/main` = `4afb631`. Blob V13 trên `origin/main` = `19f61523d4b5edc019963596b5e9b5a5e37cac45`, trùng working tree. Checksum Flyway V1–V13 tính trong phiên ở 3.M1. |
| Brief | DoD của T0 (8), T1 (9), T2 (8), T3 (9), T4 (8), T5 (12), T6 (12) = **66 tiêu chí**, ánh xạ ở 3.M5 |
| Web | Toàn bộ grounding của T6 §2.1 |

### 2.2. Drift

| Mã | Đề T7 | Hợp đồng đã duyệt / repo | Xử lý |
|---|---|---|---|
| T7-D01 | `409 MAX_ACTIVE_ATTEMPTS_REACHED` | T3 O1: `OPEN_ATTEMPT_LIMIT_REACHED` (`openAttemptCount`, `maxOpenAttempts`, `activeAttemptIds`) | Test assert tên đã duyệt |
| T7-D02 | `ATTEMPT_NOT_OPEN` kèm `status` | T4 O1: metadata là `attemptStatus`. `status` là số HTTP của ProblemDetail. | Assert `attemptStatus` (chuỗi) **và** `status = 409` (số) |
| T7-D03 | Đẩy `updated_at`/`created_at` lùi 8 ngày | T3 3.M3: abandon lazy theo `last_active_at <= now − 7 ngày` và `deadline_at IS NULL` | Test sửa `last_active_at`. Thêm ca biên 7 ngày − 1 phút. |
| T7-D04 | `optionText == null` | Cột `attempt_snapshot_options.body`; DTO `options[].body` | Assert cả DB lẫn JSON |
| T7-D05 | `AttemptSubmittedEvent` | `ResultsEvents.AttemptSubmitted` (T4). Mỗi listener một dòng `event_publication`; T5 có đúng 1 listener (`learning.mistake-records`). | Đếm theo `event_type` và `listener_id` |
| T7-D06 | Sửa stem, đổi đáp án, xóa lựa chọn, đổi media "trên bảng gốc" | Revision đã publish bất biến (V7 `published_revision_immutable`). Projection `questions`/`question_options` được ghi lại khi publish revision mới (`QuestionProjectionListener:94-108` xóa rồi chèn lại option). `attempt_snapshot_options.source_option_id` không có FK (T1). | Thay đổi bằng **publish revision mới** qua `ContentAuthoringService` (đường thật). Thêm một ca `UPDATE` SQL trực tiếp lên `questions.prompt` để chứng minh không có đọc ngầm. |
| T7-D07 | Không có file Compose / Hikari cho benchmark | Xem 2.1 | Tạo `compose.benchmark.yaml` riêng. Không sửa `compose.local.yaml` hay `Dockerfile`; JVM flag qua `JAVA_TOOL_OPTIONS`, Hikari qua biến môi trường. |
| T7-D08 | Đếm SQL "qua p6spy" | p6spy không có trong build | `pg_stat_statements` trong container benchmark và Hibernate `Statistics` trong test. Không thêm dependency. (O2) |
| T7-D09 | Seed T1 dùng cho Full Test 200 câu | Seed S01 chỉ có 24 câu, 5 nhóm | Fixture riêng 200 câu, 42 nhóm, 775 lựa chọn (4.3) |
| T7-D10 | "5 hạng mục" nhưng đề chỉ ghi 4 | Tiêu đề có "Security Audit"; mục A.3 yêu cầu ánh xạ DoD | Hạng mục 5 = security audit + ma trận DoD (3.M5) |
| T7-D11 | 20 lần tạo attempt P1/P2 liên tiếp | Trần 5 lượt mở (R4) | Mỗi vòng nộp lượt vừa tạo rồi mới mở lượt tiếp |
| T7-D12 | `app.security.cookie-secure` mặc định `true` | k6 gọi `http://web:3000`; cookie `Secure` không được gửi qua HTTP | Stack benchmark đặt `APP_SECURITY_COOKIE_SECURE=false`, chỉ trong mạng Docker nội bộ |
| T7-D13 | Kiểm `DELETE media_assets` | Media seed còn bị `revision_media_refs` (revision đã publish) tham chiếu, nên FK đó chặn trước | Ca test dùng một `media_assets` mới, chỉ có trong `attempt_media_refs`; assert đúng tên constraint |
| T7-D14 | Phục hồi state sau F5 (frontend) | T6: hàng đợi chỉ trong bộ nhớ; `savedAnswers` của `GET /attempts/{id}` là nguồn phục hồi; `beforeunload` cảnh báo khi còn việc chờ | Test: sau reload, lựa chọn **đã lưu** hiện lại; lựa chọn chưa lưu thì mất, đúng thiết kế |

---

## 3. Mandatory Design Resolutions

### M1. Architecture, build và migration integrity

**Test gói `src/test/java/com/masterenglish/api/m05/` `[CREATE]`.** Gói ngoài `modules/`, nên rule `businessModulesUseOnlyMvcPackages` không áp; rule đó chỉ import code main.

| Test | Nội dung | Assert |
|---|---|---|
| `ArchitectureTests` `[MODIFY]` (T3 đã thêm `results`) | Thêm 2 assert | `modules.getModuleByName("results")` có mặt. `learning` không có dependency trực tiếp tới `results`: `modules.getModuleByName("learning").orElseThrow().getDirectDependencies(modules)` không chứa module `results`. Nếu API Modulith 2.1.1 khác tên, dùng rule slices sẵn có và ghi chú. `verify()` xanh (không vòng, không phụ thuộc trái `allowedDependencies`). |
| `LayerArchitectureTests` | Giữ 14 rule (13 + T2) + A1–A3 (T5) ⟂ | Xanh |
| `CrossModuleRelationTests.noJpaRelationsAcrossModules` `[CREATE]` | ArchUnit `fields().that().areAnnotatedWith(ManyToOne/OneToMany/OneToOne/ManyToMany)`. Kiểu đích (hoặc kiểu phần tử của collection) phải nằm trong **cùng** `modules.<x>` với class khai báo. | 0 vi phạm. Kèm `noRelationsAtAllInResults`: `results.entity` không có annotation quan hệ nào (T0 §7.2). |
| `TableOwnershipTests.everyTableHasOneOwner` `[CREATE]` | Quét mọi `@Entity` trong `com.masterenglish.api.modules`, đọc `@Table(name)`, so với bảng sở hữu ở 4.1 | `media_assets` chỉ map ở `content`; `mistake_records` chỉ ở `learning` (S3); `attempt_*`, `attempts` chỉ ở `results`; `questions`, `question_*`, `practice_sets`, `set_items`, `question_groups`, `group_materials` chỉ ở `practice`. Không bảng nào map ở hai module. |
| `TableOwnershipTests.syncCrossModuleCallsUseCommonEvents` | ArchUnit: class trong `..modules.X..` chỉ phụ thuộc `..modules.X..`, `..common..`, JDK, framework | Gộp với rule slices hiện có. Thêm message rõ cho người đọc. |
| `PracticeEventsSurfaceTests.onlyPrepareSeal` `[CREATE]` (thêm ở rà soát chéo, phủ DoD T2-2) | Reflection trên `common.events.PracticeEvents`: method `public` khai báo trong class | Đúng một method tên `prepareSeal`, tham số `(UUID, SealRequest)`. Không còn `countAvailable`, `selectQuestions`, `loadSetForSealing` (T2 O4). |
| `MigrationImmutabilityTests.mergedMigrationsKeepTheirChecksums` `[CREATE]` | `Flyway.configure().locations("classpath:db/migration").load().info().all()`. Lấy `getChecksum()` của V1–V13 và so với bảng hằng 4.2. Không cần DB (Flyway tính checksum từ file). | Khớp từng phiên bản |
| CI `[MODIFY]` `.github/workflows/ci.yml` | Bước trước Gradle: `git fetch --no-tags --depth=1 origin 4afb631` rồi `git diff --exit-code 4afb631 -- src/main/resources/db/migration/V1__*.sql … V13__*.sql` (liệt kê 13 file) | Exit 0 |
| `spotlessCheck` | Đã có trong CI và `check` | Xanh |

**`M05UpgradeFromV13Tests` `[CREATE]`.** Mô phỏng production có dữ liệu V13 (D8 v2).

1. Container `postgres:17` riêng (`@Container static`). Trong `@DynamicPropertySource` (chạy **trước** khi tạo context):
   - `Flyway…target("13").migrate()`;
   - nạp `src/test/resources/db/m05-upgrade/v13_grammar_fixture.sql` bằng JDBC.
2. Fixture V13 (4.4), viết tay theo DDL V13:
   - 2 `grammar_topics`, 12 câu GRAMMAR đã publish (`content_items`, `content_revisions`, `questions`, `question_options`, `question_topics`);
   - user L1: lượt `GRAMMAR_TOPIC` **SUBMITTED** (10 câu, 7 đúng, `score = 70`), lượt `MIXED_GRAMMAR` **IN_PROGRESS** (5 câu, 2 đã chọn);
   - user L2: lượt `IN_PROGRESS` 0 câu đã chọn.
3. Context khởi động với `spring.flyway.enabled=true`: Flyway chạy V14+, Hibernate `ddl-auto=validate` phải xanh.
4. Assert sau nâng cấp:
   - `flyway_schema_history` có V14…V18 `success = true`;
   - dòng V13: `question_snapshot ->> 'schema_version' = '1'` và `legacy_backfill = true` (V15), `scoring_version = 1`;
   - `GET /attempts/{inProgress}` → 200, đủ 5 câu, `savedAnswers` khớp 2 lựa chọn cũ, `version` ≥ 0;
   - `PUT` câu thứ 3 → 200; `submit` → 200; `score` theo % `HALF_UP` (T4 O4 của T3); `GET /results` → 200 với `review.correctOptionId` đúng lựa chọn `is_correct` cũ;
   - lượt SUBMITTED cũ: `/results` → 200, `correctCount = 7`, giữ `score = 70`;
   - `GET /attempts/statistics/topics?category=GRAMMAR&view=allocation` → số liệu bằng số tính tay trên fixture;
   - V15 chạy lại được: gọi `Flyway…target("15")` trên DB đã ở latest là no-op, không lỗi (T1 rerunnable).

### M2. Integration, anti-leak, concurrency, R4/R8 (PostgreSQL 17 thật)

Quy ước chung:
- `@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`, MockMvc + `springSecurity()`, `csrf()`, `user(...)`, theo mẫu `LessonProgressControllerIntegrationTests`.
- Dữ liệu là seed T1 (S01 TOEIC 24 câu P1–P7, S02 VOCAB có `LISTEN_AND_CHOOSE`, S03 GRAMMAR). Nạp bằng `LocalM05SeedRunner.seedFlyway(flyway).migrate()` trong `@BeforeAll`.
- Test đồng thời **không** dùng `@Transactional` và chạy qua `ConcurrentRequests.runAtOnce`.
- Cuối mỗi lớp có listener async: chờ `event_publication` hết dòng `completion_date IS NULL AND status <> 'FAILED'` (Awaitility 4.3.0, tối đa 10 s), tránh làm bẩn lớp sau.
- Helper `support/M05Api.java` `[CREATE]`: `start(user, body, key)`, `get`, `put(user, attemptId, snapshotId, optionId, expectedVersion, key)`, `submit`, `results`. Mỗi hàm trả `MvcResult`.

#### `SnapshotImmutabilityTests` (ADR-07, R1)

| Test | Bước | Assert |
|---|---|---|
| `republishDoesNotChangeOpenAttempt` | 1) L1 start S01 → lưu JSON `GET` (A0) và chọn đáp án đúng cho câu P5 `q5`. 2) Publish revision mới cho `q5`: đổi `prompt`, đổi lựa chọn đúng từ B sang D. Với câu P3 `g3`, đổi `media_asset_id` của audio sang media mới. 3) Publish revision mới cho câu GRAMMAR (lượt khác của L1 từ S03) bỏ 1 lựa chọn. 4) `GET` lại (A1), nộp, `/results`. | `A1` giống `A0` từng byte, sau khi bỏ `lastActiveAt` và `savedAnswers`. Kết quả chấm `q5` đúng (đáp án cũ B). `review.correctOptionLabel = "B"`. `mediaUrl` của `g3` vẫn trỏ media cũ. `attempt_media_refs` vẫn có media cũ. Lựa chọn đã xóa khỏi `question_options` vẫn có trong `attempt_snapshot_options` và vẫn chọn được. |
| `directProjectionUpdateIsInvisible` | `UPDATE questions SET prompt = 'MUTATED' WHERE id = :q5` (JDBC) sau khi start | `GET` và `/results` không chứa `MUTATED` |
| `newAttemptSeesNewRevision` | Start lượt mới sau khi publish | Có prompt mới, đáp án D. Đối chứng cho thấy test trên không xanh giả. |

#### `DeepAntiLeakTests` (ADR-08, R1)

Hàm `JsonLeakScanner.scan(JsonNode)` duyệt đệ quy mọi object và mảng:
- **Khóa cấm** (so khớp chính xác, không phân biệt hoa thường): `private_snapshot`, `privateSnapshot`, `answer_key_snapshot`, `answerKeySnapshot`, `correctOption`, `correctOptionId`, `correctOptionLabel`, `correct_option_snapshot_id`, `correct_option_ordinal`, `isCorrect`, `is_correct`, `explanation`, `evidence`, `transcript`, `optionTranscripts`, `option_transcripts`, `promptTranscript`, `prompt_transcript`, `reviewMaterials`, `review`, `answer`, `meta`, `sourceOptionId`, `source_option_id`, `score`.
- **Giá trị cấm:** `materialType = "TRANSCRIPT"`; mọi `visibility = "AFTER_SUBMIT"`.
- **Văn bản cấm:** tập chuỗi lấy từ seed (`explanation` của mọi câu, `text` của mọi `segments`, `option_transcripts`, `prompt_transcript`). Tập này đọc từ DB bằng `SELECT answer_key_snapshot, private_snapshot` của chính lượt; không chuỗi nào được là chuỗi con của body response.

| Test | Assert |
|---|---|
| `startResponseHasNoPrivateDataForAllSevenParts` | Body `POST /attempts` (S01, đủ Part 1–7, gồm P3 và P4) → scanner 0 vi phạm; có đủ `partNumber` 1..7 trong `questions` |
| `getResponseHasNoPrivateDataInEveryStatus` | `GET` khi `IN_PROGRESS`, sau `SUBMITTED` (T4 O2), và khi `ABANDONED` → 0 vi phạm |
| `autosaveResponseHasNoPrivateData` | Body `PUT` → 0 vi phạm |
| `labelOnlyBodiesNull` | DB: `SELECT s.part_number, count(*) FILTER (WHERE o.body IS NOT NULL), count(*) FROM attempt_snapshot_options o JOIN attempt_question_snapshots s ON s.id = o.snapshot_id WHERE s.attempt_id = :a AND s.part_number IN (1,2) GROUP BY s.part_number, s.id` → mọi dòng có 0 body; P1 có 4 lựa chọn, P2 có 3. JSON: mọi `options[].body` của P1/P2 là `null`. |
| `isCorrectNullAfterAutosave` | Sau 3 `PUT` (chọn, đổi, bỏ chọn): `SELECT count(*) FROM attempt_answers WHERE attempt_id = :a AND is_correct IS NOT NULL` = 0 |
| `noTemplateStrings` | Body không chứa `{{` (R6) |

#### `FixedOptionOrderTests` (ADR-08)

`twentyStartsKeepSourceOrder`: lặp 20 lần (L1 start S01 → kiểm → submit để giải phóng trần 5 lượt). Assert với mọi câu P1/P2:
- `options[i].label = chr(65 + i)`;
- `attempt_snapshot_options.position` bằng `question_options.position` của `source_option_id` tương ứng (join theo `source_option_id`);
- `review.correctOptionLabel` giống nhau ở cả 20 lượt.

#### `AttemptLifecycleGuardrailTests` (R4, S2)

| Test | Bước | Assert |
|---|---|---|
| `sixthOpenAttemptRejectedWithIds` | L1 mở 5 lượt trộn category: S01 (TOEIC), S02 (VOCAB), S03 (GRAMMAR set), `GRAMMAR_TOPIC`, `MIXED_GRAMMAR` (2 topic; test publish thêm topic thứ hai bằng `ContentAuthoringService`). Mở lượt 6. | 409, `code = OPEN_ATTEMPT_LIMIT_REACHED`, `openAttemptCount = 5`, `maxOpenAttempts = 5`, `activeAttemptIds` là mảng chuỗi UUID **bằng đúng** tập 5 id (không thứ tự). Không có dòng `attempts` mới. |
| `staleAttemptIsAbandonedLazily` | `UPDATE attempts SET last_active_at = now() - interval '8 days' WHERE id = :oldest`; mở lượt 6 | 201. Lượt cũ `status = 'ABANDONED'`, `abandoned_at IS NOT NULL`. Đếm `IN_PROGRESS` = 5. `GET` lượt cũ → 200 `status = ABANDONED`. |
| `sevenDaysMinusOneMinuteIsNotAbandoned` | `last_active_at = now() - interval '7 days' + interval '1 minute'` | 409 như ca đầu; lượt cũ vẫn `IN_PROGRESS` |
| `abandonRollsBackWhenStartFails` | Lượt cũ quá 8 ngày; start một set không đủ câu → 409 `INSUFFICIENT_QUESTIONS` | Lượt cũ **vẫn** `IN_PROGRESS` (T3 `rollbackOnInsufficientKeepsAbandonUndone`) |
| `noExpiredAnywhere` | `SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'attempts'::regclass AND contype = 'c'` | Không định nghĩa nào chứa `EXPIRED`. `UPDATE attempts SET status = 'EXPIRED'` → SQLState `23514`. |

#### `IdempotencyAndCasTests` (ADR-09, R8)

| Test | Bước | Assert |
|---|---|---|
| `parallelStartSameKeyCreatesOne` | 2 luồng `POST /attempts` cùng key K, cùng body (`runAtOnce(2, …)`) | Tập status = {201, 200}; hai body cùng `id`. `SELECT count(*) FROM attempts WHERE user_id = :u AND request_id = :K` = 1. |
| `sameKeyDifferentPayload409` | K đã dùng với S01; gửi K với S02 | 409 `IDEMPOTENCY_CONFLICT` |
| `examModeRejected` | `mode = "EXAM"` | 400, `errors[0].field = "mode"` (R3) |
| `casSequence` | (1) K1, `expectedVersion = 0`, chọn X | 200, `version = 1`, `selectedOptionId = X` |
| | (2) Gửi lại K1, `expectedVersion = 0`, X | 200, `version = 1` (replay, **không** 409) |
| | (3) K2, `expectedVersion = 0`, X | 409 `VERSION_CONFLICT`, `currentVersion = 1` (số), `currentSelectedOptionId = X` (chuỗi UUID) |
| | (4) K3, `expectedVersion = 1`, `selectedOptionId = null` | 200, `version = 2`, khóa `selectedOptionId` **có mặt** với giá trị `null` |
| | (5) K4, `expectedVersion = 2`, X; sau đó K5, `expectedVersion = 2` | 409 `VERSION_CONFLICT` với `currentSelectedOptionId = X` |
| | (6) K3 với body khác (chọn Y, `expectedVersion = 1`) | 409 `IDEMPOTENCY_CONFLICT` |
| `conflictMetadataNullIsJsonNull` | Đưa câu về `null` (v n), rồi gửi `expectedVersion = n − 1` | `currentSelectedOptionId` có mặt và là JSON `null` (R8 `UUID_OR_NULL`) |
| `optionOfOtherQuestionBlockedTwice` | Service: `PUT` câu A với option của câu B | 400 `OPTION_NOT_IN_SNAPSHOT`; `attempt_answers` không đổi |
| | DB: `UPDATE attempt_answers SET selected_option_id = :optionOfB WHERE snapshot_id = :snapshotA` (JDBC) | SQLState `23503`, constraint `fk_attempt_answers_snapshot_option` (FK kép S7) |
| `missingOrBadIdempotencyKey400` | Thiếu header; header `abc` | 400 `VALIDATION_ERROR` cho cả `POST /attempts` và `PUT` |

#### `SubmitConcurrencyAndListenerTests` (ADR-12, S3) ⟂ T5

| Test | Bước | Assert |
|---|---|---|
| `fiveParallelSubmitsGradeOnce` | L1 start S01, trả lời 20/24 câu (biết trước 6 câu sai); 5 luồng `POST /submit` cùng lúc | Cả 5 là 200, 5 body `result` bằng nhau. `attempts.status = 'SUBMITTED'`, `correct_count` đúng. `SELECT count(*) FROM event_publication WHERE event_type LIKE '%ResultsEvents$AttemptSubmitted' AND serialized_event LIKE '%' \|\| :attemptId \|\| '%'` = **1**, `listener_id = 'learning.mistake-records'`. |
| `listenerCompletesAndRecordsMistakes` | Chờ publication `COMPLETED` | `mistake_records` của L1 = 10 dòng (6 sai + 4 bỏ trống), `wrong_count = 1`, `status = 'OPEN'`; 1 dòng `mistake_processed_attempts` |
| `replayingListenerTwiceDoesNotDoubleCount` | Đọc `serialized_event` → deserialize bằng bean `EventSerializer` → `@MockitoSpyBean MistakeRecordRepository` → gọi `MistakeRecordListener.on(event)` qua bean 2 lần. Lời gọi đi qua proxy `@Async` nên chạy trên `module-event-*`: chờ bằng Awaitility (tối đa 10 s) tới khi spy ghi nhận đủ 2 lần gọi `recordSubmittedAttempt` (C-09 của rà soát chéo). | Vẫn 10 dòng, mọi `wrong_count = 1`; 2 giá trị trả về mà spy ghi lại đều là 0 |
| `submitRacingAutosave` | 1 luồng `submit` + 4 luồng `PUT` cùng lúc | Mọi `PUT` là 200 (trước khóa) hoặc 409 `ATTEMPT_NOT_OPEN`/`attemptStatus = SUBMITTED`. `answered_count` bằng số slot có `selected_option_id` sau cùng. Không 5xx. |

#### `SecurityAndOwnershipTests`

| Test | Assert |
|---|---|
| `idorReturns404Everywhere` | L2 gọi trên lượt của L1: `GET`, `PUT`, `POST /submit`, `GET /results`, `GET /media/{id}` → đều 404 `RESOURCE_NOT_FOUND`; body không chứa id câu hay tiêu đề set |
| `resultsBeforeSubmit409` | `GET /results` khi `IN_PROGRESS` → 409 `ATTEMPT_NOT_SUBMITTED`, `attemptStatus = "IN_PROGRESS"` |
| `putAfterSubmitted409` | 409 `ATTEMPT_NOT_OPEN`, `attemptStatus = "SUBMITTED"`, `status = 409` (số) |
| `putAfterAbandoned409` | Lượt bị abandon lazy → `attemptStatus = "ABANDONED"` |
| `csrfRequired` | `POST /attempts`, `PUT`, `POST /submit` không có `csrf()` → 403 |
| `authRequired` | Không đăng nhập → 401 cho cả 6 endpoint `/api/v1/attempts/**` và `/api/v1/practice-sets` |
| `learnerRoleRequired` | User chỉ có `CONTENT_ADMIN` → 403 cho `/api/v1/attempts/**` |
| `mediaInAttemptCannotBeDeleted` | JDBC: chèn `media_assets` mới (`READY`, `CONTENT`, `AUDIO`) và `attempt_media_refs(:attempt, :media)`; `DELETE FROM media_assets WHERE id = :media` → `23503`, constraint `fk_attempt_media_refs_media_asset_id_media_assets`. `pg_constraint.confdeltype = 'r'`. |
| `problemDetailHasNoStackTrace` | Mọi body lỗi ở trên không chứa `Exception`, `at com.`, `org.springframework` |

#### `UnifiedEngineRegressionTests`

| Test | Assert |
|---|---|
| `toeicFullFlowSevenParts` | S01: start → trả lời hết → submit → `/results`. `partBreakdown` có 7 phần tử (Part 1–7); `listeningCorrect + readingCorrect = correctCount`; `accuracyPercent = HALF_UP(correct × 100 / total)` |
| `multiPartSetHasNullPart` | `practice_sets.part_number IS NULL` cho S01; `GET /practice-sets?category=TOEIC&partNumber=0` có S01 (S9) |
| `vocabularyWithAudio` | S02: câu `LISTEN_AND_CHOOSE` có `materials[materialType = AUDIO].mediaUrl` khớp `^/api/v1/attempts/{id}/media/[0-9a-f-]{36}$`; `attempt_media_refs` có media đó; `GET` mediaUrl với storage giả của T3 `MediaRedirectTests` → 302; nộp bài thành công, `partBreakdown = []` |
| `mixedGrammarQuiz` | `MIXED_GRAMMAR`, 2 topic, `questionCount = 6` → `attempt_source_topics` 2 dòng; nộp; thống kê allocation tăng đúng |
| `legacyGrammarEndpointsGone` | `POST /api/v1/grammar/topics/{id}/attempts`, `POST /api/v1/grammar/attempts/mixed`, `GET /api/v1/grammar/statistics/topics` → 404 hoặc 405 (không còn handler). `GET /topics/{id}/exercises` vẫn 200 (R5). |
| `noScoreFields` | Body `submit` và `/results` không có khóa `score`, `scaledScore`, `toeicScore`, `listeningScore`, `readingScore`, `estimated*` (ADR-11) |

### M3. Benchmark `t3.small` (ADR-13)

#### Ngân sách SQL trong test: `RoundTripBudgetTests` `[CREATE]`

- Nạp fixture Full Test (4.3) trong `@BeforeAll`.
- Bật `spring.jpa.properties.hibernate.generate_statistics=true` chỉ cho lớp này (`@TestPropertySource`).
- Mỗi ca:
  - `statistics.clear()`, gọi một request, đọc `getPrepareStatementCount()`;
  - MockMvc `user()` không chạy JWT filter, nên số đếm không gồm truy vấn xác thực.

| Ca | Ngân sách | Nguồn |
|---|---:|---|
| `startFullTest` (200 câu) | ≤ **15** (đọc 9, ghi 6) | T3 §7 |
| `getFullTest` | ≤ 5 | T3 R1–R5 |
| `autosave` | ≤ 4 (replay ≤ 2) | T4 M1 |
| `submitFullTest` lần đầu | ≤ 4 (3 + 1 `event_publication`) | T4 M5, T5 §7.2 |
| `submitAgain` | ≤ 3 | T4 M5 |
| `results` | ≤ 5 | T4 M4 |
| `startPart5Set` (30 câu) | ≤ 15 | Như start |

Đối chiếu ước lượng T0 §7.3 (≈ 25 round-trip, giả định batch JDBC 100): T3 thay batch bằng `unnest`, một câu mỗi bảng, nên kỳ vọng ≈ 15. Báo cáo ghi cả hai cột.

#### Stack benchmark: `compose.benchmark.yaml` `[CREATE]` (repo API, cạnh `compose.local.yaml`)

```yaml
# Benchmark stack for the t3.small target (T7). Not for development or production.
name: masterenglish-bench

services:
  postgres:
    image: postgres:17
    mem_limit: 384m
    cpus: 0.5
    shm_size: 64mb
    environment:
      POSTGRES_DB: masterenglish
      POSTGRES_USER: masterenglish
      POSTGRES_PASSWORD: ${BENCH_DB_PASSWORD:?set in .env.benchmark}
    command:
      - postgres
      - -c
      - shared_buffers=64MB
      - -c
      - max_connections=30
      - -c
      - work_mem=4MB
      - -c
      - effective_cache_size=192MB
      - -c
      - shared_preload_libraries=pg_stat_statements
      - -c
      - pg_stat_statements.track=all
      - -c
      - pg_stat_statements.track_utility=off
    ports: ["127.0.0.1:25432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U masterenglish -d masterenglish"]
      interval: 5s
      retries: 12

  api:
    build: .                        # Dockerfile hiện có; chạy .\gradlew.bat bootJar trước
    mem_limit: 768m
    cpus: 1.0
    depends_on: { postgres: { condition: service_healthy } }
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/masterenglish
      SPRING_DATASOURCE_USERNAME: masterenglish
      SPRING_DATASOURCE_PASSWORD: ${BENCH_DB_PASSWORD}
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "5"
      SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT: "30000"
      JWT_SECRET: ${BENCH_JWT_SECRET:?}
      OTP_HMAC_SECRET: ${BENCH_OTP_SECRET:?}
      RATE_LIMIT_SECRET: ${BENCH_RATE_LIMIT_SECRET:?}
      APP_SECURITY_COOKIE_SECURE: "false"
      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: "health,metrics"
    ports: ["127.0.0.1:28080:8080"]

  web:
    build:
      context: ../masterenglish-web  # O1
      args: { BACKEND_URL: "http://api:8080" }
    mem_limit: 384m
    cpus: 0.5
    environment:
      NODE_OPTIONS: "--max-old-space-size=256"
    depends_on: [api]
    ports: ["127.0.0.1:23000:3000"]

  k6:
    image: grafana/k6:1.0.0
    profiles: ["load"]
    depends_on: [web]
    environment:
      BASE_URL: http://web:3000
      BENCH_PASSWORD: ${BENCH_PASSWORD:?}
    volumes: ["./scripts/benchmark:/scripts:ro", "./build/benchmark:/out"]
    command: ["run", "--summary-export=/out/k6-summary.json", "--out", "json=/out/k6-raw.json", "/scripts/m05.js"]
```

- Tổng CPU 2,0 tương ứng 2 vCPU của `t3.small`. Không mô phỏng được cơ chế CPU credit; báo cáo ghi rõ giới hạn này.
- Không commit secret. `.env.benchmark.example` `[CREATE]` chỉ chứa tên biến với giá trị `change-me`. Thêm `.env.benchmark` vào `.gitignore` `[MODIFY]`.

#### Quy trình chạy (`scripts/benchmark/README.md` `[CREATE]`, PowerShell)

```powershell
.\gradlew.bat bootJar
docker compose -f compose.benchmark.yaml --env-file .env.benchmark down -v      # DB sạch mỗi lần (trigger V17 cấm xóa snapshot)
docker compose -f compose.benchmark.yaml --env-file .env.benchmark up -d --build postgres api web
# đợi /api/actuator/health = UP (Flyway đã chạy tới latest)
Get-Content src\test\resources\db\m05-bench\m05_fulltest_seed.sql | docker compose -f compose.benchmark.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -U masterenglish -d masterenglish
Get-Content scripts\benchmark\seed_users.sql | docker compose -f compose.benchmark.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -v bench_password="$env:BENCH_PASSWORD" -U masterenglish -d masterenglish
.\scripts\benchmark\sample-stats.ps1 -Out build\benchmark\docker-stats.csv     # chạy nền, mỗi 2 s
.\scripts\benchmark\count-roundtrips.ps1                                        # đo SQL từng request lẻ (dưới)
docker compose -f compose.benchmark.yaml --env-file .env.benchmark --profile load run --rm k6
.\scripts\benchmark\collect-metrics.ps1 -Out build\benchmark\metrics.json       # Hikari, JVM, OOMKilled
```

`scripts/benchmark/seed_users.sql`:
- tạo 25 user `bench-01@bench.invalid` … `bench-25@bench.invalid`, `status = 'ACTIVE'`, `email_verified_at = now()`, `first_name = 'Bench'`, `last_name = 'NN'`;
- `password_hash = crypt(:'bench_password', gen_salt('bf', 12))`. Cần `CREATE EXTENSION IF NOT EXISTS pgcrypto`, chỉ ở DB benchmark. Hash dạng `$2a$`, `BCryptPasswordEncoder` đọc được.
- `user_roles (user_id, 1)`.
- Không lưu mật khẩu trong repo.

#### Đếm round-trip thực tế: `count-roundtrips.ps1`

Với mỗi request lẻ, chạy bằng `curl.exe` qua web với cookie của `bench-25`:
1. Gọi `SELECT pg_stat_statements_reset()`.
2. Gửi request.
3. Đọc `SELECT sum(calls) FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat_statements%'`.

Đo **nền xác thực** bằng `GET /api/v1/users/me` rồi trừ ra. Ghi bảng:

| Request | SQL đo được | Trừ nền xác thực | Ngân sách 3.M3 | T0 ước lượng |
|---|---:|---:|---:|---:|
| `POST /attempts` Full Test | | | 15 | ≈ 25 |
| `GET /attempts/{id}` Full Test | | | 5 | 4 |
| `PUT /answers` | | | 4 | 4 |
| `POST /submit` Full Test | | | 4 | — |
| Listener (async, đo riêng sau 5 s) | | — | 3 | — |

#### Kịch bản k6: `scripts/benchmark/m05.js` `[CREATE]`

- `setup()`: mỗi VU đăng nhập bằng tài khoản riêng.
  - `GET /api/v1/auth/csrf`, rồi `POST /api/v1/auth/login` kèm header `{headerName}: {token}`.
  - Cookie jar mặc định của k6 giữ cookie theo VU. Login **không** tính vào ngưỡng (tag `phase:setup`).
- Mọi request đặt tag `name`: `catalog`, `start_full`, `get_full`, `autosave`, `submit`, `results`.
- `Idempotency-Key` sinh bằng `crypto.randomUUID()` (k6 `k6/crypto`). Thử lại do lỗi mạng giữ nguyên key.

| Scenario | Executor | Người dùng | Luồng |
|---|---|---|---|
| `burst` | `per-vu-iterations`, `vus: 10`, `iterations: 1`, `startTime: 0s` | `bench-01..10` | `catalog` → `start_full` (Full Test) → `get_full`. Cả 10 VU bắt đầu cùng lúc. |
| `steady` | `constant-vus`, `vus: 15`, `duration: 5m`, `startTime: 60s`, `gracefulStop: 60s` | `bench-11..25` | `start_full` → `get_full` → `autosave` 40 câu đầu (đáp án ngẫu nhiên có seed), nghỉ 1,0–2,0 s giữa hai câu (≈ 60 s) → `submit` (chấm cả 200 câu, 160 câu bỏ trống) → `results` → bắt đầu lại. Mỗi VU khoảng 4 vòng, tổng khoảng 60 lần `submit`, đủ mẫu cho p95. Nếu mỗi vòng trả lời đủ 200 câu thì một vòng mất khoảng 300 s, và gần như không có mẫu `submit`. |

Ngưỡng (`options.thresholds`):

```js
thresholds: {
  'http_req_duration{name:get_full}': ['p(95)<200'],
  'http_req_duration{name:catalog}': ['p(95)<200'],
  'http_req_duration{name:results}': ['p(95)<200'],
  'http_req_duration{name:submit}': ['p(95)<500'],
  'http_req_duration{name:start_full}': ['p(95)<800'],   // trần đề xuất T0; SLA chính thức chốt theo O3
  'checks{check:no_5xx}': ['rate==1'],
  'checks{check:expected_status}': ['rate==1'],
  'checks{check:gzip_full}': ['rate==1'],                // AD-3
}
```

`autosave` chỉ ghi số (p50, p95, p99), không chặn sign-off.

#### Tiêu chí sign-off

Tất cả phải đạt:
- Ngưỡng k6 xanh.
- `hikaricp.connections.timeout` (actuator `/api/actuator/metrics/hikaricp.connections.timeout`) **= 0**; ghi thêm max `hikaricp.connections.pending` và `active`.
- Đỉnh `docker stats` < `mem_limit` cho cả 3 container; `docker inspect -f '{{.State.OOMKilled}}'` = `false`.
- Heap đỉnh (`jvm.memory.used`, `area=heap`) ≤ 460 MiB (60% của 768 MiB).
- Postgres không có `FATAL` hay `out of memory` trong log.
- Số `PUT` 409 `VERSION_CONFLICT` = 0 (mỗi VU một tab).
- Nén (AD-3): mọi request của k6 gửi tường minh header `Accept-Encoding: gzip`. Check `gzip_full`: response `get_full` và `results` qua web có `Content-Encoding: gzip`, tỉ lệ đạt = 1. `count-roundtrips.ps1` ghi thêm kích thước body nén và chưa nén của `GET` Full Test vào báo cáo §2.

### M4. Kiểm thử frontend (`masterenglish-web`)

T6 §11 đã đặc tả phần lớn. T7 kiểm rằng chúng tồn tại và xanh, và thêm 4 ca:

| Yêu cầu đề | Test | Nguồn |
|---|---|---|
| R6: P6 ô trống, P7 `[1]–[4]`, không `dangerouslySetInnerHTML` | `tests/structured-content.test.mjs` (blank, insert, text-as-text) + `static-safety.test.ts` | T6 11.1, 11.2 |
| P1–P2 không render text lựa chọn trước nộp | **Mới** `tests/structured-content.test.mjs › label-only options render letters only`: render `OptionList` với `optionDisplay = LABEL_ONLY`, `body = null` → mỗi nút chỉ có đúng 1 text node là chữ cái. Fixture mang `body = "leak"` (cố ý sai hợp đồng) → vẫn không render `leak`, vì `LABEL_ONLY` bỏ qua `body`. | Mới |
| CAS A→B→C | `answer-queue › coalesces A→B→C into two PUTs` + e2e | T6 |
| 409 giới hạn lượt | `e2e/practice.spec.ts › limit dialog` | T6 |
| Phục hồi sau F5 | **Mới** `e2e/practice.spec.ts › reload restores saved answers`: chọn 3 câu (mock PUT 200) → `page.reload()`. Mock `GET` trả `savedAnswers` 3 câu đó → navigator `Đã trả lời` = 3 và đúng nút được chọn. | Mới |
| Cảnh báo khi còn việc chờ | **Mới** `e2e › beforeunload when a save is pending`: PUT treo (`route` không fulfill) → `page.reload()` gây dialog `beforeunload` (`page.on('dialog')`) | Mới |
| Smoke trên stack thật | **Mới** `e2e/practice-real.spec.ts` chạy khi `E2E_REAL_BACKEND=1 E2E_BASE_URL=http://127.0.0.1:23000`, tài khoản `bench-25`: start S01, trả lời 3 câu, F5, nộp, trang kết quả không có "Điểm" | Mới |

### M5. Security audit và ma trận DoD

#### Checklist audit (kết quả ghi vào báo cáo 03 §6, mỗi dòng có bằng chứng)

| # | Hạng mục | Cách kiểm | Bằng chứng |
|---|---|---|---|
| S-01 | IDOR | `SecurityAndOwnershipTests.idorReturns404Everywhere` | Test xanh |
| S-02 | CSRF trên mọi lệnh ghi | `csrfRequired` | Test xanh |
| S-03 | Lộ đáp án trước khi nộp | `DeepAntiLeakTests` (7 Part, 3 trạng thái) | Test xanh |
| S-04 | XSS frontend | `static-safety.test.ts`, `structured-content › renders text as text` | Test xanh |
| S-05 | SQL injection | `git grep -nE 'createNativeQuery\(.*\+'` và `git grep -nE '@Query\(.*\+ *[a-z]'` trong `src/main/java` → 0 chỗ ghép chuỗi theo input. Mọi tham số qua `:name`. | Output lệnh |
| S-06 | Idempotency key | `missingOrBadIdempotencyKey400`; key chỉ là UUID | Test xanh |
| S-07 | Media URL | TTL 5 phút (`R2PronunciationStorage.signGet` pattern); URL ký không bị log: `git grep -n "log.*signed\|log.*presign"` = 0 | Output lệnh |
| S-08 | ProblemDetail không lộ nội bộ | `problemDetailHasNoStackTrace` | Test xanh |
| S-09 | Payload event không chứa nội dung | T5 `serializedEventHasNoContent` | Test xanh |
| S-10 | Secret | `git grep -nE "(JWT_SECRET|PASSWORD|SECRET)=" -- compose.benchmark.yaml scripts/benchmark` chỉ thấy tham chiếu biến `${…}`; `.env.benchmark` nằm trong `.gitignore` | Output lệnh |
| S-11 | Giới hạn lạm dụng | Autosave và start **không** có rate limit (chỉ có trần 5 lượt và CAS). Ghi là **phát hiện mức Thấp** kèm đề xuất (không sửa ở T7). | Báo cáo |
| S-12 | Dependency đã biết lỗ hổng | Ngoài phạm vi tự động. Ghi phiên bản Boot 4.1.2, Modulith 2.1.1, Next 16.4.6, React 19.3.0 vào báo cáo. | Báo cáo |

#### Ma trận DoD T0–T6 → kiểm chứng (`docs/m05/03` §7)

| DoD | Kiểm chứng | Loại |
|---|---|---|
| T0-1…T0-5, T0-7 | Script C-01…C-06 (T0 §11) trên tài liệu | Script |
| T0-6 | Bản ghi duyệt S1–S12 trong CONTRACT "Lịch sử thay đổi" | Tay |
| T0-8, T1-1, T2-1, T5-1 (phần V1–V17) | `MigrationImmutabilityTests` + bước CI `git diff` | Tự động |
| T1-2, T3-1 | `M05UpgradeFromV13Tests` | Tự động |
| T1-3, T1-4 | Test T1 §11 trong `check` | Tự động |
| T1-5, T2-6, T3-8, T4-7, T5-11 | `.\gradlew.bat spotlessCheck check bootJar` (một lần chạy cuối) | Tự động |
| T1-6 | T1 `seedIsIdempotent`, `seedRunnerNotLoadedWithoutLocalProfile` (thêm vào T1 §11 ở rà soát chéo) | Tự động |
| T1-7, T2-7, T3-9, T4-8, T5-12 | Script C-01/C-02 + soát diff tài liệu | Script + tay |
| T1-8 | T3 §3.M8 có tham chiếu T1 §3.M1 | Tay |
| T1-9 | Kết quả Q7 production (người vận hành) | Tay |
| T2-2 | `PracticeEventsSurfaceTests.onlyPrepareSeal` (mới ở T7, 3.M1) | Tự động |
| T2-3, T2-4 | Test T2 §11 | Tự động |
| T2-5, T3-7, T5-9 | `ArchitectureTests`, `LayerArchitectureTests`, `CrossModuleRelationTests`, `TableOwnershipTests` | Tự động |
| T2-8 | Kiểm tra tay 206 trên R2 | Tay |
| T3-2 | `UnifiedEngineRegressionTests.legacyGrammarEndpointsGone` + ArchUnit không còn `practice.entity.Attempt*` | Tự động |
| T3-3 | `ErrorHandlingTests`, `ErrorCodeCompatibilityTests` | Tự động |
| T3-4, T4-1 | `RoundTripBudgetTests` | Tự động |
| T3-5, T4-4 | `DeepAntiLeakTests` | Tự động |
| T3-6 | Test concurrency T3 + `IdempotencyAndCasTests.parallelStartSameKeyCreatesOne` | Tự động |
| T4-2 | `DeepAntiLeakTests.isCorrectNullAfterAutosave` | Tự động |
| T4-3 | `SubmitConcurrencyAndListenerTests.fiveParallelSubmitsGradeOnce`, T5 `serializedEventHasNoContent` | Tự động |
| T4-5 | `PercentTests`, `legacyScoringVersion1KeepsPercent`, `M05UpgradeFromV13Tests` | Tự động |
| T4-6 | `UnifiedEngineRegressionTests.noScoreFields`, T6 `practice-result › never shows score words` | Tự động |
| T5-2 | `LearningSqlBoundaryTests` (A4) | Tự động |
| T5-3 | `replayingListenerTwiceDoesNotDoubleCount` + T5 11.2 | Tự động |
| T5-4 | T5 `outOfOrderDeliveryKeepsLatestSource` | Tự động |
| T5-5…T5-8 | Test T5 11.2, 11.4 | Tự động |
| T5-10 | `git grep -n "AttemptCreationFacade\|startAttempt(" src/main/java` = 0 (nếu O1 của T5 = A) | Script |
| T6-1…T6-9, T6-11 | Test T6 §11 + 3.M4 | Tự động |
| T6-10 | `npm run lint`, `npm test`, `tsc --noEmit`, `npm run build:pages`, `npm run build` | Tự động |
| T6-12 | e2e `360px no horizontal overflow` + soát tay 600/850/1100 px | Tự động + tay |

---

## 4. Thiết kế chi tiết

### 4.1. Bảng sở hữu (input của `TableOwnershipTests`)

| Module | Bảng có `@Entity` |
|---|---|
| `identity` | `users`, `roles`, `user_roles`, … (giữ như hiện tại; test chỉ kiểm bảng M05 và `media_assets`) |
| `content` | `media_assets`, `content_items`, `content_revisions`, … |
| `practice` | `questions`, `question_options`, `question_topics`, `question_types`, `question_groups`, `group_materials`, `question_materials`, `practice_sets`, `set_items`, `question_words` |
| `results` | `attempts`, `attempt_source_topics`, `attempt_group_snapshots`, `attempt_question_snapshots`, `attempt_snapshot_options`, `attempt_answers`, `attempt_media_refs` |
| `learning` | `mistake_records`, `lesson_progress`, `user_word_progress`, `word_reviews`, `user_saved_words` |

Bảng không có entity (ví dụ `mistake_processed_attempts`) không cần mặt trong test.

### 4.2. Hằng checksum Flyway (V1–V13, `origin/main` `4afb631`)

Giá trị dưới đây được tính trong phiên bằng CRC32 theo dòng, mô phỏng thuật toán Flyway (bỏ BOM, bỏ ký tự xuống dòng). Working tree khớp `origin/main` (`4afb631`) cho cả V1–V13 (đối chiếu lại ở lượt rà soát chéo).

**Phải xác nhận lại lần đầu** bằng `SELECT version, checksum FROM flyway_schema_history` trên DB CI trước khi chốt vào test. Nếu lệch vì thuật toán, lấy giá trị Flyway.

| Version | Checksum |
|---|---:|
| 1 | 984502526 |
| 2 | -1798516304 |
| 3 | 1011714676 |
| 4 | -544588280 |
| 5 | 1738293674 |
| 6 | 2146216278 |
| 7 | 14964820 |
| 8 | 51971993 |
| 8.1 | -1297678922 |
| 9 | 1759541553 |
| 10 | -1608114649 |
| 12 | -490787706 |
| 13 | 322550281 |

Blob git của V13 là `19f61523d4b5edc019963596b5e9b5a5e37cac45`, dùng cho bước CI.

### 4.3. Fixture Full Test 200 câu

- **Generator:** `scripts/benchmark/m05_fulltest_seed.py` `[CREATE]`, chỉ dùng thư viện chuẩn, kết quả xác định.
- **File sinh ra:** `src/test/resources/db/m05-bench/m05_fulltest_seed.sql` `[CREATE]`, được commit (O4). Dùng chung cho test (nạp bằng JDBC) và benchmark (psql).
- **Cách sinh:** mỗi câu, nhóm và học liệu nhân bản **đúng mẫu theo Part** của seed T1 (`db/seed/m05/V1__m05_local_seed.sql`):
  - cùng thứ tự bảng: `media_assets` → `content_items` → `content_revisions` → `questions`/`question_groups`/… → `practice_sets`/`set_items`;
  - cùng envelope JSON;
  - chỉ đổi UUID và chữ.
- **UUID:** `e5100000-00TT-4000-8000-NNNNNNNNNNNN` (TT = loại bảng như T1, NNNN = số thứ tự). Không trùng dải `e5000000-…` của seed T1.

| Part | Câu | Nhóm | Lựa chọn | Media |
|---|---:|---:|---:|---:|
| 1 | 6 | 0 | 24 | 6 ảnh + 6 audio |
| 2 | 25 | 0 | 75 | 25 audio |
| 3 | 39 | 13 (3 có graphic `TABLE`) | 156 | 13 audio |
| 4 | 30 | 10 (2 có graphic `IMAGE`) | 120 | 10 audio + 2 ảnh |
| 5 | 30 | 0 | 120 | 0 |
| 6 | 16 | 4 | 64 | 0 |
| 7 | 54 | 15 (10 đơn, 3 đôi, 2 ba tài liệu; 2 `CHAT`, 1 `TABLE`) | 216 | 0 |
| **Tổng** | **200** | **42** | **775** | **62** |

Hai bộ:
- `BENCH-FULL`: `practice_sets` FIXED, `part_number = NULL`, 200 `set_items`.
- `BENCH-P5`: 30 câu Part 5, `part_number = 5`.

Kiểm bởi `RoundTripBudgetTests.fixtureShape`: đếm theo Part bằng 6/25/39/30/30/16/54; start `BENCH-FULL` → `attempt_snapshot_options` = 775, `attempt_group_snapshots` = 42, `attempt_media_refs` = 62. Media trỏ object key giả; benchmark không gọi `/media`.

### 4.4. Fixture nâng cấp V13: `src/test/resources/db/m05-upgrade/v13_grammar_fixture.sql` `[CREATE]`

- Viết tay theo DDL V13, UUID `e5200000-…`.
- Có chú thích đầu file: "Chỉ hợp lệ với schema V13; chạy trước V14."
- Gồm đủ cột `NOT NULL` của V13 cho `attempts` (`request_id`, `request_hash` 64 hex, `source_type`, `status`…), `attempt_question_snapshots` (dạng V13), `attempt_snapshot_options` (`is_correct`, `body`), `attempt_answers`.

### 4.5. Lấy mẫu tài nguyên

- `scripts/benchmark/sample-stats.ps1`: vòng `docker stats --no-stream --format "{{.Name}},{{.MemUsage}},{{.CPUPerc}}"` mỗi 2 s → CSV, có cột thời gian.
- `collect-metrics.ps1`: gọi `/api/actuator/metrics/{name}` cho:
  - `hikaricp.connections.timeout`, `hikaricp.connections.active`, `hikaricp.connections.pending`;
  - `jvm.memory.used?tag=area:heap`, `jvm.gc.pause`;
  - `http.server.requests?tag=uri:/api/v1/attempts`.
- Ghi thêm `docker inspect -f '{{.State.OOMKilled}}'` cho 3 container.

### 4.6. Khuôn báo cáo `docs/m05/03-benchmark-and-signoff.md` (tạo sau khi chạy)

```
# M05 — Benchmark và sign-off

Trạng thái: ĐẠT | KHÔNG ĐẠT · Ngày chạy · Người chạy

## 1. Môi trường
Commit API / web (SHA), image, máy chủ (CPU, RAM, OS, Docker), EC2 t3.small hay compose cục bộ (O5),
giới hạn container, cờ JVM, Hikari, tham số Postgres.

## 2. Round-trip SQL
Bảng 3.M3 (đo được, trừ nền, ngân sách, T0). Kết luận so với ≈ 25.

## 3. k6
Bảng mỗi tag: count, p50, p95, p99, max, % lỗi. Ngưỡng đạt/không. Đính kèm k6-summary.json.

## 4. Tài nguyên
Đỉnh RAM mỗi container (MiB và % trần), heap đỉnh, GC pause max, Hikari timeout/pending/active max, OOMKilled.

## 5. SLA chốt (ADR-13)
start Full Test = <giá trị theo O3>, start Part lẻ, read, submit, autosave (tham khảo).

## 6. Security audit
Bảng S-01…S-12, mỗi dòng: kết quả, bằng chứng, mức (nếu là phát hiện).

## 7. Ma trận DoD T0–T6
Bảng 3.M5 với cột kết quả.

## 8. Giới hạn và rủi ro còn lại
CPU credit, Docker Desktop vs EC2, R2 chưa đo, ...

## 9. Sign-off
Danh sách tiêu chí 3.M3; chữ ký người duyệt.
```

---

## 5. Hợp đồng giao tiếp

T7 không đổi hợp đồng nào. Mọi assert dùng đúng hợp đồng T3/T4/T5/T6. Các khóa quan trọng:

| Endpoint | Assert chính |
|---|---|
| `POST /api/v1/attempts` | 201/200; 409 `OPEN_ATTEMPT_LIMIT_REACHED` {`openAttemptCount`: int, `maxOpenAttempts`: int, `activeAttemptIds`: string[]}; 409 `IDEMPOTENCY_CONFLICT`; 400 `VALIDATION_ERROR` |
| `PUT …/answers/{snapshotId}` | 200 {`snapshotId`, `selectedOptionId`: string\|null, `version`: int, `answeredAt`}; 409 `VERSION_CONFLICT` {`currentVersion`: int, `currentSelectedOptionId`: string\|null}; 409 `ATTEMPT_NOT_OPEN` {`attemptStatus`}; 400 `OPTION_NOT_IN_SNAPSHOT` |
| `POST …/submit` | 200 `AttemptSubmitResponse` (giống nhau khi gọi lại) |
| `GET …/results` | 200; 409 `ATTEMPT_NOT_SUBMITTED` {`attemptStatus`} |
| Mọi lỗi | `application/problem+json` có `type`, `title`, `status` (số), `detail`, `instance`, `code`, `traceId`, `errors` |

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn đồng thời

- Anti-leak kiểm ở 3 tầng:
  - JSON đệ quy (khóa, giá trị, văn bản);
  - SQL (`body IS NULL`, `is_correct IS NULL`);
  - frontend (`LABEL_ONLY` bỏ qua `body`).
- Đồng thời: start cùng key, CAS đa phiên bản, 5 submit song song, submit đua với autosave. Tất cả qua `ConcurrentRequests` với commit thật.
- Bảo mật stack benchmark:
  - cổng chỉ bind `127.0.0.1`;
  - `cookie-secure=false` chỉ trong mạng Docker nội bộ;
  - actuator `metrics` chỉ mở ở compose benchmark;
  - secret từ `.env.benchmark` (không commit).
- Không chạy benchmark trên DB dùng chung hay production.

---

## 7. Ranh giới Spring Modulith và quy hoạch tài nguyên

| Mục tiêu | Cách áp trong benchmark | Cách xác nhận |
|---|---|---|
| API 768 MiB, `MaxRAMPercentage=60` | `mem_limit: 768m`, `JAVA_TOOL_OPTIONS` | `docker stats`, heap ≤ 460 MiB |
| Hikari 5 | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5` | Actuator `hikaricp.connections.max = 5` (ghi vào báo cáo) |
| Postgres 384 MiB, `shared_buffers=64MB` | `mem_limit`, `-c shared_buffers=64MB` | `SHOW shared_buffers` (ghi vào báo cáo) |
| Web 384 MiB, old-space 256 | `mem_limit`, `NODE_OPTIONS` | `docker stats` |
| Listener ≤ 2 kết nối (T5) | Có sẵn trong code | `hikaricp.connections.active` max ≤ 5, pending/timeout = 0 |

Không có thay đổi ranh giới module. Các test kiến trúc ở 3.M1 chỉ **khóa** thêm.

---

## 8. Edge Cases & Failure Modes Matrix (của chính bộ kiểm chứng)

| # | Tình huống | Rủi ro cho kết quả | Biện pháp |
|---|---|---|---|
| 1 | Listener async của lớp test trước còn chạy | Lớp sau thấy dữ liệu lạ, test flaky | `@AfterAll` chờ `event_publication` sạch |
| 2 | Trần 5 lượt chặn vòng lặp 20 lần | Test fail giả | Nộp sau mỗi vòng (T7-D11) |
| 3 | `user()` của MockMvc bỏ qua JWT | Đếm SQL thiếu truy vấn xác thực | Trong test không tính; benchmark đo và trừ nền riêng |
| 4 | Trigger V17 cấm xóa snapshot | Không reset được DB benchmark | `down -v` mỗi lần chạy |
| 5 | Checksum mô phỏng khác thuật toán Flyway | Test đỏ ngay từ đầu | Xác nhận bằng `flyway_schema_history` (4.2) |
| 6 | Docker Desktop (Windows) chậm I/O hơn EC2 | SLA sai lệch | Ghi môi trường; sign-off chính thức theo O5 |
| 7 | CPU credit `t3.small` không mô phỏng | p95 trên EC2 tệ hơn khi hết credit | Ghi giới hạn; nếu chạy EC2 thì ghi số credit trước và sau |
| 8 | k6 `crypto.randomUUID` không có ở phiên bản cũ | Script lỗi | Pin `grafana/k6:1.0.0`; fallback `uuidv4` từ `k6/experimental` nếu thiếu |
| 9 | Cookie `Secure` qua HTTP | Login được nhưng các request sau 401 | `APP_SECURITY_COOKIE_SECURE=false` (T7-D12) |
| 10 | Bcrypt 12 khi 25 VU login cùng lúc | Làm nhiễu số đo | Login trong `setup()`, tag `phase:setup`, ngoài ngưỡng |
| 11 | pg_stat_statements đếm cả câu nền (Hikari, Modulith staleness) | Đếm dư | Đo khi không có tải; lặp 3 lần lấy trung vị; lọc theo `query` |
| 12 | Fixture 200 câu sai ràng buộc V-14..V-17 | Start trả 409 hoặc 500 | `fixtureShape` chạy trước; generator nhân bản mẫu T1 đã hợp lệ |
| 13 | Test nâng cấp V13 chạy Flyway hai lần (thủ công + Spring) | Xung đột lịch sử | Bước thủ công chỉ tới `target("13")`; Spring chạy phần còn lại |
| 14 | Test frontend thật cần backend | CI web không có backend | `practice-real.spec.ts` chỉ chạy khi `E2E_REAL_BACKEND=1` |

---

## 9. Kế hoạch chỉnh sửa file

**Repo `masterenglish-api`:**

| File | Nhãn |
|---|---|
| `src/test/java/com/masterenglish/api/m05/{SnapshotImmutability,DeepAntiLeak,FixedOptionOrder,AttemptLifecycleGuardrail,IdempotencyAndCas,SubmitConcurrencyAndListener,SecurityAndOwnership,UnifiedEngineRegression,RoundTripBudget,MigrationImmutability,M05UpgradeFromV13,TableOwnership,CrossModuleRelation,PracticeEventsSurface}Tests.java` | CREATE |
| `src/test/java/com/masterenglish/api/m05/JsonLeakScanner.java`, `src/test/java/com/masterenglish/api/support/M05Api.java` | CREATE |
| `src/test/java/com/masterenglish/api/ArchitectureTests.java` | MODIFY (3.M1) |
| `src/test/resources/db/m05-bench/m05_fulltest_seed.sql`, `src/test/resources/db/m05-upgrade/v13_grammar_fixture.sql` | CREATE |
| `compose.benchmark.yaml`, `.env.benchmark.example` | CREATE |
| `scripts/benchmark/{m05_fulltest_seed.py, seed_users.sql, m05.js, sample-stats.ps1, collect-metrics.ps1, count-roundtrips.ps1, README.md}` | CREATE |
| `.gitignore` | MODIFY (`.env.benchmark`) |
| `.github/workflows/ci.yml` | MODIFY (bước `git diff` V1–V13) |
| `docs/m05/03-benchmark-and-signoff.md` | CREATE **sau khi chạy** |
| `src/main/**` | DO NOT TOUCH |
| `src/main/resources/db/migration/V1..V18` | DO NOT TOUCH |
| `compose.local.yaml`, `Dockerfile`, `build.gradle` | DO NOT TOUCH |

**Repo `masterenglish-web`:**

| File | Nhãn |
|---|---|
| `tests/structured-content.test.mjs` | MODIFY (ca `label-only`) |
| `e2e/practice.spec.ts` | MODIFY (reload, beforeunload) |
| `e2e/practice-real.spec.ts` | CREATE |
| `src/**` | DO NOT TOUCH |

---

## 10. Lộ trình commit

Chỉ commit khi người dùng yêu cầu; không có dòng ghi công.

| Bước | Repo | Nội dung | Lệnh | Commit đề xuất |
|---|---|---|---|---|
| 1 | api | Test kiến trúc và migration | `.\gradlew.bat test --tests '*ArchitectureTests' --tests 'com.masterenglish.api.m05.TableOwnership*' --tests '*CrossModuleRelation*' --tests '*MigrationImmutability*'` | `test(m05): khóa ranh giới module và checksum migration đã merge` |
| 2 | api | Fixture V13 + `M05UpgradeFromV13Tests` | `--tests '*M05UpgradeFromV13Tests'` | `test(m05): kiểm thử nâng cấp từ dữ liệu V13` |
| 3 | api | `M05Api`, `JsonLeakScanner`, 7 lớp integration | `--tests 'com.masterenglish.api.m05.*'` | `test(m05): bộ nghiệm thu anti-leak, CAS, R4 và listener` |
| 4 | api | Generator + fixture 200 câu + `RoundTripBudgetTests` | `python -B scripts/benchmark/m05_fulltest_seed.py > src/test/resources/db/m05-bench/m05_fulltest_seed.sql` rồi `--tests '*RoundTripBudgetTests'` | `test(m05): fixture Full Test và ngân sách câu lệnh SQL` |
| 5 | api | Compose, script benchmark, `.gitignore`, CI | `docker compose -f compose.benchmark.yaml config` (kiểm cú pháp) | `chore(bench): stack benchmark mô phỏng t3.small` |
| 6 | web | Test frontend bổ sung | `npm test`, `npm run test:e2e -- e2e/practice.spec.ts` | `test(practice): phục hồi sau tải lại và nút chỉ hiện nhãn` |
| 7 | api | Chạy benchmark, viết `03` | Quy trình 3.M3 | `docs(m05): báo cáo benchmark và sign-off` |
| 8 | cả hai | Cổng cuối | `.\gradlew.bat spotlessCheck check bootJar`; web: `npm run lint`, `npm test`, `npm run build:pages` | — |

---

## 11. Test Matrix

Tổng hợp theo tầng. Chi tiết từng ca ở 3.M1–3.M4.

| Tầng | Lớp / file | Số ca |
|---|---|---:|
| ArchUnit / Modulith | `ArchitectureTests`, `LayerArchitectureTests`, `CrossModuleRelationTests`, `TableOwnershipTests`, `PracticeEventsSurfaceTests` | 5 mới + rule cũ |
| Migration | `MigrationImmutabilityTests`, `M05UpgradeFromV13Tests` | 2 (upgrade có 7 assert) |
| Integration | `SnapshotImmutability` (3), `DeepAntiLeak` (6), `FixedOptionOrder` (1), `AttemptLifecycleGuardrail` (5), `IdempotencyAndCas` (7), `SubmitConcurrencyAndListener` (4), `SecurityAndOwnership` (9), `UnifiedEngineRegression` (6) | 41 |
| Ngân sách SQL | `RoundTripBudgetTests` | 8 (7 ngân sách + `fixtureShape`) |
| Hiệu năng | k6 `burst` + `steady` | 6 ngưỡng + 2 ngưỡng check |
| Frontend | T6 §11 + 4 ca mới | — |
| Security | S-01…S-12 | 12 |

Mọi test integration cần Docker (`docker info`). Nếu không có Docker, ghi "chưa chạy" vào báo cáo; không được ghi "đạt".

---

## 12. Definition of Done và Open Decisions

### 12.1. DoD

| # | Tiêu chí | ☐ |
|---|---|---|
| 1 | `ArchitectureTests` (`verify()`), `LayerArchitectureTests`, `CrossModuleRelationTests`, `TableOwnershipTests` xanh | ☐ |
| 2 | `spotlessCheck` xanh; `MigrationImmutabilityTests` xanh; bước CI `git diff` V1–V13 sạch | ☐ |
| 3 | `M05UpgradeFromV13Tests` xanh (validate, backfill, đọc/chấm dữ liệu cũ) | ☐ |
| 4 | 41 ca integration 3.M2 xanh trên PostgreSQL 17 | ☐ |
| 5 | `RoundTripBudgetTests` xanh; bảng round-trip đo thật có trong `03` | ☐ |
| 6 | k6 chạy đủ `burst` + `steady`; mọi ngưỡng 3.M3 đạt; 0 Hikari timeout; 0 5xx; không OOM; RAM dưới trần | ☐ |
| 7 | SLA start Full Test được chốt trong `03` theo quy tắc O3 | ☐ |
| 8 | Test frontend (T6 + 4 ca mới) xanh; smoke thật chạy trên stack benchmark | ☐ |
| 9 | Security S-01…S-12 có kết quả và bằng chứng | ☐ |
| 10 | Ma trận DoD T0–T6 (66 tiêu chí) có cột kết quả trong `03` | ☐ |
| 11 | Không sửa `src/main/**` hay migration | ☐ |
| 12 | Không có secret trong commit (S-10) | ☐ |

### 12.2. Câu hỏi cần duyệt

| # | Câu hỏi | Đề xuất |
|---|---|---|
| O1 | Stack benchmark build web từ thư mục cạnh `../masterenglish-web` (cần hai repo cạnh nhau), hay kéo image GHCR? | **Build từ source.** Ghi SHA cả hai repo vào báo cáo; không phụ thuộc quyền GHCR. |
| O2 | Đếm SQL bằng `pg_stat_statements` + Hibernate statistics thay vì thêm p6spy? | **Đồng ý.** Không thêm dependency; đếm ở phía DB là chính xác nhất. |
| O3 | Quy tắc chốt SLA `start` Full Test: p95 đo được, làm tròn lên bội 100 ms, phải ≤ 800 ms (trần T0). Vượt 800 ms thì **không** sign-off và mở việc tối ưu. | **Đồng ý.** |
| O4 | Commit file SQL fixture 200 câu đã sinh (ước lượng 0,6–1 MB) vào `src/test/resources`? | **Đồng ý.** CI không phải chạy Python; diff xác định nhờ generator. |
| O5 | Số đo sign-off chính thức lấy từ EC2 `t3.small` thật hay compose cục bộ? | **EC2 `t3.small` nếu có**, chạy cùng compose và cùng giới hạn. Nếu không có, sign-off cục bộ kèm cảnh báo CPU credit và I/O ở báo cáo §8. |
