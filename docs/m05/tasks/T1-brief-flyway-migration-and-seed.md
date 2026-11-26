# T1 — Implementation Brief: Flyway V14/V15 (additive từ V13), backfill Grammar và seed M05

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026. O1–O4 theo đề xuất ở 12.2: O1 media thật để T6; O2 giữ cả hai FK; O3 quyết định sau Q7; O4 chấp nhận. Rà soát chéo T0–T7 (duyệt 26/11/2026): bỏ 3 cột ước tính 990 khỏi V14 (AD-1); tên file seed là `V1__m05_local_seed.sql`; thêm test `seedRunnerNotLoadedWithoutLocalProfile`. |
| Ngày | 26/11/2026 |
| Commit nền | `4afb631` cộng các tài liệu T0 (chưa commit) |
| Phụ thuộc | T0 đã duyệt: [brief T0](T0-brief-contract-freeze.md), [00](../00-toeic-research-and-gap-report.md), [01](../01-adrs-07-to-13.md), [02](../02-snapshot-json-schemas-v1.md), [CONTRACT](../../practice/CONTRACT.md) |
| Chế độ viết brief | READ-ONLY. Brief chỉ mô tả, mọi file đích do task triển khai T1 tạo. |

Nhãn: `[CREATE]`, `[MODIFY]`, `[DO NOT TOUCH]`. SQL trong brief là đặc tả để chép vào file migration. Không phải file đã chạy.

---

## 1. Metadata & Traceability

### 1.1. Sản phẩm của T1

1. `V14__practice_toeic_schema_expand.sql`: DDL additive.
2. `V15__practice_backfill_v13_attempts.sql`: backfill dữ liệu V13, viết để chạy lại được.
3. Seed M05 cho profile `local` và cho test (SQL kèm runner).
4. Test cho schema, đường nâng cấp và seed.
5. Cập nhật tài liệu: delta JSON Schema v1 và CONTRACT §14.4.

### 1.2. In-Scope

| Mã | Nội dung T1 |
|---|---|
| R1, S6, S7 | Cột JSONB snapshot; `attempt_snapshot_options.id` theo lượt; không có `explanation_snapshot` |
| R2, S2 | CHECK trạng thái `('IN_PROGRESS','SUBMITTED','ABANDONED')` |
| R3 | `attempts.attempt_mode`; giữ `questions.practice_mode` |
| R4 | Index cho đếm lượt mở và lazy cleanup |
| R6 | Seed dùng node JSON có cấu trúc, không có chuỗi `{{…}}` |
| R7 (D8 v2) | V13 `[DO NOT TOUCH]`, mọi thay đổi đi qua V14+ |
| S9 | `practice_sets` cho TOEIC, `part_number NULL` |
| ADR-07 | `schema_version`, backfill sang JSON v1 |
| ADR-08 | `group_materials.visibility` GENERATED; `option_order` FIXED cho TOEIC |
| ADR-09 | `attempt_answers.version`, `last_request_id`, `is_correct` nullable; FK chống chọn lựa chọn của câu khác |
| ADR-10 | `deadline_at`, `time_limit_seconds`, `submit_reason`, `abandoned_at` |
| ADR-11 | `scoring_version`, `listening_correct`, `reading_correct`. Không có cột ước tính 990 (AD-1). |
| ADR-13 | `attempt_media_refs`; không có GIN |
| M05.01–M05.18 | Chỉ ở tầng dữ liệu. Không có API mới. |

### 1.3. Explicitly Out-of-Scope

- Mọi thay đổi logic Java nghiệp vụ, entity và API. Lý do không cần sửa entity ở T1: xem 3.M6.
- Tách module `results` (T3). Ghi dữ liệu theo schema v1 từ code (T3).
- Trigger append-only cho bảng snapshot (dời sang T3, lý do ở 3.M1).
- Slot đáp án rỗng cho lượt V13 (dời sang T3, lý do ở 3.M2).
- Projection và validator TOEIC (task projection). Seed T1 ghi thẳng bảng projection, không đi qua pipeline publish.
- File media thật trong R2 (câu hỏi mở O1).
- Hàng rào YAGNI theo preamble v2 §4.

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc

| Nhóm | File |
|---|---|
| Quy ước | `AGENTS.md`, `CLAUDE.md` |
| Migration | Danh sách `db/migration` (V1–V10, V12, V13, V8_1; **không có V11, không có V14**). Đọc: `V13` toàn văn; `V6` DDL `content_items`, `content_revisions`, `media_assets`, `revision_media_refs`, `words`, `word_senses` và toàn bộ FK (dòng 281–343); `V7` dòng 1–52 (trigger); `V10` `grammar_topics` và FK; `V2` `users`; `V3` dòng 1–49 |
| Code | `practice/entity/{Attempt,AttemptAnswer,AttemptQuestionSnapshot,AttemptSnapshotOption,AttemptSourceTopic,Question,QuestionOption,QuestionTopic}.java` (mapping cột); `PracticeServiceImpl.java:143,279-280`; `infrastructure/runner/LocalVocabularySeedRunner.java`; `grammar/service/impl/GrammarSnapshotValidator.java:68`, `GrammarProjectionListener.java:50-70`; `vocabulary` validator dòng 68–116 |
| Cấu hình | `build.gradle` (Spotless dòng `spotless {}`, task `test` dòng 77–86), `application.properties` (dòng 3–8, 70), `application-local.properties` |
| Test | Tìm `delete`/`truncate`/`update` trên bảng attempt trong `src/test/java`: **0 kết quả** cho bảng practice |
| Tài liệu | 4 tài liệu T0, `approved-baseline.sql` dòng 540–846 |

### 2.2. Drift

| ID | Yêu cầu task / docs | Repo thật | Giải quyết |
|---|---|---|---|
| T1-D01 | Preamble: cấu hình `application*.yml`, profile `dev`/`test` | Repo dùng `.properties`. Profile chạy máy local là `local` (`LocalVocabularySeedRunner`: `@Profile("local")` + `@ConditionalOnProperty`). Không có profile `dev`. Test không dùng profile riêng. | Seed gắn `@Profile("local")` + `app.m05.seed=true`. Test nạp seed bằng `ScriptUtils`. |
| T1-D02 | Preamble: pass `spotlessCheck` | Spotless chỉ quét `src/**/*.java` (google-java-format 1.28.0 AOSP). Không format `.sql`. | Java mới (runner, test) phải qua `spotlessApply`/`spotlessCheck`. SQL không chịu Spotless. |
| T1-D03 | T1 B.2: thêm `attempts.idempotency_key`, `request_hash`, `UNIQUE (user_id, idempotency_key) WHERE … IS NOT NULL` | V13 đã có `request_id UUID NOT NULL`, `request_hash`, `attempts_c2 UNIQUE (user_id, request_id)`. CONTRACT §9: `request_id` lấy từ `Idempotency-Key`. | **Không thêm cột.** `request_id` là nơi lưu `Idempotency-Key` (3.M3). |
| T1-D04 | T1 B.2: `attempt_snapshot_options(ordinal, option_text, snapshot_id NOT NULL)`, `UNIQUE(snapshot_id, ordinal)`, `UNIQUE(snapshot_id, label)` | V13 dùng tên `position`, `body`. Khóa là `(attempt_id, question_position, …)`. | Giữ tên V13 (đổi tên là thay đổi không additive và phá entity). Thêm `snapshot_id` nullable ở T1, `NOT NULL` ở T3. Bỏ hai UNIQUE thừa (3.M5). |
| T1-D05 | T1 B.2: `attempt_answers UNIQUE (attempt_id, snapshot_id)` và FK `(snapshot_id, selected_option_id)` | V13 có PK `(attempt_id, question_position)` và FK `attempt_answers_c3 (attempt_id, question_position, selected_option_id) → options`. | Thêm cả hai theo spec. FK mới trùng tác dụng với `c3`, giữ cả hai cho tới khi T3 chọn đường đọc duy nhất. |
| T1-D06 | T1 B.2: `attempt_group_snapshots.source_group_id`, `private_snapshot` nullable | T0 (theo DD) dùng `group_id`, `private_snapshot NOT NULL DEFAULT '{}'` | Theo T1: `source_group_id`; `private_snapshot NULL` khi không có tài liệu sau nộp. Cập nhật 02 (4.8). |
| T1-D07 | T1 B.2: `attempt_media_refs (media_asset_id)`, `UNIQUE (attempt_id, media_asset_id)` | T0 (theo DD): `media_id`, PK có thêm `usage_role` | Theo T1. Bỏ `usage_role` vì GC chỉ cần biết có tham chiếu (YAGNI). |
| T1-D08 | T1 B.2: `attempt_question_snapshots.option_order` | T0/02: `option_order` chỉ nằm trong JSON | Thêm cột, kèm CHECK bằng giá trị trong JSON (quy tắc 02 §2: cột được lặp JSON khi có CHECK bằng nhau). |
| T1-D09 | T1 B.2: `category` có `VOCABULARY` | T0/02: QuestionSnapshot chỉ có `GRAMMAR`, `TOEIC` | Thêm `VOCABULARY` vào DB và vào 02 (delta tương thích ngược, 4.8). |
| T1-D10 | T1 B.1: backfill TEXT → JSON v1 | T0 4.1.2 để dòng V13 ở định dạng cũ (`schema_version NULL`) | Backfill toàn bộ sang v1 (3.M2). Giữ nguyên cột TEXT cũ, đánh dấu `legacy_backfill: true`. |
| T1-D11 | — | `AttemptSnapshotOption.correct` là `boolean` nguyên thủy, map `is_correct` (`AttemptSnapshotOption.java:33-34`) | Không được set `is_correct = NULL` trên dòng cũ trước T3: Hibernate không gán được NULL vào `boolean` nguyên thủy, gây lỗi khi đọc. |
| T1-D12 | — | `PracticeServiceImpl.java:279-280` đưa `selectedOptionId` vào map theo vị trí | Không chèn slot rỗng (`selected_option_id NULL`) trước T3 (3.M2). |
| T1-D13 | — | `V7:46-51`: trigger `BEFORE INSERT` chặn `revision_media_refs` của revision đã `PUBLISHED`. `ON CONFLICT DO NOTHING` **không** bỏ qua trigger `BEFORE`. | Seed chèn ref trước khi chuyển `PUBLISHED`. Lệnh chèn ref dùng `INSERT … SELECT … WHERE NOT EXISTS (revision đã PUBLISHED)` để chạy lại được (4.6). |
| T1-D14 | — | `content_items.created_by`, `content_revisions.created_by/reviewed_by`, `media_assets.uploaded_by` có FK tới `users` (`V6:285-294`) | Seed tạo user cố định không đăng nhập được (4.6.1). |
| T1-D15 | — | `ScriptUtils` (dùng trong `@Sql`/`ResourceDatabasePopulator`) tách lệnh theo `;` và không hiểu khối `$$` | Seed **không** có `DO $$`. Migration (Flyway) được phép dùng `DO $$`. |
| T1-D16 | Preamble: Hikari 5, RAM 768m/384m | Không có trong repo (T0 DR-23) | Giữ là giả định. |
| T1-D17 | 02 §2: chủ điểm được gán chỉ nằm ở cột `topic_id` | Snapshot V13 chỉ biết `topic_id` | Backfill `meta.topic_ids = [topic_id]`. Mất thông tin đa nhãn của câu cũ, chấp nhận. |

---

## 3. Mandatory Design Resolutions

### M1. Chiến lược additive và cửa sổ tương thích T1 → T3

- T1 **không sửa Java nghiệp vụ**. Code V13 vẫn chạy và vẫn ghi dòng định dạng cũ cho tới khi T3 thay nó.
- Mọi ràng buộc mới ở V14 phải chấp nhận cả hai loại dòng:
  - (a) dòng V13 và dòng code cũ ghi sau T1: `schema_version IS NULL`, chưa có `snapshot_id`/`label`;
  - (b) dòng v1 (đã backfill hoặc do T3 ghi).
- Vì vậy, các cột chỉ bắt buộc cho v1 là **nullable ở T1**. `NOT NULL` và trigger append-only thuộc migration của T3.

**Hợp đồng bàn giao cho T3** (bắt buộc, kiểm ở DoD của T3). Migration đầu tiên của T3 phải:
1. Chạy lại nguyên văn khối 1–4 của V15, để bắt các dòng code cũ ghi giữa T1 và T3.
2. Chèn slot rỗng cho mọi câu chưa có `attempt_answers`.
3. `UPDATE attempt_snapshot_options SET is_correct = NULL`.
4. `SET NOT NULL`:
   - `attempt_question_snapshots`: `schema_version`, `question_snapshot`, `answer_key_snapshot`, `display_number`, `category`, `grading_policy_version`, `option_order`;
   - `attempt_snapshot_options`: `snapshot_id`, `label`;
   - `attempt_answers`: `snapshot_id`.
5. Bỏ vế "dòng V13" của `attempt_question_snapshots_c10`.
6. Tạo 3 trigger `me_history_append_only()` trên bảng snapshot.

T1 không tạo trigger vì bước 1 và 3 của T3 cần `UPDATE` trên bảng snapshot.

### M2. Backfill V13 → JSON v1

