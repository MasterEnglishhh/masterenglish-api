# T0 — Implementation Brief: Chốt tài liệu M05, hòa giải V13/`CONTRACT.md` và khóa JSON Schema v1

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026: S1–S12 theo đề xuất ở mục 12.2. 4 tài liệu đích ở mục 4.7 đã tạo. Do S2, `EXPIRED` bị gỡ: ở 4.1.1, `attempts_c16` chỉ gồm `('IN_PROGRESS','SUBMITTED','ABANDONED')` và `attempts_c27` thành `(status = 'ABANDONED') = (abandoned_at IS NOT NULL)`. Schema thực thi cuối cùng nằm ở brief T1. **Phần thân brief này đã bị các brief sau thay; khi khác nhau, brief sau thắng** (ghi ở lượt rà soát chéo T0–T7): §4.1 (DDL) → T1; §4.2–4.4 (JSON Schema, ví dụ, ánh xạ seal) → bản hiện hành ở `02` (đã cộng delta T1 §4.8); §4.6 (lớp ghi snapshot dùng `JdbcTemplate`) → T3 (fragment + `EntityManager`); §5.1–5.2 (API, DTO `AttemptView`, "không có `/results`", `mediaUrlExpiresAt`, URL ký trực tiếp) → T3 §5.1 + T4 §5.1 + T2 O2 (redirect 5 phút); bảng mã lỗi §5.4 (`ATTEMPT_NOT_OPEN (status)`, `EXPIRED`) → T3 §3.M2 + T4 O1 (`attemptStatus`); facade §5.3 (`loadSetForSealing`) → T2 (`prepareSeal`); event §5.3 → T4 §3.M3; ước lượng §7.3 (≈ 25 round-trip) → T3 §7 (15 câu lệnh). Bản hợp nhất hiện hành nằm ở `docs/practice/CONTRACT.md`, `01` và `02`. |
| Ngày | 26/11/2026 |
| Commit nền | `4afb631` (Merge PR #15 `feat/m04-grammar`), nhánh `dev` đã fast-forward bằng `origin/main` |
| Chế độ | READ-ONLY với Java, TypeScript, YAML, `.sql`. Brief chỉ ghi Markdown. |
| Nguồn đã đọc | Mục 2. Mọi trích dẫn dạng `file:dòng` lấy từ commit nền. |

Quy ước nhãn: `[CREATE]` thực thể chưa có trong repo. `[MODIFY]` đã có và sẽ đổi ở task sau T0. `[DO NOT TOUCH]` cấm sửa. SQL trong brief là **đặc tả hợp đồng**, không phải migration. File migration do task triển khai viết sau khi brief được duyệt.

---

## 1. Metadata & Traceability

### 1.1. Định danh

| Mục | Nội dung |
|---|---|
| Mã task | T0 — Contract Freeze M05 |
| Sản phẩm của T0 | 4 tài liệu (mục 4.7). Không có code, không có migration. |
| Phụ thuộc vào | Không có. Đầu vào là SRS/SDD v5.0, báo cáo rà soát TOEIC (artifact "Rà soát M05 TOEIC"), `docs/practice/CONTRACT.md`, `docs/database/proposals/approved-baseline.sql`, code tại commit nền. |
| Chặn | Mọi task triển khai M05 sau T0 (schema V14, module `results`, projection TOEIC, API attempt, frontend). Tên và thứ tự các task đó do người duyệt định nghĩa. Brief này gọi chung là "task triển khai". |

### 1.2. In-Scope

| Mã | Nội dung chốt trong T0 | Mục |
|---|---|---|
| M05.01, M05.02 | Hợp đồng catalog `practice-sets` ở mức API và ownership | 3.R5, 5.2 |
| M05.03 | Giao thức start, sealing snapshot, idempotency | 3.R1, 3.R4, 4.4, 5.1 |
| M05.04, M05.11–M05.16 | JSON Schema học liệu theo Part 1–7 | 4.2, 4.3 |
| M05.05 | Chỉ chốt dữ liệu audio (`AUDIO`, `duration_seconds`). Điều khiển phát là việc của frontend. | 4.2 |
| M05.06, M05.07 | Autosave CAS, bỏ chọn, idempotency PUT | 3.R1, 5.1 |
| M05.08, M05.09, M05.18 | Nộp idempotent, chấm server-side dùng chung | 4.5, 5.1 |
| M05.10 | Dữ liệu xem lại sau nộp (`private_snapshot`, `answer_key_snapshot`) | 4.2, 5.1 |
| M05.17 | `category` VOCABULARY/GRAMMAR/TOEIC, một grader | 4.1 |
| M03.12–M03.16, M04.10–M04.12 | Dùng chung engine. T0 chỉ bảo đảm schema v1 không phá luồng GRAMMAR hiện có. | 3.R1, 4.1 |
| ADR-07 → ADR-13 | Hiện thực hóa thành quyết định cụ thể | 3, 4 |

### 1.3. Explicitly Out-of-Scope

- Mọi thay đổi Java, `.sql`, TypeScript, YAML. Viết `V14__*.sql` thuộc task triển khai.
- Hàng rào YAGNI (DEFER P1): giao diện Exam Mode (khóa audio, ép giờ, tự nộp), tốc độ audio, gạch đáp án, highlight, cắm cờ, transcript karaoke, dịch song ngữ, tô sáng dẫn chứng P7, lưu từ từ bài đọc, quy đổi 990/IRT, luyện lại câu sai (D13), cache audio offline, scheduler tự nộp hoặc dọn bài.
- Thiết kế chi tiết luồng nhập liệu CMS cho TOEIC (form soạn đề). T0 chỉ chốt định dạng dữ liệu mà CMS phải tạo ra.
- `mistake_records` và `attempt_source_mistakes`: chỉ chốt payload event (mục 5.3). Bảng và vòng đời vẫn hoãn theo D13.
- Thống kê tích lũy M06 (lịch sử, thống kê theo Part/Topic theo thời gian): chỉ giữ chỗ URI (mục 5.2).

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc trực tiếp

| Nhóm | File |
|---|---|
| Migration | Danh sách `src/main/resources/db/migration/` (V1–V10, V12, V13, V8_1; **không có V11**). Đọc toàn văn `V13__create_practice_engine.sql`. Đọc khối `media_assets`, `revision_media_refs` trong `V6__…sql`, trigger trong `V7__…sql`. |
| Hợp đồng | `docs/practice/CONTRACT.md` (toàn văn, 788 dòng), `docs/ARCHITECTURE.md` (mục "Giao tiếp giữa module"), `docs/database/proposals/approved-baseline.sql` dòng 549–846. `docs/m05/` **chưa tồn tại**. |
| Java | `modules/practice/package-info.java`, `entity/Attempt.java`, `entity/AttemptSnapshotOption.java`, `service/impl/PracticeServiceImpl.java`, `service/impl/QuestionSnapshotValidator.java`, `service/impl/QuestionProjectionListener.java`, `controller/AttemptController.java`, `dto/request/*.java`; `common/error/{ErrorCode,BusinessException,GlobalExceptionHandler}.java`; `common/events/PracticeEvents.java`, `ContentEvents.java`; `config/SecurityConfiguration.java` dòng 128–134; `src/test/.../ArchitectureTests.java`. |
| Cấu hình | `build.gradle` (Boot 4.1.2, Modulith BOM 2.1.1, Java 21), `application.properties`, `application-local.properties`. |
| Frontend | `../masterenglish-web` nhánh `dev` tại `616e217`: tìm `/attempts`, `SUBMITTED`, `COMPLETED`, `IN_PROGRESS` trong `src/**/*.ts(x)`. |

Không có module `results` hay `media` trong repo. `ls modules/` cho ra: `admin, content, grammar, identity, learning, practice, vocabulary`.

### 2.2. Bảng drift

| ID | Nguồn A nói | Repo thật (bằng chứng) | Xung đột | Giải quyết |
|---|---|---|---|---|
| DR-01 | `CONTRACT.md:508`: V12/V13 "chưa có trên `origin/main`". `CONTRACT.md:25` (D8): không tạo V14. | `git log origin/main -- …/V13__create_practice_engine.sql` → `9ea157d`, vào `main` qua merge `4afb631` (PR #15). | AGENTS.md: "Giữ nguyên migration đã merge vào `main`". D8 không còn đúng. | **R7**: V13 bất biến, thay đổi schema đi qua `V14` kiểu expand. D8 được thay bằng D8 v2. |
| DR-02 | Preamble và SRS 3.2 nhắc `modules.results`, `modules.media`. | Không có hai module này. Attempt nằm trong `modules.practice` (`entity/Attempt.java`). Media thuộc `content` (`ARCHITECTURE.md:49-50`, `V6:24`). | Ownership đích khác hiện trạng. | Đích: `results` sở hữu `attempts*` (CONTRACT §1). Media vẫn thuộc `content`. Không tạo module `media`. Tách `results` là việc của task triển khai. |
| DR-03 | Preamble: giao tiếp đồng bộ qua `@NamedInterface`. | `@NamedInterface` chỉ có ở `common/*` (9 package). Gọi đồng bộ liên module đi qua facade trong `common/events` + `EventReply` (`ARCHITECTURE.md:57-66`, `PracticeEvents.java`). | Hai cách nói về cùng một cơ chế. | Giữ pattern của repo: hợp đồng đồng bộ là facade trong `common::events` (một `@NamedInterface`). Không tạo `@NamedInterface` mới trong module nghiệp vụ. |
| DR-04 | ADR-12 dùng `event_publication`. | `V1` tạo `event_publication`. Không có `@ApplicationModuleListener` nào trong `src/main/java`. | Đây sẽ là listener async đầu tiên. Pool thread mặc định có thể tranh 5 kết nối Hikari. | Mục 7.4: giới hạn executor khi listener đầu tiên ra đời. |
| DR-05 | SRS v5.0 SEQ-04: `status = 'COMPLETED'`. | V13:79,82 `'SUBMITTED'`; `Attempt.java:21`; `AttemptRepository.java:76`; `PracticeIntegrationTests.java:201`; approved-baseline:732; FE: 0 lần. `COMPLETED` đã mang nghĩa khác ở `lesson_progress` (V12 + 7 file Java/test). | Tên trạng thái lệch. | **R2**: giữ `SUBMITTED`. |
| DR-06 | ADR-10: thêm cột mode, không đụng `practice_mode`. | V13:13,22 `questions.practice_mode IN ('STANDARD','FILL_OPTION')`. DATA_DICTIONARY:1014 định nghĩa đây là **cách trình bày câu** (5 giá trị ở baseline). | Trùng từ "mode". | **R3**: `attempts.attempt_mode`. |
| DR-07 | Full Test 200 câu. CONTRACT/DD: 1–100. | V13:80 `attempts_c7 total_count BETWEEN 1 AND 50`. V13:108 `attempt_question_snapshots_c3 position BETWEEN 1 AND 50`. `PracticeServiceImpl.java:49 MAX_QUESTION_COUNT = 50`. | Không chứa nổi Full Test. | V14 nới thành 1–200 (4.1). Giới hạn theo nguồn đặt ở tầng service (Grammar ≤ 100). |
| DR-08 | ADR-11 và DD (`approved-baseline:739` `score = correct_count`). | V13:84 `score BETWEEN 0 AND 100`, `PracticeServiceImpl.java:158` ghi phần trăm. | Dữ liệu cũ là %, đích là số câu đúng. | `scoring_version` (4.1.1). |
| DR-09 | DD: snapshot có `id`, JSONB. | V13:97-109: PK `(attempt_id, position)`, `prompt`/`explanation` TEXT, `topic_id NOT NULL → grammar_topics`. | Câu TOEIC không có chủ điểm ngữ pháp nên không ghi được. Không có `snapshotId` cho URL. | V14 thêm cột (4.1.2). |
| DR-10 | DD (`approved-baseline:806-814`): PK `(snapshot_id, option_id)`, `option_id` lấy từ envelope, không có `is_correct`. | V13:113-125: `id UUID PRIMARY KEY` sinh ngẫu nhiên cho từng attempt (`PracticeServiceImpl.java:241-242`), `is_correct BOOLEAN NOT NULL`. | Hai mô hình định danh khác nhau. | **R1**: giữ `id` theo attempt, thêm `source_option_id`. |
| DR-11 | DD: `attempt_answers.id`, `snapshot_id UNIQUE`, `version`, chọn nullable. | V13:127-137: PK `(attempt_id, question_position)`, `selected_option_id NOT NULL`, không có `version`. | Không CAS được, không bỏ chọn được. | V14 thêm cột (4.1.4). |
| DR-12 | CONTRACT §3.1: `Idempotency-Key` là header. | `StartAttemptRequest.java`: `requestId` trong body. CORS đã cho phép header (`SecurityConfiguration.java:134`). | Transport lệch. | Header (5.1). |
| DR-13 | CONTRACT §9 (`:479`): autosave **không** dùng `Idempotency-Key`. | Chưa có autosave CAS. | Mâu thuẫn với ADR-09. | ADR-09 thắng (người dùng đã khóa). Cách làm rẻ nhất: cột `last_request_id` (3.R1, 5.1). |
| DR-14 | CONTRACT §4.3: `STALE_ANSWER_VERSION`. | `ErrorCode.java:7-22` chưa có mã này. | Tên lệch với ADR-09 `VERSION_CONFLICT`. | Dùng `VERSION_CONFLICT`. Xóa `STALE_ANSWER_VERSION` khỏi CONTRACT. |
| DR-15 | ADR-09: 409 kèm `currentSelectedOptionId`. CONTRACT §4.3: `ATTEMPT_NOT_OPEN` kèm `status`. | `BusinessException.java:55-57`: `details` chỉ nhận `Integer`/`Long` ≥ 0. | Không gửi được UUID, null hay enum. | **R8**: allowlist có kiểu. |
| DR-16 | DD + CONTRACT §6.2: cột `explanation_snapshot` riêng. | Chưa có. | Spec T0 mục C.3 đặt `explanation` trong `answer_key_snapshot`. | Theo spec T0. Không tạo `explanation_snapshot`. Ghi lệch DD (3.R1). |
| DR-17 | DD: `group_materials.material_type IN ('TEXT','IMAGE','AUDIO')`, `usage_role`, `body TEXT ≤ 50000`. | Chưa có bảng. | ADR-08 cần 6 loại và `visibility`. | 4.1.6: 6 loại, `body JSONB`, `visibility` là cột GENERATED từ `usage_role`, không có hai nguồn sự thật. |
| DR-18 | DD: `attempt_group_snapshots.public_snapshot` / `private_snapshot`. | Chưa có. | Spec T0 gọi là `group_snapshot`. | Giữ tên cột DD. "group snapshot" là cặp hai cột (3.R1). |
| DR-19 | ADR-10: thêm `EXPIRED`. DD: chỉ `IN_PROGRESS`, `SUBMITTED`, `ABANDONED`. CONTRACT §5.5: bài có giờ quá hạn thì **tự nộp** `TIME_LIMIT`. | — | `EXPIRED` không có ngữ nghĩa riêng. | **R4**: thêm vào CHECK, cấm mọi đường chuyển ở MVP. Có câu hỏi duyệt S2. |
| DR-20 | DD: `practice_sets_c5` bắt buộc `part_number` 1–7 khi TOEIC. | Chưa có bảng. | Không tạo được bộ Full Test (7 Part). | `part_number NULL` = Full Test hoặc bộ nhiều Part (4.1.8). Câu hỏi duyệt S9. |
| DR-21 | SRS 4.4: `mistake_records` thuộc nhóm "Exam Results". CONTRACT §1: `results`. ADR-12 và sơ đồ SRS 3.1: `learning`. | Chưa có bảng. | Ba nguồn, hai ý. | Chỉ chốt event. Owner bảng là câu hỏi duyệt S3. |
| DR-22 | SRS RTM: M05 → `/api/v1/practice-sets`, M06 → `/api/v1/attempts`. | Code: `POST /api/v1/grammar/topics/{topicId}/attempts`, `POST /api/v1/grammar/attempts/mixed`, `GET /api/v1/grammar/statistics/topics`, `GET /api/v1/attempts/{id}`, `PUT /api/v1/attempts/{id}/answers/{position}`, `POST /api/v1/attempts/{id}/submit`. FE: 0 lời gọi (feature `toeic` dùng `../data/sample`). | Ownership chức năng khác ownership module. | **R5**. Endpoint cũ có thể gỡ vì không có consumer. |
| DR-23 | Preamble: Hikari 5/1, `mem_limit`. | `application*.properties` không có `hikari`. Repo Infra không có ở workspace. | Không kiểm chứng được trong repo. | Dùng số liệu preamble làm giả định ở mục 7. Ghi rõ là chưa kiểm chứng. |
| DR-24 | ADR-13: JDBC batch. | Không có `hibernate.jdbc.batch_size`, `order_inserts`, `reWriteBatchedInserts`. | Mặc định là từng INSERT. | 7.3: `JdbcTemplate.batchUpdate` trong repository nội bộ `results`. |
| DR-25 | ADR-13: audio ≤ 20 MiB. | `V6` `media_assets_c13`: AUDIO ≤ 20971520 byte; `c14`: `audio/mpeg`, `audio/mp4`. | Không xung đột. | Giữ nguyên. |
| DR-26 | Cần `QUESTION_GROUP`, `PRACTICE_SET`. | `V6:79 content_items_c2` đã cho phép cả hai. | Không xung đột. | — |
| DR-27 | TOEIC qua validator. | `QuestionSnapshotValidator.java:76-84` bắt buộc `category = GRAMMAR`, `part_number`/`group` null, `materials` rỗng. HTML bị chặn bằng regex `<\s*/?\s*[a-zA-Z!]` (`:45`). | Validator phải mở rộng. | Task triển khai. Schema v1 dùng lại đúng regex này cho `PlainText` (4.2). |
| DR-28 | — | `PracticeEvents.java` javadoc: facade khai báo ở A6, chưa có handler. Gọi lúc này ra 500. | — | Mở rộng facade ở 5.3. |
| DR-29 | SRS SEQ-04: CAS "version >= client_version". | — | Sai logic, request cũ ghi đè được. | ADR-09: `version = expectedVersion`. |
| DR-30 | SRS NFR-SEC-04: chỉ ẩn transcript P1, P2. | — | Transcript P3, P4 cũng chứa đáp án. | ADR-08: lọc theo `visibility`, không theo số Part. |
| DR-31 | SRS 2.3.1: P6 ví dụ `blank_key = [131]`. DD `questions.blank_key VARCHAR(80)`, bắt buộc khi P6 (`approved-baseline:593-594`). | — | Dùng số câu hiển thị làm khóa thì vỡ khi đề động. | **R6**: khóa ổn định `b1..b4`, `display_number` tính lúc seal. |
| DR-32 | CONTRACT §6.3: trigger `me_guard_attempt*`. | Không có trigger nào trên bảng attempt. Có tiền lệ `me_history_append_only()` ở `V7:2-11`. | Bất biến chỉ nằm ở service. | V14 gắn `me_history_append_only()` cho 3 bảng snapshot (4.1.9). |
| DR-33 | CONTRACT §5.2: khóa user → attempt → slot cả khi autosave. | — | Khóa user mỗi lần autosave là một round-trip event thừa. | 6.4: autosave và submit chỉ khóa attempt → slot. Lập luận deadlock ở đó. |
| DR-34 | `ProblemDetail` cần `instance`. | `GlobalExceptionHandler.java` `problem(...)`: `setInstance(URI.create(request.getRequestURI()))`, `code`, `traceId`, `errors`. | Không xung đột. | — |

---

## 3. Mandatory Design Resolutions

### R1. Một nguồn sự thật cho snapshot

**Quyết định.** Mỗi thông tin chỉ có một chỗ lưu.

| Thông tin | Nơi lưu duy nhất | Lý do |
|---|---|---|
| Định danh lựa chọn trong lượt làm | `attempt_snapshot_options.id` (UUID theo attempt, PK sẵn có ở V13) | FK sẵn có `attempt_answers (attempt_id, question_position, selected_option_id) → attempt_snapshot_options (attempt_id, question_position, id)` (V13:135-136) đã bảo đảm lựa chọn thuộc đúng câu. |
| Thứ tự và nhãn lựa chọn | `attempt_snapshot_options.position` (1–6) và `label` `[CREATE]` (`'A'..'F'`, CHECK `label = chr(64 + position)`) | Nhãn là dữ liệu hiển thị nhưng được ràng buộc với `position`, không lệch được. |
| Nội dung lựa chọn được phép hiện trước khi nộp | `attempt_snapshot_options.body` (`NULL` với P1, P2) | P1, P2 chỉ nói, không in. Bảng quan hệ chỉ chứa thứ an toàn để trả ra trước khi nộp. |
| ID lựa chọn gốc trong revision | `attempt_snapshot_options.source_option_id` `[CREATE]` | Truy vết và đối chiếu, không bao giờ trả ra API. |
| Đề bài, Part, dạng câu, tham chiếu, học liệu cấp câu (ảnh P1, audio P1/P2) | `attempt_question_snapshots.question_snapshot` JSONB | **Không chứa mảng `options`.** |
| Chủ điểm được gán (Grammar) | Cột `topic_id` sẵn có (FK `grammar_topics`) | Có FK. `question_snapshot.meta` chỉ giữ `topic_ids` đầy đủ. Bỏ `attributedTopicId` khỏi JSON để không nhân đôi (lệch CONTRACT §6.2). |
| Số câu hiển thị | Cột `display_number` `[CREATE]` | Không lặp trong JSON. |
| Đáp án, lời giải, dẫn chứng, transcript lựa chọn P1/P2, transcript câu hỏi P2 | `attempt_question_snapshots.answer_key_snapshot` JSONB | Chỉ đọc sau khi nộp. |
| Học liệu nhóm trước khi nộp | `attempt_group_snapshots.public_snapshot` | — |
| Học liệu nhóm sau khi nộp (transcript, lời giải đoạn) | `attempt_group_snapshots.private_snapshot` | — |
| `is_correct` của từng lựa chọn | **Không lưu cho bản v1.** Cột V13 `attempt_snapshot_options.is_correct` chỉ còn cho dữ liệu cũ. | Đáp án chỉ có trong `answer_key_snapshot`. |

`answer_key_snapshot` lưu cả `correct_option_snapshot_id` (để chấm bằng phép so UUID) và `correct_option_ordinal` (để đọc). Bất biến kiểm tra lúc seal, trong service, trước khi INSERT:

```
EXISTS option o: o.attempt_id = s.attempt_id AND o.question_position = s.position
              AND o.id = key.correct_option_snapshot_id AND o.position = key.correct_option_ordinal
```

Vi phạm thì ném `IllegalStateException` → rollback toàn bộ start → 500 `INTERNAL_ERROR`. Đây là lỗi dữ liệu nguồn, không phải lỗi người dùng. Validator lúc publish đã chặn trường hợp này.

**Trade-off.**
- Lệch DD ở hai điểm: `attempt_snapshot_options` giữ `id` theo attempt thay vì PK `(snapshot_id, option_id)`, và không có `explanation_snapshot`.
  - Lợi: không phải đổi PK của bảng đã có trên `main`. ID ngẫu nhiên theo attempt không cho phép ghép ID lựa chọn giữa hai lượt làm. Bớt một cột riêng tư.
  - Giá: chấm cần join với JSON (`->>`). Chi phí này không đáng kể với 200 dòng.
- `group_snapshot` trong spec T0 được hiện thực bằng hai cột theo DD. Lợi: tách vật lý dữ liệu trước nộp và sau nộp, nên mapper trước nộp không có cách đọc nhầm transcript.

### R2. Tên trạng thái đã nộp: giữ `SUBMITTED`

Số lần xuất hiện, đếm bằng `grep` tại commit nền:

| Vị trí | `SUBMITTED` | `COMPLETED` cho attempt |
|---|---:|---:|
| Java main (`Attempt.java` ×3, `AttemptResponse.java` ×1 javadoc, `AttemptRepository.java` ×1) | 5 | 0 |
| Test (`PracticeIntegrationTests.java:201`) | 1 | 0 |
| Migration (`V13:79,82`) | 2 | 0 |
| Docs (DD, SCHEMA_CONTRACTS, CONTRACT, baseline, công cụ SQL) | 36 | 0 |
| Frontend (`masterenglish-web@616e217`) | 0 | 0 |
| SRS v5.0 | 0 | 1 (SEQ-04) |

Đổi sang `COMPLETED` phải sửa CHECK đã lên `main`, sửa 4 file Java/test và 36 chỗ trong docs. Ngoài ra `COMPLETED` đã được `lesson_progress` dùng với nghĩa "hoàn thành bài học" (V12). **Chọn `SUBMITTED`**. Ghi SRS Addendum A-01 trong `01-adrs-07-to-13.md`: "SEQ-04 `COMPLETED` đọc là `SUBMITTED`".

### R3. Cột chế độ lượt làm: `attempt_mode`

- `questions.practice_mode` (V13:13,22) là **cách trình bày câu hỏi** (`STANDARD`, `FILL_OPTION`; DD có thêm `TRANSLATE_EN_VI`, `TRANSLATE_VI_EN`, `LISTEN_AND_CHOOSE`). Đây là thuộc tính của câu, không phải của lượt làm.
- Cột mới: `attempts.attempt_mode VARCHAR(16) NOT NULL DEFAULT 'PRACTICE'`, `CHECK (attempt_mode IN ('PRACTICE','EXAM'))`.
- MVP: API từ chối `mode = "EXAM"` bằng 400 `VALIDATION_ERROR` (`errors[0].field = "mode"`). DB vẫn cho phép, để mở Exam Mode không cần migration.
- `CHECK (attempt_mode <> 'EXAM' OR deadline_at IS NOT NULL)`. Bài EXAM luôn có giờ. Bài PRACTICE có thể có giờ về sau (ví dụ Study4 cho đặt giờ khi luyện Part). Không cấm điều này.

### R4. Phiên đồng thời và dọn dẹp

| Quy tắc | Giá trị | Nguồn |
|---|---|---|
| Số lượt `IN_PROGRESS` tối đa mỗi người học | **5, tính chung mọi category và nguồn** | CONTRACT D5, baseline `practice.max_open` |
| Một người học mở nhiều lượt cùng một practice set | Được. Không có ràng buộc theo set. Retry dùng lại `Idempotency-Key` nên không sinh bài trùng. | YAGNI |
| Chuyển sang `ABANDONED` | Chỉ lazy, ở bước 4 của start (CONTRACT §5.1): bài `deadline_at IS NULL` và `last_active_at <= now() - 7 ngày` | D14 |
| Bài có giờ quá hạn | Tự nộp: `status = SUBMITTED`, `submit_reason = 'TIME_LIMIT'`, `effective_finished_at = deadline_at` | CONTRACT §5.5, DD c19 |
| `EXPIRED` | Có trong CHECK. **Không có đường chuyển nào ở MVP.** Code không được ghi giá trị này. | ADR-10 (đã khóa). Đề xuất gỡ ở câu hỏi duyệt S2. |
| `POST /api/v1/attempts/{id}/abandon` | **Không làm ở MVP.** Người học luôn có thể nộp để giải phóng chỗ. `OPEN_ATTEMPT_LIMIT_REACHED` có `detail` hướng dẫn nộp bài đang mở. | YAGNI |
| Scheduler | Hoãn (CONTRACT §5.5). MVP không có bài có giờ, nên không có bài nào cần tự nộp. | D12 |

Hệ quả đã chấp nhận: bài bỏ dở vẫn hiện `IN_PROGRESS` đến lần start kế tiếp. Frontend hiển thị dựa trên `lastActiveAt`.

### R5. Ranh giới API giữa M05 và M06

Mã chức năng và module Spring là hai trục khác nhau. Module `results` (đích) phục vụ cả M05 lẫn M06.

| URI | Mã chức năng | Module Spring | Trạng thái |
|---|---|---|---|
| `GET /api/v1/practice-sets` | M05.01, M05.02 | `practice` | `[CREATE]` hợp đồng v1 |
| `GET /api/v1/practice-sets/{practiceSetId}` | M05.02 | `practice` | `[CREATE]` hợp đồng v1 |
| `GET /api/v1/grammar/topics/{topicId}/exercises` | M04 | `practice` | Giữ nguyên |
| `POST /api/v1/attempts` | M05.03, M05.17 | `results` | `[CREATE]` (thay 2 endpoint grammar cũ) |
| `GET /api/v1/attempts/{attemptId}` | M05.04, M05.07, M05.10 | `results` | `[MODIFY]` hình dạng v1 |
| `PUT /api/v1/attempts/{attemptId}/answers/{snapshotId}` | M05.06 | `results` | `[MODIFY]` (`{position}` → `{snapshotId}`) |
| `POST /api/v1/attempts/{attemptId}/submit` | M05.08, M05.09, M05.18 | `results` | `[MODIFY]` |
| `GET /api/v1/attempts?status=SUBMITTED&category=…` | M06 (lịch sử) | `results` | Giữ chỗ URI, ngoài T0 |
| `GET /api/v1/attempts/statistics/topics`, `…/statistics/toeic-parts` | M06 | `results` | Giữ chỗ URI, ngoài T0 |
| `GET /api/v1/mistakes` | M06 (sổ tay) | theo S3 | Hoãn D13 |
| `POST /api/v1/grammar/topics/{topicId}/attempts`, `POST /api/v1/grammar/attempts/mixed`, `GET /api/v1/grammar/statistics/topics` | — | `practice` | **DEPRECATED**. Gỡ trong task phát hành API v1. Không có consumer (DR-22). |

Không có endpoint `/results` riêng. `GET /attempts/{id}` trả hình dạng theo `status` (CONTRACT §3.4), nên một lượt làm chỉ có một tài nguyên.

### R6. Neo trong văn bản: node có cấu trúc, không dùng chuỗi template

**Quyết định.** Văn bản `RICH_TEXT` là cây JSON gồm block và inline. Ô trống và vị trí chèn là **inline node**, không phải chuỗi `{{blank:b1}}` nằm trong text.

| Mục đích | Node | Hiển thị |
|---|---|---|
| Ô trống Part 6 | `{"type":"blank","blank_key":"b1"}` | Ô có số `display_number` của câu có `blank_key = "b1"` trong cùng nhóm, ví dụ `-- 131 --` |
| Vị trí chèn câu Part 7 | `{"type":"insert","position":1}` | `— [1] —` |
| Neo dẫn chứng trong chat | `messages[].anchor` | — |

- `blank_key`: regex `^b[1-9]$`, duy nhất trong nhóm, mỗi khóa xuất hiện đúng một lần trong toàn bộ `materials` của nhóm. P6 có đúng 4 khóa `b1..b4` (CHECK nhóm 4 câu, DD c7).
- `insert.position`: 1–4. Nếu một tài liệu có node `insert` thì phải có đủ 1, 2, 3, 4, mỗi số một lần.
- `display_number` = `position` (ordinal) của câu trong lượt làm. Với Full Test theo thứ tự P1→P7, số này trùng số câu ETS (P6 bắt đầu 131).
- Chuỗi `{{blank:b1}}` và `{{insert:1}}` chỉ là **cú pháp nhập liệu** cho công cụ import hoặc CMS sau này. Validator đổi chuỗi đó thành node trước khi lưu. DB không bao giờ chứa chuỗi template.
- Frontend render bằng cách map từng node sang React element (`<span>`, `<strong>`, `<em>`, `<br/>`, component `BlankSlot`, `InsertMarker`). **Cấm `dangerouslySetInnerHTML`.** Text là plain text, đã bị validator chặn HTML (regex của DR-27).

**Trade-off.** JSON dài hơn chuỗi template. Đổi lại không cần parser, không có lỗi escape, validator kiểm tra được khóa ở tầng schema.

### R7. Flyway sau khi V13 lên `main` (phát sinh từ DR-01)

- **D8 v2:** `V13` `[DO NOT TOUCH]`. Mọi thay đổi schema M05 đi vào `V14__practice_results_expand.sql` (tên đề xuất), viết ở task triển khai, theo kiểu expand: `ADD COLUMN` nullable hoặc có default; `DROP NOT NULL`; thay CHECK bằng `DROP CONSTRAINT` + `ADD CONSTRAINT` trong cùng file; `CREATE TABLE` mới. Không xóa cột.
- Truy vấn Q1–Q8 (CONTRACT §11.3) không còn chặn việc chọn nhánh, vì rule "đã merge vào main" đủ để chọn nhánh B. Q7 (số dòng thật) vẫn nên chạy trước khi deploy V14 để ước lượng thời gian backfill. Câu hỏi duyệt S12.
- Thiếu V11 là khoảng trống lịch sử. `spring.flyway.out-of-order=true` (`application.properties:70`). Không xử lý.

### R8. Metadata lỗi có kiểu (phát sinh từ DR-15)

`ErrorCode` khai báo allowlist kèm kiểu. `BusinessException.copyDetails` kiểm tra theo kiểu:

| Kiểu | Giá trị hợp lệ | JSON |
|---|---|---|
| `COUNT` | `Integer` hoặc `Long` ≥ 0 | số |
| `UUID_OR_NULL` | `java.util.UUID` hoặc `null` | chuỗi UUID hoặc `null` |
| `ENUM` | `String` khớp `^[A-Z][A-Z_]{0,31}$` | chuỗi |

Tương thích ngược: hai mã hiện có (`NO_ELIGIBLE_QUESTIONS`, `INSUFFICIENT_QUESTIONS`) chuyển sang `COUNT`, body lỗi không đổi. Bảng mã cuối cùng ở 5.4.

---

## 4. Thiết kế chi tiết

### 4.1. Schema đích (đặc tả cho V14, không phải file migration)

#### 4.1.1. `attempts` `[MODIFY]`

```sql
ALTER TABLE attempts
    ADD COLUMN attempt_mode          VARCHAR(16) NOT NULL DEFAULT 'PRACTICE',
    ADD COLUMN source_set_id         UUID,
    ADD COLUMN source_revision_id    UUID,
    ADD COLUMN source_config         JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN scoring_version       SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN answered_count        INTEGER,
    ADD COLUMN listening_correct     INTEGER,
    ADD COLUMN reading_correct       INTEGER,
    ADD COLUMN snapshot_sealed_at    TIMESTAMPTZ,
    ADD COLUMN last_active_at        TIMESTAMPTZ,
    ADD COLUMN deadline_at           TIMESTAMPTZ,
    ADD COLUMN time_limit_seconds    INTEGER,
    ADD COLUMN submit_reason         VARCHAR(16),
    ADD COLUMN effective_finished_at TIMESTAMPTZ,
    ADD COLUMN abandoned_at          TIMESTAMPTZ,
    ADD COLUMN duration_seconds      INTEGER;

UPDATE attempts SET last_active_at = COALESCE(submitted_at, updated_at);  -- backfill dữ liệu V13
ALTER TABLE attempts ALTER COLUMN last_active_at SET NOT NULL,
                     ALTER COLUMN last_active_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE attempts
    DROP CONSTRAINT attempts_c3,   -- category = 'GRAMMAR'
    DROP CONSTRAINT attempts_c4,   -- source_type
    DROP CONSTRAINT attempts_c6,   -- status
    DROP CONSTRAINT attempts_c7,   -- total_count 1..50
    DROP CONSTRAINT attempts_c10;  -- score 0..100

ALTER TABLE attempts
    ADD CONSTRAINT attempts_c13 CHECK (category IN ('GRAMMAR','TOEIC')),
    ADD CONSTRAINT attempts_c14 CHECK (source_type IN ('GRAMMAR_TOPIC','MIXED_GRAMMAR','PRACTICE_SET')),
    ADD CONSTRAINT attempts_c15 CHECK ((source_type = 'PRACTICE_SET') = (source_set_id IS NOT NULL)),
    ADD CONSTRAINT attempts_c16 CHECK (status IN ('IN_PROGRESS','SUBMITTED','ABANDONED','EXPIRED')),
    ADD CONSTRAINT attempts_c17 CHECK (total_count BETWEEN 1 AND 200),
    ADD CONSTRAINT attempts_c18 CHECK (attempt_mode IN ('PRACTICE','EXAM')),
    ADD CONSTRAINT attempts_c19 CHECK (attempt_mode <> 'EXAM' OR deadline_at IS NOT NULL),
    ADD CONSTRAINT attempts_c20 CHECK ((deadline_at IS NULL) = (time_limit_seconds IS NULL)),
    ADD CONSTRAINT attempts_c21 CHECK (time_limit_seconds IS NULL
        OR (time_limit_seconds > 0 AND deadline_at = started_at + make_interval(secs => time_limit_seconds))),
    ADD CONSTRAINT attempts_c22 CHECK (scoring_version IN (1, 2)),
    ADD CONSTRAINT attempts_c23 CHECK (
        (scoring_version = 1 AND (score IS NULL OR score BETWEEN 0 AND 100))
        OR (scoring_version = 2 AND (score IS NULL OR score = correct_count))),
    ADD CONSTRAINT attempts_c24 CHECK (submit_reason IS NULL OR submit_reason IN ('MANUAL','TIME_LIMIT')),
    ADD CONSTRAINT attempts_c25 CHECK (submit_reason IS DISTINCT FROM 'TIME_LIMIT'
        OR (deadline_at IS NOT NULL AND effective_finished_at = deadline_at)),
    ADD CONSTRAINT attempts_c26 CHECK (scoring_version = 1 OR status <> 'SUBMITTED' OR (
        submit_reason IS NOT NULL AND answered_count IS NOT NULL AND effective_finished_at IS NOT NULL
        AND duration_seconds IS NOT NULL AND snapshot_sealed_at IS NOT NULL)),
    ADD CONSTRAINT attempts_c27 CHECK ((status IN ('ABANDONED','EXPIRED')) = (abandoned_at IS NOT NULL)),
    ADD CONSTRAINT attempts_c28 CHECK (status <> 'IN_PROGRESS'
        OR num_nonnulls(submit_reason, answered_count, effective_finished_at, duration_seconds,
                        listening_correct, reading_correct, abandoned_at) = 0),
    ADD CONSTRAINT attempts_c29 CHECK (category = 'TOEIC' OR (listening_correct IS NULL AND reading_correct IS NULL)),
    ADD CONSTRAINT attempts_c30 CHECK (category <> 'TOEIC' OR status <> 'SUBMITTED' OR (
        listening_correct IS NOT NULL AND reading_correct IS NOT NULL
        AND listening_correct + reading_correct = correct_count)),
    ADD CONSTRAINT attempts_c31 CHECK (answered_count IS NULL OR answered_count BETWEEN 0 AND total_count),
    ADD CONSTRAINT attempts_c32 CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    ADD CONSTRAINT attempts_c33 CHECK (jsonb_typeof(source_config) = 'object'
        AND octet_length(source_config::text) <= 16384);

CREATE INDEX ix_attempts_user_open ON attempts (user_id, last_active_at) WHERE status = 'IN_PROGRESS';
```

- Giữ nguyên `attempts_c1`, `c2`, `c5`, `c8`, `c9`, `c11`, `c12` của V13. `c8` vẫn đúng với ABANDONED và EXPIRED (`submitted_at` NULL ở cả hai vế).
- `scoring_version`: `1` là dữ liệu V13 (`score` là %). `2` là bản v1 (`score = correct_count`). Code mới luôn ghi `2`. API v1 **không trả `score`** (ADR-11). Kết quả trả về là `correctCount`, `listeningCorrect`, `readingCorrect`.
- `source_set_id` không có FK ở V14 vì `practice_sets` có thể tạo cùng file. Nếu tạo cùng file thì thêm `FOREIGN KEY (source_set_id) REFERENCES practice_sets (id) ON DELETE RESTRICT`.
- `listening_correct` = số câu đúng ở `part_number` 1–4. `reading_correct` = số câu đúng ở 5–7. Số đúng theo Part không lưu cột: tính từ `attempt_question_snapshots.part_number` (truy vấn ở 4.5).
- `local_day` và `timezone` (CONTRACT §10) **không** đưa vào V14. Chỉ D13 và streak cần, nên thêm cùng D13. Đây là lệch CONTRACT §10, ghi trong bản cập nhật CONTRACT.

#### 4.1.2. `attempt_question_snapshots` `[MODIFY]`

```sql
ALTER TABLE attempt_question_snapshots
    ADD COLUMN id                     UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN group_snapshot_id      UUID,
    ADD COLUMN source_revision_id     UUID,
    ADD COLUMN category               VARCHAR(16),
    ADD COLUMN part_number            SMALLINT,
    ADD COLUMN display_number         SMALLINT,
    ADD COLUMN schema_version         SMALLINT,
    ADD COLUMN grading_policy_version VARCHAR(32),
    ADD COLUMN question_snapshot      JSONB,
    ADD COLUMN answer_key_snapshot    JSONB,
    ALTER COLUMN topic_id    DROP NOT NULL,
    ALTER COLUMN prompt      DROP NOT NULL,
    ALTER COLUMN explanation DROP NOT NULL,
    DROP CONSTRAINT attempt_question_snapshots_c3;   -- position 1..50

ALTER TABLE attempt_question_snapshots
    ADD CONSTRAINT attempt_question_snapshots_c4 UNIQUE (id),
    ADD CONSTRAINT attempt_question_snapshots_c5 UNIQUE (attempt_id, id),
    ADD CONSTRAINT attempt_question_snapshots_c6 CHECK (position BETWEEN 1 AND 200),
    ADD CONSTRAINT attempt_question_snapshots_c7 CHECK (
        (schema_version IS NULL                       -- dòng V13
            AND prompt IS NOT NULL AND explanation IS NOT NULL AND topic_id IS NOT NULL
            AND question_snapshot IS NULL AND answer_key_snapshot IS NULL)
        OR (schema_version = 1                        -- dòng v1
            AND prompt IS NULL AND explanation IS NULL
            AND question_snapshot IS NOT NULL AND answer_key_snapshot IS NOT NULL
            AND source_revision_id IS NOT NULL AND category IS NOT NULL
            AND display_number IS NOT NULL AND grading_policy_version IS NOT NULL)),
    ADD CONSTRAINT attempt_question_snapshots_c8 CHECK (display_number IS NULL OR display_number BETWEEN 1 AND 200),
    ADD CONSTRAINT attempt_question_snapshots_c9 CHECK (schema_version IS NULL OR (
        (category = 'TOEIC' AND part_number BETWEEN 1 AND 7 AND topic_id IS NULL)
        OR (category = 'GRAMMAR' AND part_number IS NULL AND topic_id IS NOT NULL))),
    ADD CONSTRAINT attempt_question_snapshots_c10 CHECK (grading_policy_version IS NULL
        OR grading_policy_version = 'SINGLE_CHOICE_V1'),
    ADD CONSTRAINT attempt_question_snapshots_c11 CHECK (question_snapshot IS NULL OR (
        jsonb_typeof(question_snapshot) = 'object' AND octet_length(question_snapshot::text) <= 1048576
        AND (question_snapshot->>'schema_version')::int = schema_version)),
    ADD CONSTRAINT attempt_question_snapshots_c12 CHECK (answer_key_snapshot IS NULL OR (
        jsonb_typeof(answer_key_snapshot) = 'object' AND octet_length(answer_key_snapshot::text) <= 1048576)),
    ADD CONSTRAINT attempt_question_snapshots_c13 FOREIGN KEY (attempt_id, group_snapshot_id)
        REFERENCES attempt_group_snapshots (attempt_id, id) ON DELETE RESTRICT;

CREATE INDEX ix_aqs_group_snapshot ON attempt_question_snapshots (group_snapshot_id)
    WHERE group_snapshot_id IS NOT NULL;
```

- `position` (V13) chính là `ordinal` trong API. Không đổi tên cột.
- `snapshotId` trong URL = cột `id` mới. Dòng V13 được sinh `id` qua `gen_random_uuid()` lúc `ADD COLUMN` (PostgreSQL 17 có sẵn hàm này).
- `c13` chỉ chạy khi `group_snapshot_id IS NOT NULL` (FK nhiều cột bỏ qua dòng có NULL theo mặc định `MATCH SIMPLE`).

#### 4.1.3. `attempt_snapshot_options` `[MODIFY]`

```sql
ALTER TABLE attempt_snapshot_options
    ADD COLUMN label            CHAR(1),
    ADD COLUMN source_option_id UUID,
    ALTER COLUMN body       DROP NOT NULL,
    ALTER COLUMN is_correct DROP NOT NULL;

ALTER TABLE attempt_snapshot_options
    ADD CONSTRAINT attempt_snapshot_options_c5 CHECK (label IS NULL OR label = chr(64 + position)),
    ADD CONSTRAINT attempt_snapshot_options_c6 CHECK ((label IS NULL) = (is_correct IS NOT NULL)),  -- V13: is_correct có, label NULL; v1: ngược lại
    ADD CONSTRAINT attempt_snapshot_options_c7 CHECK ((label IS NULL) = (source_option_id IS NULL)),
    ADD CONSTRAINT attempt_snapshot_options_c8 CHECK (body IS NULL OR btrim(body) <> '');
```

`body IS NULL` khi và chỉ khi câu có `question_snapshot.option_display = 'LABEL_ONLY'` (P1, P2). Ràng buộc này nằm ở service vì nó đi qua hai bảng. Test: `SealMappingTests.labelOnlyOptionsHaveNullBody`.

#### 4.1.4. `attempt_answers` `[MODIFY]`

```sql
ALTER TABLE attempt_answers
    ADD COLUMN version         BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN is_correct      BOOLEAN,
    ADD COLUMN last_request_id UUID,
    ADD COLUMN updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN selected_option_id DROP NOT NULL,
    ALTER COLUMN answered_at DROP NOT NULL,
    ALTER COLUMN answered_at DROP DEFAULT;

ALTER TABLE attempt_answers
    ADD CONSTRAINT attempt_answers_c4 CHECK (version >= 0),
    ADD CONSTRAINT attempt_answers_c5 CHECK ((selected_option_id IS NULL) = (answered_at IS NULL));
```

- Dòng V13 luôn có `selected_option_id` và `answered_at`, nên `c5` hợp lệ ngay mà không cần `NOT VALID`.
- Bản v1 tạo **slot rỗng cho mọi câu khi start** (`version = 0`), đúng một dòng mỗi snapshot. Bất biến: `count(attempt_answers) = attempts.total_count` với dòng v1.
- `is_correct` là `NULL` cho đến khi nộp (ADR-09). Không thêm `score` theo câu (lệch DD c3–c5): `is_correct` đã đủ.

#### 4.1.5. `attempt_group_snapshots` `[CREATE]`

```sql
CREATE TABLE attempt_group_snapshots (
    id                      UUID PRIMARY KEY,
    attempt_id              UUID NOT NULL REFERENCES attempts (id) ON DELETE RESTRICT,
    group_id                UUID NOT NULL REFERENCES question_groups (id) ON DELETE RESTRICT,
    source_revision_id      UUID NOT NULL,
    ordinal                 SMALLINT NOT NULL,
    schema_version          SMALLINT NOT NULL DEFAULT 1,
    category                VARCHAR(16) NOT NULL,
    part_number             SMALLINT,
    expected_question_count SMALLINT NOT NULL,
    public_snapshot         JSONB NOT NULL,
    private_snapshot        JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT attempt_group_snapshots_c1 UNIQUE (attempt_id, id),
    CONSTRAINT attempt_group_snapshots_c2 UNIQUE (attempt_id, group_id),
    CONSTRAINT attempt_group_snapshots_c3 UNIQUE (attempt_id, ordinal),
    CONSTRAINT attempt_group_snapshots_c4 CHECK (ordinal > 0 AND schema_version = 1),
    CONSTRAINT attempt_group_snapshots_c5 CHECK (category = 'TOEIC' AND part_number IN (3,4,6,7)),
    CONSTRAINT attempt_group_snapshots_c6 CHECK (part_number NOT IN (3,4) OR expected_question_count = 3),
    CONSTRAINT attempt_group_snapshots_c7 CHECK (part_number <> 6 OR expected_question_count = 4),
    CONSTRAINT attempt_group_snapshots_c8 CHECK (part_number <> 7 OR expected_question_count BETWEEN 2 AND 5),
    CONSTRAINT attempt_group_snapshots_c9 CHECK (jsonb_typeof(public_snapshot) = 'object'
        AND octet_length(public_snapshot::text) <= 1048576
        AND (public_snapshot->>'schema_version')::int = schema_version),
    CONSTRAINT attempt_group_snapshots_c10 CHECK (jsonb_typeof(private_snapshot) = 'object'
        AND octet_length(private_snapshot::text) <= 1048576)
);
```

`c5` chỉ cho TOEIC vì MVP không có nhóm cho GRAMMAR/VOCABULARY. Lệch DD c5 (DD cho phép `GENERAL`). Khi cần thì nới bằng migration sau.

#### 4.1.6. `group_materials`, `question_materials` `[CREATE]` (owner `practice`, là projection)

```sql
CREATE TABLE group_materials (
    id            UUID PRIMARY KEY,
    group_id      UUID NOT NULL REFERENCES question_groups (id) ON DELETE RESTRICT,
    position      SMALLINT NOT NULL,
    document_key  VARCHAR(32) NOT NULL,
    material_type VARCHAR(16) NOT NULL,
    usage_role    VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    visibility    VARCHAR(16) GENERATED ALWAYS AS
                  (CASE WHEN usage_role = 'STIMULUS' THEN 'BEFORE_SUBMIT' ELSE 'AFTER_SUBMIT' END) STORED,
    media_id      UUID REFERENCES media_assets (id) ON DELETE RESTRICT,
    body          JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT group_materials_c1 UNIQUE (group_id, position),
    CONSTRAINT group_materials_c2 UNIQUE (group_id, document_key),
    CONSTRAINT group_materials_c3 CHECK (position BETWEEN 1 AND 20),
    CONSTRAINT group_materials_c4 CHECK (document_key ~ '^[a-z][a-z0-9_-]{0,31}$'),
    CONSTRAINT group_materials_c5 CHECK (material_type IN ('AUDIO','IMAGE','RICH_TEXT','CHAT','TABLE','TRANSCRIPT')),
    CONSTRAINT group_materials_c6 CHECK (usage_role IN ('STIMULUS','TRANSCRIPT','EXPLANATION')),
    CONSTRAINT group_materials_c7 CHECK ((material_type IN ('AUDIO','IMAGE')) = (media_id IS NOT NULL)),
    CONSTRAINT group_materials_c8 CHECK ((material_type = 'TRANSCRIPT') = (usage_role = 'TRANSCRIPT')),
    CONSTRAINT group_materials_c9 CHECK (material_type NOT IN ('AUDIO','IMAGE') OR usage_role = 'STIMULUS'),
    CONSTRAINT group_materials_c10 CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 262144)
);
-- question_materials: giống hệt, thay group_id bằng question_id UUID NOT NULL REFERENCES questions (id).
```

- `visibility` là cột GENERATED từ `usage_role` của DD. ADR-08 có `visibility` mà không có hai nguồn sự thật.
- `media_id` là FK DB sang bảng của `content`. Ở tầng JPA chỉ là `UUID` (quy tắc 7.2).
- `body` là **đúng object `body`** trong JSON Schema `MaterialV1` (4.2). Các trường có cột riêng (`document_key` ↔ `material_key`, `material_type`, `position`, `visibility`, `media_id` ↔ `media_asset_id`) không lặp lại trong `body`.

#### 4.1.7. `question_groups`, `questions`, `question_types` `[CREATE]`/`[MODIFY]`

- `question_groups` `[CREATE]`: theo `approved-baseline.sql:549-564` nguyên trạng, trừ `c3` chỉ còn `('GRAMMAR','TOEIC')`.
- `questions` `[MODIFY]`:
  - Thêm `part_number SMALLINT`, `group_id UUID REFERENCES question_groups (id)`, `group_position SMALLINT`, `blank_key VARCHAR(80)`.
  - Thay `questions_c2` bằng `category IN ('GRAMMAR','TOEIC')`.
  - `prompt` `DROP NOT NULL`. Thay `questions_c6` bằng: `(prompt IS NULL OR (btrim(prompt) <> '' AND char_length(prompt) <= 10000)) AND btrim(explanation) <> '' AND char_length(explanation) <= 20000 AND (category <> 'GRAMMAR' OR prompt IS NOT NULL)`.
  - Thêm `c7`–`c11` của DD (`approved-baseline.sql:590-594`), với `blank_key` theo regex `^b[1-9]$`.
- `question_options`: không đổi. Với P1, P2, `body` là câu được đọc trong audio. Câu này vào `answer_key_snapshot.option_transcripts` lúc seal.
- `question_types` `[CREATE]`: theo DD, chỉ có `SINGLE_CHOICE` với `enabled = true`.

#### 4.1.8. `practice_sets`, `set_items` `[CREATE]`

Theo `approved-baseline.sql:646-679`, với hai thay đổi:

1. Thay `practice_sets_c5` bằng `(category = 'TOEIC' AND (part_number IS NULL OR part_number BETWEEN 1 AND 7)) OR (category <> 'TOEIC' AND part_number IS NULL)`. TOEIC với `part_number IS NULL` là bộ nhiều Part, gồm Full Test.
2. MVP chỉ cho `selection_mode = 'FIXED'` với TOEIC (kiểm ở validator publish).

Hai thay đổi này mở lại D6 (hoãn `practice_sets`) cho TOEIC. Câu hỏi duyệt S9.

#### 4.1.9. `attempt_media_refs` `[CREATE]` và trigger bất biến

```sql
CREATE TABLE attempt_media_refs (
    attempt_id UUID NOT NULL REFERENCES attempts (id) ON DELETE RESTRICT,
    media_id   UUID NOT NULL REFERENCES media_assets (id) ON DELETE RESTRICT,
    usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    CONSTRAINT attempt_media_refs_c1 PRIMARY KEY (attempt_id, media_id, usage_role),
    CONSTRAINT attempt_media_refs_c2 CHECK (usage_role IN ('STIMULUS','EXPLANATION','TRANSCRIPT'))
);

-- Đặt sau mọi backfill trong V14. Dùng lại hàm của V7.
CREATE TRIGGER attempt_group_snapshots_append_only BEFORE UPDATE OR DELETE ON attempt_group_snapshots
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();
CREATE TRIGGER attempt_question_snapshots_append_only BEFORE UPDATE OR DELETE ON attempt_question_snapshots
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();
CREATE TRIGGER attempt_snapshot_options_append_only BEFORE UPDATE OR DELETE ON attempt_snapshot_options
    FOR EACH ROW EXECUTE FUNCTION me_history_append_only();
```

`attempt_answers` không có trigger: slot phải UPDATE được khi `IN_PROGRESS`. Service chặn ghi sau khi nộp (`ATTEMPT_NOT_OPEN`). Câu hỏi duyệt S5 về việc có cần trigger theo trạng thái không.

### 4.2. JSON Schema v1 (Draft 2020-12)

**Quy tắc phiên bản.**
- Thêm trường **tùy chọn** hoặc thêm **giá trị enum** là tương thích ngược, vẫn `schema_version = 1`. Renderer gặp `document_kind` lạ thì hiển thị kiểu chung.
- Xóa, đổi tên trường, hoặc đổi nghĩa trường thì tăng lên `2`. Dữ liệu cũ giữ nguyên `1` vĩnh viễn (snapshot bất biến).
- Mọi object có `additionalProperties: false`. Tên trường trong JSON lưu DB dùng `snake_case`.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:masterenglish:m05:snapshot:v1",
  "title": "M05 snapshot JSON v1",
  "$defs": {
    "Uuid": { "type": "string", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" },
    "PlainText": { "type": "string", "minLength": 1, "not": { "pattern": "<\\s*/?\\s*[a-zA-Z!]" } },
    "Key": { "type": "string", "pattern": "^[a-z][a-z0-9_-]{0,31}$" },
    "BlankKey": { "type": "string", "pattern": "^b[1-9]$" },
    "SpeakerLabel": { "type": "string", "pattern": "^(N|[MW]-(Am|Br|Cn|Au))$" },
    "ClockTime": { "type": "string", "pattern": "^((0?[1-9]|1[0-2]):[0-5][0-9] (A\\.M\\.|P\\.M\\.)|([01]?[0-9]|2[0-3]):[0-5][0-9])$" },
    "Visibility": { "enum": ["BEFORE_SUBMIT", "AFTER_SUBMIT"] },
    "Ref": {
      "type": "object", "additionalProperties": false, "required": ["material_key"],
      "properties": { "material_key": { "$ref": "#/$defs/Key" }, "anchor": { "$ref": "#/$defs/Key" } }
    },

    "Inline": {
      "oneOf": [
        { "type": "object", "additionalProperties": false, "required": ["type", "text"],
          "properties": { "type": { "const": "text" },
                          "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 4000 }] },
                          "marks": { "type": "array", "uniqueItems": true, "items": { "enum": ["BOLD", "ITALIC", "UNDERLINE"] } } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "blank_key"],
          "properties": { "type": { "const": "blank" }, "blank_key": { "$ref": "#/$defs/BlankKey" } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "position"],
          "properties": { "type": { "const": "insert" }, "position": { "type": "integer", "minimum": 1, "maximum": 4 } } },
        { "type": "object", "additionalProperties": false, "required": ["type"],
          "properties": { "type": { "const": "line_break" } } }
      ]
    },
    "Inlines": { "type": "array", "minItems": 1, "maxItems": 400, "items": { "$ref": "#/$defs/Inline" } },
    "Block": {
      "oneOf": [
        { "type": "object", "additionalProperties": false, "required": ["type", "level", "inlines"],
          "properties": { "type": { "const": "heading" }, "level": { "type": "integer", "minimum": 1, "maximum": 3 },
                          "inlines": { "$ref": "#/$defs/Inlines" } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "inlines"],
          "properties": { "type": { "const": "paragraph" }, "inlines": { "$ref": "#/$defs/Inlines" } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "ordered", "items"],
          "properties": { "type": { "const": "list" }, "ordered": { "type": "boolean" },
                          "items": { "type": "array", "minItems": 1, "maxItems": 50,
                                     "items": { "type": "object", "additionalProperties": false, "required": ["inlines"],
                                                "properties": { "inlines": { "$ref": "#/$defs/Inlines" } } } } } }
      ]
    },

    "AudioBody": {
      "type": "object", "additionalProperties": false,
      "properties": { "duration_seconds": { "type": "integer", "minimum": 1, "maximum": 3600 },
                      "directions_text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 2000 }] } }
    },
    "ImageBody": {
      "type": "object", "additionalProperties": false, "required": ["alt_text"],
      "properties": { "alt_text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 500 }] },
                      "caption": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] } }
    },
    "RichTextBody": {
      "type": "object", "additionalProperties": false, "required": ["document_kind", "blocks"],
      "properties": {
        "document_kind": { "enum": ["EMAIL", "LETTER", "MEMO", "NOTICE", "ANNOUNCEMENT", "ADVERTISEMENT", "ARTICLE",
                                    "REVIEW", "INFORMATION", "FORM", "INVOICE", "WEB_PAGE", "SCHEDULE", "INSTRUCTIONS",
                                    "EXPLANATION", "OTHER"] },
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "header": {
          "type": "object", "additionalProperties": false, "minProperties": 1,
          "properties": {
            "from": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "to": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "cc": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "subject": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "date": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 100 }] }
          }
        },
        "blocks": { "type": "array", "minItems": 1, "maxItems": 200, "items": { "$ref": "#/$defs/Block" } }
      }
    },
    "ChatBody": {
      "type": "object", "additionalProperties": false, "required": ["channel", "messages"],
      "properties": {
        "channel": { "enum": ["TEXT_MESSAGE", "ONLINE_CHAT"] },
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "messages": { "type": "array", "minItems": 2, "maxItems": 60,
          "items": { "type": "object", "additionalProperties": false, "required": ["anchor", "author", "time", "text"],
            "properties": { "anchor": { "$ref": "#/$defs/Key" },
                            "author": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 100 }] },
                            "time": { "$ref": "#/$defs/ClockTime" },
                            "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 2000 }] } } } }
      }
    },
    "TableBody": {
      "type": "object", "additionalProperties": false, "required": ["headers", "rows"],
      "properties": {
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "headers": { "type": "array", "minItems": 1, "maxItems": 10,
                     "items": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 100 }] } },
        "rows": { "type": "array", "minItems": 1, "maxItems": 50,
                  "items": { "type": "array", "minItems": 1, "maxItems": 10,
                             "items": { "type": "string", "maxLength": 500, "not": { "pattern": "<\\s*/?\\s*[a-zA-Z!]" } } } },
        "caption": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] }
      }
    },
    "TranscriptBody": {
      "type": "object", "additionalProperties": false, "required": ["segments"],
      "properties": {
        "segments": { "type": "array", "minItems": 1, "maxItems": 400,
          "items": { "oneOf": [
            { "type": "object", "additionalProperties": false, "required": ["type", "speaker", "text"],
              "properties": { "type": { "const": "speech" }, "speaker": { "$ref": "#/$defs/SpeakerLabel" },
                              "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 4000 }] },
                              "start_ms": { "type": "integer", "minimum": 0 },
                              "end_ms": { "type": "integer", "minimum": 1 } } },
            { "type": "object", "additionalProperties": false, "required": ["type", "seconds"],
              "properties": { "type": { "const": "pause" }, "seconds": { "type": "integer", "minimum": 1, "maximum": 60 } } }
          ] } }
      }
    },

    "Material": {
      "type": "object", "additionalProperties": false,
      "required": ["material_key", "material_type", "visibility", "position", "body"],
      "properties": {
        "material_key": { "$ref": "#/$defs/Key" },
        "material_type": { "enum": ["AUDIO", "IMAGE", "RICH_TEXT", "CHAT", "TABLE", "TRANSCRIPT"] },
        "visibility": { "$ref": "#/$defs/Visibility" },
        "position": { "type": "integer", "minimum": 1, "maximum": 20 },
        "media_asset_id": { "$ref": "#/$defs/Uuid" },
        "body": { "type": "object" }
      },
      "allOf": [
        { "if": { "properties": { "material_type": { "const": "AUDIO" } } },
          "then": { "required": ["media_asset_id"], "properties": { "visibility": { "const": "BEFORE_SUBMIT" }, "body": { "$ref": "#/$defs/AudioBody" } } } },
        { "if": { "properties": { "material_type": { "const": "IMAGE" } } },
          "then": { "required": ["media_asset_id"], "properties": { "visibility": { "const": "BEFORE_SUBMIT" }, "body": { "$ref": "#/$defs/ImageBody" } } } },
        { "if": { "properties": { "material_type": { "const": "RICH_TEXT" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "body": { "$ref": "#/$defs/RichTextBody" } } } },
        { "if": { "properties": { "material_type": { "const": "CHAT" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "body": { "$ref": "#/$defs/ChatBody" } } } },
        { "if": { "properties": { "material_type": { "const": "TABLE" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "body": { "$ref": "#/$defs/TableBody" } } } },
        { "if": { "properties": { "material_type": { "const": "TRANSCRIPT" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "visibility": { "const": "AFTER_SUBMIT" }, "body": { "$ref": "#/$defs/TranscriptBody" } } } }
      ]
    },

    "GroupPublicSnapshot": {
      "type": "object", "additionalProperties": false,
      "required": ["schema_version", "category", "part_number", "group_type", "materials"],
      "properties": {
        "schema_version": { "const": 1 },
        "category": { "const": "TOEIC" },
        "part_number": { "enum": [3, 4, 6, 7] },
        "group_type": { "enum": ["CONVERSATION", "TALK", "TEXT_COMPLETION", "PASSAGE"] },
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "speaker_count": { "type": "integer", "minimum": 1, "maximum": 3 },
        "materials": { "type": "array", "minItems": 1, "maxItems": 10,
                       "items": { "allOf": [{ "$ref": "#/$defs/Material" },
                                            { "properties": { "visibility": { "const": "BEFORE_SUBMIT" } } }] } }
      }
    },
    "GroupPrivateSnapshot": {
      "type": "object", "additionalProperties": false, "required": ["schema_version", "materials"],
      "properties": {
        "schema_version": { "const": 1 },
        "materials": { "type": "array", "maxItems": 10,
                       "items": { "allOf": [{ "$ref": "#/$defs/Material" },
                                            { "properties": { "visibility": { "const": "AFTER_SUBMIT" } } }] } }
      }
    },

    "QuestionSnapshot": {
      "type": "object", "additionalProperties": false,
      "required": ["schema_version", "category", "part_number", "question_type_code", "practice_mode",
                   "option_order", "option_display", "question_kind", "prompt", "refs", "materials", "meta"],
      "properties": {
        "schema_version": { "const": 1 },
        "category": { "enum": ["GRAMMAR", "TOEIC"] },
        "part_number": { "type": ["integer", "null"], "minimum": 1, "maximum": 7 },
        "question_type_code": { "const": "SINGLE_CHOICE" },
        "practice_mode": { "enum": ["STANDARD", "FILL_OPTION"] },
        "option_order": { "enum": ["FIXED", "SHUFFLED"] },
        "option_display": { "enum": ["TEXT", "LABEL_ONLY"] },
        "question_kind": { "enum": ["STANDARD", "GRAPHIC", "INTENT", "SENTENCE_INSERTION", "INSERTION_POSITION", "NOT_EXCEPT"] },
        "blank_key": { "$ref": "#/$defs/BlankKey" },
        "prompt": { "oneOf": [{ "type": "null" }, { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 10000 }] }] },
        "insert_sentence": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 1000 }] },
        "refs": { "type": "array", "maxItems": 10, "items": { "$ref": "#/$defs/Ref" } },
        "materials": { "type": "array", "maxItems": 4,
                       "items": { "allOf": [{ "$ref": "#/$defs/Material" },
                                            { "properties": { "visibility": { "const": "BEFORE_SUBMIT" } } }] } },
        "meta": {
          "type": "object", "additionalProperties": false, "required": ["topic_ids", "word_sense_ids"],
          "properties": { "topic_ids": { "type": "array", "uniqueItems": true, "items": { "$ref": "#/$defs/Uuid" } },
                          "word_sense_ids": { "type": "array", "uniqueItems": true, "items": { "$ref": "#/$defs/Uuid" } } }
        }
      },
      "allOf": [
        { "if": { "properties": { "category": { "const": "GRAMMAR" } } },
          "then": { "properties": { "part_number": { "const": null }, "option_display": { "const": "TEXT" },
                                    "question_kind": { "const": "STANDARD" }, "prompt": { "type": "string" },
                                    "materials": { "maxItems": 0 } } } },
        { "if": { "properties": { "category": { "const": "TOEIC" } } },
          "then": { "properties": { "part_number": { "type": "integer" }, "practice_mode": { "const": "STANDARD" },
                                    "option_order": { "const": "FIXED" } } } },
        { "if": { "properties": { "part_number": { "enum": [1, 2] } }, "required": ["part_number"] },
          "then": { "properties": { "option_display": { "const": "LABEL_ONLY" }, "prompt": { "type": "null" },
                                    "question_kind": { "const": "STANDARD" } } },
          "else": { "properties": { "option_display": { "const": "TEXT" } } } },
        { "if": { "properties": { "part_number": { "const": 6 } }, "required": ["part_number"] },
          "then": { "required": ["blank_key"], "properties": { "question_kind": { "enum": ["STANDARD", "SENTENCE_INSERTION"] } } },
          "else": { "not": { "required": ["blank_key"] } } },
        { "if": { "properties": { "question_kind": { "const": "INSERTION_POSITION" } } },
          "then": { "required": ["insert_sentence"], "properties": { "part_number": { "const": 7 }, "refs": { "minItems": 1 } } },
          "else": { "not": { "required": ["insert_sentence"] } } },
        { "if": { "properties": { "question_kind": { "const": "GRAPHIC" } } },
          "then": { "properties": { "part_number": { "enum": [3, 4] }, "refs": { "minItems": 1 } } } },
        { "if": { "properties": { "question_kind": { "const": "SENTENCE_INSERTION" } } },
          "then": { "properties": { "part_number": { "const": 6 } } } }
      ]
    },

    "AnswerKeySnapshot": {
      "type": "object", "additionalProperties": false,
      "required": ["schema_version", "correct_option_snapshot_id", "correct_option_ordinal", "explanation", "evidence"],
      "properties": {
        "schema_version": { "const": 1 },
        "correct_option_snapshot_id": { "$ref": "#/$defs/Uuid" },
        "correct_option_ordinal": { "type": "integer", "minimum": 1, "maximum": 6 },
        "explanation": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 20000 }] },
        "evidence": { "type": "array", "maxItems": 10, "items": { "$ref": "#/$defs/Ref" } },
        "option_transcripts": { "type": "array", "minItems": 3, "maxItems": 4,
          "items": { "type": "object", "additionalProperties": false, "required": ["ordinal", "label", "speaker", "text"],
            "properties": { "ordinal": { "type": "integer", "minimum": 1, "maximum": 4 },
                            "label": { "enum": ["A", "B", "C", "D"] },
                            "speaker": { "$ref": "#/$defs/SpeakerLabel" },
                            "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 1000 }] } } } },
        "prompt_transcript": {
          "type": "object", "additionalProperties": false, "required": ["speaker", "text"],
          "properties": { "speaker": { "$ref": "#/$defs/SpeakerLabel" },
                          "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 1000 }] } } }
      }
    }
  }
}
```

**Ràng buộc mà JSON Schema không biểu diễn được.** Validator Java phải kiểm, và có test riêng ở 11.2:

| Mã | Ràng buộc |
|---|---|
| V-01 | `TableBody`: mọi `rows[i]` có cùng độ dài với `headers`. |
| V-02 | `ChatBody`: `messages[].anchor` duy nhất trong tài liệu. |
| V-03 | Trong một nhóm: mỗi `blank_key` của câu con xuất hiện đúng một lần dưới dạng inline `blank` trong `materials`, và ngược lại. P6 đúng `b1..b4`. |
| V-04 | Tài liệu có inline `insert` thì có đủ `position` 1–4, mỗi số đúng một lần. |
| V-05 | `refs[].material_key` trỏ tới material có trong nhóm hoặc câu. `anchor` (nếu có) trỏ tới `messages[].anchor` của một `CHAT`. Câu `INSERTION_POSITION` có ref tới tài liệu chứa node `insert`. |
| V-06 | `option_transcripts` có khi và chỉ khi `part_number ∈ {1,2}`. Độ dài: 4 với P1, 3 với P2. `ordinal` và `label` khớp `chr(64+ordinal)`. |
| V-07 | `prompt_transcript` có khi và chỉ khi `part_number = 2`. |
| V-08 | `correct_option_snapshot_id` và `correct_option_ordinal` khớp đúng một dòng `attempt_snapshot_options` (R1). |
| V-09 | `speaker_count` có khi `part_number = 3`. Giá trị ∈ {2, 3}, và bằng số nhãn `M-*`/`W-*` khác nhau trong `TRANSCRIPT` của nhóm. |
| V-10 | `TRANSCRIPT.segments`: `end_ms > start_ms` khi có cả hai. |
| V-11 | Header email không chứa `<` theo sau là chữ cái (regex `PlainText`). Địa chỉ viết `Tên (a@b.example)`, không viết `Tên <a@b.example>`. |
| V-12 | `practice_mode`: GRAMMAR giữ giá trị envelope (`STANDARD`/`FILL_OPTION`), TOEIC luôn `STANDARD`. |

### 4.3. Ví dụ JSON hợp lệ

Nội dung ví dụ do nhóm tự viết, không chép đề ETS. Các UUID là cố định để dễ tham chiếu chéo.

#### 4.3.1. Học liệu (`MaterialV1`) — đủ 6 loại

```json
{ "material_key": "audio", "material_type": "AUDIO", "visibility": "BEFORE_SUBMIT", "position": 1,
  "media_asset_id": "3c9e1f20-7a41-4b6e-9d2c-5f8a0b1c2d31",
  "body": { "duration_seconds": 41, "directions_text": "Listen to the conversation and answer three questions." } }
```

```json
{ "material_key": "photo", "material_type": "IMAGE", "visibility": "BEFORE_SUBMIT", "position": 1,
  "media_asset_id": "4d0f2a31-8b52-4c7f-8e3d-6a9b1c2d3e41",
  "body": { "alt_text": "Two workers are carrying a long table through an open doorway." } }
```

```json
{ "material_key": "notice", "material_type": "RICH_TEXT", "visibility": "BEFORE_SUBMIT", "position": 1,
  "body": { "document_kind": "NOTICE", "title": "Parking garage maintenance",
    "blocks": [
      { "type": "heading", "level": 2, "inlines": [ { "type": "text", "text": "Level B closed on Saturday" } ] },
      { "type": "paragraph", "inlines": [
        { "type": "text", "text": "Please use the visitor lot on Elm Street. " },
        { "type": "text", "text": "Vehicles left on Level B will be moved.", "marks": ["BOLD"] } ] },
      { "type": "list", "ordered": false, "items": [
        { "inlines": [ { "type": "text", "text": "Saturday, 7:00 A.M. to 6:00 P.M." } ] },
        { "inlines": [ { "type": "text", "text": "Questions: facilities desk, extension 214" } ] } ] } ] } }
```

```json
{ "material_key": "chat", "material_type": "CHAT", "visibility": "BEFORE_SUBMIT", "position": 2,
  "body": { "channel": "TEXT_MESSAGE",
    "messages": [
      { "anchor": "t1043", "author": "Mara Lindqvist", "time": "10:43 A.M.", "text": "The caterer just called. They can only deliver at noon." },
      { "anchor": "t1047", "author": "Kenji Watanabe", "time": "10:47 A.M.", "text": "That works. I'll ask reception to hold the boardroom." } ] } }
```

```json
{ "material_key": "graphic", "material_type": "TABLE", "visibility": "BEFORE_SUBMIT", "position": 2,
  "body": { "title": "Training rooms - Thursday", "headers": ["Room", "Available from"],
    "rows": [ ["Room A", "9:00 A.M."], ["Room B", "Closed for repair"], ["Room C", "11:00 A.M."], ["Room D", "2:00 P.M."] ] } }
```

```json
{ "material_key": "transcript", "material_type": "TRANSCRIPT", "visibility": "AFTER_SUBMIT", "position": 1,
  "body": { "segments": [
    { "type": "speech", "speaker": "N", "text": "Questions 41 through 43 refer to the following conversation with three speakers and schedule." },
    { "type": "speech", "speaker": "W-Am", "text": "Daniel, Priya, the projector in Room B stopped working again this morning." },
    { "type": "speech", "speaker": "M-Cn", "text": "That's the third time this month. Should we move Thursday's workshop?" },
    { "type": "speech", "speaker": "W-Br", "text": "Room D is free on Thursday, but only in the afternoon." },
    { "type": "speech", "speaker": "M-Cn", "text": "Then let's start at two thirty. I'll update the invitation." },
    { "type": "speech", "speaker": "N", "text": "41. What problem does the woman mention?" },
    { "type": "pause", "seconds": 8 },
    { "type": "speech", "speaker": "N", "text": "42. Look at the graphic. Which room will the workshop most likely use?" },
    { "type": "pause", "seconds": 8 } ] } }
```

Trong bảng `group_materials`, mỗi ví dụ trên được lưu thành một dòng: `document_key = material_key`, `material_type`, `position`, `media_id = media_asset_id`, `usage_role` (`STIMULUS` khi BEFORE_SUBMIT, `TRANSCRIPT` với TRANSCRIPT, `EXPLANATION` với văn bản lời giải), và `body` giữ nguyên object `body`.

#### 4.3.2. (a) Part 3: ba người nói, có graphic và transcript

`attempt_group_snapshots` (id `0a1b2c3d-1111-4a2b-8c3d-000000000041`, `part_number = 3`, `expected_question_count = 3`):

`public_snapshot`:
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 3, "group_type": "CONVERSATION", "speaker_count": 3,
  "materials": [
    { "material_key": "audio", "material_type": "AUDIO", "visibility": "BEFORE_SUBMIT", "position": 1,
      "media_asset_id": "3c9e1f20-7a41-4b6e-9d2c-5f8a0b1c2d31", "body": { "duration_seconds": 41 } },
    { "material_key": "graphic", "material_type": "TABLE", "visibility": "BEFORE_SUBMIT", "position": 2,
      "body": { "title": "Training rooms - Thursday", "headers": ["Room", "Available from"],
                "rows": [ ["Room A", "9:00 A.M."], ["Room B", "Closed for repair"], ["Room C", "11:00 A.M."], ["Room D", "2:00 P.M."] ] } } ] }
```

`private_snapshot`:
```json
{ "schema_version": 1,
  "materials": [
    { "material_key": "transcript", "material_type": "TRANSCRIPT", "visibility": "AFTER_SUBMIT", "position": 1,
      "body": { "segments": [
        { "type": "speech", "speaker": "W-Am", "text": "Daniel, Priya, the projector in Room B stopped working again this morning." },
        { "type": "speech", "speaker": "M-Cn", "text": "That's the third time this month. Should we move Thursday's workshop?" },
        { "type": "speech", "speaker": "W-Br", "text": "Room D is free on Thursday, but only in the afternoon." },
        { "type": "speech", "speaker": "M-Cn", "text": "Then let's start at two thirty. I'll update the invitation." },
        { "type": "pause", "seconds": 8 } ] } } ] }
```

`question_snapshot` của câu 42 (`display_number = 42`, `group_snapshot_id` = nhóm trên):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 3, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT", "question_kind": "GRAPHIC",
  "prompt": "Look at the graphic. Which room will the workshop most likely use?",
  "refs": [ { "material_key": "graphic" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

`attempt_snapshot_options` của câu 42: `(id 5b…01, position 1, label 'A', body 'Room A')`, `(5b…02, 2, 'B', 'Room B')`, `(5b…03, 3, 'C', 'Room C')`, `(5b…04, 4, 'D', 'Room D')`.

`answer_key_snapshot`:
```json
{ "schema_version": 1, "correct_option_snapshot_id": "5b6c7d8e-2222-4c3d-9e4f-000000000424",
  "correct_option_ordinal": 4,
  "explanation": "The woman says Room D is free only in the afternoon, and the man moves the start to two thirty. The schedule shows Room D opens at 2:00 P.M.",
  "evidence": [ { "material_key": "graphic" }, { "material_key": "transcript" } ] }
```

#### 4.3.3. (b) Part 6: bốn `blank_key`, một câu `SENTENCE_INSERTION`

`public_snapshot`:
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 6, "group_type": "TEXT_COMPLETION",
  "materials": [
    { "material_key": "email", "material_type": "RICH_TEXT", "visibility": "BEFORE_SUBMIT", "position": 1,
      "body": { "document_kind": "EMAIL",
        "header": { "from": "Lena Ortiz (l.ortiz@harbourprint.example)", "to": "Sam Achebe (s.achebe@northfield.example)",
                    "subject": "Your program order", "date": "14 March" },
        "blocks": [
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Thank you for choosing Harbour Print. Your order of 300 conference programs has been " },
            { "type": "blank", "blank_key": "b1" },
            { "type": "text", "text": " and will ship on Monday." } ] },
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Because the cover uses a " },
            { "type": "blank", "blank_key": "b2" },
            { "type": "text", "text": " paper, please allow one extra day for drying. " },
            { "type": "blank", "blank_key": "b3" } ] },
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "If you need to change the delivery address, reply to this e-mail " },
            { "type": "blank", "blank_key": "b4" },
            { "type": "text", "text": " Friday." } ] } ] } } ] }
