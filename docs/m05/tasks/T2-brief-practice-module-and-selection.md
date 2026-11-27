# T2 — Implementation Brief: Module `practice` — catalog, selection engine, media qua `content`, facade `common/events`

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026. O1–O4 theo đề xuất ở 12.2: O1 chưa mở DYNAMIC TOEIC; O2 đổi S11 sang redirect 5 phút; O3 đợi index; O4 gỡ API A6. |
| Ngày | 26/11/2026 |
| Phụ thuộc | T0 (đã duyệt), T1 (đã duyệt, đã sửa §4.7 do drift T2-D01). T2 chạy sau khi V14/V15 của T1 đã có trong code. |
| Chế độ viết brief | READ-ONLY. Mọi file đích do task triển khai T2 tạo/sửa. |

Nhãn: `[CREATE]`, `[MODIFY]`, `[DO NOT TOUCH]`.

---

## 1. Metadata & Traceability

### 1.1. Sản phẩm của T2

1. Facade đồng bộ `PracticeEvents.prepareSeal(...)` và handler trong `practice`. `results` (T3) gọi để lấy toàn bộ dữ liệu cần chụp snapshot.
2. Facade media trong `ContentEvents` (`describeMedia`, `mediaPlaybackLocation`) và handler trong `content`.
3. `QuestionSelectionService` cho FIXED và DYNAMIC (TOEIC, VOCABULARY, GRAMMAR), có đánh số `display_number` và bảng `blank_key → display_number`.
4. `SourceIntegrityValidator`: kiểm tra fail-fast nguồn trước khi seal.
5. Catalog API `GET /api/v1/practice-sets`, `GET /api/v1/practice-sets/{practiceSetId}`.
6. Migration `V16__practice_sets_catalog_fields.sql` (additive: `difficulty`, `description`).
7. ArchUnit rule chặn record seal lọt ra tầng HTTP.

### 1.2. Traceability

| Mã | Nội dung T2 |
|---|---|
| M05.01, M05.02 | Catalog API, mô tả Part |
| M05.03 (phần nguồn) | `prepareSeal`. Phần ghi snapshot thuộc T3. |
| M05.11–M05.16 | Kiểm định nguồn theo Part (3.M5) |
| M05.17, M03.12–16, M04.10–12 | Một engine chọn câu cho 3 category |
| R1, S6, S7 | Record seal mang cả phần public và private. T3 tách vào `public_snapshot`/`private_snapshot`/`answer_key_snapshot`. |
| R5 | `/api/v1/practice-sets/**` thuộc `practice` |
| R6 | Kiểm node `blank`/`insert`; map `blank_key → display_number` |
| S9 | Lọc bộ nhiều Part (`part_number IS NULL`) bằng `partNumber=0` |
| ADR-08 | P1/P2 `LABEL_ONLY`, `option_order = FIXED`; transcript luôn `AFTER_SUBMIT` |
| ADR-13 | Media cắt theo câu/set ≤ 20 MiB; nạp dữ liệu ≤ 5 câu SQL |

### 1.3. Explicitly Out-of-Scope

- Ghi snapshot, API `/api/v1/attempts/**`, endpoint redirect media (T3). T2 chỉ cung cấp facade mà endpoint dùng.
- Tách module `results` (T3).
- Validator và projection publish nội dung TOEIC trong pipeline CMS (task projection). T2 cung cấp `SourceIntegrityValidator` để task đó dùng lại.
- Upload media, CORS bucket.
- Hàng rào YAGNI (preamble v2 §4).

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc

| Nhóm | File |
|---|---|
| `common/events` | `ModuleEvents.java`, `EventReply.java`, `package-info.java` (`@NamedInterface("events")`), `GrammarEvents.java`, `IdentityEvents.java`, `PracticeEvents.java`, `ContentEvents.java` (danh sách method/record) |
| Hạ tầng | `infrastructure/events/SpringModuleEvents.java`, `common/storage/PronunciationStorage.java`, `infrastructure/storage/R2PronunciationStorage.java`, `config/R2Properties.java`, `config/R2Configuration.java`, `infrastructure/persistence/vocabulary/JpaWordSenseExampleOperations.java` |
| `content` | `controller/PronunciationController.java`, `service/impl/PronunciationServiceImpl.java:29-60`, `service/impl/ContentEventHandler.java:46-72`, `entity/MediaAsset.java`, `repository/MediaAssetRepository.java`, `repository/fragment/PronunciationOperations.java` |
| `practice` | `package-info.java`, `service/PracticeService.java`, `repository/QuestionRepository.java`, `service/impl/SelectionPlanner.java`, danh sách controller mapping |
| Kiến trúc | `ArchitectureTests.java`, `LayerArchitectureTests.java` (toàn văn rule dòng 25–177) |
| Khác | `common/pagination/PageRequestUtils.java`, `common/api/PageResponse.java`, `grammar/controller/GrammarController.java:33-46`, `V8_1__add_catalog_read_views.sql`, `README.md:135-157` |

### 2.2. Drift