| Bảng | Hành động V15 | Lý do |
|---|---|---|
| `attempt_question_snapshots` (dòng `schema_version IS NULL`) | Ghi `question_snapshot`, `answer_key_snapshot`, `schema_version = 1`, `category = 'GRAMMAR'`, `display_number = position`, `option_order = 'FIXED'`, `grading_policy_version = 'SINGLE_CHOICE_V1'`. **Giữ** `prompt`, `explanation`, `topic_id`. | Cột TEXT là bản gốc. Nó không được đọc nữa sau T3, và không xóa (additive). |
| `attempt_snapshot_options` (`snapshot_id IS NULL`) | Ghi `snapshot_id`, `label`. **Giữ** `is_correct` (T1-D11). `source_option_id` để `NULL` vì không còn biết ID gốc. | — |
| `attempt_answers` (`snapshot_id IS NULL`) | Ghi `snapshot_id`. Với lượt `SUBMITTED`: `is_correct = (selected = correct_option_snapshot_id)`. | Không chèn slot rỗng (T1-D12). |
| `attempts` | `last_active_at`, `source_config`, `snapshot_sealed_at = started_at`. Với `SUBMITTED`: `answered_count`, `submit_reason = 'MANUAL'`, `effective_finished_at = submitted_at`, `duration_seconds`. | `scoring_version` giữ mặc định `1` (score %). |

- **Kiểm tra trước:** V15 dừng (`RAISE EXCEPTION`) nếu có snapshot V13 không có **đúng một** lựa chọn `is_correct = true`. Đây là điều kiện để dựng được `answer_key_snapshot`.
- **Kiểm tra sau:** so `correct_count` của lượt `SUBMITTED` với số `is_correct` vừa tính. Lệch thì chỉ `RAISE NOTICE` (không chặn deploy) và ghi vào log Flyway. Q7 (S12) chạy trước để biết số dòng.
- **Chạy lại được:** mọi lệnh đều có điều kiện `WHERE … IS NULL`. T3 chạy lại khối 1–4 nguyên văn.

### M3. Lưu Idempotency

| Thao tác | Lưu ở | DDL | Lý do chọn |
|---|---|---|---|
| `POST /attempts` | `attempts.request_id` (đã có) + `request_hash` (đã có) | Không đổi. `attempts_c2 UNIQUE (user_id, request_id)` đã có. | Không nhân đôi cột (T1-D03). V13 đã có `NOT NULL`, nên không cần partial unique. |
| `PUT /answers` | `attempt_answers.last_request_id UUID NULL` | Một cột, **không index** | Xem phân tích bên dưới. |

Phân tích chi phí của `last_request_id` (16 byte mỗi slot):
- **Không thêm `last_idempotency_hash`.** Request được coi là replay khi key trùng, lựa chọn hiện tại trùng body, và `version = expectedVersion + 1`. Điều kiện này đã xác định payload lần trước (T0 §5.1).
- **Không dùng bảng TTL riêng.** Bảng riêng tốn thêm một INSERT và một index mỗi lần autosave, cộng job dọn dẹp.
- **HOT update.** Autosave chỉ sửa các cột không có index (`selected_option_id`, `version`, `answered_at`, `last_request_id`, `updated_at`), nên PostgreSQL dùng được HOT update: không ghi index, ít WAL. Đặt `fillfactor = 90` trên `attempt_answers` để trang còn chỗ cho HOT. Lựa chọn này hợp với `shared_buffers = 64MB`.
- **Giới hạn đã chấp nhận:** chỉ nhớ key gần nhất của mỗi slot. Retry mạng luôn gửi lại key vừa dùng, nên đủ.

### M4. DDL: chỗ lệch có chủ đích so với đề T1

| Đề T1 | Quyết định | Lý do |
|---|---|---|
| `option_order` CHECK `FIXED` khi P1/P2 | CHECK `FIXED` cho **mọi** câu TOEIC | 02 §3 đã khóa TOEIC `option_order = FIXED`. Ràng buộc chặt hơn bao trùm yêu cầu. |
| `question_kind` nullable | Nullable với GRAMMAR/VOCABULARY. **Bắt buộc** với TOEIC. | JSON snapshot TOEIC bắt buộc có `question_kind`. |
| `version INT` | `INTEGER NOT NULL DEFAULT 0` | Theo đề. Đủ cho số lần autosave một câu. |
| `material_type` có 6 giá trị, `visibility` | `visibility` là cột GENERATED từ `usage_role` | Tránh hai nguồn sự thật (T0 4.1.6). |
| ~~Cột ước tính 990~~ | ~~`listening_scaled_estimate`, `reading_scaled_estimate`, `conversion_table_version`, nullable, có CHECK miền giá trị~~ | **Bỏ theo AD-1** (duyệt 26/11/2026, rà soát chéo T0–T7, C-07): hàng rào YAGNI cấm đưa quy đổi 990 vào code. V14 không có 3 cột này, không có `attempts_c34`/`attempts_c35`, không có test `scoreEstimatesAreAllOrNothing`. Khi nào làm quy đổi 990 thì thêm bằng migration additive mới. |
| `practice_sets` hỗ trợ `DYNAMIC` | CHECK `(selection_mode = 'FIXED') = (selection_config = '{}'::jsonb)` | FIXED dùng `set_items`, DYNAMIC dùng `selection_config`. Không có hai nguồn. |

Bổ sung projection cho TOEIC (đề T1 chưa nêu, nhưng seal cần, T0 §5.3 `SealQuestion`). Tất cả nằm trên `questions`, trừ `speaker` nằm trên `question_options`:
- `questions.prompt_speaker`, `prompt_transcript`: câu hỏi nói ở P2.
- `question_options.speaker`: người nói lựa chọn P1/P2.
- `questions.insert_sentence`.
- `questions.refs`, `questions.evidence` (JSONB mảng).

### M5. Index cho PostgreSQL 384 MiB

Nguyên tắc:
- Chỉ tạo index phục vụ một truy vấn đã định danh, hoặc làm đích FK/UNIQUE.
- **Không GIN trên JSONB.** Không truy vấn nào lọc theo nội dung JSON; JSON chỉ được đọc theo khóa chính.

| # | Index / ràng buộc | Mới? | Truy vấn sử dụng |
|---|---|---|---|
| I1 | `ix_attempts_user_status (user_id, status, submitted_at)` | Có sẵn (V13) | R4 đếm lượt mở: `SELECT count(*) FROM attempts WHERE user_id = ? AND status = 'IN_PROGRESS'`. Lazy cleanup: `… AND deadline_at IS NULL AND last_active_at <= ? FOR UPDATE` (tối đa 5 dòng mỗi user sau khi lọc index). |
| I2 | `attempts_c2 UNIQUE (user_id, request_id)` | Có sẵn | Replay start |
| I3 | `attempt_question_snapshots_c4 UNIQUE (id)` | Mới | `PUT …/answers/{snapshotId}`: `WHERE id = ?` |
| I4 | `attempt_question_snapshots_c5 UNIQUE (attempt_id, position, id)` | Mới | Đích FK cho options và answers (giữ `snapshot_id` khớp vị trí) |
| I5 | `attempt_snapshot_options_c6 UNIQUE (snapshot_id, id)` | Mới | Đích FK `(snapshot_id, selected_option_id)` |
| I6 | `attempt_answers_c6 UNIQUE (attempt_id, snapshot_id)` | Mới | Khóa slot `WHERE attempt_id = ? AND snapshot_id = ? FOR UPDATE` |
| I7 | `attempt_group_snapshots_c1/c2/c3` | Mới | `c1 (attempt_id, id)`: đích FK. `c3 (attempt_id, ordinal)`: đọc nhóm theo thứ tự. `c2 (attempt_id, source_group_id)`: chống trùng nhóm. |
| I8 | `attempt_media_refs` PK `(attempt_id, media_asset_id)` và `ix_attempt_media_refs_media (media_asset_id)` | Mới | Kiểm FK RESTRICT khi xóa `media_assets`, và truy vấn GC `EXISTS (… WHERE media_asset_id = ?)` |
| I9 | `questions_c17 UNIQUE (group_id, group_position)` | Mới | Nạp câu của nhóm khi seal: `WHERE group_id = ANY(?) ORDER BY group_id, group_position` |
| I10 | `group_materials_c1 (group_id, position)`, `c2 (group_id, document_key)`; tương tự cho `question_materials` | Mới | Nạp học liệu theo nhóm/câu |
| I11 | `set_items_c1 (set_id, position)`, `c4 (set_id, question_id)`, `c5 (set_id, group_id)` | Mới | Nạp set FIXED, chống trùng |
| I12 | `question_words` PK `(question_id, word_sense_id)` và `ix_question_words_sense (word_sense_id, question_id)` | Mới | Chọn câu VOCABULARY theo sense |
| — | Không tạo: partial index `IN_PROGRESS` (I1 đã đủ, mỗi user ≤ 5 bài mở); index `group_snapshot_id`; index `attempt_answers.selected_option_id` (để giữ HOT); `UNIQUE (snapshot_id, position)`, `UNIQUE (snapshot_id, label)` (trùng `c1` của V13 và CHECK `label = chr(64 + position)`); index trên `practice_sets` (dưới 1.000 dòng, quét tuần tự rẻ hơn) | — | — |

Ước lượng cho một Full Test (chưa đo):
- Index mới: snapshot 200 × 2 + options 775 × 1 + answers 200 × 1 + nhóm 42 × 3 + media refs ≈ 16 × 2, tổng khoảng 1.530 entry.
- Cộng index V13 (snapshot PK, options `c1`/`c2`, answers PK): khoảng 3.500 entry.
- Khoảng 40 byte mỗi entry, tổng khoảng 140 KB mỗi Full Test.
- Working set nóng chỉ là lượt đang làm, rất nhỏ so với `shared_buffers = 64MB`.

### M6. JPA Entity: không sửa ở T1

`spring.jpa.hibernate.ddl-auto=validate` (`application.properties:6`) chỉ kiểm cột đã map **có tồn tại** và **kiểu tương thích**. Nó không kiểm nullability hay CHECK, và bỏ qua cột chưa map.

V14:
- không đổi tên hay kiểu cột nào đã map;
- chỉ `DROP NOT NULL` (`prompt`, `explanation`, `topic_id`, `body`, `is_correct`, `selected_option_id`, `answered_at`);
- thêm cột mới chưa map;
- cột `NOT NULL` mới đều có `DEFAULT`.

Kiểm từng lệnh ghi của code V13 với ràng buộc mới:

| Lệnh ghi V13 | Ràng buộc mới liên quan | Kết quả |
|---|---|---|
| INSERT `attempts` (category GRAMMAR, status IN_PROGRESS, total ≤ 50, score NULL) | `c13`–`c33` | Đạt: cột mới nhận DEFAULT hoặc NULL; `scoring_version = 1` |
| UPDATE `attempts` khi nộp (`score` %, `submitted_at`, `correct_count`) | `c23` (`scoring_version = 1` → score 0–100), `c24`–`c26` chỉ áp cho v2, `c27` chỉ áp cho ABANDONED | Đạt |
| INSERT `attempt_question_snapshots` (prompt, explanation, topic_id; không có cột mới) | `c10` vế "dòng V13"; `id` nhận DEFAULT `gen_random_uuid()` | Đạt |
| INSERT `attempt_snapshot_options` (id, body, is_correct) | `c5`–`c8` chấp nhận `label`/`snapshot_id` NULL | Đạt |
| INSERT/UPDATE `attempt_answers` (selected, answered_at do entity set) | `c4` `(selected IS NULL) = (answered_at IS NULL)` | Đạt (cả hai luôn có giá trị) |
| INSERT/UPDATE `questions` (projection GRAMMAR) | `c7`–`c17`; `option_order` DEFAULT `'FIXED'`; `question_kind` NULL | Đạt |

**Kết luận:** T1 không sửa entity nào. T3 sửa entity khi tách sang `results`.

### M7. Cơ chế seed

| Phương án | Quyết định |
|---|---|
| Đi qua pipeline publish (`ContentEvents.createAndPublish`) | Loại: validator QUESTION hiện từ chối TOEIC (`QuestionSnapshotValidator.java:76-84`), và projection TOEIC chưa có |
| Migration Flyway `R__seed` trong `db/migration` | Loại: chạy cả ở production |
| **Một file SQL thuần** `src/main/resources/db/seed/m05/m05_local_seed.sql` (T2 đổi tên thành `V1__m05_local_seed.sql` và chạy qua Flyway API, mục 4.7), chạy bằng runner `@Profile("local")` + `app.m05.seed=true`, và nạp trong test bằng `ScriptUtils` | **Chọn.** Cùng một file cho local và test. Flyway không quét `db/seed`. |

Quy tắc file seed:
- Không có `DO $$`.
- Mọi lệnh chạy lại được (idempotent).
- Mốc thời gian cố định `TIMESTAMPTZ '2026-11-26 00:00:00+00'`.
- Dấu nháy đơn trong JSON được nhân đôi (`That''s`).
- JSON học liệu khớp 02 §3 cộng delta 4.8.
- Không có chuỗi `{{`.

### M8. Envelope revision cho seed

Pipeline hiện dùng `{"schema_version":1,"content_type":"<TYPE>","data":{…}}`:
- CONTRACT §6.1;
- WORD: `AdminApiIntegrationTests.java:403` đọc `data.senses`;
- GRAMMAR_TOPIC: `GrammarSnapshotValidator.java:68` đọc các trường `slug`, `name`, `description`, `difficulty`, `sort_order`.

T1 chốt `data` cho ba loại TOEIC mới (validator ở task projection phải nhận đúng định dạng này):

| `content_type` | Trường `data` |
|---|---|
| `QUESTION` (TOEIC/VOCABULARY) | `category`, `part_number`, `question_type_code`, `practice_mode`, `difficulty`, `question_kind`, `option_order`, `prompt`, `prompt_transcript` (`{speaker,text}` hoặc null), `insert_sentence`, `blank_key`, `group` (`{group_id, group_position}` hoặc null), `options[{id, position, body, speaker?}]`, `answer_key{correctOptionId}`, `explanation`, `refs[]`, `evidence[]`, `materials[]` (MaterialV1, 02 §3), `topic_ids[]`, `word_sense_ids[]` |
| `QUESTION_GROUP` | `category`, `part_number`, `group_type`, `title`, `materials[]` (MaterialV1, gồm cả `AFTER_SUBMIT`) |
| `PRACTICE_SET` | `category`, `title`, `part_number`, `selection_mode`, `selection_config`, `time_limit_seconds`, `deck_id`, `topic_id`, `items[{position, question_id \| group_id}]` |