```

`private_snapshot`: `{ "schema_version": 1, "materials": [] }`

Câu `b3` (`display_number = 133`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 6, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT",
  "question_kind": "SENTENCE_INSERTION", "blank_key": "b3", "prompt": null,
  "refs": [ { "material_key": "email" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```
Các lựa chọn là câu hoàn chỉnh, nằm trong `attempt_snapshot_options.body`, ví dụ `'We apologize for the delay this may cause.'`.

Câu `b1` (`display_number = 131`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 6, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT",
  "question_kind": "STANDARD", "blank_key": "b1", "prompt": null,
  "refs": [ { "material_key": "email" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

#### 4.3.4. (c) Part 7 đôi: `RICH_TEXT` có `[1]–[4]` và `CHAT`

`public_snapshot` (`expected_question_count = 5`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 7, "group_type": "PASSAGE",
  "materials": [
    { "material_key": "memo", "material_type": "RICH_TEXT", "visibility": "BEFORE_SUBMIT", "position": 1,
      "body": { "document_kind": "MEMO",
        "header": { "from": "Office Services", "to": "All staff", "subject": "Quarterly lunch", "date": "2 May" },
        "blocks": [
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Our quarterly staff lunch will be held in the boardroom on Friday. " },
            { "type": "insert", "position": 1 },
            { "type": "text", "text": " Vegetarian options will be available. " },
            { "type": "insert", "position": 2 } ] },
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Please confirm your attendance by Wednesday. " },
            { "type": "insert", "position": 3 },
            { "type": "text", "text": " Late replies cannot be included in the order. " },
            { "type": "insert", "position": 4 } ] } ] } },
    { "material_key": "chat", "material_type": "CHAT", "visibility": "BEFORE_SUBMIT", "position": 2,
      "body": { "channel": "TEXT_MESSAGE",
        "messages": [
          { "anchor": "t1043", "author": "Mara Lindqvist", "time": "10:43 A.M.", "text": "The caterer just called. They can only deliver at noon." },
          { "anchor": "t1047", "author": "Kenji Watanabe", "time": "10:47 A.M.", "text": "That works. I'll ask reception to hold the boardroom." },
          { "anchor": "t1049", "author": "Mara Lindqvist", "time": "10:49 A.M.", "text": "Perfect. I'll update the memo." } ] } } ] }