| ID | Docs / đề T2 | Repo thật | Giải quyết |
|---|---|---|---|
| T2-D01 | T1 §4.7 runner dùng `DataSource` + `ScriptUtils`. ADR-13: `JdbcTemplate.batchUpdate`. | `LayerArchitectureTests:117-124`: mọi class main không được phụ thuộc `org.springframework.jdbc..`, `java.sql..`, `javax.sql..`. Test được miễn (`DO_NOT_INCLUDE_TESTS`). | **Đã sửa tài liệu ở lượt này:** T1 §4.7 chuyển sang Flyway API; ADR-13 chuyển sang repository fragment + `EntityManager`. T2 chỉ dùng Spring Data native query. |
| T2-D02 | Đề T2: facade tên `PublishedPracticeQueryFacade` | Quy ước repo: một class `<Module>Events` (`@Component`) trong `common/events`, method gọi `ModuleEvents.request(...)`, record lồng bên trong (`GrammarEvents`, `IdentityEvents`, `PracticeEvents`) | **Mở rộng `PracticeEvents`**, không tạo class mới (3.M1). |
| T2-D03 | T0 §5.3: `PracticeEvents.loadSetForSealing(setId)`. A6 đã khai báo `countAvailable`, `selectQuestions`. | `PracticeEvents.java` có `countAvailable`, `selectQuestions` nhưng **không có handler và không có caller** (grep `AvailableCountRequested`/`SelectionRequested` ngoài `common/events`: 0 kết quả). Javadoc ghi gọi lúc này ra 500. | Thay bằng một method `prepareSeal(requestId, SealRequest)`. Gỡ hai method chưa dùng (3.M1). |
| T2-D04 | T0 S11: URL ký TTL 30 phút, làm mới bằng `GET /attempts/{id}` | `R2PronunciationStorage.signGet` ký **5 phút** cố định. Pattern phát media hiện có: endpoint ổn định trả **302** tới URL ký (`PronunciationController.playback`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`). README:154: "presigned URL cho trình duyệt và CORS bucket chưa được triển khai. Không bật public access". | Theo pattern repo: endpoint redirect + TTL 5 phút (3.M2). Sửa S11 (ADR-13 đã ghi). |
| T2-D05 | Đề T2: "tái sử dụng facade đọc media từ `content`" | `ContentEvents` chỉ có `audioUrl(wordId, revisionId)` cho phát âm từ vựng. Không có facade đọc metadata `media_assets` theo ID. | `[CREATE]` `ContentEvents.describeMedia`, `mediaPlaybackLocation` (3.M2). |
| T2-D06 | Đề T2: lọc theo `difficulty`; detail trả mô tả | `practice_sets` (T1 V14) không có `difficulty`, `description` | V16 additive (4.1). Mô tả từng Part là hằng trong code (`ToeicPart`), không lưu DB. |
| T2-D07 | Đề T2: `PracticeSetSummaryDto` | `LayerArchitectureTests.httpRequestAndResponseClassesLiveInTheirDtoPackages`; quy ước repo: `dto/response/*Response.java` | Đặt tên `PracticeSetSummaryResponse`, `PracticeSetDetailResponse`. |
| T2-D08 | T0 4.1.8: TOEIC chỉ FIXED ở MVP | Đề T2 yêu cầu đặc tả DYNAMIC TOEIC | Đặc tả và cài engine DYNAMIC TOEIC. Validator publish vẫn chặn set TOEIC DYNAMIC cho tới khi duyệt O1 (12.2). Engine có unit test. |
| T2-D09 | — | `QuestionRepository.pickQuestions` dùng `random()` (không xác định), trong khi `SelectionPlanner` (D9) xác định bằng SHA-256 | Engine mới chỉ dùng thứ tự xác định. `pickQuestions` giữ nguyên cho code V13 cho tới khi T3 thay. |
| T2-D10 | — | `practice/package-info.java` chỉ cho `common::events`, `common::error`, `common::security` | Thêm `common::api`, `common::pagination` cho catalog (`PageResponse`, `PageRequestUtils`). Grammar đã dùng `common::api`. |
| T2-D11 | — | View `catalog_visible_items` lọc `NOT is_archived AND access_revoked_at IS NULL`, trả `published_revision_id` hiện hành | Điều kiện "đủ điều kiện" = join view **và** `published_revision_id` của projection khớp (như `QuestionRepository:21-23`). |
| T2-D12 | — | Spring Data native `IN (:ids)` với collection rỗng sinh `IN ()`, gây lỗi cú pháp | Service bỏ qua truy vấn khi danh sách rỗng (8 #12). |

---

## 3. Mandatory Design Resolutions

### M1. Facade đồng bộ: mở rộng `PracticeEvents`

- **Quyết định:** `PracticeEvents` (đã có, `@Component` trong `common/events`) có đúng **một** method công khai mới:

  ```java
  public SealSource prepareSeal(UUID requestId, SealRequest request) {
      return events.request(reply -> new SealRequested(requestId, request, reply));
  }
  ```

- Gỡ `countAvailable`, `selectQuestions` và các record `AvailableCountRequested`, `SelectionRequested`, `SelectedQuestion`, `SelectedOption`. Chúng chưa có handler hay caller (T2-D03), và `prepareSeal` bao trùm cả hai.
- Handler: `modules/practice/service/impl/PracticeEventHandler.java`, `@EventListener` đồng bộ (mẫu `ContentEventHandler`). Handler gọi `QuestionSelectionService.prepareSeal(...)` rồi `reply.complete(...)`.
- **Transaction:** `QuestionSelectionServiceImpl.prepareSeal` khai báo `@Transactional(propagation = Propagation.MANDATORY)`.
  - Khóa `FOR SHARE` (3.M6) sống trong transaction start của T3, cho tới khi snapshot ghi xong.
  - Gọi ngoài transaction là lỗi lập trình (`IllegalTransactionStateException`).
- **Phần public và private.** Record seal mang cả hai loại dữ liệu:
  - public: đề, lựa chọn, học liệu `BEFORE_SUBMIT`;
  - private: đáp án, lời giải, dẫn chứng, transcript, học liệu `AFTER_SUBMIT`, `body` lựa chọn P1/P2.
  - Javadoc của mỗi record ghi rõ trường nào là private.
- **Cấm** các record này xuất hiện ở controller hoặc DTO. Một ArchUnit rule mới cưỡng chế điều này (11.4).

### M2. Phục vụ media cho bài dài 120 phút

| Phương án | Đánh giá | Kết luận |
|---|---|---|
| Public R2/CDN URL | README:154 cấm bật public access. URL vĩnh viễn làm lộ học liệu `AFTER_SUBMIT` và cho phép hotlink. | Loại |
| URL ký TTL dài (≥ 150 phút) trong JSON `AttemptView` | URL dài hạn bị chia sẻ được. Phải ký lại toàn bộ URL mỗi lần `GET` (~60 URL/Full Test). Không thu hồi được quyền khi lượt bị ABANDONED. | Loại |
| **Endpoint ổn định trả 302 tới URL ký 5 phút** | Đúng pattern `PronunciationController.playback`. Kiểm quyền ở **mỗi** lần tải. Không đổi TTL của `R2PronunciationStorage`. | **Chọn** |

Hợp đồng (endpoint do T3 hiện thực, facade do T2):
- `AttemptView.materials[].mediaUrl = "/api/v1/attempts/{attemptId}/media/{mediaAssetId}"`. Đường dẫn ổn định, không có `mediaUrlExpiresAt`. 02 §8 sửa trong T2 (4.9).
- `GET /api/v1/attempts/{attemptId}/media/{mediaAssetId}`, theo thứ tự kiểm:
  1. Chủ lượt. Sai → 404.
  2. `(attempt_id, media_asset_id)` có trong `attempt_media_refs`. Sai → 404.
  3. Media thuộc học liệu `BEFORE_SUBMIT`, hoặc lượt đã `SUBMITTED`. Sai → 404, không tiết lộ là tồn tại.
  4. `ContentEvents.mediaPlaybackLocation(mediaAssetId)`. Kết quả rỗng → 503 `SERVICE_UNAVAILABLE`.
  5. Trả `302 Location: <presigned>`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`.
- **Range / 206:**
  - Trình duyệt theo 302 rồi gửi `Range: bytes=…` thẳng tới R2. R2 (S3-compatible) trả `206 Partial Content` kèm `Accept-Ranges: bytes`. Backend không proxy byte nào.
  - **Chưa kiểm chứng trên bucket thật trong phiên này.** Bước kiểm ở 11.5.
- **Khi URL hết hạn** (tạm dừng hoặc tua sau 5 phút): R2 trả 403 và `<audio>` phát sự kiện `error`. Frontend (T6) gán lại `src` bằng chính `mediaUrl`, chờ `loadedmetadata`, khôi phục `currentTime`. Mỗi lần như vậy là một request redirect mới.
- **CORS:** không cần cấu hình CORS bucket, với điều kiện frontend dùng `<audio>`/`<img>` **không** đặt thuộc tính `crossorigin` và không `fetch()` media. Đây là ràng buộc cho T6.
- **≤ 20 MiB và cắt theo câu/set:**
  - DB đã chặn `media_assets_c13` (AUDIO ≤ 20 971 520 byte) và `c14` (`audio/mpeg`, `audio/mp4`).
  - T2 kiểm thêm khi seal (V-14, 3.M5): AUDIO chỉ gắn vào câu P1/P2 (`question_materials`), nhóm P3/P4 (`group_materials`), hoặc câu VOCABULARY `LISTEN_AND_CHOOSE`.
  - Mỗi câu P1/P2 và mỗi nhóm P3/P4 có đúng 1 AUDIO, trạng thái `READY`, `fileSizeBytes ≤ 20 971 520`.

### M3. Catalog API

- Bộ đề hiển thị khi:
  - `practice_sets` join `catalog_visible_items` với `published_revision_id` khớp;
  - với FIXED: mọi item (câu đơn, nhóm và mọi câu trong nhóm) đều hiển thị. Bộ FIXED có item bị archive hoặc thu hồi bị **lọc khỏi danh sách** (người học không bấm vào để rồi nhận 409);
  - với DYNAMIC: luôn hiển thị. Thiếu câu thì báo 409 khi start.
- `partNumber`:
  - bỏ trống → mọi bộ;
  - `0` → chỉ bộ nhiều Part (`part_number IS NULL`, S9);
  - `1..7` → đúng Part.
  - Truyền `partNumber` khi `category ≠ TOEIC` → 400.
- `q`: trim, độ dài 1–100, so khớp `title ILIKE %q%` với `%`, `_`, `\` được escape.
- `difficulty`: `EASY | MEDIUM | HARD` (cột mới V16).
- Sắp xếp cố định `title ASC, id ASC`. Không nhận `sortBy`, vì danh mục nhỏ.
- Phân trang: `PageRequestUtils.of(page, size)` (mặc định 20, tối đa 100).
- Detail chỉ trả metadata, `partBreakdown` và mô tả từng Part từ hằng `ToeicPart`. **Không** trả câu hỏi, lựa chọn, học liệu hay đáp án.

### M4. Engine chọn đề

Đầu vào: `requestId` (UUID; thứ tự xác định theo D9) và `SealRequest`:

| `SealRequest` | Nguồn câu | Thứ tự |
|---|---|---|
| `PracticeSetSeal(practiceSetId)`, FIXED | `set_items` theo `position`. Nhóm giữ nguyên, câu trong nhóm theo `group_position`. | Theo `position` của set |
| `PracticeSetSeal`, DYNAMIC TOEIC | `selection_config`: `{"questionCount":1..200, "partNumbers":[1..7], "difficulty"?}` | Thuật toán TU (bên dưới) |
| `PracticeSetSeal`, DYNAMIC VOCABULARY | Nguồn sense là đúng một trong hai: `deck_id` (qua `catalog_visible_deck_senses`) hoặc `selection_config.wordSenseIds`. Câu chọn qua `question_words`. | `key(q) = SHA-256(requestId ‖ questionId)` tăng dần, lấy N đầu |
| `PracticeSetSeal`, DYNAMIC GRAMMAR | Nguồn chủ điểm là đúng một trong hai: `topic_id` hoặc `selection_config.grammarTopicIds` (2–10). Câu chọn qua `question_topics`. | `SelectionPlanner.plan` (D9) dùng lại nguyên |
| `TopicSeal(topicIds, questionCount)` | Nguồn `GRAMMAR_TOPIC`/`MIXED_GRAMMAR` của API (không qua set) | `SelectionPlanner.plan` |

**Thuật toán TU (DYNAMIC TOEIC, không cắt nhóm):**

1. **Đơn vị.** Câu đơn P1, P2, P5 có `size = 1`. Nhóm P3, P4 có `size = 3`; P6 có `size = 4`; P7 có `size` 2–5 (số câu thực tế trong nhóm).
   - Đơn vị đủ điều kiện khi nó và mọi câu trong nó đều hiển thị, khớp revision, qua `SourceIntegrityValidator`.
   - Nếu có `difficulty`: **mọi** câu trong đơn vị có `difficulty` bằng giá trị đó.
2. `P` = `partNumbers` đã khử trùng, sắp tăng dần. `N = questionCount`.
3. **Hạn mức theo Part:** `q_i = floor(N / |P|) + (i < N mod |P| ? 1 : 0)`, với `i` là chỉ số Part trong `P`.
4. **Vòng 1**, lần lượt từng Part theo thứ tự tăng: sắp đơn vị của Part theo `key(u) = SHA-256(requestId ‖ unitId)` tăng dần. Lấy đơn vị nếu `size ≤ hạn mức còn lại` của Part đó.
5. **Vòng 2:** `L = N − tổng đã lấy`. Duyệt mọi đơn vị còn lại của mọi Part theo `key` tăng dần, lấy nếu `size ≤ L còn lại`.
6. **Kết quả:**
   - `tổng = N`: thành công.
   - Không có đơn vị nào đủ điều kiện: 409 `NO_ELIGIBLE_QUESTIONS` (`requestedCount = N`, `availableCount = 0`).
   - `0 < tổng < N`: 409 `INSUFFICIENT_QUESTIONS` (`requestedCount = N`, `availableCount = tổng`). `availableCount` ở đây là **số câu tối đa xếp được mà không cắt nhóm** theo thuật toán, không phải tổng số câu trong ngân hàng.
7. **Thứ tự đầu ra:** theo Part tăng dần; trong cùng Part theo `key`; trong nhóm theo `group_position`. `display_number` = 1..N theo thứ tự này, nên đúng thứ tự P1→P7 của đề thật.

Bất biến (kiểm bằng unit test): mỗi câu xuất hiện đúng một lần; không nhóm nào bị cắt; cùng đầu vào cho cùng kết quả; `Σ size = N` khi thành công.

### M5. Kiểm định nguồn và đánh số

`SourceIntegrityValidator` (thuần Java, không truy cập DB) chạy trên dữ liệu vừa nạp, trước khi trả `SealSource`.

| Mã | Quy tắc | Khi vi phạm |
|---|---|---|
| V-14 | P1: đúng 4 lựa chọn, `option_order = FIXED`, 1 IMAGE + 1 AUDIO trong `question_materials`. P2: đúng 3 lựa chọn, `FIXED`, 1 AUDIO, có `prompt_transcript`. Mỗi lựa chọn P1/P2 có `speaker`. P3/P4: nhóm có đúng 1 AUDIO, đúng 3 câu. P6: 4 câu. P7: 2–5 câu. Không có AUDIO ở P5–P7. AUDIO có `READY`, `≤ 20 971 520` byte. | Đơn vị bị loại |
| V-01..V-05, V-09, V-10 | Theo 02 §4 (bảng, anchor chat, `blank_key` ↔ node `blank`, đủ `insert` 1–4, `refs`/`evidence` trỏ được, `speaker_count`, `start_ms`/`end_ms`) | Đơn vị bị loại |
| V-15 | Mọi học liệu `material_type = TRANSCRIPT` có `visibility = AFTER_SUBMIT` (DB đã ép bằng `c8`; validator kiểm lại để phòng dữ liệu cũ) | Đơn vị bị loại |
| V-16 | Đúng một lựa chọn có `is_correct = true` | Đơn vị bị loại |
| V-17 | Tổng số câu sau khi lắp ≤ 200 | `IllegalStateException` (500): lỗi dữ liệu set, validator publish phải chặn trước |

- **Khi đơn vị bị loại:**
  - log `ERROR` với `practiceSetId`, `unitId`, mã quy tắc (không log nội dung đề);
  - đơn vị được tính là không đủ điều kiện.
  - Với FIXED, mọi đơn vị đều bắt buộc, nên kết quả là 409 `INSUFFICIENT_QUESTIONS` (`requestedCount` = số câu của set, `availableCount` = số câu của các đơn vị hợp lệ).
  - Không trả 500, để người học thấy "bộ đề tạm không dùng được" thay vì lỗi hệ thống.
- **Đánh số:**
  - `displayNumber` = 1..N theo thứ tự cuối cùng.
  - `SealGroup.blankDisplayNumbers` (chỉ P6) là `Map<String, Integer>` từ `blank_key` của từng câu sang `displayNumber` của câu đó, ví dụ `{"b1":14,"b2":15,"b3":16,"b4":17}` với seed S01.
  - Map này chỉ dùng để kiểm và test. T3 **không** lưu nó (frontend tự suy từ danh sách câu, 02 §7).
- **`optionDisplay`:** `LABEL_ONLY` khi `category = TOEIC` và `partNumber ∈ {1,2}`, còn lại `TEXT`.
- **`speakerCount`** (chỉ P3): số nhãn `M-*`/`W-*` khác nhau trong `TRANSCRIPT` của nhóm.

### M6. Nạp dữ liệu không N+1

Một Full Test (200 câu, 42 nhóm, 775 lựa chọn, ~100 học liệu) được nạp bằng **4 câu SQL của `practice` cộng 1 câu của `content`**. Mỗi câu SQL có danh sách ID cố định.

| # | Repository method | Nội dung | Khóa |
|---|---|---|---|
| S1 | `PracticeSetRepository.findSealUnits(setId)` | Header set cùng `set_items` theo `position` (một dòng mỗi item) | `FOR SHARE OF ps` |
| S2 | `QuestionRepository.findSealGroups(groupIds)` | Nhóm kèm cờ hiển thị | `FOR SHARE OF g`, sắp theo `id` |
| S3 | `QuestionRepository.findSealQuestions(questionIds, groupIds)` | Câu (đơn và thuộc nhóm) kèm cờ hiển thị, `topic_ids`, `word_sense_ids` dạng mảng, và **lựa chọn dạng JSON** (`json_agg`) | `FOR SHARE OF q`, sắp theo `id` |
| S4 | `QuestionRepository.findSealMaterials(groupIds, questionIds)` | `group_materials UNION ALL question_materials` | Không (chủ đã bị khóa ở S2/S3) |
| C1 | `MediaAssetRepository.findAllById(mediaIds)` (trong `content`, qua `ContentEvents.describeMedia`) | Metadata media | Không |

- **Lý do khóa:** projection bị thay khi publish. `FOR SHARE` trên dòng `practice_sets`, `question_groups`, `questions` buộc transaction publish đồng thời phải chờ start commit, hoặc ngược lại. Thứ tự khóa là set → nhóm (theo `id`) → câu (theo `id`), theo CONTRACT §2.2.
- **DYNAMIC:** S1 trả header với 0 item. Một câu "ứng viên" (C2 TOEIC / C3 VOCABULARY / C4 GRAMMAR, 4.4) chạy trước. Sau đó chạy S2–S4 cho đơn vị đã chọn.
- **Nguồn `TopicSeal`:** C4, rồi S3, S4. Không có S1/S2 vì không có set và nhóm.

---

## 4. Thiết kế chi tiết

### 4.1. `V16__practice_sets_catalog_fields.sql` `[CREATE]`

```sql
-- V16: Trường catalog cho practice_sets (additive, D8 v2).
ALTER TABLE practice_sets
    ADD COLUMN difficulty  VARCHAR(8),
    ADD COLUMN description TEXT,
    ADD CONSTRAINT practice_sets_c10 CHECK (difficulty IS NULL OR difficulty IN ('EASY','MEDIUM','HARD')),
    ADD CONSTRAINT practice_sets_c11 CHECK (description IS NULL OR (btrim(description) <> '' AND char_length(description) <= 2000));
```

- `difficulty NULL` nghĩa là "hỗn hợp". Envelope `PRACTICE_SET.data` thêm hai trường tùy chọn `difficulty`, `description` (delta cho T1 §3.M8, ghi ở 4.9).
- Seed T1 không đổi: hai cột để NULL.

### 4.2. Package và class

`modules/practice` (đường dẫn gốc `src/main/java/com/masterenglish/api/modules/practice/`):

| Đường dẫn | Loại | Nội dung |
|---|---|---|
| `package-info.java` | MODIFY | `allowedDependencies = {"common::events","common::error","common::security","common::api","common::pagination"}` |
| `entity/PracticeSet.java` | CREATE | Entity chỉ đọc cho `practice_sets`. Map: `id`, `category`, `title`, `partNumber` (`Integer`), `difficulty`, `description`, `selectionMode`, `timeLimitSeconds`, `publishedRevisionId`. Không setter công khai. |
| `repository/PracticeSetRepository.java` | CREATE | `@Repository interface PracticeSetRepository extends JpaRepository<PracticeSet, UUID>`. Native query 4.4 S1 và catalog 4.5. |
| `repository/QuestionRepository.java` | MODIFY | Thêm S2, S3, S4, C2, C3, C4 |
| `service/QuestionSelectionService.java` | CREATE | `SealSource prepareSeal(UUID requestId, SealRequest request)` |
| `service/PracticeCatalogService.java` | CREATE | `PageResponse<PracticeSetSummaryResponse> list(PracticeSetQuery query, Pageable pageable)`, `PracticeSetDetailResponse get(UUID practiceSetId)` |
| `service/impl/QuestionSelectionServiceImpl.java` | CREATE | Điều phối: nạp, chọn, kiểm định, lắp `SealSource`. `@Transactional(propagation = MANDATORY)` |
| `service/impl/PracticeCatalogServiceImpl.java` | CREATE | `@Transactional(readOnly = true)` ở từng method |
| `service/impl/PracticeEventHandler.java` | CREATE | `@EventListener void on(PracticeEvents.SealRequested event)` |
| `service/impl/ToeicUnitPlanner.java` | CREATE | `final class`, thuần, thuật toán TU (3.M4) |
| `service/impl/SealAssembler.java` | CREATE | `final class`, thuần: thứ tự, `displayNumber`, `optionDisplay`, `blankDisplayNumbers`, `speakerCount`, ánh xạ sang record seal |
| `service/impl/SourceIntegrityValidator.java` | CREATE | `final class`, thuần: V-01..V-05, V-09, V-10, V-14..V-17 |
| `service/impl/ToeicPart.java` | CREATE | `enum ToeicPart { P1(1,"Mô tả tranh","Listening"), … P7(7,"Đọc hiểu","Reading") }` có `number`, `titleVi`, `section`, `descriptionVi` (4.6) |
| `controller/PracticeSetController.java` | CREATE | 5.1 |
| `dto/request/PracticeSetQuery.java` | CREATE | `record PracticeSetQuery(String category, Integer partNumber, String difficulty, String q)` (record nội bộ, không bind body) |
| `dto/response/PracticeSetSummaryResponse.java` | CREATE | 5.1 |
| `dto/response/PracticeSetDetailResponse.java` | CREATE | 5.1 |
| `dto/response/PartBreakdownResponse.java` | CREATE | `record PartBreakdownResponse(int partNumber, String section, String title, String description, int questionCount, int groupCount)` |

`modules/content`:

| Đường dẫn | Loại | Nội dung |
|---|---|---|
| `service/ContentQueryService.java` | MODIFY | Thêm `Map<UUID, ContentEvents.MediaDescriptor> describeMedia(Set<UUID> ids)` và `Optional<URI> mediaPlaybackLocation(UUID mediaId)` |
| `service/impl/ContentQueryServiceImpl.java` | MODIFY | Hiện thực. Dùng `MediaAssetRepository.findAllById` và `ObjectProvider<PronunciationStorage>` (cùng cách `PronunciationServiceImpl`). `@Transactional(readOnly = true)`. |
| `service/impl/ContentEventHandler.java` | MODIFY | Hai `@EventListener` mới |

`common/events`:

| Đường dẫn | Loại | Nội dung |
|---|---|---|
| `PracticeEvents.java` | MODIFY | 4.3 |
| `ContentEvents.java` | MODIFY | 5.2 |

### 4.3. `PracticeEvents` sau T2

```java
/** Synchronous questions answered by the practice module (practice/CONTRACT.md section 2.1, brief T2). */
@Component
public class PracticeEvents {
    private final ModuleEvents events;

    PracticeEvents(ModuleEvents events) {
        this.events = events;
    }

    /**
     * Loads and validates every published source needed to seal one attempt. Must run inside the
     * caller's transaction: source rows stay FOR SHARE locked until the caller commits.
     *
     * @throws BusinessException RESOURCE_NOT_FOUND (set or topic not visible), NO_ELIGIBLE_QUESTIONS,
     *     INSUFFICIENT_QUESTIONS (requestedCount, availableCount)
     */
    public SealSource prepareSeal(UUID requestId, SealRequest request) {
        return events.request(reply -> new SealRequested(requestId, request, reply));
    }

    public record SealRequested(UUID requestId, SealRequest request, EventReply<SealSource> reply) {}

    public sealed interface SealRequest permits PracticeSetSeal, TopicSeal {}

    public record PracticeSetSeal(UUID practiceSetId) implements SealRequest {}

    /** GRAMMAR_TOPIC (one topic) or MIXED_GRAMMAR (2..10 topics); questionCount 1..100. */
    public record TopicSeal(List<UUID> topicIds, int questionCount) implements SealRequest {}

    /** Ordered, validated sources. PRIVATE parts are marked; never expose this type over HTTP. */
    public record SealSource(
            UUID practiceSetId,        // null for TopicSeal
            UUID sourceRevisionId,     // set revision; null for TopicSeal
            String category,           // VOCABULARY | GRAMMAR | TOEIC
            Integer partNumber,        // set part; null = multi-part or non-TOEIC
            Integer timeLimitSeconds,  // from set; null = untimed
            List<SealGroup> groups,    // ordered by ordinal
            List<SealQuestion> questions) {} // ordered by displayNumber 1..N

    public record SealGroup(
            UUID groupId,
            UUID sourceRevisionId,
            int ordinal,
            int partNumber,
            String groupType,
            String title,
            int expectedQuestionCount,
            Integer speakerCount,                    // P3 only
            Map<String, Integer> blankDisplayNumbers, // P6 only, else empty; not persisted
            List<SealMaterial> materials) {}         // BEFORE_SUBMIT and AFTER_SUBMIT (PRIVATE)

    /** bodyJson is the MaterialV1 "body" object (docs/m05/02 section 3), raw JSON text. */
    public record SealMaterial(
            String materialKey,
            String materialType,
            String visibility,   // AFTER_SUBMIT materials are PRIVATE
            int position,
            UUID mediaAssetId,
            String bodyJson) {}

    public record SealQuestion(
            UUID questionId,
            UUID sourceRevisionId,
            int displayNumber,
            UUID groupId,
            Integer groupPosition,
            String category,
            Integer partNumber,
            String questionTypeCode,
            String practiceMode,
            String questionKind,      // STANDARD when the projection column is null
            String optionOrder,
            String optionDisplay,     // TEXT | LABEL_ONLY
            String blankKey,
            String prompt,
            String insertSentence,
            List<SealRef> refs,
            List<SealMaterial> materials,         // question-level, BEFORE_SUBMIT
            List<SealOption> options,             // body is PRIVATE when optionDisplay = LABEL_ONLY
            UUID correctSourceOptionId,           // PRIVATE
            String explanation,                   // PRIVATE
            List<SealRef> evidence,               // PRIVATE
            SealSpoken promptTranscript,          // PRIVATE, P2 only
            UUID attributedTopicId,               // GRAMMAR only (D9)
            Set<UUID> topicIds,
            Set<UUID> wordSenseIds) {}

    public record SealOption(UUID sourceOptionId, int position, String label, String body, String speaker) {}

    public record SealRef(String materialKey, String anchor) {}

    public record SealSpoken(String speaker, String text) {}
}
```

- Mọi `List`/`Map`/`Set` trong record được tạo bằng `List.copyOf`/`Map.copyOf`/`Set.copyOf`, nên bất biến.
- Ánh xạ sang cột/JSON của T3 theo 02 §6:
  - `options[].body` của `LABEL_ONLY` → `answer_key_snapshot.option_transcripts`;
  - `materials` `AFTER_SUBMIT` → `private_snapshot`.

### 4.4. Truy vấn nạp (native, `nativeQuery = true`, projection interface)

Danh sách rỗng không bao giờ được truyền vào `IN (:ids)`: service bỏ qua truy vấn và dùng kết quả rỗng.

**S1 — `PracticeSetRepository.findSealUnits(UUID setId)`**

```sql
SELECT ps.id AS setId, ps.published_revision_id AS setRevisionId, ps.category AS category,
       ps.part_number AS setPartNumber, ps.selection_mode AS selectionMode,
       ps.selection_config::text AS selectionConfig, ps.time_limit_seconds AS timeLimitSeconds,
       ps.topic_id AS topicId, ps.deck_id AS deckId,
       si.position AS itemPosition, si.question_id AS questionId, si.group_id AS groupId
FROM practice_sets ps
JOIN catalog_visible_items ci ON ci.id = ps.id AND ci.published_revision_id = ps.published_revision_id
LEFT JOIN set_items si ON si.set_id = ps.id
WHERE ps.id = :setId
ORDER BY si.position
FOR SHARE OF ps
```

Không có dòng nào → `RESOURCE_NOT_FOUND`. DYNAMIC → một dòng với các cột item là NULL.

**S2 — `QuestionRepository.findSealGroups(Collection<UUID> groupIds)`**

```sql
SELECT g.id AS groupId, g.published_revision_id AS revisionId, g.category AS category,
       g.part_number AS partNumber, g.group_type AS groupType, g.title AS title,
       (vg.id IS NOT NULL) AS visible
FROM question_groups g
LEFT JOIN catalog_visible_items vg ON vg.id = g.id AND vg.published_revision_id = g.published_revision_id
WHERE g.id IN (:groupIds)
ORDER BY g.id
FOR SHARE OF g
```

**S3 — `QuestionRepository.findSealQuestions(Collection<UUID> questionIds, Collection<UUID> groupIds)`**

```sql
SELECT q.id AS questionId, q.published_revision_id AS revisionId, q.category AS category,
       q.part_number AS partNumber, q.group_id AS groupId, q.group_position AS groupPosition,
       q.question_type_code AS questionTypeCode, q.practice_mode AS practiceMode, q.difficulty AS difficulty,
       q.question_kind AS questionKind, q.option_order AS optionOrder, q.blank_key AS blankKey,
       q.prompt AS prompt, q.insert_sentence AS insertSentence,
       q.prompt_speaker AS promptSpeaker, q.prompt_transcript AS promptTranscript,
       q.explanation AS explanation, q.refs::text AS refsJson, q.evidence::text AS evidenceJson,
       (vq.id IS NOT NULL) AS visible,
       ARRAY(SELECT qt.topic_id FROM question_topics qt WHERE qt.question_id = q.id ORDER BY qt.topic_id) AS topicIds,
       ARRAY(SELECT qw.word_sense_id FROM question_words qw WHERE qw.question_id = q.id ORDER BY qw.word_sense_id) AS wordSenseIds,
       (SELECT json_agg(json_build_object('id', o.id, 'position', o.position, 'body', o.body,
                                          'speaker', o.speaker, 'correct', o.is_correct) ORDER BY o.position)
        FROM question_options o WHERE o.question_id = q.id)::text AS optionsJson
FROM questions q
LEFT JOIN catalog_visible_items vq ON vq.id = q.id AND vq.published_revision_id = q.published_revision_id
WHERE q.id IN (:questionIds) OR q.group_id IN (:groupIds)
ORDER BY q.id
FOR SHARE OF q
```

- Nếu một trong hai danh sách rỗng, service truyền `List.of(NIL)` với `NIL = new UUID(0, 0)`. Đây là UUID không bao giờ tồn tại, nên vế đó luôn sai.
- `FOR SHARE` đi kèm subquery `ARRAY(...)` và `json_agg` trong subquery vô hướng. Đây là aggregate ở cấp subquery, không phải cấp câu truy vấn. **Chưa chạy trong phiên này.** Test `SealLoadQueryTests.forShareWithScalarSubqueriesRuns` (11.2) xác nhận. Nếu PostgreSQL từ chối, tách `optionsJson` thành một truy vấn riêng (tổng thành 5 câu `practice`).

**S4 — `QuestionRepository.findSealMaterials(Collection<UUID> groupIds, Collection<UUID> questionIds)`**

```sql
SELECT 'GROUP' AS ownerKind, m.group_id AS ownerId, m.document_key AS materialKey, m.material_type AS materialType,
       m.visibility AS visibility, m.position AS position, m.media_id AS mediaAssetId, m.body::text AS bodyJson
FROM group_materials m WHERE m.group_id IN (:groupIds)
UNION ALL
SELECT 'QUESTION', m.question_id, m.document_key, m.material_type, m.visibility, m.position, m.media_id, m.body::text
FROM question_materials m WHERE m.question_id IN (:questionIds)
ORDER BY 1, 2, 6
```

**C2 — DYNAMIC TOEIC `QuestionRepository.findToeicUnits(Collection<Integer> parts, String difficulty)`**

```sql
SELECT COALESCE(q.group_id, q.id) AS unitId, (q.group_id IS NOT NULL) AS grouped, q.part_number AS partNumber,
       count(*) AS size,
       bool_and(vq.id IS NOT NULL) AS allVisible,
       bool_and(CAST(:difficulty AS varchar) IS NULL OR q.difficulty = CAST(:difficulty AS varchar)) AS difficultyMatches
FROM questions q
LEFT JOIN catalog_visible_items vq ON vq.id = q.id AND vq.published_revision_id = q.published_revision_id
WHERE q.category = 'TOEIC' AND q.part_number IN (:parts)
GROUP BY COALESCE(q.group_id, q.id), (q.group_id IS NOT NULL), q.part_number
```

- Service giữ đơn vị có `allVisible AND difficultyMatches`.
- Với nhóm, service kiểm thêm cờ hiển thị của nhóm ở S2 sau khi chọn. Nếu nhóm bị ẩn, đơn vị bị loại và kết quả xử lý như "đơn vị bị loại" ở M5. Không chạy lại planner (đơn giản, chấp nhận được vì hiếm).
- `CAST(:x AS varchar) IS NULL` tránh lỗi "could not determine data type" khi tham số NULL.

**C3 — DYNAMIC VOCABULARY `QuestionRepository.findVocabularyCandidates(UUID deckId, Collection<UUID> senseIds)`**

```sql
SELECT DISTINCT q.id AS questionId
FROM questions q
JOIN catalog_visible_items vq ON vq.id = q.id AND vq.published_revision_id = q.published_revision_id
JOIN question_words qw ON qw.question_id = q.id
JOIN catalog_visible_senses s ON s.word_sense_id = qw.word_sense_id
WHERE q.category = 'VOCABULARY'
  AND ((CAST(:deckId AS uuid) IS NOT NULL
        AND qw.word_sense_id IN (SELECT ds.word_sense_id FROM catalog_visible_deck_senses ds WHERE ds.deck_id = CAST(:deckId AS uuid)))
       OR qw.word_sense_id IN (:senseIds))
```

Khi dùng `deckId`, `senseIds = List.of(NIL)`. Khi dùng `senseIds`, `deckId = null`.

**C4 — GRAMMAR `QuestionRepository.findGrammarCandidates(Collection<UUID> topicIds)`**

```sql
SELECT q.id AS questionId, ARRAY(SELECT qt2.topic_id FROM question_topics qt2 WHERE qt2.question_id = q.id) AS topicIds
FROM questions q
JOIN catalog_visible_items vq ON vq.id = q.id AND vq.published_revision_id = q.published_revision_id
WHERE q.category = 'GRAMMAR' AND EXISTS (SELECT 1 FROM question_topics qt WHERE qt.question_id = q.id AND qt.topic_id IN (:topicIds))
```

Kết quả vào `SelectionPlanner.plan(requestId, topicIds, n, candidates)` như hiện có. Kiểm `GrammarEvents.isVisibleTopic` từng topic trước (404 nếu không hiển thị), giữ CONTRACT §5.1 bước 3.

**Index dùng:** đều đã có từ V13/T1:
- `questions` PK, `questions_c17 (group_id, group_position)`;
- `question_options_c1 (question_id, position)`;
- `group_materials_c1`, `question_materials_c1`;
- `set_items_c1`;
- `question_topics_c1`, `ix_question_topics_topic`;
- `question_words_c1`, `ix_question_words_sense`.

C2 quét `questions` theo `category` và `part_number`. Ngân hàng TOEIC dự kiến vài nghìn câu, nên quét tuần tự rẻ. Nếu > 50.000 câu, thêm `CREATE INDEX … ON questions (category, part_number)` ở migration sau (O3).

### 4.5. Truy vấn catalog `PracticeSetRepository`

```sql
-- list (value)
SELECT ps.id AS id, ps.category AS category, ps.title AS title, ps.part_number AS partNumber,
       ps.difficulty AS difficulty, ps.selection_mode AS selectionMode, ps.time_limit_seconds AS timeLimitSeconds,
       CASE WHEN ps.selection_mode = 'FIXED' THEN COALESCE(c.question_count, 0)
            ELSE (ps.selection_config->>'questionCount')::int END AS questionCount,
       COALESCE(c.group_count, 0) AS groupCount
FROM practice_sets ps
JOIN catalog_visible_items ci ON ci.id = ps.id AND ci.published_revision_id = ps.published_revision_id
LEFT JOIN LATERAL (
    SELECT count(DISTINCT si.group_id) AS group_count,
           count(q.id) AS question_count,
           bool_and(vq.id IS NOT NULL AND (si.group_id IS NULL OR vg.id IS NOT NULL)) AS all_visible
    FROM set_items si
    LEFT JOIN question_groups g ON g.id = si.group_id
    LEFT JOIN catalog_visible_items vg ON vg.id = g.id AND vg.published_revision_id = g.published_revision_id
    JOIN questions q ON q.id = si.question_id OR q.group_id = si.group_id
    LEFT JOIN catalog_visible_items vq ON vq.id = q.id AND vq.published_revision_id = q.published_revision_id
    WHERE si.set_id = ps.id) c ON TRUE
WHERE (CAST(:category AS varchar) IS NULL OR ps.category = CAST(:category AS varchar))
  AND (CAST(:partMode AS varchar) = 'ANY'
       OR (CAST(:partMode AS varchar) = 'MULTI' AND ps.part_number IS NULL)
       OR (CAST(:partMode AS varchar) = 'EXACT' AND ps.part_number = :partNumber))
  AND (CAST(:difficulty AS varchar) IS NULL OR ps.difficulty = CAST(:difficulty AS varchar))
  AND (CAST(:pattern AS varchar) IS NULL OR ps.title ILIKE CAST(:pattern AS varchar) ESCAPE '\')
  AND (ps.selection_mode = 'DYNAMIC' OR COALESCE(c.all_visible, false))
ORDER BY ps.title, ps.id
```

- `countQuery`: cùng `FROM`/`WHERE`, `SELECT count(*)`.
- `partMode` do service tính: `partNumber == null` → `ANY`; `0` → `MULTI`; `1..7` → `EXACT`.
- `pattern = "%" + escape(q) + "%"`, với `escape` thay `\` → `\\`, `%` → `\%`, `_` → `\_`.

**Detail.**
- `findVisibleById(id)`: dòng header với cùng điều kiện hiển thị.
- `findPartBreakdown(id)`:
  ```sql
  SELECT q.part_number AS partNumber, count(*) AS questionCount, count(DISTINCT q.group_id) AS groupCount
  FROM set_items si
  JOIN questions q ON q.id = si.question_id OR q.group_id = si.group_id
  WHERE si.set_id = :setId AND q.part_number IS NOT NULL
  GROUP BY q.part_number ORDER BY q.part_number
  ```
- Với DYNAMIC TOEIC, `partBreakdown` lấy từ `selection_config.partNumbers` với `questionCount = null`, vì số câu theo Part chỉ biết khi start.

### 4.6. `ToeicPart` (hằng mô tả)

| Part | `section` | `titleVi` | `descriptionVi` |
|---|---|---|---|
| 1 | LISTENING | Mô tả tranh | Nghe 4 câu mô tả một bức ảnh, chọn câu đúng nhất. Câu mô tả không in trên đề. |
| 2 | LISTENING | Hỏi – đáp | Nghe một câu hỏi hoặc câu nói và 3 lời đáp, chọn lời đáp phù hợp. |
| 3 | LISTENING | Đoạn hội thoại | Nghe hội thoại 2–3 người, trả lời 3 câu hỏi. Một số câu dùng bảng hoặc hình. |
| 4 | LISTENING | Bài nói ngắn | Nghe bài nói của một người, trả lời 3 câu hỏi. Một số câu dùng bảng hoặc hình. |
| 5 | READING | Hoàn thành câu | Chọn từ hoặc cụm từ điền vào chỗ trống trong câu. |
| 6 | READING | Hoàn thành đoạn văn | Điền 4 chỗ trống trong một văn bản, có chỗ trống là nguyên một câu. |
| 7 | READING | Đọc hiểu | Đọc 1–3 văn bản (email, quảng cáo, tin nhắn…) và trả lời câu hỏi. |

Nội dung trên tự viết theo [00 §2](../00-toeic-research-and-gap-report.md), không chép Directions của ETS.

### 4.7. Luồng `prepareSeal`

```
SealRequested(requestId, request)
 └─ PracticeEventHandler.on → QuestionSelectionService.prepareSeal [MANDATORY tx]
     ├─ PracticeSetSeal
     │   ├─ S1 findSealUnits (FOR SHARE ps) ──► 404 nếu rỗng
     │   ├─ FIXED  : groupIds, questionIds từ set_items
     │   ├─ DYNAMIC TOEIC: C2 → ToeicUnitPlanner.plan(requestId, config, units)
     │   ├─ DYNAMIC VOCAB: C3 → order by key, take N
     │   └─ DYNAMIC GRAMMAR: isVisibleTopic* → C4 → SelectionPlanner.plan
     ├─ TopicSeal: isVisibleTopic* → C4 → SelectionPlanner.plan
     ├─ S2 findSealGroups (FOR SHARE g, bỏ qua nếu rỗng)
     ├─ S3 findSealQuestions (FOR SHARE q)
     ├─ S4 findSealMaterials
     ├─ ContentEvents.describeMedia(mediaIds)            (C1, bỏ qua nếu rỗng)
     ├─ SourceIntegrityValidator.validate(units, media) ─► loại đơn vị lỗi
     ├─ thiếu câu ─► NO_ELIGIBLE_QUESTIONS / INSUFFICIENT_QUESTIONS
     └─ SealAssembler.assemble(order) ─► SealSource
```

### 4.8. Hiệu năng ước lượng (chưa đo)

| Nguồn | Câu SQL | Dòng đọc |
|---|---|---|
| FIXED Full Test | S1, S2, S3, S4, C1 = **5** | 12 + 42 + 200 (+775 lựa chọn trong JSON) + ~100 + ~60 |
| DYNAMIC TOEIC 200 | S1, C2, S2, S3, S4, C1 = 6 | C2 trả một dòng mỗi đơn vị trong ngân hàng |
| `TopicSeal` 20 câu | C4, S3, S4 = 3 (+ `isVisibleTopic` mỗi topic) | — |

- Bộ nhớ: record seal của Full Test khoảng 1–2 MB, tồn tại trong một request.
- Thời gian giữ kết nối là thời gian của transaction start ở T3. Đo ở T7.

### 4.9. Delta tài liệu (thực hiện trong T2)

| File | Thay đổi |
|---|---|
| `docs/m05/02-snapshot-json-schemas-v1.md` §8 | `mediaUrl` là `/api/v1/attempts/{attemptId}/media/{mediaAssetId}`; xóa `mediaUrlExpiresAt`; ghi quy tắc `<audio>`/`<img>` không đặt `crossorigin` |
| `docs/m05/01-adrs-07-to-13.md` | Đã sửa ADR-13 ở lượt viết brief này. Thêm vào bảng S1–S12 một dòng "S11 (sửa T2): redirect 5 phút". |
| `docs/practice/CONTRACT.md` §2.1 | Gạch `countAvailable`, `selectQuestions`, `loadSetForSealing`; ghi `prepareSeal` cùng record 4.3; ghi `ContentEvents.describeMedia`/`mediaPlaybackLocation` |
| `docs/practice/CONTRACT.md` §3.2 | Tham số catalog đầy đủ (5.1). Endpoint media (3.M2) thuộc `results`. |
| `docs/m05/tasks/T1-…md` §3.M8 | Envelope `PRACTICE_SET.data` thêm `difficulty?`, `description?`. `selection_config` DYNAMIC có các khóa `questionCount`, `partNumbers` (TOEIC), `difficulty?`, `wordSenseIds` (VOCAB), `grammarTopicIds` (GRAMMAR) |

---

## 5. Hợp đồng giao tiếp

### 5.1. HTTP (`practice`)

Chung:
- `@PreAuthorize("hasRole('LEARNER')")`. Chưa đăng nhập → 401 `UNAUTHENTICATED`. Thiếu role → 403 `ACCESS_DENIED`.
- `GET` không cần CSRF.
- Lỗi là `application/problem+json` với `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, `errors` (`GlobalExceptionHandler.problem`).

#### `GET /api/v1/practice-sets`

| Query | Kiểu | Quy tắc | Lỗi |
|---|---|---|---|
| `category` | `VOCABULARY \| GRAMMAR \| TOEIC` | Tùy chọn | Giá trị khác → 400 `VALIDATION_ERROR` (`errors[0].field = "category"`) |
| `partNumber` | int | Tùy chọn. `0` = nhiều Part, `1..7` = đúng Part. Chỉ hợp lệ khi `category = TOEIC`. | Ngoài `0..7`, hoặc có mà `category ≠ TOEIC` → 400 (`field = "partNumber"`) |
| `difficulty` | `EASY \| MEDIUM \| HARD` | Tùy chọn | 400 (`field = "difficulty"`) |
| `q` | string | Tùy chọn, trim, 1–100 ký tự | Rỗng sau trim → bỏ qua. > 100 ký tự → 400 (`field = "q"`) |
| `page`, `size` | int | `PageRequestUtils.of` (mặc định 0/20, tối đa 100) | 400 theo `PageRequestUtils` |

200 `PageResponse<PracticeSetSummaryResponse>`:

```json
{ "items": [
    { "id": "e5000000-0030-4000-8000-000000000001", "category": "TOEIC", "title": "Seed TOEIC mixed (P1-P7)",
      "partNumber": null, "difficulty": null, "selectionMode": "FIXED", "questionCount": 24, "groupCount": 5,
      "timeLimitSeconds": null } ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1, "hasNext": false }
```

`record PracticeSetSummaryResponse(UUID id, String category, String title, Integer partNumber, String difficulty, String selectionMode, Integer questionCount, int groupCount, Integer timeLimitSeconds)`

#### `GET /api/v1/practice-sets/{practiceSetId}`

200 `PracticeSetDetailResponse` (các trường của summary cộng `description`, `partBreakdown`):

```json
{ "id": "e5000000-0030-4000-8000-000000000001", "category": "TOEIC", "title": "Seed TOEIC mixed (P1-P7)",
  "description": null, "partNumber": null, "difficulty": null, "selectionMode": "FIXED",
  "questionCount": 24, "groupCount": 5, "timeLimitSeconds": null,
  "partBreakdown": [
    { "partNumber": 1, "section": "LISTENING", "title": "Mô tả tranh", "description": "Nghe 4 câu mô tả một bức ảnh, chọn câu đúng nhất. Câu mô tả không in trên đề.", "questionCount": 2, "groupCount": 0 },
    { "partNumber": 3, "section": "LISTENING", "title": "Đoạn hội thoại", "description": "Nghe hội thoại 2–3 người, trả lời 3 câu hỏi. Một số câu dùng bảng hoặc hình.", "questionCount": 3, "groupCount": 1 },
    { "partNumber": 7, "section": "READING", "title": "Đọc hiểu", "description": "Đọc 1–3 văn bản (email, quảng cáo, tin nhắn…) và trả lời câu hỏi.", "questionCount": 7, "groupCount": 2 } ] }
```

Ví dụ trên rút gọn `partBreakdown`. Với seed S01, mảng có đủ Part 1–7.

| Lỗi | HTTP | `code` |
|---|---|---|
| `practiceSetId` không phải UUID | 400 | `VALIDATION_ERROR` |
| Không tồn tại, chưa publish, archive, thu hồi, hoặc FIXED có item không hiển thị | 404 | `RESOURCE_NOT_FOUND` |

Ví dụ 404:

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "detail": "Practice set not found.",
  "instance": "/api/v1/practice-sets/e5000000-0030-4000-8000-000000000099", "code": "RESOURCE_NOT_FOUND",
  "traceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", "errors": [] }
```

Response catalog **không có**: câu hỏi, lựa chọn, học liệu, `selection_config` thô, `topic_id`, `deck_id`, `published_revision_id`.

### 5.2. `ContentEvents` (bổ sung)

```java
/** Metadata of CONTENT media; ids not found or not CONTENT purpose are absent from the map. */
public Map<UUID, MediaDescriptor> describeMedia(Set<UUID> mediaIds) {
    return events.request(reply -> new MediaDescriptionRequested(Set.copyOf(mediaIds), reply));
}

/** Presigned GET location (5 minutes) when storage is configured and the asset is playable. */
public Optional<URI> mediaPlaybackLocation(UUID mediaId) {
    return events.request(reply -> new MediaPlaybackRequested(mediaId, reply));
}

public record MediaDescriptor(UUID id, String mediaType, String mimeType, long fileSizeBytes,
                              Integer durationMs, String status) {}
public record MediaDescriptionRequested(Set<UUID> mediaIds, EventReply<Map<UUID, MediaDescriptor>> reply) {}
public record MediaPlaybackRequested(UUID mediaId, EventReply<Optional<URI>> reply) {}
```

Quy tắc của `content` khi trả `mediaPlaybackLocation`:
- Kết quả rỗng khi:
  - `PronunciationStorage` không có bean (R2 tắt);
  - asset không tồn tại hoặc `purpose ≠ 'CONTENT'`;
  - `status` không thuộc `('READY','ARCHIVED')`;
  - `bucket` khác `storage.bucket()` (cùng điều kiện với `PronunciationServiceImpl.java:34,48`).
- `ARCHIVED` vẫn phát được: bài cũ còn tham chiếu, và FK `attempt_media_refs` chặn xóa dòng media.

### 5.3. Event async

T2 không phát hay nghe event async nào.

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn

| Rủi ro | Biện pháp |
|---|---|
| Record seal (có đáp án) lọt ra HTTP | ArchUnit `sealRecordsNeverReachHttpLayer`: class trong `..controller..` và `..dto..` không được phụ thuộc lớp có tên bắt đầu bằng `com.masterenglish.api.common.events.PracticeEvents$Seal` (11.4) |
| Catalog lộ đề | DTO catalog chỉ có metadata. `SecurityLeakTests.catalogNeverContainsQuestionText` assert JSON không chứa `prompt`/`body` của seed. |
| Media `AFTER_SUBMIT` được ký trước khi nộp | Endpoint T3 kiểm `visibility`/`status` trước khi gọi `mediaPlaybackLocation` (3.M2). `content` không biết attempt, nên không thể tự kiểm. |
| URL ký bị chia sẻ | TTL 5 phút; `Referrer-Policy: no-referrer`; `Cache-Control: no-store` |
| `object_key` gợi ý đáp án | ADR-08: `content/<uuid>.<ext>`. T2 không tạo object key nào. |
| IDOR catalog | Catalog là dữ liệu chung đã publish, không gắn user. Bộ ẩn trả 404, không phân biệt lý do. |
| CSRF | Chỉ có `GET`, không đổi trạng thái |
| SQL injection | Native query chỉ dùng tham số bind. `q` được escape ký tự `LIKE` và bind dưới dạng tham số. |
| Đọc nguồn không nhất quán khi publish song song | `FOR SHARE` theo thứ tự set → nhóm (`id`) → câu (`id`) trong transaction của caller (3.M6) |
| Deadlock với publish | Publish (projection) khóa ghi dòng `questions`/`question_groups` của chính item đang publish. `prepareSeal` khóa chia sẻ theo thứ tự `id` tăng dần. Hai phía không giữ khóa rồi chờ ngược nhau trên cùng tập theo hai thứ tự khác nhau. Nếu PostgreSQL vẫn phát hiện deadlock, nó hủy một bên (`40P01`) và T3 trả 500. Test `PrepareSealConcurrencyTests` (11.3) kiểm. |
| Thứ tự lựa chọn | TOEIC `FIXED`. `SealOption.position` giữ nguyên, không xáo. |

---

## 7. Ranh giới Spring Modulith và tài nguyên

- **DAG:**
  - `practice` → `common::{events,error,security,api,pagination}`.
  - `content` → `common::{…, storage}` (đã có).
  - `practice` gọi `content` qua `ContentEvents` và `grammar` qua `GrammarEvents`. `content` và `grammar` không gọi `practice`. Không có vòng.
- **ArchUnit hiện có, không đổi:**
  - Controller chỉ gọi service interface.
  - Service không dùng JDBC/`jakarta.persistence`.
  - Repository là interface JPA có `@Repository`.
  - `*Impl` nằm trong `service.impl`.
  - Package nằm trong tập MVC cho phép.
  - Planner và validator thuần nằm trong `service.impl` và **không** có hậu tố `Impl` (không vi phạm rule `serviceImplementationsLiveInImplPackages`).
- **UUID FK:** entity mới `PracticeSet` không có quan hệ JPA. Nó có `topicId`, `deckId` (`UUID`), nhưng các trường này chưa cần map ở T2 vì catalog không trả.
- **Pool 5 kết nối:** catalog là 1 transaction chỉ đọc với 2 câu SQL (list + count). `prepareSeal` không mở kết nối mới: listener đồng bộ chạy trong transaction của caller.
- **RAM:**
  - API: record seal ≤ 2 MB mỗi lượt start; không cache.
  - PostgreSQL: không index mới ngoài V16 (V16 không thêm index).

---

## 8. Edge Cases & Failure Modes Matrix

| # | Tình huống | Nguyên nhân | Hành vi | Kết quả |
|---|---|---|---|---|
| 1 | Bộ FIXED S01 có nhóm G04 (P7, 2 câu) bị archive sau khi publish bộ | Vận hành nội dung | Catalog lọc bộ khỏi danh sách. `prepareSeal` tính đơn vị không đủ điều kiện. | List không còn bộ; start 409 `INSUFFICIENT_QUESTIONS` (`requestedCount = 24`, `availableCount = 22`) |
| 2 | DYNAMIC TOEIC `partNumbers=[3]`, `questionCount=10` | 10 không chia hết cho 3 | Vòng 1 lấy 3 nhóm (9 câu). Vòng 2 không có đơn vị size ≤ 1. | 409 `INSUFFICIENT_QUESTIONS` (10, 9) |
| 3 | DYNAMIC TOEIC `[1,5]`, N = 7 | — | Hạn mức P1 = 4, P5 = 3 | 7 câu: P1 trước, P5 sau |
| 4 | Ngân hàng không có câu nào cho Part yêu cầu | — | — | 409 `NO_ELIGIBLE_QUESTIONS` (N, 0) |
| 5 | Nhóm P6 có `blank_key` thiếu node `blank` tương ứng | Dữ liệu lỗi | V-03 loại đơn vị, log `ERROR` | 409 hoặc thiếu câu |
| 6 | Câu P1 có 3 lựa chọn | Dữ liệu lỗi | V-14 loại | 409 |
| 7 | Audio P3 có status `FAILED` | Upload lỗi | V-14 loại (AUDIO phải `READY`) | 409 |
| 8 | Publish bộ mới trong lúc người học start | Đồng thời | `FOR SHARE` tuần tự hóa | Start đọc một bản nhất quán |
| 9 | `prepareSeal` được gọi ngoài transaction | Bug T3 | `Propagation.MANDATORY` | `IllegalTransactionStateException` → 500 |
| 10 | R2 tắt (local/test) | Cấu hình | `mediaPlaybackLocation` trả rỗng | Endpoint T3 trả 503 `SERVICE_UNAVAILABLE` |
| 11 | Media seed có bucket khác bucket cấu hình | Seed local (O1 của T1) | Rỗng | 503 |
| 12 | Set chỉ có câu đơn (không nhóm) | — | Bỏ qua S2; S3 truyền `groupIds = [NIL]` | Không lỗi `IN ()` |
| 13 | `GET /practice-sets?partNumber=3&category=GRAMMAR` | Client sai | — | 400 `VALIDATION_ERROR` |
| 14 | `q = "50%_off\"` | Ký tự đặc biệt | Escape `%`, `_`, `\` | Tìm đúng chuỗi literal |
| 15 | DYNAMIC VOCAB có cả `deck_id` lẫn `wordSenseIds` | Dữ liệu lỗi | Validator publish chặn. Nếu lọt tới seal: `IllegalStateException` | 500 và log |
| 16 | Topic GRAMMAR bị ẩn | Vận hành | `isVisibleTopic = false` | 404 `RESOURCE_NOT_FOUND` |
| 17 | Full Test có tổng câu > 200 do nhóm thêm câu sau khi publish bộ | Dữ liệu lỗi | V-17 | 500 và log, validator publish phải chặn |
| 18 | URL ký hết hạn lúc người học tạm dừng | TTL 5 phút | R2 403; frontend gán lại `src` | Phát tiếp (T6) |

---

## 9. Kế hoạch chỉnh sửa file

| Đường dẫn | Hành động | Nội dung |
|---|---|---|
| `src/main/resources/db/migration/V16__practice_sets_catalog_fields.sql` | CREATE | 4.1 |
| `src/main/java/com/masterenglish/api/common/events/PracticeEvents.java` | MODIFY | 4.3 (gỡ API A6 chưa dùng) |
| `src/main/java/com/masterenglish/api/common/events/ContentEvents.java` | MODIFY | 5.2 |
| `src/main/java/com/masterenglish/api/modules/practice/**` | CREATE/MODIFY | 4.2 |
| `src/main/java/com/masterenglish/api/modules/content/service/ContentQueryService.java` | MODIFY | 4.2 |
| `src/main/java/com/masterenglish/api/modules/content/service/impl/ContentQueryServiceImpl.java` | MODIFY | 4.2 |
| `src/main/java/com/masterenglish/api/modules/content/service/impl/ContentEventHandler.java` | MODIFY | 2 listener mới |
| `src/test/java/com/masterenglish/api/LayerArchitectureTests.java` | MODIFY | Thêm `sealRecordsNeverReachHttpLayer` |
| `src/test/java/com/masterenglish/api/modules/practice/**` | CREATE | 11 |
| `src/test/java/com/masterenglish/api/modules/content/service/MediaFacadeTests.java` | CREATE | 11 |
| `docs/m05/02-…md`, `docs/m05/01-…md`, `docs/practice/CONTRACT.md`, `docs/m05/tasks/T1-…md` | MODIFY | 4.9 |
| `src/main/resources/db/migration/V1__…` → `V15__…` | DO NOT TOUCH | D8 v2 |
| `practice/controller/AttemptController.java`, `GrammarPracticeController.java`, `PracticeServiceImpl.java`, entity attempt | DO NOT TOUCH | Thuộc T3 |
| `infrastructure/storage/R2PronunciationStorage.java`, `common/storage/PronunciationStorage.java` | DO NOT TOUCH | Giữ TTL 5 phút (3.M2) |
| `content/controller/PronunciationController.java` | DO NOT TOUCH | — |

---

## 10. Lộ trình commit

Mỗi bước: test được chọn xanh và `.\gradlew.bat spotlessCheck`. Commit chỉ khi người dùng yêu cầu.

| Bước | Nội dung | Lệnh | Commit đề xuất |
|---|---|---|---|
| 1 | V16 + entity `PracticeSet` + `PracticeSetRepository` (catalog) + `PracticeCatalogService` + controller + DTO + `ToeicPart` | `--tests '*PracticeSetCatalog*' --tests '*ArchitectureTests' --tests '*LayerArchitectureTests'` | `feat(practice): thêm API danh mục bộ đề` |
| 2 | Facade media `ContentEvents` + handler `content` | `--tests '*MediaFacadeTests' --tests 'com.masterenglish.api.modules.content.*'` | `feat(content): cung cấp facade metadata và phát media` |
| 3 | `SourceIntegrityValidator`, `ToeicUnitPlanner`, `SealAssembler` (thuần) + unit test | `--tests '*SourceIntegrityValidatorTests' --tests '*ToeicUnitPlannerTests' --tests '*SealAssemblerTests'` | `feat(practice): kiểm định nguồn và lập kế hoạch chọn câu` |
| 4 | Truy vấn S1–S4, C2–C4, `QuestionSelectionServiceImpl`, `PracticeEventHandler`, `PracticeEvents.prepareSeal` + rule ArchUnit mới | `--tests '*PrepareSeal*' --tests '*SealLoadQueryTests' --tests '*LayerArchitectureTests' --tests 'com.masterenglish.api.modules.practice.*'` | `feat(practice): facade chuẩn bị snapshot cho results` |
| 5 | Delta tài liệu 4.9 | Script C-01/C-02 trên 02 | `docs(m05): cập nhật hợp đồng media và facade practice` |
| 6 | Cổng cuối | `.\gradlew.bat check bootJar` | — |

---

## 11. Test Matrix

### 11.1. Unit (thuần, không Spring)

| Test | Assert |
|---|---|
| `ToeicUnitPlannerTests.quotaSplitsEvenlyWithRemainderToLowerParts` | `[1,2,5]`, N = 10 → hạn mức 4/3/3 |
| `ToeicUnitPlannerTests.neverSplitsGroups` | Mọi nhóm được chọn có đủ câu |
| `ToeicUnitPlannerTests.sameInputSameOutput` / `differentRequestIdDifferentOrder` | Kết quả xác định theo `requestId` |
| `ToeicUnitPlannerTests.indivisibleCountReportsMaxPlaceable` | Edge #2: `availableCount = 9` |
| `ToeicUnitPlannerTests.fillsRemainderAcrossParts` | Vòng 2 lấy được đơn vị ở Part khác |
| `ToeicUnitPlannerTests.outputOrderedByPartThenKey` | Thứ tự đầu ra P1→P7 |
| `SealAssemblerTests.displayNumbersAreContiguousFromOne` | 1..N |
| `SealAssemblerTests.part6BlankMapMatchesSeedS01` | `{"b1":14,"b2":15,"b3":16,"b4":17}` |
| `SealAssemblerTests.part1And2AreLabelOnly` | `optionDisplay = LABEL_ONLY`, `label` A–D/A–C |
| `SealAssemblerTests.speakerCountFromTranscript` | G01 = 3 |
| `SourceIntegrityValidatorTests` | Mỗi V-01..V-05, V-09, V-10, V-14..V-17 có 1 ca hợp lệ và 1 ca vi phạm. Vi phạm → đơn vị bị loại, kèm mã quy tắc trong kết quả. |

### 11.2. Integration (Testcontainers PostgreSQL 17, seed T1 qua `LocalM05SeedRunner.seedFlyway(flyway).migrate()`)

| Test | Assert |
|---|---|
| `PracticeSetCatalogTests.listsSeedSets` | 3 bộ. S01 `questionCount = 24`, `groupCount = 5`. |
| `PracticeSetCatalogTests.partNumberZeroReturnsMultiPartOnly` | Chỉ S01 |
| `PracticeSetCatalogTests.partNumberWithNonToeicIs400` | 400 `VALIDATION_ERROR`, `errors[0].field = "partNumber"` |
| `PracticeSetCatalogTests.archivedItemHidesFixedSet` | Archive G04 → S01 không còn trong list; detail 404 |
| `PracticeSetCatalogTests.searchEscapesWildcards` | Edge #14 |
| `PracticeSetCatalogTests.detailHasPartBreakdown` | S01: P1 2 câu, P3 3/1, P6 4/1, P7 7/2 |
| `PracticeSetCatalogTests.learnerRoleRequired` | Không đăng nhập 401; role khác 403 |
| `SealLoadQueryTests.forShareWithScalarSubqueriesRuns` | S3 chạy được trên PostgreSQL 17 (xác nhận giả định ở 4.4) |
| `SealLoadQueryTests.fullSeedSetLoadsInFiveStatements` | Đếm câu lệnh bằng `datasource-proxy` hoặc thống kê Hibernate (`hibernate.generate_statistics` trong test): ≤ 5 câu cho S01 |
| `PrepareSealTests.fixedSetS01ReturnsOrderedSource` | 24 câu, `displayNumber` 1..24, 5 nhóm, `correctSourceOptionId` khớp seed |
| `PrepareSealTests.privatePartsPresentForResults` | G01 `materials` có `TRANSCRIPT` `AFTER_SUBMIT`. Q01 `options[].body` có văn bản. Q03 `promptTranscript` có. |
| `PrepareSealTests.dynamicGrammarS03UsesD9` | 3 câu, cùng `requestId` cho cùng kết quả |
| `PrepareSealTests.vocabularyS02` | 3 câu, Q31 có AUDIO |
| `PrepareSealTests.requiresTransaction` | Gọi ngoài transaction → `IllegalTransactionStateException` |
| `PrepareSealTests.hiddenSetIs404` | `RESOURCE_NOT_FOUND` |
| `MediaFacadeTests.describeMediaReturnsContentOnly` | Media `AVATAR` không có trong map |
| `MediaFacadeTests.playbackEmptyWhenStorageMissing` | Không có bean `PronunciationStorage` → `Optional.empty()` |
| `MediaFacadeTests.playbackSignsWhenBucketMatches` | `@MockitoBean PronunciationStorage` (`bucket` = seed bucket) → trả đúng URI mock |

### 11.3. Concurrency

| Test | Assert |
|---|---|
| `PrepareSealConcurrencyTests.publishWaitsForOpenSeal` | Thread A mở transaction và gọi `prepareSeal(S01)`, giữ transaction. Thread B `UPDATE questions SET updated_at = now() WHERE id = Q06` phải chờ (timeout 500 ms → vẫn bị chặn). A commit thì B xong. |
| `PrepareSealConcurrencyTests.twoSealsShareLocks` | Hai `prepareSeal(S01)` song song đều thành công (khóa chia sẻ) |

### 11.4. Security và ArchUnit

| Test | Assert |
|---|---|
| `LayerArchitectureTests.sealRecordsNeverReachHttpLayer` | `noClasses().that().resideInAnyPackage("..controller..", "..dto..").should().dependOnClassesThat().haveNameMatching("com\\.masterenglish\\.api\\.common\\.events\\.PracticeEvents\\$Seal.*")` |
| `ArchitectureTests` | Không đổi, xanh |
| `LayerArchitectureTests` (13 rule hiện có) | Xanh |
| `SecurityLeakTests.catalogNeverContainsQuestionText` | JSON list/detail không chứa `prompt`, `body` của bất kỳ câu seed nào (so chuỗi) |

### 11.5. Kiểm tra thủ công trên R2 thật (bắt buộc trước T6)

Trên EC2, theo cách chạy `R2SmokeCheck` ở README:
1. Upload một MP3 thử.
2. Ký URL bằng `signGet`.
3. `curl -s -D - -o /dev/null -H "Range: bytes=0-1023" "<url>"` → phải có `HTTP/1.1 206`, `Content-Range: bytes 0-1023/<size>`, `Accept-Ranges: bytes`.

Ghi kết quả vào PR. Nếu không có 206, dừng T6 và mở lại 3.M2.

---

## 12. Definition of Done & Open Decisions

### 12.1. DoD

| # | Tiêu chí | Pass/Fail |
|---|---|---|
| 1 | V16 additive, V1–V15 không đổi | ☐ |
| 2 | `PracticeEvents` chỉ còn `prepareSeal`, không còn method A6 không dùng | ☐ |
| 3 | Mọi test 11.1–11.4 xanh | ☐ |
| 4 | `SealLoadQueryTests.fullSeedSetLoadsInFiveStatements` xanh | ☐ |
| 5 | `LayerArchitectureTests` (cả rule mới) và `ArchitectureTests` xanh | ☐ |
| 6 | `.\gradlew.bat spotlessCheck check bootJar` xanh | ☐ |
| 7 | Delta tài liệu 4.9 đã ghi; C-01/C-02 đạt trên 02 | ☐ |
| 8 | Kiểm tra 11.5 (206 trên R2) có kết quả | ☐ |

### 12.2. Câu hỏi cần duyệt

| # | Câu hỏi | Đề xuất |
|---|---|---|
| O1 | Mở cổng publish cho set TOEIC `DYNAMIC` ngay khi engine xong (T0 4.1.8 đang chặn)? | Chưa mở ở MVP. Engine có test; mở bằng một thay đổi validator khi nội dung đủ lớn. |
| O2 | Đổi S11 từ "URL ký 30 phút trong JSON" sang "endpoint redirect + URL ký 5 phút" (3.M2)? | Đồng ý. ADR-13 đã ghi. |
| O3 | Thêm `INDEX questions (category, part_number)` ngay, hay đợi ngân hàng > 50.000 câu? | Đợi. Hiện quét tuần tự rẻ hơn chi phí ghi index. |
| O4 | Gỡ hẳn `countAvailable`/`selectQuestions` khỏi `PracticeEvents` (không caller, không handler)? | Gỡ. Tránh API chết trong `common`. |