---

## 4. Thiết kế chi tiết

### 4.1. `V14__practice_toeic_schema_expand.sql` — phần 1: danh mục, nhóm, câu

```sql
-- V14: Additive-only từ V13 (D8 v2). Không sửa V13.
-- Tương thích ngược với code V13 cho tới T3 (brief T1 §3.M1).

CREATE TABLE question_types (
    code           VARCHAR(32) PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1,
    enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT question_types_c1 CHECK (schema_version > 0 AND btrim(name) <> '')
);
INSERT INTO question_types (code, name, schema_version, enabled)
VALUES ('SINGLE_CHOICE', 'Single choice', 1, TRUE);

ALTER TABLE questions
    ADD CONSTRAINT fk_questions_question_type_code_question_types
        FOREIGN KEY (question_type_code) REFERENCES question_types (code) ON DELETE RESTRICT;

CREATE TABLE question_groups (
    id                    UUID PRIMARY KEY,
    content_type          VARCHAR(30) GENERATED ALWAYS AS ('QUESTION_GROUP'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    category              VARCHAR(16) NOT NULL,
    part_number           SMALLINT,
    title                 VARCHAR(200) NOT NULL,
    group_type            VARCHAR(24) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT question_groups_c1 UNIQUE (id, category),
    CONSTRAINT question_groups_c2 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    CONSTRAINT question_groups_c3 CHECK (group_type IN ('GENERAL','CONVERSATION','TALK','TEXT_COMPLETION','PASSAGE')),
    CONSTRAINT question_groups_c4 CHECK (
        (category = 'TOEIC' AND part_number IS NOT NULL AND (
            (part_number = 3 AND group_type = 'CONVERSATION') OR (part_number = 4 AND group_type = 'TALK')
            OR (part_number = 6 AND group_type = 'TEXT_COMPLETION') OR (part_number = 7 AND group_type = 'PASSAGE')))
        OR (category <> 'TOEIC' AND part_number IS NULL AND group_type = 'GENERAL')),
    CONSTRAINT question_groups_c5 CHECK (btrim(title) <> ''),
    CONSTRAINT fk_question_groups_id_content_type_content_items
        FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT,
    CONSTRAINT fk_question_groups_id_published_revision_id_content_revisions
        FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE questions
    ADD COLUMN part_number       SMALLINT,
    ADD COLUMN group_id          UUID CONSTRAINT fk_questions_group_id_question_groups
                                      REFERENCES question_groups (id) ON DELETE RESTRICT,
    ADD COLUMN group_position    SMALLINT,
    ADD COLUMN blank_key         VARCHAR(8),
    ADD COLUMN option_order      VARCHAR(8) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN question_kind     VARCHAR(24),
    ADD COLUMN insert_sentence   TEXT,
    ADD COLUMN prompt_speaker    VARCHAR(8),
    ADD COLUMN prompt_transcript TEXT,
    ADD COLUMN refs              JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN evidence          JSONB NOT NULL DEFAULT '[]'::jsonb,
    ALTER COLUMN prompt DROP NOT NULL;

ALTER TABLE questions
    DROP CONSTRAINT questions_c2,   -- category = 'GRAMMAR'
    DROP CONSTRAINT questions_c4,   -- practice_mode IN ('STANDARD','FILL_OPTION')
    DROP CONSTRAINT questions_c6;   -- prompt NOT blank

ALTER TABLE questions
    ADD CONSTRAINT questions_c7  CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    ADD CONSTRAINT questions_c8  CHECK (practice_mode IN
        ('STANDARD','TRANSLATE_EN_VI','TRANSLATE_VI_EN','LISTEN_AND_CHOOSE','FILL_OPTION')),
    ADD CONSTRAINT questions_c9  CHECK (
        (prompt IS NULL OR (btrim(prompt) <> '' AND char_length(prompt) <= 10000))
        AND btrim(explanation) <> '' AND char_length(explanation) <= 20000),
    ADD CONSTRAINT questions_c10 CHECK (category = 'TOEIC' OR prompt IS NOT NULL OR practice_mode = 'LISTEN_AND_CHOOSE'),
    ADD CONSTRAINT questions_c11 CHECK (
        (category = 'TOEIC' AND part_number BETWEEN 1 AND 7) OR (category <> 'TOEIC' AND part_number IS NULL)),
    ADD CONSTRAINT questions_c12 CHECK (
        (group_id IS NULL AND group_position IS NULL AND blank_key IS NULL)
        OR (group_id IS NOT NULL AND group_position BETWEEN 1 AND 10)),
    ADD CONSTRAINT questions_c13 CHECK (category <> 'TOEIC' OR (part_number IN (3,4,6,7)) = (group_id IS NOT NULL)),
    ADD CONSTRAINT questions_c14 CHECK ((blank_key IS NOT NULL) = (category = 'TOEIC' AND part_number = 6)
                                        AND (blank_key IS NULL OR blank_key ~ '^b[1-9]$')),
    ADD CONSTRAINT questions_c15 CHECK (option_order IN ('FIXED','SHUFFLED') AND (category <> 'TOEIC' OR option_order = 'FIXED')),
    ADD CONSTRAINT questions_c16 CHECK (
        (question_kind IS NULL OR question_kind IN
            ('STANDARD','GRAPHIC','INTENT','SENTENCE_INSERTION','INSERTION_POSITION','NOT_EXCEPT'))
        AND (category <> 'TOEIC' OR question_kind IS NOT NULL)
        AND (category = 'TOEIC' OR question_kind IS NULL OR question_kind = 'STANDARD')
        AND (question_kind IS DISTINCT FROM 'SENTENCE_INSERTION' OR part_number = 6)
        AND (question_kind IS DISTINCT FROM 'INSERTION_POSITION' OR part_number = 7)
        AND (question_kind IS DISTINCT FROM 'GRAPHIC' OR part_number IN (3,4))
        AND ((question_kind IS NOT DISTINCT FROM 'INSERTION_POSITION') = (insert_sentence IS NOT NULL))
        AND (insert_sentence IS NULL OR (btrim(insert_sentence) <> '' AND char_length(insert_sentence) <= 1000))),
    ADD CONSTRAINT questions_c17 UNIQUE (group_id, group_position),
    ADD CONSTRAINT questions_c18 CHECK (
        ((part_number IS NOT DISTINCT FROM 2) = (prompt_transcript IS NOT NULL))
        AND ((prompt_transcript IS NULL) = (prompt_speaker IS NULL))
        AND (prompt_speaker IS NULL OR prompt_speaker ~ '^(N|[MW]-(Am|Br|Cn|Au))$')
        AND (prompt_transcript IS NULL OR (btrim(prompt_transcript) <> '' AND char_length(prompt_transcript) <= 1000))),
    ADD CONSTRAINT questions_c19 CHECK (part_number NOT IN (1,2) OR prompt IS NULL),
    ADD CONSTRAINT questions_c20 CHECK (jsonb_typeof(refs) = 'array' AND jsonb_typeof(evidence) = 'array'
        AND octet_length(refs::text) <= 8192 AND octet_length(evidence::text) <= 8192);

ALTER TABLE question_options
    ADD COLUMN speaker VARCHAR(8),
    ADD CONSTRAINT question_options_c4 CHECK (speaker IS NULL OR speaker ~ '^(N|[MW]-(Am|Br|Cn|Au))$');
```

Ghi chú:
- Với `part_number IS NULL`, `questions_c19` tính ra NULL nên được coi là đạt. Hành vi này đúng cho GRAMMAR/VOCABULARY.
- `questions_c14` buộc mọi câu P6 có `blank_key`, và chỉ P6 mới có.
- Ràng buộc "`speaker` bắt buộc với P1/P2" đi qua hai bảng, nên nằm ở validator projection (V-06).

### 4.2. V14 — phần 2: học liệu, bộ đề, liên kết từ

```sql
CREATE TABLE group_materials (
    id            UUID PRIMARY KEY,
    group_id      UUID NOT NULL CONSTRAINT fk_group_materials_group_id_question_groups
                       REFERENCES question_groups (id) ON DELETE RESTRICT,
    position      SMALLINT NOT NULL,
    document_key  VARCHAR(32) NOT NULL,
    material_type VARCHAR(16) NOT NULL,
    usage_role    VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    visibility    VARCHAR(16) GENERATED ALWAYS AS
                  (CASE WHEN usage_role = 'STIMULUS' THEN 'BEFORE_SUBMIT' ELSE 'AFTER_SUBMIT' END) STORED,
    media_id      UUID CONSTRAINT fk_group_materials_media_id_media_assets
                       REFERENCES media_assets (id) ON DELETE RESTRICT,
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

CREATE TABLE question_materials (
    id            UUID PRIMARY KEY,
    question_id   UUID NOT NULL CONSTRAINT fk_question_materials_question_id_questions
                       REFERENCES questions (id) ON DELETE RESTRICT,
    position      SMALLINT NOT NULL,
    document_key  VARCHAR(32) NOT NULL,
    material_type VARCHAR(16) NOT NULL,
    usage_role    VARCHAR(20) NOT NULL DEFAULT 'STIMULUS',
    visibility    VARCHAR(16) GENERATED ALWAYS AS
                  (CASE WHEN usage_role = 'STIMULUS' THEN 'BEFORE_SUBMIT' ELSE 'AFTER_SUBMIT' END) STORED,
    media_id      UUID CONSTRAINT fk_question_materials_media_id_media_assets
                       REFERENCES media_assets (id) ON DELETE RESTRICT,
    body          JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT question_materials_c1 UNIQUE (question_id, position),
    CONSTRAINT question_materials_c2 UNIQUE (question_id, document_key),
    CONSTRAINT question_materials_c3 CHECK (position BETWEEN 1 AND 20),
    CONSTRAINT question_materials_c4 CHECK (document_key ~ '^[a-z][a-z0-9_-]{0,31}$'),
    CONSTRAINT question_materials_c5 CHECK (material_type IN ('AUDIO','IMAGE','RICH_TEXT','CHAT','TABLE','TRANSCRIPT')),
    CONSTRAINT question_materials_c6 CHECK (usage_role IN ('STIMULUS','TRANSCRIPT','EXPLANATION')),
    CONSTRAINT question_materials_c7 CHECK ((material_type IN ('AUDIO','IMAGE')) = (media_id IS NOT NULL)),
    CONSTRAINT question_materials_c8 CHECK ((material_type = 'TRANSCRIPT') = (usage_role = 'TRANSCRIPT')),
    CONSTRAINT question_materials_c9 CHECK (material_type NOT IN ('AUDIO','IMAGE') OR usage_role = 'STIMULUS'),
    CONSTRAINT question_materials_c10 CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 262144)
);

CREATE TABLE practice_sets (
    id                    UUID PRIMARY KEY,
    content_type          VARCHAR(30) GENERATED ALWAYS AS ('PRACTICE_SET'::VARCHAR) STORED,
    published_revision_id UUID NOT NULL,
    category              VARCHAR(16) NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    selection_mode        VARCHAR(8) NOT NULL,
    selection_config      JSONB NOT NULL DEFAULT '{}'::jsonb,
    deck_id               UUID CONSTRAINT fk_practice_sets_deck_id_decks REFERENCES decks (id) ON DELETE RESTRICT,
    topic_id              UUID CONSTRAINT fk_practice_sets_topic_id_grammar_topics
                               REFERENCES grammar_topics (id) ON DELETE RESTRICT,
    part_number           SMALLINT,
    time_limit_seconds    INTEGER,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT practice_sets_c1 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    CONSTRAINT practice_sets_c2 CHECK (selection_mode IN ('FIXED','DYNAMIC')),
    CONSTRAINT practice_sets_c3 CHECK (jsonb_typeof(selection_config) = 'object'
                                       AND octet_length(selection_config::text) <= 16384),
    CONSTRAINT practice_sets_c4 CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0),
    CONSTRAINT practice_sets_c5 CHECK (
        (category = 'TOEIC' AND (part_number IS NULL OR part_number BETWEEN 1 AND 7))
        OR (category <> 'TOEIC' AND part_number IS NULL)),
    CONSTRAINT practice_sets_c6 CHECK (deck_id IS NULL OR category = 'VOCABULARY'),
    CONSTRAINT practice_sets_c7 CHECK (topic_id IS NULL OR category = 'GRAMMAR'),
    CONSTRAINT practice_sets_c8 CHECK (btrim(title) <> ''),
    CONSTRAINT practice_sets_c9 CHECK ((selection_mode = 'FIXED') = (selection_config = '{}'::jsonb)),
    CONSTRAINT fk_practice_sets_id_content_type_content_items
        FOREIGN KEY (id, content_type) REFERENCES content_items (id, content_type) ON DELETE RESTRICT,
    CONSTRAINT fk_practice_sets_id_published_revision_id_content_revisions
        FOREIGN KEY (id, published_revision_id) REFERENCES content_revisions (content_item_id, id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE set_items (
    id          UUID PRIMARY KEY,
    set_id      UUID NOT NULL CONSTRAINT fk_set_items_set_id_practice_sets REFERENCES practice_sets (id) ON DELETE RESTRICT,
    position    INTEGER NOT NULL,
    question_id UUID CONSTRAINT fk_set_items_question_id_questions REFERENCES questions (id) ON DELETE RESTRICT,
    group_id    UUID CONSTRAINT fk_set_items_group_id_question_groups REFERENCES question_groups (id) ON DELETE RESTRICT,
    CONSTRAINT set_items_c1 UNIQUE (set_id, position),
    CONSTRAINT set_items_c2 CHECK (position BETWEEN 1 AND 200),
    CONSTRAINT set_items_c3 CHECK (num_nonnulls(question_id, group_id) = 1),
    CONSTRAINT set_items_c4 UNIQUE (set_id, question_id),
    CONSTRAINT set_items_c5 UNIQUE (set_id, group_id)
);

CREATE TABLE question_words (
    question_id   UUID NOT NULL CONSTRAINT fk_question_words_question_id_questions
                       REFERENCES questions (id) ON DELETE RESTRICT,
    word_sense_id UUID NOT NULL CONSTRAINT fk_question_words_word_sense_id_word_senses
                       REFERENCES word_senses (id) ON DELETE RESTRICT,
    CONSTRAINT question_words_c1 PRIMARY KEY (question_id, word_sense_id)
);
CREATE INDEX ix_question_words_sense ON question_words (word_sense_id, question_id);
```