```

Câu `INSERTION_POSITION` (`display_number = 176`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 7, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT",
  "question_kind": "INSERTION_POSITION",
  "prompt": "In which of the positions marked [1], [2], [3], and [4] does the following sentence best belong?",
  "insert_sentence": "Lunch will be served at noon.",
  "refs": [ { "material_key": "memo" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```
Lựa chọn: `body` = `'[1]'`, `'[2]'`, `'[3]'`, `'[4]'`.

Câu `INTENT` (`display_number = 177`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 7, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT", "question_kind": "INTENT",
  "prompt": "At 10:47 A.M., what does Mr. Watanabe most likely mean when he writes, \"That works\"?",
  "refs": [ { "material_key": "chat", "anchor": "t1047" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

`answer_key_snapshot` của câu 176:
```json
{ "schema_version": 1, "correct_option_snapshot_id": "6c7d8e9f-3333-4d4e-8f50-000000001761",
  "correct_option_ordinal": 1,
  "explanation": "The sentence about noon follows the first mention of the lunch and matches the delivery time in the chat.",
  "evidence": [ { "material_key": "memo" }, { "material_key": "chat", "anchor": "t1043" } ] }
```

#### 4.3.5. Part 1 và Part 2 (`LABEL_ONLY`, có transcript sau nộp)

Câu P1 (`display_number = 1`, không có nhóm):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 1, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "LABEL_ONLY", "question_kind": "STANDARD",
  "prompt": null, "refs": [],
  "materials": [
    { "material_key": "photo", "material_type": "IMAGE", "visibility": "BEFORE_SUBMIT", "position": 1,
      "media_asset_id": "4d0f2a31-8b52-4c7f-8e3d-6a9b1c2d3e41",
      "body": { "alt_text": "Two workers are carrying a long table through an open doorway." } },
    { "material_key": "audio", "material_type": "AUDIO", "visibility": "BEFORE_SUBMIT", "position": 2,
      "media_asset_id": "7e8f9a0b-4444-4e5f-9a61-000000000001", "body": { "duration_seconds": 19 } } ],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```
`attempt_snapshot_options`: 4 dòng `label` A–D, `body = NULL`.

`answer_key_snapshot`:
```json
{ "schema_version": 1, "correct_option_snapshot_id": "8f9a0b1c-5555-4f60-8b72-000000000013",
  "correct_option_ordinal": 3, "explanation": "Only statement C describes two people moving a table.",
  "evidence": [],
  "option_transcripts": [
    { "ordinal": 1, "label": "A", "speaker": "M-Au", "text": "They're painting a doorway." },
    { "ordinal": 2, "label": "B", "speaker": "M-Au", "text": "They're folding some chairs." },
    { "ordinal": 3, "label": "C", "speaker": "M-Au", "text": "They're carrying a table." },
    { "ordinal": 4, "label": "D", "speaker": "M-Au", "text": "They're closing a window." } ] }
```

Câu P2 có `answer_key_snapshot` với 3 `option_transcripts` (A–C) và `"prompt_transcript": { "speaker": "W-Br", "text": "Where should I leave these invoices?" }`.

### 4.4. Ánh xạ seal (projection → snapshot)

Thứ tự trong một transaction start với nguồn `PRACTICE_SET`:

1. Nhận `SealSource` từ `PracticeEvents.loadSetForSealing` (5.3).
2. Mỗi nhóm → một dòng `attempt_group_snapshots`. `ordinal` tăng theo thứ tự `set_items.position`. Học liệu có `visibility = BEFORE_SUBMIT` vào `public_snapshot.materials`, `AFTER_SUBMIT` vào `private_snapshot.materials`. Giữ nguyên `position` và `body`.
3. Mỗi câu → một dòng `attempt_question_snapshots`:
   - `position = display_number` = thứ tự câu trong lượt (1..N, theo thứ tự nhóm rồi `group_position`).
   - Câu P1, P2: `option_display = LABEL_ONLY`.
4. Mỗi lựa chọn → một dòng `attempt_snapshot_options`:
   - `id = gen_random_uuid()` (sinh ở Java bằng `UUID.randomUUID()` để biết trước khi ghi `answer_key_snapshot`).
   - `label = chr(64+position)`, `source_option_id` = ID trong envelope.
   - `body = NULL` nếu `LABEL_ONLY`, ngược lại là `body` của envelope.
   - `is_correct = NULL`.
5. `answer_key_snapshot.correct_option_snapshot_id` = `id` mới của lựa chọn có `source_option_id = correctSourceOptionId`. Kiểm V-08.
   - Với `LABEL_ONLY`: `option_transcripts` lấy từ `body` envelope. `speaker` lấy từ trường `speaker` của option trong envelope; envelope TOEIC P1/P2 phải có trường này (validator task sau).
6. Slot đáp án: một `INSERT … SELECT` từ `attempt_question_snapshots` của chính attempt, với `version = 0`, `selected_option_id = NULL`.
7. `attempt_media_refs`: mọi `media_asset_id` trong public, private và học liệu cấp câu (`usage_role` theo material). `ON CONFLICT DO NOTHING` vì một file có thể được nhiều câu dùng.
8. `attempts`:
   - `scoring_version = 2`, `snapshot_sealed_at = now`, `last_active_at = now`, `total_count = N`.
   - `source_config` = `{"practiceSetId": "...", "mode": "PRACTICE"}`. Allowlist khóa: `category`, `topicIds`, `questionCount`, `practiceSetId`, `mode`.

Với `GRAMMAR_TOPIC`/`MIXED_GRAMMAR`: không có nhóm. `question_snapshot.category = GRAMMAR`, `topic_id` = chủ điểm gán theo D9, `option_display = TEXT`.

### 4.5. Chấm điểm (một policy `SINGLE_CHOICE_V1`)

```sql
-- Bước 1: ghi is_correct (trong transaction nộp, sau khi đã khóa attempt FOR UPDATE)
UPDATE attempt_answers aa
SET is_correct = (aa.selected_option_id IS NOT NULL
                  AND aa.selected_option_id = (s.answer_key_snapshot->>'correct_option_snapshot_id')::uuid),
    updated_at = :now
FROM attempt_question_snapshots s
WHERE s.attempt_id = aa.attempt_id AND s.position = aa.question_position AND aa.attempt_id = :attemptId;

-- Bước 2: tổng hợp
SELECT count(*) FILTER (WHERE aa.is_correct)                                  AS correct_count,
       count(*) FILTER (WHERE aa.selected_option_id IS NOT NULL)              AS answered_count,
       count(*) FILTER (WHERE aa.is_correct AND s.part_number BETWEEN 1 AND 4) AS listening_correct,
       count(*) FILTER (WHERE aa.is_correct AND s.part_number BETWEEN 5 AND 7) AS reading_correct
FROM attempt_answers aa
JOIN attempt_question_snapshots s ON s.attempt_id = aa.attempt_id AND s.position = aa.question_position
WHERE aa.attempt_id = :attemptId;

-- Theo Part (chỉ đọc, dùng cho result.byPart)
SELECT s.part_number, count(*) AS total, count(*) FILTER (WHERE aa.is_correct) AS correct
FROM attempt_answers aa
JOIN attempt_question_snapshots s ON s.attempt_id = aa.attempt_id AND s.position = aa.question_position
WHERE aa.attempt_id = :attemptId AND s.part_number IS NOT NULL
GROUP BY s.part_number ORDER BY s.part_number;
```

Bỏ trống thì `is_correct = false`, không trừ điểm (đúng ETS). Dòng V13 (`schema_version IS NULL`) đi nhánh chấm cũ (`attempt_snapshot_options.is_correct`) với `scoring_version = 1` và giữ `score` %. Câu hỏi duyệt S4.

### 4.6. Service và facade (chữ ký đích)

Module `results` `[CREATE]` (package `com.masterenglish.api.modules.results`), `allowedDependencies = {"common::events","common::error","common::security","common::api"}`:

```java
public interface AttemptService {
    StartResult start(UUID userId, UUID idempotencyKey, StartAttemptCommand command);     // 201 hoặc 200 replay
    AttemptView get(UUID userId, UUID attemptId);
    AnswerView saveAnswer(UUID userId, UUID attemptId, UUID snapshotId, UUID idempotencyKey,
                          @Nullable UUID selectedOptionId, long expectedVersion);
    AttemptView submit(UUID userId, UUID attemptId);
}
record StartResult(AttemptView view, boolean created) {}
sealed interface StartAttemptCommand permits PracticeSetStart, GrammarTopicStart, MixedGrammarStart {}
record PracticeSetStart(UUID practiceSetId, String mode) implements StartAttemptCommand {}
record GrammarTopicStart(UUID topicId, int questionCount, String mode) implements StartAttemptCommand {}
record MixedGrammarStart(List<UUID> topicIds, int questionCount, String mode) implements StartAttemptCommand {}
```

Lớp nội bộ `results`:
- `SnapshotWriter`: `JdbcTemplate.batchUpdate`, lô 100 dòng, JSONB bind bằng `?::jsonb`.
- `AnswerKeyMapper`: chỉ đọc `answer_key_snapshot` khi `status = SUBMITTED`.
- `PreSubmitViewMapper`: whitelist field, không bao giờ đọc `private_snapshot` hay `answer_key_snapshot`.

Grader nằm trong `results` (CONTRACT §1: một engine chấm).

### 4.7. Cấu trúc 4 tài liệu đích (tạo sau khi brief được duyệt)

| File | Hành động | Mục lục bắt buộc |
|---|---|---|
| `docs/m05/00-toeic-research-and-gap-report.md` | CREATE | 1. Phạm vi và quy ước độ tin cậy. 2. Cấu trúc ETS P1–P7 (có nguồn [1][2]). 3. Chấm điểm và quy đổi. 4. Chế độ làm bài. 5. Sau khi nộp. 6. Media (công thức bitrate × thời lượng). 7. Tổng kết A (đã kiểm chứng) và B (khác biệt nền tảng). 8. Bảng gap SRS v5.0 ↔ ETS. 9. Phân loại YAGNI. 10. Nguồn. Chép nội dung từ artifact "Rà soát M05 TOEIC", bỏ phần trình bày HTML. |
| `docs/m05/01-adrs-07-to-13.md` | CREATE | Mỗi ADR một mục: Bối cảnh, Quyết định (lấy từ mục 3 và 4 của brief này), Hệ quả, Trạng thái. ADR-07 ↔ R1 + 4.2. ADR-08 ↔ R1, 4.1.6. ADR-09 ↔ 5.1, 6.3. ADR-10 ↔ R3, R4. ADR-11 ↔ 4.1.1, 4.5. ADR-12 ↔ 5.3. ADR-13 ↔ 4.1.9, 7.3. Phụ lục "SRS v5.0 Addendum": A-01 (`SUBMITTED`), A-02 (CAS), A-03 (NFR-SEC-04 theo `visibility`), A-04 (P3/4 lưu đáp án theo câu), A-05 (`blank_key` ổn định), A-06 (`practice_sets` Full Test). |
| `docs/m05/02-snapshot-json-schemas-v1.md` | CREATE | 1. Quy tắc phiên bản. 2. Quy tắc nguồn sự thật (bảng R1). 3. JSON Schema 4.2 nguyên văn. 4. Ràng buộc V-01..V-12. 5. Ví dụ 4.3 nguyên văn. 6. Ánh xạ seal 4.4. 7. Quy tắc render (R6, cấm `dangerouslySetInnerHTML`). 8. DTO API 5.1 (camelCase; `body` của material đi nguyên văn snake_case). |
| `docs/practice/CONTRACT.md` | MODIFY | Thêm mục đầu "Lịch sử thay đổi" (ngày, mục bị thay, lý do, link brief T0). Sửa: D8 → D8 v2 (R7); D6 mở cho TOEIC (4.1.8); §3.2 thêm `practice-sets`, đổi `STALE_ANSWER_VERSION` → `VERSION_CONFLICT`; §3.4 DTO v1; §4.3 bảng mã 5.4; §5.2 khóa attempt → slot (6.4) và idempotency PUT; §6.2 trỏ sang `02-…md`; §9 thêm idempotency PUT; §10 thay bằng 4.1; §11.1 ghi nhận V13 đã lên `main`; §14 thêm lệch DD (R1, 4.1.4, 4.1.5, 4.1.8). Không xóa nội dung cũ: đánh dấu `~~…~~ (thay bởi T0, 26/11/2026)`. |

---

## 5. Hợp đồng giao tiếp

### 5.1. HTTP API v1 (`results`)

Chung: role `LEARNER` (401 nếu chưa đăng nhập, 403 nếu thiếu role). `POST`/`PUT` cần `X-XSRF-TOKEN` khớp cookie `XSRF-TOKEN`. `X-Request-Id` là tùy chọn và được trả lại. Lỗi là `application/problem+json` với `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, `errors`, cộng metadata ở 5.4.

#### `POST /api/v1/attempts`

| Header | Bắt buộc | Định dạng |
|---|---|---|
| `Idempotency-Key` | Có | UUID. Thiếu hoặc sai định dạng → 400 `VALIDATION_ERROR` (`errors[0].field = "Idempotency-Key"`). |
| `X-XSRF-TOKEN` | Có | — |

Body (một trong ba):
```json
{ "source": { "type": "PRACTICE_SET", "practiceSetId": "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b" }, "mode": "PRACTICE" }
```
```json
{ "source": { "type": "GRAMMAR_TOPIC", "topicId": "2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d" }, "questionCount": 10 }
```
```json
{ "source": { "type": "MIXED_GRAMMAR", "topicIds": ["2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d", "3b4c5d6e-7f80-4b9c-8d1e-2f3a4b5c6d7e"] }, "questionCount": 20 }
```

- `mode`: tùy chọn, mặc định `"PRACTICE"`. `"EXAM"` → 400.
- `questionCount`: **cấm** với `PRACTICE_SET` (400), **bắt buộc** 1–100 với GRAMMAR.
- Hash idempotency: SHA-256 của JSON chuẩn hóa (khóa sắp xếp, UUID chữ thường, `topicIds` sắp xếp, `mode` luôn có mặt sau khi áp mặc định). Bỏ `mode` và gửi `"PRACTICE"` cho ra cùng hash.

| Kết quả | HTTP | `code` |
|---|---|---|
| Tạo mới | 201 + `Location: /api/v1/attempts/{id}` | — |
| Replay cùng key, cùng hash | 200 (bài cũ, kể cả khi set đã archive) | — |
| Cùng key, khác hash | 409 | `IDEMPOTENCY_CONFLICT` |
| Set không tồn tại, chưa publish, hoặc archive | 404 | `RESOURCE_NOT_FOUND` |
| Câu của set không còn đủ điều kiện | 409 | `INSUFFICIENT_QUESTIONS` (`requestedCount` = số câu của set, `availableCount`) hoặc `NO_ELIGIBLE_QUESTIONS` |
| Đã có 5 bài mở (sau lazy cleanup) | 409 | `OPEN_ATTEMPT_LIMIT_REACHED` (`openAttemptCount`, `maxOpenAttempts`) |
| Body sai | 400 | `VALIDATION_ERROR` |

#### `GET /api/v1/attempts/{attemptId}` → `AttemptView`

Ví dụ trước khi nộp (rút gọn: một câu P1, một nhóm P3 và một câu của nhóm):
```json
{
  "id": "7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50",
  "category": "TOEIC", "attemptMode": "PRACTICE", "status": "IN_PROGRESS",
  "source": { "type": "PRACTICE_SET", "practiceSetId": "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b" },
  "totalCount": 200, "version": 3, "deadlineAt": null,
  "startedAt": "2026-11-26T02:10:00Z", "lastActiveAt": "2026-11-26T02:14:31Z",
  "groups": [
    { "groupSnapshotId": "0a1b2c3d-1111-4a2b-8c3d-000000000041", "ordinal": 3, "partNumber": 3,
      "groupType": "CONVERSATION", "title": null,
      "materials": [
        { "materialKey": "audio", "materialType": "AUDIO", "position": 1,
          "mediaUrl": "https://<r2-presigned-url>", "mediaUrlExpiresAt": "2026-11-26T02:44:31Z",
          "body": { "duration_seconds": 41 } },
        { "materialKey": "graphic", "materialType": "TABLE", "position": 2, "mediaUrl": null, "mediaUrlExpiresAt": null,
          "body": { "title": "Training rooms - Thursday", "headers": ["Room", "Available from"],
                    "rows": [["Room A", "9:00 A.M."], ["Room B", "Closed for repair"], ["Room C", "11:00 A.M."], ["Room D", "2:00 P.M."]] } } ] } ],
  "questions": [
    { "snapshotId": "9a0b1c2d-6666-4a71-8c83-000000000001", "ordinal": 1, "displayNumber": 1, "groupSnapshotId": null,
      "partNumber": 1, "questionKind": "STANDARD", "questionTypeCode": "SINGLE_CHOICE", "optionDisplay": "LABEL_ONLY",
      "prompt": null, "blankKey": null, "insertSentence": null, "refs": [],
      "materials": [
        { "materialKey": "photo", "materialType": "IMAGE", "position": 1, "mediaUrl": "https://<r2-presigned-url>",
          "mediaUrlExpiresAt": "2026-11-26T02:44:31Z", "body": { "alt_text": "Two workers are carrying a long table through an open doorway." } },
        { "materialKey": "audio", "materialType": "AUDIO", "position": 2, "mediaUrl": "https://<r2-presigned-url>",
          "mediaUrlExpiresAt": "2026-11-26T02:44:31Z", "body": { "duration_seconds": 19 } } ],
      "options": [ { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000011", "label": "A", "body": null },
                   { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000012", "label": "B", "body": null },
                   { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000013", "label": "C", "body": null },
                   { "optionId": "8f9a0b1c-5555-4f60-8b72-000000000014", "label": "D", "body": null } ],
      "selectedOptionId": null, "answerVersion": 0 },
    { "snapshotId": "9a0b1c2d-6666-4a71-8c83-000000000042", "ordinal": 42, "displayNumber": 42,
      "groupSnapshotId": "0a1b2c3d-1111-4a2b-8c3d-000000000041", "partNumber": 3, "questionKind": "GRAPHIC",
      "questionTypeCode": "SINGLE_CHOICE", "optionDisplay": "TEXT",
      "prompt": "Look at the graphic. Which room will the workshop most likely use?",
      "blankKey": null, "insertSentence": null, "refs": [ { "material_key": "graphic" } ], "materials": [],
      "options": [ { "optionId": "5b6c7d8e-2222-4c3d-9e4f-000000000421", "label": "A", "body": "Room A" },
                   { "optionId": "5b6c7d8e-2222-4c3d-9e4f-000000000422", "label": "B", "body": "Room B" },
                   { "optionId": "5b6c7d8e-2222-4c3d-9e4f-000000000423", "label": "C", "body": "Room C" },
                   { "optionId": "5b6c7d8e-2222-4c3d-9e4f-000000000424", "label": "D", "body": "Room D" } ],
      "selectedOptionId": "5b6c7d8e-2222-4c3d-9e4f-000000000424", "answerVersion": 2 } ],
  "result": null
}
```

Trước khi nộp, response **không có**: `isCorrect`, `correctOptionId`, `explanation`, `evidence`, `optionTranscripts`, `promptTranscript`, `reviewMaterials`, học liệu `AFTER_SUBMIT`, `score`, `meta`, `sourceOptionId`.

Sau khi nộp, response có thêm:
- `"result": { "correctCount": 142, "answeredCount": 196, "totalCount": 200, "listeningCorrect": 74, "readingCorrect": 68, "byPart": [ { "partNumber": 1, "correct": 5, "total": 6 } ], "submittedAt": "…", "effectiveFinishedAt": "…", "submitReason": "MANUAL", "durationSeconds": 6920 }`. Mảng `byPart` có một phần tử cho mỗi Part 1–7.
- `groups[].reviewMaterials`: học liệu từ `private_snapshot`.
- `questions[].review`: `{ "isCorrect": true, "correctOptionId": "…", "explanation": "…", "evidence": [ … ], "optionTranscripts": [ … ] | null, "promptTranscript": { … } | null }`.

Không có trường nào tên `score` (ADR-11). Lượt `ABANDONED`/`EXPIRED`: hình dạng như trước khi nộp, `result = null`.

Không phải chủ bài → 404 `RESOURCE_NOT_FOUND`.

#### `PUT /api/v1/attempts/{attemptId}/answers/{snapshotId}`

Header `Idempotency-Key` (UUID, bắt buộc) và `X-XSRF-TOKEN`. Body:
```json
{ "selectedOptionId": "5b6c7d8e-2222-4c3d-9e4f-000000000424", "expectedVersion": 2 }
```
`selectedOptionId: null` nghĩa là bỏ chọn.

200 `AnswerView`:
```json
{ "snapshotId": "9a0b1c2d-6666-4a71-8c83-000000000042", "selectedOptionId": "5b6c7d8e-2222-4c3d-9e4f-000000000424",
  "answerVersion": 3, "answeredAt": "2026-11-26T02:15:02Z" }
```
CONTRACT §3.4 có `attemptVersion`, nhưng frontend không cần nên bỏ.

| Điều kiện (kiểm theo thứ tự) | HTTP | `code` / metadata |
|---|---|---|
| Attempt không tồn tại hoặc không thuộc người gọi | 404 | `RESOURCE_NOT_FOUND` |
| `status ≠ IN_PROGRESS` | 409 | `ATTEMPT_NOT_OPEN` (`status`) |
| `snapshotId` không thuộc attempt | 404 | `RESOURCE_NOT_FOUND` |
| `selectedOptionId` khác null và không thuộc snapshot | 400 | `OPTION_NOT_IN_SNAPSHOT` |
| `Idempotency-Key = last_request_id` **và** `selected_option_id` hiện tại = body **và** `version = expectedVersion + 1` | 200 | Replay: trả trạng thái hiện tại, không tăng version |
| `Idempotency-Key = last_request_id` nhưng điều kiện trên sai | 409 | `IDEMPOTENCY_CONFLICT` |
| `version ≠ expectedVersion` | 409 | `VERSION_CONFLICT` (`currentVersion`, `currentSelectedOptionId`) |
| Hợp lệ | 200 | `version + 1`, `last_request_id = key`, `answered_at = now` hoặc `NULL`, `attempts.last_active_at = now` |

Ví dụ 409:
```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "The answer was changed by another request.",
  "instance": "/api/v1/attempts/7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50/answers/9a0b1c2d-6666-4a71-8c83-000000000042",
  "code": "VERSION_CONFLICT", "currentVersion": 3, "currentSelectedOptionId": null,
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", "errors": [] }
```

#### `POST /api/v1/attempts/{attemptId}/submit`

Không có body, không cần `Idempotency-Key` (idempotent theo trạng thái).

| Trạng thái | Kết quả |
|---|---|
| `IN_PROGRESS` | Chấm (4.5), ghi kết quả, phát `AttemptSubmitted`, trả 200 `AttemptView` đã nộp |
| `SUBMITTED` | 200, trả đúng kết quả đã ghi. Không chấm lại, không phát event lần hai |
| `ABANDONED`, `EXPIRED` | 409 `ATTEMPT_NOT_OPEN` (`status`) |
| Không phải chủ | 404 |

### 5.2. HTTP API v1 (`practice`)

`GET /api/v1/practice-sets?category=TOEIC&partNumber=3&page=0&size=20` → `PageResponse<PracticeSetSummary>`:
- `category` bắt buộc.
- `partNumber` tùy chọn. Giá trị `0` nghĩa là bộ nhiều Part (`part_number IS NULL`).
- Mặc định `size = 20`, tối đa 100, sắp theo `title`.

```json
{ "items": [ { "id": "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b", "category": "TOEIC", "title": "Part 3 - Set 01",
               "partNumber": 3, "questionCount": 39, "groupCount": 13, "timeLimitSeconds": null } ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1, "hasNext": false }
```

`GET /api/v1/practice-sets/{practiceSetId}` → `PracticeSetSummary` và `"partBreakdown": [ { "partNumber": 3, "questionCount": 39 } ]`. Không trả câu hỏi.

Lỗi: 400 (tham số sai), 404 (set không hiển thị).

### 5.3. Hợp đồng liên module (`common/events`)

`PracticeEvents` `[MODIFY]` thêm phương thức:

```java
public SealSource loadSetForSealing(UUID practiceSetId) { … events.request(reply -> new SetSealingRequested(practiceSetId, reply)); }

public record SetSealingRequested(UUID practiceSetId, EventReply<SealSource> reply) {}
public record SealSource(UUID practiceSetId, UUID sourceRevisionId, String category, Integer partNumber,
                         Integer timeLimitSeconds, List<SealGroup> groups, List<SealQuestion> questions) {}
public record SealGroup(UUID groupId, UUID sourceRevisionId, int ordinal, int partNumber, String groupType,
                        String title, int expectedQuestionCount, List<SealMaterial> materials) {}
public record SealMaterial(String materialKey, String materialType, String visibility, int position,
                           UUID mediaAssetId, String bodyJson) {}
public record SealQuestion(UUID questionId, UUID sourceRevisionId, UUID groupId, Integer groupPosition,
                           Integer partNumber, String questionTypeCode, String practiceMode, String questionKind,
                           String blankKey, String prompt, String insertSentence, List<SealRef> refs,
                           List<SealMaterial> materials, List<SealOption> options, UUID correctSourceOptionId,
                           String explanation, List<SealRef> evidence, SealSpoken promptTranscript,
                           Set<UUID> topicIds, Set<UUID> wordSenseIds) {}
public record SealOption(UUID sourceOptionId, int position, String body, String speaker) {}
public record SealRef(String materialKey, String anchor) {}
public record SealSpoken(String speaker, String text) {}
```

- Handler trong `practice` chạy `@EventListener` đồng bộ (ARCHITECTURE.md:63-69).
- Handler khóa nguồn bằng `FOR SHARE` theo thứ tự `question_id`.
- Set không hiển thị thì ném `BusinessException(RESOURCE_NOT_FOUND)`. Câu không đủ điều kiện thì ném `INSUFFICIENT_QUESTIONS`/`NO_ELIGIBLE_QUESTIONS`.

`ResultsEvents` `[CREATE]` (`common/events/ResultsEvents.java`), event async qua `event_publication`:

```java
public record AttemptSubmitted(UUID attemptId, UUID userId, String category, String sourceType,
                               Instant effectiveFinishedAt, List<QuestionOutcome> outcomes) {}
public record QuestionOutcome(UUID questionId, UUID snapshotId, Integer partNumber, String outcome) {}
// outcome ∈ {"CORRECT","INCORRECT","UNANSWERED"}
```

- Phát bằng `ApplicationEventPublisher` trong transaction nộp, **chỉ khi chuyển `IN_PROGRESS → SUBMITTED`**.
- ADR-12 gọi là `AttemptSubmittedEvent`. Tên record theo quy ước repo, không có hậu tố `Event` (xem `ContentEvents.RevisionPublished`).
- Listener (`@ApplicationModuleListener`) đến cùng D13 và phải idempotent theo `(attempt_id, question_id)`.
- Kích thước: 200 outcome × ~120 byte ≈ 24 KB một dòng `event_publication`.

`IdentityEvents.verifyAndLockUser(userId)` và `GrammarEvents.isVisibleTopic(topicId)` dùng nguyên trạng.

### 5.4. Bảng mã lỗi chốt (thay CONTRACT §4.3)

| `code` | HTTP | Metadata (kiểu, R8) | Trạng thái |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | `errors` | Có sẵn |
| `RESOURCE_NOT_FOUND` | 404 | — | Có sẵn |
| `NO_ELIGIBLE_QUESTIONS` | 409 | `requestedCount` COUNT, `availableCount` COUNT | Có sẵn |
| `INSUFFICIENT_QUESTIONS` | 409 | `requestedCount` COUNT, `availableCount` COUNT | Có sẵn |
| `IDEMPOTENCY_CONFLICT` | 409 | — | `[CREATE]` |
| `OPEN_ATTEMPT_LIMIT_REACHED` | 409 | `openAttemptCount` COUNT, `maxOpenAttempts` COUNT | `[CREATE]` |
| `VERSION_CONFLICT` | 409 | `currentVersion` COUNT, `currentSelectedOptionId` UUID_OR_NULL | `[CREATE]` (thay `STALE_ANSWER_VERSION`) |
| `ATTEMPT_NOT_OPEN` | 409 | `status` ENUM | `[CREATE]` |
| `OPTION_NOT_IN_SNAPSHOT` | 400 | — | `[CREATE]` |

Mã `CONFLICT` có sẵn vẫn dùng cho `ObjectOptimisticLockingFailureException` (test `staleVersionReturnsConflictWithoutExposingPersistenceDetails`), không đổi.

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn đồng thời

### 6.1. Lộ đề

| Rủi ro | Biện pháp | Test (11.2) |
|---|---|---|
| Transcript P1–P4 trả ra trước khi nộp | Transcript chỉ nằm ở `private_snapshot` hoặc `answer_key_snapshot`. `PreSubmitViewMapper` không có đường đọc hai cột này. | `PreSubmitLeakTests.inProgressViewHasNoTranscriptText` |
| Lựa chọn P1/P2 lộ qua `body` | `attempt_snapshot_options.body = NULL` khi `LABEL_ONLY` | `SealMappingTests.labelOnlyOptionsHaveNullBody` |
| Đáp án lộ qua ID lựa chọn | ID sinh ngẫu nhiên theo attempt. `source_option_id` không bao giờ vào DTO. | `PreSubmitLeakTests.viewNeverContainsSourceOptionIds` |
| `is_correct` có trước khi nộp | Cột là `NULL` đến lúc nộp. DTO không có trường này. | `AutosaveTests.isCorrectStaysNullUntilSubmit` |
| Tên object key gợi ý đáp án (ví dụ `p1-q3-answer-c.mp3`) | Quy tắc: `object_key` của media `CONTENT` không chứa thông tin nội dung, dạng `content/{uuid}.{ext}`. Kiểm ở luồng upload của `content` (task media). | `MediaKeyPolicyTests` (task media) |
| Media sau nộp (giải thích) được ký trước | Chỉ ký URL cho học liệu `BEFORE_SUBMIT` khi `IN_PROGRESS` | `PreSubmitLeakTests.noSignedUrlForAfterSubmitMedia` |
| Lộ thứ tự sắp đáp án | TOEIC `option_order = FIXED`, không xáo, nên không có tín hiệu nào để lộ | — |

Kiểm thử kiểu hộp đen: serialize `AttemptView` trước nộp thành chuỗi JSON. Assert không chứa các khóa `isCorrect`, `correctOptionId`, `explanation`, `evidence`, `optionTranscripts`, `promptTranscript`, `reviewMaterials`, `meta`, `sourceOptionId`, `score`. Assert không chứa **chuỗi văn bản** của transcript và lời giải trong fixture.

### 6.2. IDOR và CSRF

- Mọi truy vấn attempt có `WHERE id = :attemptId AND user_id = :userId` (dùng `attempts_c1 UNIQUE (user_id, id)`). Không có thì 404, không phân biệt "không tồn tại" và "không phải của bạn".
- `snapshotId` được tra trong `attempt_question_snapshots WHERE attempt_id = :attemptId AND id = :snapshotId`. FK V13 `attempt_answers_c3` chặn lựa chọn thuộc câu khác ở tầng DB.
- `POST`/`PUT` đi qua cấu hình CSRF hiện có. Header `Idempotency-Key` đã nằm trong CORS allowlist (`SecurityConfiguration.java:134`). Không cần đổi cấu hình bảo mật.

### 6.3. Idempotency

| Thao tác | Khóa | Lưu ở | Hành vi |
|---|---|---|---|
| Start | `(user_id, request_id)` | `attempts_c2` (V13) | Cùng hash: 200 bài cũ. Khác hash: `IDEMPOTENCY_CONFLICT`. Hai request đồng thời: request sau chờ khóa user rồi thấy dòng đã có → replay. |
| Lưu đáp án | `last_request_id` trên slot | `attempt_answers.last_request_id` | Bảng điều kiện ở 5.1. Chỉ nhớ **key gần nhất** của từng slot. Đủ cho retry mạng, vì retry luôn gửi key vừa dùng. |
| Nộp | Trạng thái | `attempts.status` | Lần hai trả kết quả cũ. |

### 6.4. Thứ tự khóa và race condition

| Luồng | Thứ tự khóa |
|---|---|
| Start | `users` (qua `verifyAndLockUser`) → các `attempts` mở của người dùng (`FOR UPDATE`, tăng dần theo `id`, khi lazy cleanup) → nguồn practice (`FOR SHARE`, tăng dần theo `question_id`) |
| Autosave | `attempts` (`FOR UPDATE`) → slot `attempt_answers` (`FOR UPDATE`) |
| Submit | `attempts` (`FOR UPDATE`) → các slot (qua `UPDATE … FROM`) |

Autosave và submit **không** khóa `users` (lệch CONTRACT §5.2, DR-33). Không có deadlock vì:
1. Không luồng nào khóa `users` **sau khi** đã giữ khóa `attempts`.
2. Start khóa `users` trước rồi mới tới `attempts`. Nếu autosave đang giữ attempt, start chờ autosave commit rồi tiếp tục.

Race đã xét:

| Race | Kết quả |
|---|---|
| Autosave đồng thời với submit | Bên nào khóa attempt trước sẽ thắng. Autosave sau submit → `ATTEMPT_NOT_OPEN`. Submit sau autosave → đáp án mới được chấm. |
| Hai tab cùng `expectedVersion` | Một 200, một `VERSION_CONFLICT` kèm trạng thái hiện tại |
| Hai start cùng key | Một tạo, một replay (khóa user tuần tự hóa) |
| Start đồng thời khi đã có 4 bài mở | Một thành công, một `OPEN_ATTEMPT_LIMIT_REACHED` |
| Publish revision mới trong lúc start | `FOR SHARE` trên nguồn: publish chờ start commit, hoặc start đọc bản đã commit trước đó. Snapshot luôn nhất quán với một revision. |

### 6.5. Bất biến

Snapshot là append-only, bảo vệ bằng trigger (4.1.9). Slot đáp án chỉ ghi khi `IN_PROGRESS` (service). Không xóa attempt (FK `RESTRICT`).

---

## 7. Ranh giới Spring Modulith và quy hoạch tài nguyên (`t3.small`)

### 7.1. DAG phụ thuộc (đích)

```
common (api, entity, error, events, pagination, security, storage, time, validation)
  ▲            ▲            ▲            ▲            ▲            ▲
identity    content      grammar      practice      results      learning   (admin, vocabulary giữ nguyên)
```

- Mọi mũi tên nghiệp vụ đi qua `common::events`. Không module nghiệp vụ nào import package của module nghiệp vụ khác.
- Luồng gọi đồng bộ: `results → PracticeEvents` (handler trong `practice`), `results → IdentityEvents`, `results → GrammarEvents`, `practice → ContentEvents` (projection).
- Luồng async: `results → ResultsEvents.AttemptSubmitted → learning` (khi D13 bật).
- Không có vòng: `practice` không gọi `results`. `learning` không gọi `results`.
- `ArchitectureTests.java` `[MODIFY]` (task tách module): thêm `assertThat(modules.getModuleByName("results")).isPresent();` và giữ `modules.verify()`.

### 7.2. Quy tắc FK `UUID`

- JPA entity trong `results` lưu `questionId`, `groupId`, `mediaId`, `userId`, `topicId` dạng `UUID`. **Cấm** `@ManyToOne` hay `@OneToMany` sang entity của module khác.
- FK ở tầng DB (ví dụ `attempts.user_id → users`, `attempt_media_refs.media_id → media_assets`) được giữ, theo tiền lệ V13.
- Quan hệ trong cùng module (`attempts` ↔ snapshot ↔ option ↔ answer) cũng dùng ID nguyên thủy kèm repository. Không dùng collection mapping, để mọi lần đọc đều do truy vấn quyết định, không lazy-load ngầm.

### 7.3. Ước lượng cho start một Full Test (200 câu)

| Hạng mục | Số dòng | Round-trip (batch 100) |
|---|---:|---:|
| Khóa user, tra idempotency, lazy cleanup, đếm bài mở | — | 4–5 |
| `loadSetForSealing` (set, items, nhóm, học liệu, câu, lựa chọn, `FOR SHARE`) | đọc ≈ 1.100 | 6–7 |
| `attempts` | 1 | 1 |
| `attempt_group_snapshots` | 42 | 1 |
| `attempt_question_snapshots` | 200 | 2 |
| `attempt_snapshot_options` (6×4 + 25×3 + 169×4) | 775 | 8 |
| Slot đáp án (`INSERT … SELECT`) | 200 | 1 |
| `attempt_media_refs` (`ON CONFLICT DO NOTHING`) | ≤ 70 (ước lượng; tùy số file mỗi set) | 1 |
| **Tổng** | ≈ 1.290 dòng ghi | **≈ 25** |

- `INSERT … SELECT` từ bảng projection của `practice` sang bảng của `results` **bị cấm** vì vượt ranh giới module. ADR-13 được hiện thực bằng JDBC batch. `INSERT … SELECT` chỉ dùng trong cùng module (slot đáp án).
- Lượng ghi: JSON khoảng 1–2 KB mỗi câu, 5–20 KB mỗi nhóm P7, nên ước lượng 0,5–1 MB mỗi Full Test. Đây là ước lượng, chưa đo.
- Thời gian giữ một kết nối: **chưa đo**. ADR-13 yêu cầu benchmark (11.5) trước khi chốt SLA start. Mục tiêu đề xuất: p95 < 800 ms cho Full Test, < 200 ms cho Part lẻ 30 câu.
- Bộ nhớ JVM: `SealSource` của Full Test ≈ 1–2 MB đối tượng. Không đáng kể so với heap ≈ 460 MiB (768 MiB × 60%, giả định từ preamble).

Các lượt khác:
- `GET` Full Test: 4 truy vấn (attempt, nhóm, câu + lựa chọn, slot) cộng ký URL (CPU, không gọi mạng). Payload dự kiến 200–400 KB JSON. Đề xuất bật `server.compression` ở task API. Chưa đo.
- Autosave: 4 câu lệnh (khóa attempt, khóa slot, update slot, update `last_active_at`), một transaction ngắn.
- Submit: khóa attempt, `UPDATE … FROM` (200 dòng), `SELECT` tổng hợp, `UPDATE attempts`, `INSERT event_publication`. 5 câu lệnh. Mục tiêu < 500 ms theo NFR.

### 7.4. Pool 5 kết nối và listener async

- Mỗi request giữ tối đa 1 kết nối. Facade event chạy **đồng bộ trong cùng thread và cùng transaction**, nên không mượn thêm kết nối.
- Khi listener `@ApplicationModuleListener` đầu tiên ra đời (D13), đặt `spring.task.execution.pool.max-size=2` và `queue-capacity=100`. Như vậy luôn còn ≥ 3 kết nối cho HTTP. Đây là việc của task D13, ghi vào ADR-12.
- Cấu hình Hikari và `mem_limit` không nằm trong repo (DR-23). Số liệu trên dùng giả định của preamble.

---

## 8. Edge Cases & Failure Modes Matrix

| # | Tình huống | Nguyên nhân | Hành vi hệ thống | HTTP / DB / Event |
|---|---|---|---|---|
| 1 | Retry start sau timeout | Mất response | Tìm thấy `(user_id, request_id)` cùng hash, trả bài cũ | 200, không ghi dòng mới |
| 2 | Cùng key, đổi `practiceSetId` | Lỗi client | Hash khác | 409 `IDEMPOTENCY_CONFLICT` |
| 3 | Hai tab sửa cùng câu | Cùng `expectedVersion` | Một bên thắng | 200 và 409 `VERSION_CONFLICT` (`currentVersion`, `currentSelectedOptionId`) |
| 4 | Retry PUT sau khi request đầu đã ghi | Mất response | Khớp `last_request_id`, lựa chọn và version | 200 replay, `version` không đổi |
| 5 | Autosave cũ đến sau autosave mới | Mạng đảo thứ tự | `version ≠ expectedVersion` | 409 `VERSION_CONFLICT`, không ghi đè |
| 6 | Autosave trong lúc nộp | Hai request song song | Khóa attempt tuần tự hóa | Autosave sau: 409 `ATTEMPT_NOT_OPEN` (`status = SUBMITTED`) |
| 7 | Bấm nộp hai lần | Double click hoặc retry | Lần hai đọc kết quả đã ghi | 200 cả hai lần, 1 dòng `event_publication` |
| 8 | Admin publish revision mới hoặc archive set khi đang làm | Vận hành nội dung | Snapshot không đổi. Resume và nộp bình thường. Start lại cùng key vẫn trả bài cũ. | 200 |
| 9 | Media bị archive khi còn trong bài cũ | Dọn nội dung | FK `attempt_media_refs` chặn xóa dòng `media_assets`. `ARCHIVED` vẫn ký được URL vì object chưa bị xóa. | Xóa: lỗi FK. GET: 200 |
| 10 | URL ký hết hạn giữa bài (Full Test 2 giờ) | TTL | R2 trả 403. Frontend gọi lại `GET /attempts/{id}` để lấy URL mới. | 200 với `mediaUrlExpiresAt` mới |
| 11 | Mở bài thứ 6 | Giới hạn | Lazy cleanup trước, đếm sau | 409 `OPEN_ATTEMPT_LIMIT_REACHED` (`openAttemptCount = 5`, `maxOpenAttempts = 5`) |
| 12 | Bài bỏ 8 ngày rồi quay lại làm | Chưa có scheduler | Vẫn `IN_PROGRESS` cho tới lần start kế tiếp, nên vẫn lưu được | 200. Lần start sau: bài này thành `ABANDONED` |
| 13 | `attemptId` của người khác | IDOR | Lọc theo `user_id` | 404 |
| 14 | `snapshotId` của attempt khác | IDOR | Tra theo `(attempt_id, id)` | 404 |
| 15 | `selectedOptionId` của câu khác | Client sai | Không khớp `(attempt_id, question_position, id)` | 400 `OPTION_NOT_IN_SNAPSHOT` |
| 16 | Bỏ chọn | Người học | `selected_option_id = NULL`, `answered_at = NULL`, `version + 1` | 200 |
| 17 | Set có câu bị thu hồi sau khi publish set | Vận hành | `loadSetForSealing` đếm câu đủ điều kiện | 409 `INSUFFICIENT_QUESTIONS` (`requestedCount = 200`, `availableCount = 199`) |
| 18 | `answer_key` trỏ lựa chọn không có | Dữ liệu nguồn hỏng | Kiểm V-08 trước INSERT, rollback | 500 `INTERNAL_ERROR`, không ghi gì, log `traceId` |
| 19 | Attempt V13 đang làm dở khi deploy V14 | Dữ liệu cũ | Mapper và grader nhánh legacy (`schema_version IS NULL`) | 200, `scoring_version = 1` |
| 20 | Listener D13 sập giữa chừng | At-least-once | Spring Modulith giao lại. Listener upsert theo `(attempt_id, question_id)`. | Không đếm đúp |
| 21 | Gửi `mode: "EXAM"` | Client đi trước MVP | Từ chối | 400 `VALIDATION_ERROR` (`field = "mode"`) |
| 22 | Header email có `Tên <a@b>` | Nhập liệu | Validator chặn (V-11) | Publish 400 `VALIDATION_ERROR` |
| 23 | Câu nằm trong 2 nhóm hoặc thiếu `blank_key` P6 | Nhập liệu | CHECK DD trên `questions` và V-03 | Publish rollback |
| 24 | JSON snapshot > 1 MiB | Tài liệu quá lớn | CHECK `octet_length`, nhưng validator publish chặn trước (`group_materials.body` ≤ 256 KiB) | Publish 400 |

---

## 9. Kế hoạch chỉnh sửa file

### 9.1. Phạm vi T0

| Đường dẫn | Hành động | Nội dung |
|---|---|---|
| `docs/m05/tasks/T0-brief-contract-freeze.md` | CREATE | File này |
| `docs/m05/00-toeic-research-and-gap-report.md` | CREATE (sau duyệt) | Mục 4.7 |
| `docs/m05/01-adrs-07-to-13.md` | CREATE (sau duyệt) | Mục 4.7 |
| `docs/m05/02-snapshot-json-schemas-v1.md` | CREATE (sau duyệt) | Mục 4.7 |
| `docs/practice/CONTRACT.md` | MODIFY (sau duyệt) | Mục 4.7, giữ lịch sử |
| `src/main/resources/db/migration/V1__…` → `V13__…` | DO NOT TOUCH | D8 v2 |
| `src/main/java/**`, `src/test/java/**` | DO NOT TOUCH | READ-ONLY |
| `../masterenglish-web/**` | DO NOT TOUCH | READ-ONLY |
| `docs/database/proposals/approved-baseline.sql`, `DATA_DICTIONARY.md`, `SCHEMA_CONTRACTS.md` | DO NOT TOUCH | Lệch DD được ghi ở CONTRACT §14 và `01-adrs` |
| `docs/grammar/CONTRACT.md` | DO NOT TOUCH | Đã trỏ sang CONTRACT practice |

### 9.2. Dự báo file cho task triển khai (tham khảo, không làm trong T0)

| Đường dẫn | Hành động |
|---|---|
| `src/main/resources/db/migration/V14__practice_results_expand.sql` | CREATE |
| `src/main/java/com/masterenglish/api/modules/results/**` | CREATE |
| `src/main/java/com/masterenglish/api/modules/practice/{entity,repository,service,controller}/Attempt*` | MODIFY, chuyển sang `results` |
| `src/main/java/com/masterenglish/api/common/error/{ErrorCode,BusinessException}.java` | MODIFY (R8) |
| `src/main/java/com/masterenglish/api/common/events/{PracticeEvents,ResultsEvents}.java` | MODIFY / CREATE |
| `src/main/java/com/masterenglish/api/modules/practice/service/impl/{QuestionSnapshotValidator,QuestionProjectionListener}.java` | MODIFY (TOEIC) |
| `src/test/java/com/masterenglish/api/ArchitectureTests.java` | MODIFY |

---

## 10. Lộ trình commit (T0)

Commit chỉ khi người dùng yêu cầu rõ (AGENTS.md). Mỗi bước chỉ đụng Markdown, nên không có bước build nào bị ảnh hưởng. Vẫn chạy `.\gradlew.bat spotlessCheck` để chắc Spotless không quét `docs/`.

| Bước | Nội dung | Tiêu đề commit đề xuất | Kiểm tra trước commit |
|---|---|---|---|
| 1 | Brief T0 | `docs(m05): thêm brief T0 chốt hợp đồng M05` | 11.1 C-01, C-04 |
| 2 | `00-toeic-research-and-gap-report.md` | `docs(m05): thêm báo cáo khảo sát TOEIC và gap SRS` | C-04 |
| 3 | `02-snapshot-json-schemas-v1.md` | `docs(m05): khóa JSON Schema snapshot v1` | C-01, C-02, C-03 |
| 4 | `01-adrs-07-to-13.md` | `docs(m05): ghi ADR-07 đến ADR-13 và addendum SRS` | C-04, C-05 |
| 5 | `CONTRACT.md` | `docs(practice): cập nhật hợp đồng theo T0 và D8 v2` | C-05, C-06 |

Bước 3 đứng trước bước 4 vì ADR trích dẫn schema.

---

## 11. Test Matrix

### 11.1. Kiểm tra của chính T0 (chạy trước mỗi commit tài liệu)

| ID | Kiểm tra | Cách chạy | Điều kiện đạt |
|---|---|---|---|
| C-01 | Mọi khối ```json trong `02-…md` và brief parse được | Script tạm ở scratchpad (Python `json.loads`), không commit | 0 lỗi parse |
| C-02 | Mỗi ví dụ ở 4.3 hợp lệ theo schema 4.2 | Python `jsonschema` (Draft 2020-12) trong venv tạm ở scratchpad. Python hệ thống không có gói này. | 0 lỗi. Có thêm 6 ví dụ âm (P1 có `prompt`, P6 thiếu `blank_key`, `TRANSCRIPT` BEFORE_SUBMIT, `AUDIO` thiếu `media_asset_id`, HTML trong text, `insert.position = 5`) và cả 6 đều bị từ chối. **Đã chạy với bản brief này (26/11/2026, `jsonschema` 4.23.0): 28 khối JSON parse được, 19/19 ví dụ dương đạt, 6/6 ví dụ âm bị từ chối.** |
| C-03 | Ví dụ thỏa V-01..V-12 | Đọc soát theo bảng, ghi kết quả vào PR | 12/12 |
| C-04 | Link tương đối trong `docs/m05/**` tồn tại | `grep -o '](\.[^)]*)'` rồi kiểm từng file | 0 link hỏng |
| C-05 | Mã lỗi nhất quán | So bảng 5.4 với CONTRACT §4.3 sau sửa | Trùng khớp. Không còn `STALE_ANSWER_VERSION`. |
| C-06 | Không file code nào đổi | `git diff --name-only` | Chỉ có `docs/**/*.md` |

### 11.2. Hợp đồng test cho task triển khai (tên và assert đã chốt)

**Unit**

| Test | Assert |
|---|---|
| `IdempotencyHashTests.omittedModeHashesAsPractice` | Hash(body không có `mode`) = hash(body `mode = "PRACTICE"`) |
| `IdempotencyHashTests.topicOrderDoesNotChangeHash` | Đảo `topicIds` → cùng hash |
| `SealMappingTests.labelOnlyOptionsHaveNullBody` | P1/P2: 100% `body` null, `label` A–D |
| `SealMappingTests.answerKeyPointsToExactlyOneOption` | V-08 đúng với mọi câu của fixture Full Test |
| `SealMappingTests.afterSubmitMaterialsGoToPrivateSnapshot` | Mọi material `AFTER_SUBMIT` nằm trong `private_snapshot`, 0 nằm trong `public_snapshot` |
| `SnapshotSchemaV1Tests.validExamplesPass` / `negativeExamplesFail` | Dùng đúng ví dụ ở 4.3 và 6 ví dụ âm của C-02 |
| `MaterialValidatorTests.tableRowsMatchHeaderWidth` | V-01 |
| `MaterialValidatorTests.blankKeysMatchQuestions` | V-03 |
| `MaterialValidatorTests.insertPositionsComplete` | V-04 |
| `GraderTests.unansweredIsIncorrectWithoutPenalty` | `correct_count` không giảm khi bỏ trống |
| `GraderTests.listeningPlusReadingEqualsCorrect` | Đúng `c30` |
| `BusinessExceptionTypedDetailsTests.uuidOrNullAccepted` / `enumAccepted` / `negativeCountRejected` | R8 |

**Integration (Testcontainers PostgreSQL 17, không `@Transactional` ở test đồng thời)**

| Test | Assert |
|---|---|
| `PracticeResultsUpgradeTests.v13DataSurvivesV14` | Migrate tới V13, chèn attempt GRAMMAR đã nộp và đang làm, migrate tiếp. Dữ liệu giữ nguyên, `scoring_version = 1`, bài đang làm vẫn nộp được. |
| `AttemptStartIntegrationTests.fullTestSealsExpectedRowCounts` | 1 / 42 / 200 / 775 / 200 dòng |
| `AttemptStartIntegrationTests.replaySameKeyReturns200` / `sameKeyDifferentBodyReturns409` | 5.1 |
| `AttemptStartIntegrationTests.archivedSetStillReplays` | Edge #8 |
| `AutosaveIntegrationTests.deselectIncrementsVersionAndNullsAnsweredAt` | Edge #16 |
| `AutosaveIntegrationTests.replayWithSameKeyDoesNotBumpVersion` | Edge #4 |
| `SubmitIntegrationTests.secondSubmitReturnsStoredResultAndNoSecondEvent` | Edge #7, đếm `event_publication` |
| `SnapshotImmutabilityTests.updateOrDeleteSnapshotRaises23514` | Trigger 4.1.9 |
| `ErrorHandlingTests` (10 ca hiện có) | Giữ xanh, không sửa |

**Security / Anti-Leak**

| Test | Assert |
|---|---|
| `PreSubmitLeakTests.inProgressViewHasNoForbiddenKeys` | Không có 10 khóa liệt kê ở 6.1 |
| `PreSubmitLeakTests.inProgressViewHasNoTranscriptText` | Không chứa chuỗi transcript và lời giải của fixture (P1, P2, P3, P4) |
| `PreSubmitLeakTests.noSignedUrlForAfterSubmitMedia` | 6.1 |
| `PreSubmitLeakTests.viewNeverContainsSourceOptionIds` | 6.1 |
| `AttemptAccessTests.otherUsersAttemptReturns404` / `foreignSnapshotReturns404` | Edge #13, #14 |
| `AttemptAccessTests.putWithoutCsrfReturns403` | CSRF |

**Concurrency**

| Test | Assert |
|---|---|
| `AutosaveConcurrencyTests.twoWritersSameExpectedVersion_oneWins` | Đúng một 200 và một 409, `version` tăng đúng 1 |
| `AutosaveConcurrencyTests.staleWriteAfterNewer_isRejected` | Edge #5 |
| `SubmitConcurrencyTests.autosaveRacingSubmit_neverWritesAfterSubmit` | Sau khi nộp, không slot nào có `updated_at > submitted_at` |
| `StartConcurrencyTests.sameKeyConcurrently_createsOneAttempt` | Đúng 1 dòng `attempts` |
| `StartConcurrencyTests.fifthAndSixthConcurrently_oneRejected` | 4 bài mở sẵn: 1×201, 1×409 |

**ArchUnit / Modulith**

| Test | Assert |
|---|---|
| `ArchitectureTests.discoversBusinessModulesAndEnforcesTheirBoundaries` | Có thêm `results`. `modules.verify()` xanh. |
| `LayerArchitectureTests` (13 ca hiện có) | Giữ xanh |
| `ResultsBoundaryTests.noEntityReferencesOtherModuleTypes` | Không có field kiểu entity của module khác trong `results.entity` |

### 11.3. Benchmark (ADR-13)

`FullTestStartBenchmarkTests` (tag `benchmark`, không chạy trong `check`): container PostgreSQL giới hạn 384 MiB (`withCreateContainerCmdModifier`), Hikari `maximumPoolSize = 5`, 50 lượt start tuần tự rồi 5 song song. Ghi p50/p95 vào `01-adrs` ADR-13. Không có ngưỡng fail cho đến khi S11 được duyệt.

---

## 12. Definition of Done & Sign-off Questions

### 12.1. DoD của T0

| # | Tiêu chí | Pass/Fail |
|---|---|---|
| 1 | Brief có đủ 12 mục, đúng thứ tự | ☐ |
| 2 | Mọi drift ở 2.2 có cột "Giải quyết" trỏ tới quyết định | ☐ |
| 3 | 6 quyết định bắt buộc (R1–R6) có phương án, lý do, trade-off | ☐ |
| 4 | JSON Schema có đủ 6 `material_type`, group public/private, question, answer key | ☐ |
| 5 | Ví dụ (a) P3, (b) P6, (c) P7 cùng P1/P2 qua C-01 và C-02 | ☑ (đã chạy trên brief, xem 11.1) |
| 6 | Người dùng trả lời S1–S12 | ☐ |
| 7 | 4 tài liệu đích tạo đúng cấu trúc 4.7, C-01…C-06 đạt | ☐ |
| 8 | `git diff --name-only` chỉ có `docs/**/*.md` | ☐ |

### 12.2. Câu hỏi cần duyệt trước khi code

| # | Câu hỏi | Đề xuất |
|---|---|---|
| S1 | Giữ `SUBMITTED` và ghi addendum SRS (R2)? | Giữ |
| S2 | ADR-10 có `EXPIRED` nhưng không có ngữ nghĩa riêng (bài có giờ quá hạn đã được tự nộp `TIME_LIMIT`). Giữ ở dạng dành sẵn, hay gỡ khỏi ADR-10 và CHECK? | Gỡ. Thêm lại bằng một migration nhỏ khi có nghiệp vụ. |
| S3 | `mistake_records` thuộc `learning` (ADR-12, sơ đồ SRS 3.1) hay `results` (SRS 4.4, CONTRACT §1)? | `learning`. Event đã tách sẵn. |
| S4 | Attempt V13 đang làm dở khi deploy V14: cho làm tiếp và nộp theo nhánh legacy, hay tự đóng (`ABANDONED`) trong V14? | Cho làm tiếp, vì dữ liệu ít và không mất bài của người học. |
| S5 | Có thêm trigger chặn UPDATE `attempt_answers` khi attempt không còn `IN_PROGRESS` không? | Không ở MVP. Service và test concurrency đã đủ. |
| S6 | Bỏ cột `explanation_snapshot` của DD, gộp vào `answer_key_snapshot` (R1)? | Đồng ý |
| S7 | Giữ `attempt_snapshot_options.id` theo attempt thay cho PK `(snapshot_id, option_id)` của DD (R1)? | Đồng ý |
| S8 | Dùng `VERSION_CONFLICT` thay `STALE_ANSWER_VERSION`? | Đồng ý |
| S9 | Mở lại D6 cho TOEIC (`practice_sets` FIXED) và cho TOEIC `part_number NULL` là bộ nhiều Part (4.1.8)? | Đồng ý. Thiếu cái này thì không có M05.02 và Full Test. |
| S10 | Giới hạn 5 bài mở tính chung GRAMMAR và TOEIC? | Đồng ý |
| S11 | TTL URL ký cho media: 30 phút, frontend làm mới bằng `GET /attempts/{id}`? Ngưỡng SLA start Full Test p95 < 800 ms chốt sau benchmark? | 30 phút. Chốt SLA sau 11.3. |
| S12 | Có cần chạy Q7 (CONTRACT §11.3) trên production trước khi deploy V14 để ước lượng backfill không? | Có, chỉ đọc, do người có quyền chạy |