`selection_config` của DYNAMIC: `{"questionCount": <1..100>}`. Nguồn câu lấy từ `topic_id` (GRAMMAR) hoặc `deck_id` (VOCABULARY). Validator publish kiểm. `question_topics` đã có từ V13, giữ nguyên.

### 4.3. V14 — phần 3: `attempts`

```sql
ALTER TABLE attempts
    ADD COLUMN attempt_mode              VARCHAR(16) NOT NULL DEFAULT 'PRACTICE',
    ADD COLUMN source_set_id             UUID CONSTRAINT fk_attempts_source_set_id_practice_sets
                                              REFERENCES practice_sets (id) ON DELETE RESTRICT,
    ADD COLUMN source_revision_id        UUID,
    ADD COLUMN source_config             JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN scoring_version           SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN answered_count            INTEGER,
    ADD COLUMN listening_correct         INTEGER,
    ADD COLUMN reading_correct           INTEGER,
    -- AD-1 (rà soát chéo T0–T7): không thêm cột ước tính 990 (YAGNI, ADR-11).
    ADD COLUMN snapshot_sealed_at        TIMESTAMPTZ,
    ADD COLUMN last_active_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN deadline_at               TIMESTAMPTZ,
    ADD COLUMN time_limit_seconds        INTEGER,
    ADD COLUMN submit_reason             VARCHAR(16),
    ADD COLUMN effective_finished_at     TIMESTAMPTZ,
    ADD COLUMN abandoned_at              TIMESTAMPTZ,
    ADD COLUMN duration_seconds          INTEGER;

ALTER TABLE attempts
    DROP CONSTRAINT attempts_c3,    -- category = 'GRAMMAR'
    DROP CONSTRAINT attempts_c4,    -- source_type
    DROP CONSTRAINT attempts_c6,    -- status
    DROP CONSTRAINT attempts_c7,    -- total_count 1..50
    DROP CONSTRAINT attempts_c10;   -- score 0..100

ALTER TABLE attempts
    ADD CONSTRAINT attempts_c13 CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC')),
    ADD CONSTRAINT attempts_c14 CHECK (source_type IN ('GRAMMAR_TOPIC','MIXED_GRAMMAR','PRACTICE_SET')),
    ADD CONSTRAINT attempts_c15 CHECK ((source_type = 'PRACTICE_SET') = (source_set_id IS NOT NULL)),
    ADD CONSTRAINT attempts_c16 CHECK (status IN ('IN_PROGRESS','SUBMITTED','ABANDONED')),
    ADD CONSTRAINT attempts_c17 CHECK (total_count BETWEEN 1 AND 200),
    ADD CONSTRAINT attempts_c18 CHECK (attempt_mode IN ('PRACTICE','EXAM')),
    ADD CONSTRAINT attempts_c19 CHECK (attempt_mode <> 'EXAM' OR deadline_at IS NOT NULL),
    ADD CONSTRAINT attempts_c20 CHECK ((deadline_at IS NULL) = (time_limit_seconds IS NULL)),
    ADD CONSTRAINT attempts_c21 CHECK (time_limit_seconds IS NULL OR (time_limit_seconds > 0
        AND deadline_at = started_at + make_interval(secs => time_limit_seconds))),
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
    ADD CONSTRAINT attempts_c27 CHECK ((status = 'ABANDONED') = (abandoned_at IS NOT NULL)),
    ADD CONSTRAINT attempts_c28 CHECK (status <> 'IN_PROGRESS' OR num_nonnulls(
        submit_reason, answered_count, effective_finished_at, duration_seconds,
        listening_correct, reading_correct, abandoned_at) = 0),
    ADD CONSTRAINT attempts_c29 CHECK (category = 'TOEIC' OR (listening_correct IS NULL AND reading_correct IS NULL)),
    ADD CONSTRAINT attempts_c30 CHECK (category <> 'TOEIC' OR status <> 'SUBMITTED' OR (
        listening_correct IS NOT NULL AND reading_correct IS NOT NULL
        AND listening_correct + reading_correct = correct_count)),
    ADD CONSTRAINT attempts_c31 CHECK (answered_count IS NULL OR answered_count BETWEEN 0 AND total_count),
    ADD CONSTRAINT attempts_c32 CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    ADD CONSTRAINT attempts_c33 CHECK (jsonb_typeof(source_config) = 'object' AND octet_length(source_config::text) <= 16384);
```

- `attempts_c5` của V13 (`(source_type = 'GRAMMAR_TOPIC') = (source_topic_id IS NOT NULL)`) được giữ.
- `attempts_c8` giữ nguyên: ABANDONED có `submitted_at` NULL, nên hai vế cùng false.
- `last_active_at`: `DEFAULT CURRENT_TIMESTAMP` là hàm stable, nên `ADD COLUMN` chỉ đổi metadata, không rewrite bảng. V15 sửa lại giá trị cho dữ liệu cũ.

### 4.4. V14 — phần 4: snapshot, lựa chọn, đáp án, media refs

```sql
CREATE TABLE attempt_group_snapshots (
    id                      UUID PRIMARY KEY,
    attempt_id              UUID NOT NULL CONSTRAINT fk_attempt_group_snapshots_attempt_id_attempts
                                 REFERENCES attempts (id) ON DELETE RESTRICT,
    source_group_id         UUID NOT NULL CONSTRAINT fk_attempt_group_snapshots_source_group_id_question_groups
                                 REFERENCES question_groups (id) ON DELETE RESTRICT,
    source_revision_id      UUID NOT NULL,
    ordinal                 SMALLINT NOT NULL,
    schema_version          SMALLINT NOT NULL DEFAULT 1,
    category                VARCHAR(16) NOT NULL,
    part_number             SMALLINT NOT NULL,
    expected_question_count SMALLINT NOT NULL,
    public_snapshot         JSONB NOT NULL,
    private_snapshot        JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT attempt_group_snapshots_c1 UNIQUE (attempt_id, id),
    CONSTRAINT attempt_group_snapshots_c2 UNIQUE (attempt_id, source_group_id),
    CONSTRAINT attempt_group_snapshots_c3 UNIQUE (attempt_id, ordinal),
    CONSTRAINT attempt_group_snapshots_c4 CHECK (ordinal BETWEEN 1 AND 200 AND schema_version = 1),
    CONSTRAINT attempt_group_snapshots_c5 CHECK (category = 'TOEIC' AND part_number IN (3,4,6,7)),
    CONSTRAINT attempt_group_snapshots_c6 CHECK (part_number NOT IN (3,4) OR expected_question_count = 3),
    CONSTRAINT attempt_group_snapshots_c7 CHECK (part_number <> 6 OR expected_question_count = 4),
    CONSTRAINT attempt_group_snapshots_c8 CHECK (part_number <> 7 OR expected_question_count BETWEEN 2 AND 5),
    CONSTRAINT attempt_group_snapshots_c9 CHECK (jsonb_typeof(public_snapshot) = 'object'
        AND octet_length(public_snapshot::text) <= 1048576
        AND (public_snapshot->>'schema_version')::int = schema_version
        AND (public_snapshot->>'part_number')::int = part_number),
    CONSTRAINT attempt_group_snapshots_c10 CHECK (private_snapshot IS NULL OR (
        jsonb_typeof(private_snapshot) = 'object' AND octet_length(private_snapshot::text) <= 1048576
        AND jsonb_typeof(private_snapshot->'materials') = 'array'
        AND jsonb_array_length(private_snapshot->'materials') >= 1)),
    CONSTRAINT fk_attempt_group_snapshots_source_revision
        FOREIGN KEY (source_group_id, source_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT
);

ALTER TABLE attempt_question_snapshots
    ADD COLUMN id                     UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN group_snapshot_id      UUID,
    ADD COLUMN source_revision_id     UUID,
    ADD COLUMN category               VARCHAR(16),
    ADD COLUMN part_number            SMALLINT,
    ADD COLUMN display_number         SMALLINT,
    ADD COLUMN option_order           VARCHAR(8),
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
    ADD CONSTRAINT attempt_question_snapshots_c5 UNIQUE (attempt_id, position, id),
    ADD CONSTRAINT attempt_question_snapshots_c6 CHECK (position BETWEEN 1 AND 200),
    ADD CONSTRAINT attempt_question_snapshots_c7 CHECK (display_number IS NULL OR display_number BETWEEN 1 AND 200),
    ADD CONSTRAINT attempt_question_snapshots_c8 CHECK (grading_policy_version IS NULL OR grading_policy_version = 'SINGLE_CHOICE_V1'),
    ADD CONSTRAINT attempt_question_snapshots_c9 CHECK (option_order IS NULL OR option_order IN ('FIXED','SHUFFLED')),
    ADD CONSTRAINT attempt_question_snapshots_c10 CHECK (
        (schema_version IS NULL                                   -- dòng V13 / code V13 trước T3
            AND prompt IS NOT NULL AND explanation IS NOT NULL AND topic_id IS NOT NULL
            AND question_snapshot IS NULL AND answer_key_snapshot IS NULL)
        OR (schema_version = 1
            AND question_snapshot IS NOT NULL AND answer_key_snapshot IS NOT NULL
            AND category IS NOT NULL AND display_number IS NOT NULL
            AND grading_policy_version IS NOT NULL AND option_order IS NOT NULL
            AND ((prompt IS NULL AND explanation IS NULL AND source_revision_id IS NOT NULL)
                 OR (question_snapshot->>'legacy_backfill') = 'true'))),
    ADD CONSTRAINT attempt_question_snapshots_c11 CHECK (schema_version IS NULL OR (
        (category = 'TOEIC' AND part_number BETWEEN 1 AND 7 AND topic_id IS NULL)
        OR (category = 'GRAMMAR' AND part_number IS NULL AND topic_id IS NOT NULL)
        OR (category = 'VOCABULARY' AND part_number IS NULL AND topic_id IS NULL))),
    ADD CONSTRAINT attempt_question_snapshots_c12 CHECK (question_snapshot IS NULL OR (
        jsonb_typeof(question_snapshot) = 'object' AND octet_length(question_snapshot::text) <= 1048576
        AND (question_snapshot->>'schema_version')::int = schema_version
        AND question_snapshot->>'category' = category
        AND (question_snapshot->>'part_number')::int IS NOT DISTINCT FROM part_number
        AND question_snapshot->>'option_order' = option_order)),
    ADD CONSTRAINT attempt_question_snapshots_c13 CHECK (answer_key_snapshot IS NULL OR (
        jsonb_typeof(answer_key_snapshot) = 'object' AND octet_length(answer_key_snapshot::text) <= 1048576
        AND (answer_key_snapshot->>'schema_version')::int = schema_version)),
    ADD CONSTRAINT fk_attempt_question_snapshots_group
        FOREIGN KEY (attempt_id, group_snapshot_id) REFERENCES attempt_group_snapshots (attempt_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_attempt_question_snapshots_source_revision
        FOREIGN KEY (question_id, source_revision_id) REFERENCES content_revisions (content_item_id, id) ON DELETE RESTRICT;

ALTER TABLE attempt_snapshot_options
    ADD COLUMN snapshot_id      UUID,
    ADD COLUMN label            CHAR(1),
    ADD COLUMN source_option_id UUID,
    ALTER COLUMN body       DROP NOT NULL,
    ALTER COLUMN is_correct DROP NOT NULL;

ALTER TABLE attempt_snapshot_options
    ADD CONSTRAINT attempt_snapshot_options_c5 CHECK (label IS NULL OR label = chr(64 + position)),
    ADD CONSTRAINT attempt_snapshot_options_c6 UNIQUE (snapshot_id, id),
    ADD CONSTRAINT attempt_snapshot_options_c7 CHECK ((label IS NULL) = (snapshot_id IS NULL)),
    ADD CONSTRAINT attempt_snapshot_options_c8 CHECK (body IS NULL OR btrim(body) <> ''),
    ADD CONSTRAINT fk_attempt_snapshot_options_snapshot
        FOREIGN KEY (attempt_id, question_position, snapshot_id)
        REFERENCES attempt_question_snapshots (attempt_id, position, id) ON DELETE RESTRICT;

ALTER TABLE attempt_answers
    ADD COLUMN snapshot_id     UUID,
    ADD COLUMN version         INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN is_correct      BOOLEAN,
    ADD COLUMN last_request_id UUID,
    ADD COLUMN updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN selected_option_id DROP NOT NULL,
    ALTER COLUMN answered_at DROP NOT NULL,
    ALTER COLUMN answered_at DROP DEFAULT;

ALTER TABLE attempt_answers
    ADD CONSTRAINT attempt_answers_c4 CHECK (version >= 0),
    ADD CONSTRAINT attempt_answers_c5 CHECK ((selected_option_id IS NULL) = (answered_at IS NULL)),
    ADD CONSTRAINT attempt_answers_c6 UNIQUE (attempt_id, snapshot_id),
    ADD CONSTRAINT fk_attempt_answers_snapshot
        FOREIGN KEY (attempt_id, question_position, snapshot_id)
        REFERENCES attempt_question_snapshots (attempt_id, position, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_attempt_answers_snapshot_option
        FOREIGN KEY (snapshot_id, selected_option_id)
        REFERENCES attempt_snapshot_options (snapshot_id, id) ON DELETE RESTRICT;

ALTER TABLE attempt_answers SET (fillfactor = 90);

CREATE TABLE attempt_media_refs (
    attempt_id     UUID NOT NULL CONSTRAINT fk_attempt_media_refs_attempt_id_attempts
                        REFERENCES attempts (id) ON DELETE RESTRICT,
    media_asset_id UUID NOT NULL CONSTRAINT fk_attempt_media_refs_media_asset_id_media_assets
                        REFERENCES media_assets (id) ON DELETE RESTRICT,
    CONSTRAINT attempt_media_refs_c1 PRIMARY KEY (attempt_id, media_asset_id)
);
CREATE INDEX ix_attempt_media_refs_media ON attempt_media_refs (media_asset_id);
```

- FK nhiều cột (`MATCH SIMPLE`, mặc định) bỏ qua dòng có ít nhất một cột NULL. Nhờ đó dòng code V13 ghi (`snapshot_id NULL`) và slot bỏ chọn (`selected_option_id NULL`) đều hợp lệ. Sau khi T3 đặt `snapshot_id NOT NULL`, mọi lựa chọn được chọn đều bị FK ép thuộc đúng snapshot.
- `ADD COLUMN id … DEFAULT gen_random_uuid()` dùng hàm volatile, nên **rewrite bảng** `attempt_question_snapshots` dưới khóa `ACCESS EXCLUSIVE`. Thời gian tỷ lệ với số dòng (Q7, S12).

### 4.5. `V15__practice_backfill_v13_attempts.sql`

```sql
-- V15: Backfill dữ liệu V13 sang JSON v1. Mọi khối 1–4 chạy lại được; T3 chạy lại nguyên văn (brief T1 §3.M1).

-- 0. Kiểm tra trước: mỗi snapshot V13 có đúng một lựa chọn đúng.
DO $$
DECLARE bad integer;
BEGIN
    SELECT count(*) INTO bad FROM (
        SELECT s.attempt_id, s.position
        FROM attempt_question_snapshots s
        LEFT JOIN attempt_snapshot_options o
               ON o.attempt_id = s.attempt_id AND o.question_position = s.position AND o.is_correct
        WHERE s.schema_version IS NULL
        GROUP BY s.attempt_id, s.position
        HAVING count(o.id) <> 1) x;
    IF bad > 0 THEN
        RAISE EXCEPTION 'V15: % snapshot V13 không có đúng một lựa chọn đúng', bad;
    END IF;
END $$;

-- 1. attempts
UPDATE attempts a
SET last_active_at     = COALESCE(a.submitted_at, a.updated_at),
    snapshot_sealed_at = a.started_at,
    source_config      = jsonb_build_object(
        'category', a.category,
        'questionCount', a.total_count,
        'topicIds', COALESCE(
            (SELECT jsonb_agg(t.topic_id::text ORDER BY t.topic_id::text)
             FROM attempt_source_topics t WHERE t.attempt_id = a.id),
            CASE WHEN a.source_topic_id IS NULL THEN '[]'::jsonb
                 ELSE jsonb_build_array(a.source_topic_id::text) END))
WHERE a.snapshot_sealed_at IS NULL;

UPDATE attempts a
SET answered_count        = (SELECT count(*) FROM attempt_answers aa
                             WHERE aa.attempt_id = a.id AND aa.selected_option_id IS NOT NULL),
    submit_reason         = 'MANUAL',
    effective_finished_at = a.submitted_at,
    duration_seconds      = GREATEST(0, floor(extract(epoch FROM a.submitted_at - a.started_at)))::int
WHERE a.status = 'SUBMITTED' AND a.scoring_version = 1 AND a.submit_reason IS NULL;

-- 2. attempt_question_snapshots
UPDATE attempt_question_snapshots s
SET schema_version         = 1,
    category               = 'GRAMMAR',
    display_number         = s.position,
    option_order           = 'FIXED',
    grading_policy_version = 'SINGLE_CHOICE_V1',
    question_snapshot      = jsonb_build_object(
        'schema_version', 1,
        'category', 'GRAMMAR',
        'part_number', 'null'::jsonb,
        'question_type_code', s.question_type_code,
        'practice_mode', s.practice_mode,
        'option_order', 'FIXED',
        'option_display', 'TEXT',
        'question_kind', 'STANDARD',
        'prompt', s.prompt,
        'refs', '[]'::jsonb,
        'materials', '[]'::jsonb,
        'meta', jsonb_build_object('topic_ids', jsonb_build_array(s.topic_id::text), 'word_sense_ids', '[]'::jsonb),
        'legacy_backfill', true),
    answer_key_snapshot    = (
        SELECT jsonb_build_object(
            'schema_version', 1,
            'correct_option_snapshot_id', o.id::text,
            'correct_option_ordinal', o.position,
            'explanation', s.explanation,
            'evidence', '[]'::jsonb)
        FROM attempt_snapshot_options o
        WHERE o.attempt_id = s.attempt_id AND o.question_position = s.position AND o.is_correct)
WHERE s.schema_version IS NULL;

-- 3. attempt_snapshot_options
UPDATE attempt_snapshot_options o
SET snapshot_id = s.id,
    label       = chr(64 + o.position)
FROM attempt_question_snapshots s
WHERE s.attempt_id = o.attempt_id AND s.position = o.question_position AND o.snapshot_id IS NULL;

-- 4. attempt_answers
UPDATE attempt_answers aa
SET snapshot_id = s.id,
    is_correct  = CASE WHEN a.status = 'SUBMITTED'
                       THEN aa.selected_option_id = (s.answer_key_snapshot->>'correct_option_snapshot_id')::uuid
                       ELSE NULL END
FROM attempt_question_snapshots s
JOIN attempts a ON a.id = s.attempt_id
WHERE s.attempt_id = aa.attempt_id AND s.position = aa.question_position AND aa.snapshot_id IS NULL;

-- 5. Kiểm tra sau (không chặn)
DO $$
DECLARE mismatched integer;
BEGIN
    SELECT count(*) INTO mismatched FROM attempts a
    WHERE a.status = 'SUBMITTED' AND a.scoring_version = 1
      AND a.correct_count <> (SELECT count(*) FROM attempt_answers aa WHERE aa.attempt_id = a.id AND aa.is_correct);
    IF mismatched > 0 THEN
        RAISE NOTICE 'V15: % lượt SUBMITTED có correct_count khác số is_correct tính lại', mismatched;
    END IF;
END $$;

ANALYZE attempts;
ANALYZE attempt_question_snapshots;
ANALYZE attempt_snapshot_options;
ANALYZE attempt_answers;
```

Kiểm chứng JSON backfill so với 02:
- `question_snapshot` dòng cũ thỏa `QuestionSnapshot`: GRAMMAR, `part_number` null, `option_display` TEXT, `question_kind` STANDARD, `prompt` là chuỗi, `materials` rỗng. Trường `legacy_backfill` được thêm ở delta 4.8.
- `answer_key_snapshot` thỏa `AnswerKeySnapshot`.

### 4.6. Seed `src/main/resources/db/seed/m05/V1__m05_local_seed.sql` `[CREATE]`

> Tên cũ `m05_local_seed.sql` được T2 đổi thành `V1__m05_local_seed.sql` để chạy qua Flyway API (mục 4.7). Nội dung file không đổi.

#### 4.6.1. Quy ước UUID và người dùng seed

UUID có dạng `e5000000-00TT-4000-8000-NNNNNNNNNNNN`. Phần `NNNNNNNNNNNN` là số thập phân đệm 0 tới 12 ký tự (vẫn là hex hợp lệ).

| TT | Thực thể | Quy tắc `N` |
|---|---|---|
| `01` | user seed | `000000000001` |
| `02` | `media_assets` | `00000000000m` (m = 1..9) |
| `10` | `content_items` QUESTION = `questions.id` | `0000000000QQ` (QQ = số câu) |
| `11` | revision của câu | như trên |
| `12` | `question_options` | `00000000QQ0O` (O = 1..4) |
| `13` | `question_materials` | `00000000QQ0M` |
| `20` | `content_items` QUESTION_GROUP = `question_groups.id` | `0000000000GG` |
| `21` | revision của nhóm | như trên |
| `22` | `group_materials` | `00000000GG0M` |
| `30` | `content_items` PRACTICE_SET = `practice_sets.id` | `0000000000SS` |
| `31` | revision của bộ | như trên |
| `32` | `set_items` | `00000000SSPP` |
| `40` | WORD item = `words.id`; `41` revision; `42` `word_senses` | `000000000001` |
| `50` | GRAMMAR_TOPIC item = `grammar_topics.id`; `51` revision | `000000000001` |

Người dùng seed:

```sql
INSERT INTO users (id, email, password_hash, display_name, status, email_verified_at)
VALUES ('e5000000-0001-4000-8000-000000000001', 'm05-seed@masterenglish.local', '!', 'M05 Seed',
        'ACTIVE', TIMESTAMPTZ '2026-11-26 00:00:00+00')
ON CONFLICT DO NOTHING;
```

`password_hash = '!'` không phải bcrypt hợp lệ, nên không đăng nhập được. Seed không gán role.

#### 4.6.2. Mẫu lệnh cho một mục nội dung (lặp cho mọi item)

```sql
INSERT INTO content_items (id, content_type, created_by)
VALUES ('<ITEM>', '<TYPE>', 'e5000000-0001-4000-8000-000000000001') ON CONFLICT DO NOTHING;

INSERT INTO content_revisions (id, content_item_id, version_no, schema_version, snapshot, status,
                               created_by, reviewed_by, reviewed_at, created_at, updated_at)
VALUES ('<REV>', '<ITEM>', 1, 1, '<ENVELOPE>'::jsonb, 'APPROVED',
        'e5000000-0001-4000-8000-000000000001', 'e5000000-0001-4000-8000-000000000001',
        TIMESTAMPTZ '2026-11-26 00:00:00+00', TIMESTAMPTZ '2026-11-26 00:00:00+00', TIMESTAMPTZ '2026-11-26 00:00:00+00')
ON CONFLICT DO NOTHING;

-- Chỉ chèn ref khi revision CHƯA publish (trigger V7 chạy BEFORE INSERT, ON CONFLICT không chặn được).
INSERT INTO revision_media_refs (revision_id, media_id, usage_role)
SELECT '<REV>', '<MEDIA>', 'STIMULUS'
WHERE NOT EXISTS (SELECT 1 FROM content_revisions WHERE id = '<REV>' AND status = 'PUBLISHED')
ON CONFLICT DO NOTHING;

UPDATE content_revisions SET status = 'PUBLISHED', published_at = TIMESTAMPTZ '2026-11-26 00:00:00+00'
WHERE id = '<REV>' AND status = 'APPROVED';

UPDATE content_items SET published_revision_id = '<REV>'
WHERE id = '<ITEM>' AND published_revision_id IS NULL;

-- Sau đó: các dòng projection (questions / question_groups / practice_sets / words / grammar_topics …) ON CONFLICT DO NOTHING.
```

Thứ tự trong file:
1. user
2. media
3. GRAMMAR_TOPIC
4. WORD
5. QUESTION_GROUP (item, revision, `question_groups`, `group_materials`)
6. QUESTION (item, revision, `questions`, `question_options`, `question_materials`, `question_topics`, `question_words`)
7. PRACTICE_SET (item, revision, `practice_sets`, `set_items`)

`media_assets` (9 dòng, `TT = 02`):
- `media_type`: `IMAGE` hoặc `AUDIO`; `purpose = 'CONTENT'`.
- `bucket = 'masterenglish-local-seed'`, `object_key = 'content/<uuid>.<ext>'`, `original_filename = 'm05-seed-<m>.<ext>'`.
- `mime_type`: `image/jpeg` hoặc `audio/mpeg`. `file_size_bytes`: 48000 (ảnh) hoặc 160000 (audio).
- `checksum_sha256 = lpad('<m>', 64, '0')`.
- Kích thước/thời lượng: ảnh `width = 1280, height = 853, duration_ms = NULL`; audio `duration_ms` theo bảng dưới, `width = height = NULL`.
- `status = 'READY'`, `uploaded_by` = user seed.

Ràng buộc liên quan: `media_assets_c8`, `c9`, `c13`, `c14`.

| m | Loại | Dùng cho | `duration_ms` |
|---|---|---|---|
| 1 | IMAGE | Q01 ảnh | — |
| 2 | AUDIO | Q01 | 19000 |
| 3 | IMAGE | Q02 ảnh | — |
| 4 | AUDIO | Q02 | 18000 |
| 5 | AUDIO | Q03 | 12000 |
| 6 | AUDIO | Q04 | 12000 |
| 7 | AUDIO | G01 (P3) | 41000 |
| 8 | AUDIO | G02 (P4) | 38000 |
| 9 | AUDIO | Q31 (VOCAB nghe) | 2000 |

#### 4.6.3. Danh mục nội dung seed

Không có câu nào trong danh mục này chép từ đề ETS. Đáp án đúng là ký hiệu **in đậm**.

**Nhóm TOEIC**

| G | Part | `group_type` | `title` | Học liệu (`document_key`: loại, vai trò, media) | JSON `body` |
|---|---|---|---|---|---|
| 01 | 3 | CONVERSATION | Seed P3 - Training room | `audio`: AUDIO, STIMULUS, m7 · `graphic`: TABLE, STIMULUS · `transcript`: TRANSCRIPT, TRANSCRIPT | `audio` `{"duration_seconds":41}`; `graphic` và `transcript` = 02 §5.2 |
| 02 | 4 | TALK | Seed P4 - Factory tour | `audio`: AUDIO, m8 · `schedule`: TABLE · `transcript`: TRANSCRIPT | 4.6.4 (a) |
| 03 | 6 | TEXT_COMPLETION | Seed P6 - Program order | `email`: RICH_TEXT | 02 §5.3 |
| 04 | 7 | PASSAGE | Seed P7 - Printer chat | `chat`: CHAT | 4.6.4 (b) |
| 05 | 7 | PASSAGE | Seed P7 - Quarterly lunch | `memo`: RICH_TEXT · `chat`: CHAT | 02 §5.4 |

**Câu TOEIC** (category TOEIC, `practice_mode = STANDARD`, `option_order = FIXED`, `difficulty = MEDIUM`)

| Q | Part | G/pos | Kind | `prompt` (P1/P2: NULL) | Lựa chọn | Ghi chú |
|---|---|---|---|---|---|---|
| 01 | 1 | — | STANDARD | NULL | A They're painting a doorway. · B They're folding some chairs. · **C They're carrying a table.** · D They're closing a window. | speaker `M-Au`. Học liệu: `photo` IMAGE m1 (`alt_text` 02 §5.5), `audio` AUDIO m2 |
| 02 | 1 | — | STANDARD | NULL | A She's hanging laundry. · **B She's watering some plants.** · C She's closing a window. · D She's sweeping the floor. | speaker `W-Am`; `photo` m3 `{"alt_text":"A woman is watering plants on a balcony."}`, `audio` m4 |
| 03 | 2 | — | STANDARD | NULL | **A In the tray on my desk.** · B Yes, I paid them yesterday. · C About twenty pages. | `prompt_speaker = W-Br`, `prompt_transcript = Where should I leave these invoices?`; lựa chọn speaker `M-Cn`; `audio` m5 |
| 04 | 2 | — | STANDARD | NULL | A In Conference Room 2. · **B Ms. Ito from the sales team.** · C It starts at nine. | `prompt_speaker = M-Au`, `Who's leading tomorrow's training session?`; lựa chọn `W-Am`; `audio` m6 |
| 05 | 3 | 01/1 | STANDARD | What problem does the woman mention? | A A room is too small. · **B Some equipment is broken.** · C A meeting was canceled. · D A colleague is late. | evidence `transcript` |
| 06 | 3 | 01/2 | GRAPHIC | Look at the graphic. Which room will the workshop most likely use? | A Room A · B Room B · C Room C · **D Room D** | refs `graphic`; evidence `graphic`, `transcript` |
| 07 | 3 | 01/3 | INTENT | Why does the man say, "That's the third time this month"? | **A To express frustration with a repeated problem.** · B To correct a schedule. · C To praise a coworker. · D To request a new projector model. | evidence `transcript` |
| 08 | 4 | 02/1 | STANDARD | Where most likely are the listeners? | A At a train station · **B At a factory** · C At a hotel · D At a museum | — |
| 09 | 4 | 02/2 | GRAPHIC | Look at the graphic. Which stop will be visited last? | A Reception · B Assembly line · **C Quality lab** · D Gift shop | refs `schedule` |
| 10 | 4 | 02/3 | STANDARD | What are listeners asked to do? | A Turn off their phones · **B Keep their badges on** · C Sign a form · D Wait at reception | — |
| 11 | 5 | — | STANDARD | The new software will be installed ------- the end of the month. | **A by** · B until · C since · D among | — |
| 12 | 5 | — | STANDARD | Ms. Park asked the team to submit their reports -------. | A prompt · **B promptly** · C prompted · D prompting | — |
| 13 | 5 | — | STANDARD | All visitors must ------- at the front desk upon arrival. | A registers · B registering · **C register** · D registered | — |
| 14 | 6 | 03/1 | STANDARD | NULL | **A confirmed** · B confirm · C confirming · D confirmation | `blank_key = b1` |
| 15 | 6 | 03/2 | STANDARD | NULL | A gloss · **B glossy** · C glossily · D glossiness | `b2` |
| 16 | 6 | 03/3 | SENTENCE_INSERTION | NULL | A Our office is closed on public holidays. · B Thank you for attending the conference. · **C We apologize for the delay this may cause.** · D The invoice was paid in full last week. | `b3` |
| 17 | 6 | 03/4 | STANDARD | NULL | **A before** · B during · C between · D among | `b4` |
| 18 | 7 | 04/1 | INTENT | At 9:17 A.M., what does Mr. Haddad mean when he writes, "I wouldn't count on it"? | **A The printer may still not work.** · B He has not counted the copies. · C He will repair the printer. · D The lobby is closed. | refs `chat#t0917`; evidence như refs |
| 19 | 7 | 04/2 | STANDARD | What will Ms. Silva probably do next? | A Call a technician · **B Use another printer** · C Go home early · D Order paper | evidence `chat#t0918` |
| 20 | 7 | 05/1 | INSERTION_POSITION | In which of the positions marked [1], [2], [3], and [4] does the following sentence best belong? | **A [1]** · B [2] · C [3] · D [4] | `insert_sentence = Lunch will be served at noon.`; refs `memo` |
| 21 | 7 | 05/2 | INTENT | At 10:47 A.M., what does Mr. Watanabe most likely mean when he writes, "That works"? | **A He accepts the delivery time.** · B He fixed a device. · C He will cancel the lunch. · D He needs a larger room. | refs `chat#t1047` |
| 22 | 7 | 05/3 | NOT_EXCEPT | What is NOT mentioned in the memo? | A The location of the lunch · B Vegetarian options · C A reply deadline · **D The price of the meal** | refs `memo` |
| 23 | 7 | 05/4 | STANDARD | When must staff confirm attendance? | A Monday · **B Wednesday** · C Friday · D Saturday | evidence `memo` |
| 24 | 7 | 05/5 | STANDARD | Who will update the memo? | **A Ms. Lindqvist** · B Mr. Watanabe · C The caterer · D Reception | evidence `chat#t1049` |

Mỗi câu có `explanation` là một câu tiếng Anh ngắn giải thích đáp án, không chứa HTML. `refs` và `evidence` dạng `[{"material_key":"chat","anchor":"t0917"}]`. Khi trong bảng ghi "evidence như refs" thì hai mảng có cùng nội dung.

**VOCABULARY** (word W01 `resilient`: `words.lemma = 'resilient'`, `ipa = '/rɪˈzɪliənt/'`; sense 01 `ADJECTIVE`, `B2`, `meaning_vi = 'kiên cường, phục hồi nhanh'`, `sense_no = 1`)

| Q | `practice_mode` | `prompt` | Lựa chọn | Ghi chú |
|---|---|---|---|---|
| 31 | LISTEN_AND_CHOOSE | NULL | **A resilient** · B reliant · C resident · D relevant | `question_materials`: `audio` AUDIO m9 `{"duration_seconds":2}`; `question_words` → sense 01 |
| 32 | TRANSLATE_EN_VI | resilient | **A kiên cường, phục hồi nhanh** · B tạm thời · C dễ vỡ · D đắt đỏ | `question_words` → sense 01 |
| 33 | STANDARD | The supply chain proved ------- during the storm. | **A resilient** · B resiliently · C resilience · D resiliency | `question_words` → sense 01 |

**GRAMMAR** (topic T01: `slug = 'm05-seed-prepositions-of-time'`, `name = 'Prepositions of time'`, `difficulty = 'BASIC'`, `sort_order = 900`; mọi câu có `question_topics` → T01, `practice_mode = STANDARD`)

| Q | `prompt` | Lựa chọn |
|---|---|---|
| 41 | The store opens ------- 9 A.M. | **A at** · B on · C in · D by |
| 42 | We met ------- Monday. | A at · **B on** · C in · D since |
| 43 | She was born ------- 1998. | A at · B on · **C in** · D for |

**Practice sets**

| S | `category` | `title` | `selection_mode` | `part_number` | `selection_config` | Items (position: tham chiếu) |
|---|---|---|---|---|---|---|
| 01 | TOEIC | Seed TOEIC mixed (P1-P7) | FIXED | NULL | `{}` | 1:Q01, 2:Q02, 3:Q03, 4:Q04, 5:G01, 6:G02, 7:Q11, 8:Q12, 9:Q13, 10:G03, 11:G04, 12:G05. Seal ra 24 câu, `display_number` 1–24. |
| 02 | VOCABULARY | Seed vocabulary - resilient | FIXED | NULL | `{}` | 1:Q31, 2:Q32, 3:Q33 |
| 03 | GRAMMAR | Seed grammar - prepositions of time | DYNAMIC | NULL | `{"questionCount":3}` | Không có `set_items`; `topic_id = T01` |

#### 4.6.4. JSON học liệu mới (ngoài 02)

(a) G02 — Part 4, một người nói, có lịch trình:

```json
{ "title": "Factory tour - Friday", "headers": ["Stop", "Time"],
  "rows": [ ["Reception", "10:00 A.M."], ["Assembly line", "10:20 A.M."], ["Quality lab", "10:50 A.M."], ["Gift shop", "11:20 A.M."] ] }
```

```json
{ "segments": [
  { "type": "speech", "speaker": "N", "text": "Questions refer to the following talk and schedule." },
  { "type": "speech", "speaker": "W-Br", "text": "Welcome to Norvik Electronics. Before we begin, please note one change to the schedule. The quality lab is being cleaned this morning, so we will visit it last, right after the gift shop. Please keep your visitor badges on at all times." },
  { "type": "pause", "seconds": 8 } ] }
```

(b) G04 — Part 7, một văn bản chat:

```json
{ "channel": "ONLINE_CHAT", "title": "Team chat",
  "messages": [
    { "anchor": "t0915", "author": "Hana Silva", "time": "9:15 A.M.", "text": "Is the printer on the third floor working again?" },
    { "anchor": "t0917", "author": "Omar Haddad", "time": "9:17 A.M.", "text": "I wouldn't count on it." },
    { "anchor": "t0918", "author": "Hana Silva", "time": "9:18 A.M.", "text": "OK, I'll use the one in the lobby." } ] }
```

Khi đưa vào SQL, mọi dấu `'` trong JSON phải được nhân đôi (`wouldn''t`, `I''ll`).

#### 4.6.5. Mẫu envelope (câu 06, nhóm 01, bộ 01)

```json
{ "schema_version": 1, "content_type": "QUESTION",
  "data": { "category": "TOEIC", "part_number": 3, "question_type_code": "SINGLE_CHOICE", "practice_mode": "STANDARD",
    "difficulty": "MEDIUM", "question_kind": "GRAPHIC", "option_order": "FIXED",
    "prompt": "Look at the graphic. Which room will the workshop most likely use?",
    "prompt_transcript": null, "insert_sentence": null, "blank_key": null,
    "group": { "group_id": "e5000000-0020-4000-8000-000000000001", "group_position": 2 },
    "options": [
      { "id": "e5000000-0012-4000-8000-000000000601", "position": 1, "body": "Room A" },
      { "id": "e5000000-0012-4000-8000-000000000602", "position": 2, "body": "Room B" },
      { "id": "e5000000-0012-4000-8000-000000000603", "position": 3, "body": "Room C" },
      { "id": "e5000000-0012-4000-8000-000000000604", "position": 4, "body": "Room D" } ],
    "answer_key": { "correctOptionId": "e5000000-0012-4000-8000-000000000604" },
    "explanation": "Room D is free only in the afternoon and the workshop moves to two thirty.",
    "refs": [ { "material_key": "graphic" } ],
    "evidence": [ { "material_key": "graphic" }, { "material_key": "transcript" } ],
    "materials": [], "topic_ids": [], "word_sense_ids": [] } }
```

```json
{ "schema_version": 1, "content_type": "QUESTION_GROUP",
  "data": { "category": "TOEIC", "part_number": 3, "group_type": "CONVERSATION", "title": "Seed P3 - Training room",
    "materials": [
      { "material_key": "audio", "material_type": "AUDIO", "visibility": "BEFORE_SUBMIT", "position": 1,
        "media_asset_id": "e5000000-0002-4000-8000-000000000007", "body": { "duration_seconds": 41 } } ] } }
```

Ví dụ trên rút gọn còn một học liệu. File seed phải liệt kê đủ cả 3 học liệu của G01.

```json
{ "schema_version": 1, "content_type": "PRACTICE_SET",
  "data": { "category": "TOEIC", "title": "Seed TOEIC mixed (P1-P7)", "part_number": null, "selection_mode": "FIXED",
    "selection_config": {}, "time_limit_seconds": null, "deck_id": null, "topic_id": null,
    "items": [ { "position": 1, "question_id": "e5000000-0010-4000-8000-000000000001" },
               { "position": 5, "group_id": "e5000000-0020-4000-8000-000000000001" } ] } }
```

Ví dụ trên rút gọn còn 2 item. File seed phải liệt kê đủ 12 item.

Envelope WORD và GRAMMAR_TOPIC theo định dạng hiện có:
- WORD: `data{lemma, language_code, ipa, senses[{id, sense_no, part_of_speech, cefr_level, meaning_vi}]}`.
- GRAMMAR_TOPIC: `data{slug, name, description, difficulty, sort_order}`.

### 4.7. Runner `LocalM05SeedRunner` `[CREATE]`

> **Sửa bởi T2 (26/11/2026). Phiên bản dưới đây thay cho đoạn code cũ, được giữ ở cuối mục để truy vết.**
>
> Lý do: `LayerArchitectureTests.databaseAccessGoesThroughSpringDataRepositories` (dòng 117–124) cấm mọi class main trong `com.masterenglish.api` phụ thuộc `org.springframework.jdbc..`, `java.sql..`, `javax.sql..`. `DataSource`, `ScriptUtils` và `DataSourceUtils` đều vi phạm rule này. Test không bị ràng buộc vì rule dùng `DO_NOT_INCLUDE_TESTS`.
>
> Cách thay: runner dùng Flyway API với cấu hình của bean `Flyway` có sẵn, nhưng trỏ tới location và bảng lịch sử riêng:
> - File seed đổi tên thành `src/main/resources/db/seed/m05/V1__m05_local_seed.sql`.
> - Flyway chạy file trong một transaction, nên FK `DEFERRABLE` được kiểm lúc commit.
> - File được phép dùng `DO $$` (bỏ ràng buộc T1-D15 cho seed).
> - Lệnh trong file vẫn chạy lại được.
>
> ```java
> @Component
> @Profile("local")
> @ConditionalOnProperty(name = "app.m05.seed", havingValue = "true")
> public class LocalM05SeedRunner implements ApplicationRunner {
>     static final String LOCATION = "classpath:db/seed/m05";
>     static final String HISTORY_TABLE = "flyway_m05_seed_history";
>     private final Flyway flyway;
>
>     public LocalM05SeedRunner(Flyway flyway) {
>         this.flyway = flyway;
>     }
>
>     @Override
>     public void run(ApplicationArguments arguments) {
>         seedFlyway(flyway).migrate();
>     }
>
>     /** Shared with tests so local seeding and test seeding run the same script the same way. */
>     public static Flyway seedFlyway(Flyway base) {
>         return Flyway.configure()
>                 .configuration(base.getConfiguration())
>                 .locations(LOCATION)
>                 .table(HISTORY_TABLE)
>                 .baselineOnMigrate(true)
>                 .baselineVersion("0")
>                 .load();
>     }
> }
> ```
>
> Ảnh hưởng tới test T1:
> - `M05SeedIntegrationTests` gọi `LocalM05SeedRunner.seedFlyway(flyway).migrate()` (inject bean `Flyway`).
> - `seedIsIdempotent` kiểm tra 3 việc:
>   1. Gọi `migrate()` lần hai không áp dụng thêm gì.
>   2. Chạy lại file SQL bằng `ScriptUtils` trong test (được phép) không sinh dòng mới.
>   3. `LayerArchitectureTests` vẫn xanh.
>
> Bảng `flyway_m05_seed_history` chỉ tồn tại ở DB local hoặc test.

Đoạn code cũ, đã bị thay (giữ để truy vết). Package `com.masterenglish.api.infrastructure.runner`, theo mẫu `LocalVocabularySeedRunner`:

```java
/** Explicit local fixture loading only; never enabled by production startup. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.m05.seed", havingValue = "true")
public class LocalM05SeedRunner implements ApplicationRunner {
    static final String SCRIPT = "db/seed/m05/m05_local_seed.sql";
    private final DataSource dataSource;
    private final TransactionTemplate transactions;

    public LocalM05SeedRunner(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        transactions.executeWithoutResult(status ->
                ScriptUtils.executeSqlScript(
                        DataSourceUtils.getConnection(dataSource),
                        new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8)));
    }
}
```

- Chạy trong một transaction, để FK `DEFERRABLE` được kiểm lúc commit và lỗi giữa chừng không để lại dữ liệu dở dang.
- `application-local.properties` `[MODIFY]`: thêm dòng `app.m05.seed=${M05_SEED:false}`.

### 4.8. Delta tài liệu (tương thích ngược, vẫn `schema_version = 1`)

`docs/m05/02-snapshot-json-schemas-v1.md` `[MODIFY]`, trong `$defs.QuestionSnapshot`:
- `properties.category.enum` → `["VOCABULARY","GRAMMAR","TOEIC"]`.
- `properties.practice_mode.enum` → `["STANDARD","TRANSLATE_EN_VI","TRANSLATE_VI_EN","LISTEN_AND_CHOOSE","FILL_OPTION"]`.
- Thêm `properties.legacy_backfill`: `{ "const": true }`. Chỉ dòng backfill từ V13 có trường này. Mapper bỏ qua trường.
- Thêm vào `allOf`:
  - Nhánh VOCABULARY:
    ```json
    { "if": { "properties": { "category": { "const": "VOCABULARY" } } },
      "then": { "properties": { "part_number": { "const": null }, "option_display": { "const": "TEXT" },
                                "question_kind": { "const": "STANDARD" }, "materials": { "maxItems": 2 } } } }
    ```
  - Thu hẹp `practice_mode` cho GRAMMAR:
    ```json
    { "if": { "properties": { "category": { "const": "GRAMMAR" } } },
      "then": { "properties": { "practice_mode": { "enum": ["STANDARD", "FILL_OPTION"] } } } }
    ```
- Thêm V-13: `prompt = null` chỉ được phép với TOEIC P1/P2/P6, hoặc VOCABULARY `LISTEN_AND_CHOOSE`.
- §2: ghi `attempt_group_snapshots.source_group_id`, `private_snapshot` NULL khi không có học liệu sau nộp, `attempt_media_refs(media_asset_id)`.

`docs/practice/CONTRACT.md` `[MODIFY]`: thêm vào §14.4:
- `attempts.request_id` là nơi lưu `Idempotency-Key`.
- Cột `snapshot_id` trên options và answers.
- Cột `option_order` trên snapshot.
- Cột P2 `prompt_speaker`/`prompt_transcript`.
- `question_options.speaker`.
- ~~Danh sách cột ước tính 990 (nullable).~~ (bỏ theo AD-1)

---

## 5. Hợp đồng giao tiếp

T1 **không** đổi HTTP API, facade `common/events` hay event. Hợp đồng mà T1 tạo ra:

| Hợp đồng | Nội dung |
|---|---|
| Schema DB | Mục 4.1–4.4. T3 chỉ được thêm `NOT NULL` và trigger theo 3.M1. |
| Backfill | V15 chạy lại được. Khối 1–4 là API dữ liệu cho T3. |
| Envelope revision TOEIC | 3.M8, 4.6.5. Validator ở task projection phải nhận đúng định dạng này. |
| Cấu hình | `app.m05.seed` (mặc định `false`, chỉ có tác dụng dưới profile `local`) |
| Dữ liệu seed | UUID cố định (4.6.1). T3, T6, T7 tham chiếu trực tiếp các UUID này trong test và fixture. |

Lỗi khi migration thất bại do vi phạm ràng buộc: PostgreSQL trả `SQLSTATE 23514` (check), `23503` (FK), `23505` (unique). Flyway dừng và đánh dấu version thất bại. Không có `ProblemDetail` vì không đi qua HTTP.

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn

| Rủi ro | Biện pháp trong T1 |
|---|---|
| Seed chạy ở production | Ba lớp chặn: `@Profile("local")`; `app.m05.seed` mặc định `false`; Flyway chỉ quét `classpath:db/migration` (`application.properties:5`), không quét `db/seed` |
| Dùng tài khoản seed để đăng nhập | `password_hash = '!'` không phải bcrypt, mọi lần kiểm mật khẩu đều thất bại. Không gán role. |
| Đáp án lộ qua tên file media | `object_key = content/<uuid>.<ext>` (ADR-08). `original_filename` không chứa nội dung đề. |
| Chọn lựa chọn của câu khác | FK `fk_attempt_answers_snapshot_option` và `attempt_answers_c3` của V13 |
| `snapshot_id` lệch với `(attempt_id, position)` | FK `(attempt_id, question_position, snapshot_id) → (attempt_id, position, id)` trên cả options và answers |
| Transcript bị ghi nhầm thành `STIMULUS` | `group_materials_c8`: `TRANSCRIPT` khi và chỉ khi `usage_role = 'TRANSCRIPT'`. Do đó `visibility` của transcript luôn là `AFTER_SUBMIT`. |
| Xáo thứ tự lựa chọn TOEIC | `questions_c15` |
| Race khi migrate | Flyway chạy mỗi file trong một transaction và giữ advisory lock của Flyway. Ứng dụng chưa nhận request khi migrate lúc khởi động. |
| Lock khi migrate dữ liệu lớn | `ADD COLUMN id … gen_random_uuid()` rewrite bảng; `ADD CONSTRAINT CHECK` quét bảng. Nếu Q7 cho thấy `attempt_question_snapshots` > 1 triệu dòng, chuyển các CHECK của V14 sang `NOT VALID` và `VALIDATE` ở V15 (câu hỏi O3). |
| `is_correct` lộ trước khi nộp | V15 chỉ ghi `is_correct` cho lượt `SUBMITTED` |

---

## 7. Ranh giới Spring Modulith và tài nguyên

- **DAG không đổi.**
  - Migration là SQL, không thuộc module nào.
  - Runner nằm ở `infrastructure.runner` như hai runner hiện có. Nó chỉ phụ thuộc `DataSource` và `PlatformTransactionManager`, không import module nghiệp vụ. `ArchitectureTests` không đổi.
- **FK UUID.** Bảng mới có FK DB xuyên module (`media_assets`, `content_revisions`, `word_senses`, `grammar_topics`, `decks`), theo tiền lệ V13. Không có entity JPA nào trong T1.
- **Round-trip.**
  - Flyway chạy V14 (khoảng 40 câu lệnh DDL) và V15 (khoảng 9 câu lệnh) trong 2 transaction, một lần lúc khởi động. Không ảnh hưởng pool 5 kết nối khi chạy.
  - Seed khoảng 600 câu lệnh trong 1 transaction, chỉ chạy ở local.
- **RAM PostgreSQL 384 MiB.**
  - Không GIN.
  - Index mới ước khoảng 140 KB mỗi Full Test (3.M5).
  - `fillfactor = 90` trên `attempt_answers` để có HOT update.
  - `ANALYZE` sau backfill để planner có thống kê đúng.
- **RAM API 768 MiB.** T1 không thêm bean chạy ở production.

---

## 8. Edge Cases & Failure Modes Matrix

| # | Tình huống | Nguyên nhân | Hành vi | Kết quả |
|---|---|---|---|---|
| 1 | Snapshot V13 không có lựa chọn đúng, hoặc có hai | Dữ liệu lỗi cũ | V15 khối 0 `RAISE EXCEPTION` | Flyway dừng ở V15, V14 vẫn đã áp dụng. Cần sửa dữ liệu thủ công rồi chạy lại. |
| 2 | `correct_count` cũ lệch với số tính lại | Lỗi chấm cũ | `RAISE NOTICE` | Migrate thành công, có cảnh báo trong log |
| 3 | Code V13 ghi lượt mới sau khi deploy T1 | Cửa sổ T1→T3 | Dòng `schema_version NULL` hợp lệ theo `c10` | T3 backfill lại (3.M1) |
| 4 | Code V13 nộp lượt sau T1 | Như trên | `scoring_version = 1`; `c26` không áp dụng | Hợp lệ |
| 5 | Chạy seed hai lần | Dev bật lại cờ | Mọi lệnh có điều kiện, không lỗi | 0 dòng mới |
| 6 | Chạy seed khi revision đã `PUBLISHED` | Lần chạy thứ hai | `INSERT … SELECT … WHERE NOT EXISTS` không sinh dòng, trigger V7 không chạy | Không lỗi |
| 7 | Bật `app.m05.seed=true` ở production | Cấu hình sai | Runner không được tạo vì profile không phải `local` | Không có gì xảy ra |
| 8 | Ghi `attempts.status = 'EXPIRED'` | Code sai | `attempts_c16` | `23514` |
| 9 | Câu TOEIC `option_order = 'SHUFFLED'` | Nhập liệu sai | `questions_c15` | `23514` |
| 10 | Câu P6 thiếu `blank_key`, hoặc câu P5 có `blank_key` | Nhập liệu sai | `questions_c14` | `23514` |
| 11 | Đáp án trỏ tới lựa chọn của snapshot khác | Bug client hoặc code | `fk_attempt_answers_snapshot_option` | `23503` |
| 12 | `group_materials` AUDIO không có `media_id` | Nhập liệu sai | `c7` | `23514` |
| 13 | TRANSCRIPT với `usage_role = STIMULUS` | Nhập liệu sai | `c8` | `23514` |
| 14 | `total_count = 201` | Code sai | `attempts_c17` | `23514` |
| 15 | Xóa `media_assets` đang được lượt làm tham chiếu | GC | FK RESTRICT; tra theo `ix_attempt_media_refs_media` | `23503` |
| 16 | Câu VOCABULARY `LISTEN_AND_CHOOSE` không có `prompt` | Hợp lệ | `questions_c10` cho phép | OK |
| 17 | Câu GRAMMAR không có `prompt` | Nhập liệu sai | `questions_c10` | `23514` |
| 18 | `private_snapshot = '{"schema_version":1,"materials":[]}'` | Code ghi rỗng thay vì NULL | `attempt_group_snapshots_c10` | `23514`. Phải ghi NULL. |
| 19 | Rollback ứng dụng về bản trước T1 sau khi V14/V15 đã chạy | Sự cố deploy | Code V13 vẫn chạy được (3.M6) | Không cần rollback DB |
| 20 | Dev local mở được URL media seed | Không có file trong R2 | R2 trả 404 | Đã biết (O1) |

---

## 9. Kế hoạch chỉnh sửa file

| Đường dẫn | Hành động | Nội dung |
|---|---|---|
| `src/main/resources/db/migration/V14__practice_toeic_schema_expand.sql` | CREATE | Mục 4.1–4.4 |
| `src/main/resources/db/migration/V15__practice_backfill_v13_attempts.sql` | CREATE | Mục 4.5 |
| `src/main/resources/db/seed/m05/V1__m05_local_seed.sql` (tên cũ `m05_local_seed.sql`, đổi bởi T2) | CREATE | Mục 4.6 |
| `src/main/java/com/masterenglish/api/infrastructure/runner/LocalM05SeedRunner.java` | CREATE | Mục 4.7 |
| `src/main/resources/application-local.properties` | MODIFY | `app.m05.seed=${M05_SEED:false}` |
| `src/test/java/com/masterenglish/api/modules/practice/migration/PracticeSchemaV14Tests.java` | CREATE | 11.2 |
| `src/test/java/com/masterenglish/api/modules/practice/migration/PracticeV13ToV15UpgradeTests.java` | CREATE | 11.2 |
| `src/test/java/com/masterenglish/api/modules/practice/migration/M05SeedIntegrationTests.java` | CREATE | 11.2 |
| `docs/m05/02-snapshot-json-schemas-v1.md` | MODIFY | Delta 4.8 |
| `docs/practice/CONTRACT.md` | MODIFY | §14.4 (4.8) |
| `docs/m05/tasks/T1-brief-flyway-migration-and-seed.md` | MODIFY | Trạng thái sau khi làm xong |
| `src/main/resources/db/migration/V1__…` → `V13__…` | DO NOT TOUCH | D8 v2 |
| `src/main/java/com/masterenglish/api/modules/**` | DO NOT TOUCH | 3.M6 |
| `src/main/resources/application.properties` | DO NOT TOUCH | Flyway location giữ `classpath:db/migration` |
| `src/test/java/**` hiện có | DO NOT TOUCH | Mọi test cũ phải xanh mà không sửa |
| `docs/database/proposals/approved-baseline.sql`, `DATA_DICTIONARY.md` | DO NOT TOUCH | Lệch được ghi ở CONTRACT §14.4 |

---

## 10. Lộ trình commit

Commit chỉ khi người dùng yêu cầu. Mỗi bước chạy được độc lập và phải xanh trước khi sang bước sau. Cần Docker (Testcontainers). Trên PowerShell dùng `.\gradlew.bat`.

| Bước | Nội dung | Lệnh kiểm tra | Commit đề xuất |
|---|---|---|---|
| 1 | V14 + `PracticeSchemaV14Tests` | `.\gradlew.bat test --tests '*PracticeSchemaV14Tests' --tests 'com.masterenglish.api.modules.practice.*' --tests '*ArchitectureTests'` rồi `.\gradlew.bat spotlessCheck` | `feat(practice): mở rộng schema TOEIC ở V14` |
| 2 | V15 + `PracticeV13ToV15UpgradeTests` | Như bước 1, thêm `--tests '*PracticeV13ToV15UpgradeTests' --tests '*VocabularyUpgradeTests' --tests '*VocabularyDatabaseMigrationTests'` | `feat(practice): backfill lượt làm V13 sang snapshot v1` |
| 3 | Seed SQL + runner + properties + `M05SeedIntegrationTests` | `--tests '*M05SeedIntegrationTests' --tests '*GrammarPracticeControllerIntegrationTests'`, `spotlessCheck` | `feat(practice): thêm seed M05 cho môi trường local` |
| 4 | Delta 02 và CONTRACT | Chạy lại C-01/C-02 (script T0) trên `02-…md` | `docs(m05): cập nhật schema v1 và hợp đồng theo T1` |
| 5 | Cổng cuối | `.\gradlew.bat check bootJar` | — |

---

## 11. Test Matrix

### 11.1. Test hiện có phải giữ xanh, không sửa

`PracticeIntegrationTests`, `GrammarPracticeControllerIntegrationTests`, `QuestionPublicationIntegrationTests`, `QuestionSnapshotValidatorTests`, `SelectionPlannerTests`, `VocabularyUpgradeTests`, `VocabularyDatabaseMigrationTests`, `ArchitectureTests`, `LayerArchitectureTests`, `ErrorHandlingTests`, cùng toàn bộ `check`.

### 11.2. Test mới

**`PracticeSchemaV14Tests`** (Testcontainers PostgreSQL 17, migrate tới bản mới nhất, mỗi test chạy trong một transaction rồi rollback):

| Test | Assert |
|---|---|
| `toeicQuestionMustUseFixedOptionOrder` | INSERT câu TOEIC `SHUFFLED` → `23514` (`questions_c15`) |
| `part6RequiresBlankKeyAndOthersForbidIt` | P6 thiếu `blank_key` → `23514`; P5 có `blank_key` → `23514` |
| `part1And2MustNotHavePrompt` | P1 có `prompt` → `23514` (`c19`) |
| `part2RequiresSpokenPrompt` | P2 thiếu `prompt_transcript` → `23514` (`c18`) |
| `insertionPositionRequiresSentence` | `INSERTION_POSITION` thiếu `insert_sentence` → `23514` |
| `statusExpiredIsRejected` | `status = 'EXPIRED'` → `23514` (`attempts_c16`) |
| `totalCountUpTo200` | 200 → OK; 201 → `23514` |
| `answerCannotPointToOtherSnapshotOption` | `(snapshot_id A, option của snapshot B)` → `23503` |
| `transcriptMaterialIsAlwaysAfterSubmit` | TRANSCRIPT + STIMULUS → `23514`; TRANSCRIPT + TRANSCRIPT → `visibility = 'AFTER_SUBMIT'` |
| `audioMaterialRequiresMedia` | AUDIO thiếu `media_id` → `23514` |
| `fixedSetHasEmptyConfigDynamicHasConfig` | FIXED + `{"questionCount":3}` → `23514`; DYNAMIC + `{}` → `23514` |
| `toeicSetMayHaveNullPartNumber` | TOEIC `part_number NULL` → OK |
| `privateSnapshotMustBeNullWhenEmpty` | `{"schema_version":1,"materials":[]}` → `23514` |
| ~~`scoreEstimatesAreAllOrNothing`~~ (bỏ theo AD-1) `noScoreEstimateColumns` | `information_schema.columns` của `attempts` không có cột nào tên chứa `scaled_estimate` hay `conversion_table` |

**`PracticeV13ToV15UpgradeTests`** (mẫu `VocabularyUpgradeTests`: `Flyway.configure().target("13")`, chèn dữ liệu, rồi migrate tiếp):

| Test | Assert |
|---|---|
| `submittedAndInProgressGrammarAttemptsAreBackfilled` | Lượt SUBMITTED và lượt IN_PROGRESS (mỗi lượt 3 câu, có trả lời và bỏ trống):<br>• mọi snapshot `schema_version = 1`; `question_snapshot ->> 'legacy_backfill' = 'true'`;<br>• `answer_key_snapshot ->> 'correct_option_snapshot_id'` = `id` của lựa chọn `is_correct`;<br>• options có `label` A–D, `snapshot_id` đúng; answers có `snapshot_id`;<br>• lượt SUBMITTED: `is_correct` khớp, `answered_count`, `submit_reason = 'MANUAL'`, `duration_seconds ≥ 0`;<br>• lượt IN_PROGRESS: `is_correct IS NULL`;<br>• `prompt`, `explanation` cũ giữ nguyên. |
| `legacyRowsKeepScoringVersion1` | `scoring_version = 1`, `score` không đổi |
| `backfillIsRerunnable` | Chạy lại khối 1–4 bằng JDBC → 0 dòng bị cập nhật |
| `precheckAbortsOnMissingCorrectOption` | DB thứ hai: snapshot V13 có 0 lựa chọn đúng → migrate V15 ném `FlywayException`, message chứa `V15:` |
| `v13CodeStillWorksAfterUpgrade` | Sau upgrade, gọi `PracticeService` hiện có (start GRAMMAR, save, submit) → thành công; dòng mới có `schema_version IS NULL` |

**`M05SeedIntegrationTests`** (Testcontainers, nạp seed bằng `ScriptUtils`):

| Test | Assert |
|---|---|
| `seedLoadsExpectedCounts` | `content_items` 40, `media_assets` 9, `question_groups` 5, `group_materials` 10, `questions` 30 (24 + 3 + 3), `question_options` 118, `question_materials` 7, `practice_sets` 3, `set_items` 15, `question_words` 3, `question_topics` 3 |
| `seedIsIdempotent` | Chạy 2 lần → số dòng không đổi, không ném lỗi |
| `seedRunnerNotLoadedWithoutLocalProfile` (thêm ở rà soát chéo T0–T7, phủ DoD 6) | `ApplicationContextRunner` với `app.m05.seed=true`: không có profile `local` → không có bean `LocalM05SeedRunner`; có `local` → có bean. Profile `local` nhưng `app.m05.seed` vắng hoặc `false` → không có bean. |
| `allSeedRevisionsArePublishedAndProjected` | Mọi `content_items` của seed có `published_revision_id` khớp revision `PUBLISHED`. Mọi revision có media đều có `revision_media_refs`. |
| `noTemplateTokensInBodies` | `SELECT count(*) FROM group_materials WHERE body::text LIKE '%{{%'` = 0 (cùng cho `question_materials`) |
| `part6BlankKeysMatchQuestions` | V-03: các `blank_key` trong `body` của G03 bằng đúng tập `blank_key` của câu thuộc G03 = `{b1,b2,b3,b4}` |
| `part7InsertPositionsComplete` | V-04: `memo` của G05 có đủ `insert.position` 1–4 |
| `chatAnchorsResolve` | V-05: mọi `refs`/`evidence` có `anchor` đều trỏ tới `messages[].anchor` tồn tại |
| `everyTranscriptIsAfterSubmit` | `group_materials WHERE material_type = 'TRANSCRIPT'` → mọi `visibility = 'AFTER_SUBMIT'` |
| ~~`grammarSeedWorksWithExistingEndpoint`~~ (thay bởi T3 §4.10, vì T3 gỡ endpoint cũ) `grammarSeedWorksWithNewEndpoint` | ~~`POST /api/v1/grammar/topics/{T01}/attempts` với `questionCount = 3` → 201 (endpoint cũ vẫn chạy trên dữ liệu seed)~~ `POST /api/v1/attempts` với `{"source":{"type":"GRAMMAR_TOPIC","topicId":"{T01}"},"questionCount":3}` và `Idempotency-Key` → 201. Test này viết ở T1 với endpoint cũ, rồi chuyển sang endpoint mới ở commit T3 gỡ endpoint cũ. |

Cách đếm:
- `question_options`: P1 2×4 + P2 2×3 + 20 câu TOEIC khác ×4 + VOCAB 3×4 + GRAMMAR 3×4 = 8 + 6 + 80 + 12 + 12 = **118**.
- `group_materials`: G01 3, G02 3, G03 1, G04 1, G05 2 = **10**.
- `question_materials`: Q01 2, Q02 2, Q03 1, Q04 1, Q31 1 = **7**.
- `content_items`: 30 câu + 5 nhóm + 3 bộ + 1 từ + 1 chủ điểm = **40**.

**Kiểm tra tài liệu (offline, không commit script):** xuất `body` của mọi học liệu seed bằng test helper ra file tạm, rồi validate bằng script JSON Schema của T0 (venv `jsonschema`) trên 02 sau delta. Kết quả ghi vào PR.

### 11.3. Concurrency và ArchUnit

- Không có logic đồng thời mới ở T1. Test đồng thời của CAS thuộc T3.
- `ArchitectureTests` và `LayerArchitectureTests` chạy lại không đổi, để chắc runner mới không tạo phụ thuộc module.

---

## 12. Definition of Done & Open Decisions

### 12.1. DoD

| # | Tiêu chí | Pass/Fail |
|---|---|---|
| 1 | V13 không đổi (`git diff --stat` không có V1–V13) | ☐ |
| 2 | V14, V15 chạy sạch trên DB trống và trên DB có dữ liệu V13 | ☐ |
| 3 | Toàn bộ test 11.1 xanh, không sửa test cũ | ☐ |
| 4 | Toàn bộ test 11.2 xanh | ☐ |
| 5 | `.\gradlew.bat spotlessCheck check bootJar` xanh | ☐ |
| 6 | Seed chạy 2 lần không lỗi; không chạy khi thiếu profile `local` | ☐ |
| 7 | Delta 02 và CONTRACT §14.4 đã ghi; C-01/C-02 đạt | ☐ |
| 8 | Hợp đồng bàn giao 3.M1 được ghi vào brief T3 | ☐ |
| 9 | Đã chạy Q7 (S12) trên production, có số dòng trước khi deploy | ☐ |

### 12.2. Câu hỏi cần duyệt

| # | Câu hỏi | Đề xuất |
|---|---|---|
| O1 | Media seed không có file thật nên R2 trả 404 ở local. Có cần thêm file mẫu (ảnh/MP3 nhỏ, tự tạo) và công cụ đẩy lên bucket local ở T1 không? | Không ở T1. Làm ở T6 khi frontend cần phát audio. |
| O2 | Giữ cả FK V13 `attempt_answers_c3` lẫn FK mới `(snapshot_id, selected_option_id)` cho tới khi T3 chọn một đường đọc? | Giữ cả hai. T3 có thể xóa `c3` bằng migration riêng nếu muốn. |
| O3 | Nếu Q7 cho thấy dữ liệu lớn (> 1 triệu snapshot), chuyển CHECK sang `NOT VALID` + `VALIDATE` (thêm V16)? | Có, quyết định sau khi có số liệu Q7. |
| O4 | Chấp nhận backfill `meta.topic_ids = [topic_id]` (mất đa nhãn của câu cũ)? | Chấp nhận. Thống kê đa nhãn chưa triển khai (CONTRACT §7.3). |
