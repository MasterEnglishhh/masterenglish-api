# T6 — Implementation Brief: Frontend Next.js — renderer node JSON (R6), 6 layout Part 1–7 và hàng đợi CAS

| Thuộc tính | Giá trị |
|---|---|
| Trạng thái | **ĐÃ DUYỆT** 26/11/2026. O1–O5 theo đề xuất: feature mới `practice`; nút bắt đầu bài VOCABULARY/GRAMMAR để task M03/M04; xung đột đa tab thì server thắng; hộp thoại 5 lượt không hiện tiêu đề; không làm dark mode. **Cần soát lại grounding trước khi code:** `origin/main` của web đã có `2e0b722` và `8e9d39a` (thiết kế lại giao diện, sửa phân quyền), trong khi brief viết trên `616e217`. |
| Ngày | 26/11/2026 |
| Phụ thuộc | T0, T3, T4 (đã duyệt). Không phụ thuộc T5 (T5 đang chờ duyệt, không đổi HTTP). |
| Repo đích | `masterenglish-web`, nhánh `dev`, HEAD `616e217` |
| Chế độ viết brief | READ-ONLY. Không sửa TypeScript, CSS, config. Brief nằm ở repo API cùng các brief khác. |

Nhãn: `[CREATE]`, `[MODIFY]`, `[DO NOT TOUCH]`.

**Cách triển khai.**
- Toàn bộ code nằm ở `masterenglish-web`. Test đơn vị và e2e dùng API giả lập (`page.route`), nên không cần backend.
- Chạy e2e với backend thật (`E2E_REAL_BACKEND=1`) chỉ khi code T3/T4 đã deploy.
- Working tree web hiện có thay đổi chưa commit ở `e2e/*.spec.ts` và `e2e/helpers.ts` (7 file). Đó là việc của người dùng: **giữ nguyên**, không stage chung.

---

## 1. Metadata & Traceability

### 1.1. Sản phẩm

1. Feature mới `src/features/practice/` (O1): type, runtime guard, client API, hàng đợi lưu đáp án, renderer, 6 layout, navigator, trang kết quả.
2. Hai route static-export: `/learn/practice/attempt?id=<uuid>` và `/learn/practice/result?id=<uuid>`.
3. Danh mục bộ đề TOEIC và nút "Bắt đầu" trên `/learn/toeic`, kèm hộp thoại giới hạn 5 lượt mở.
4. `src/lib/http.ts` nhận thêm header riêng (`Idempotency-Key`).
5. Test: `node --test` (lib và hook), `react-test-renderer` (renderer), Playwright (luồng với API giả lập).

### 1.2. Traceability

| Mã | Nội dung T6 |
|---|---|
| M05.01, M05.02 | Danh mục `GET /api/v1/practice-sets?category=TOEIC` (T2) |
| M05.03 | Bắt đầu lượt, `Idempotency-Key` theo mỗi lần bấm |
| M05.06 | Hàng đợi CAS theo từng câu |
| M05.08, M05.09 | Rào trước khi nộp (flush hàng đợi), `POST /submit` |
| M05.10 | Trang kết quả, xem lại, transcript |
| M05.11–M05.16 | 6 layout Part 1–7 (theo đề T6) |
| M03.12–M03.15, M04.06, M04.10, M04.11 | Cùng phòng làm bài cho VOCABULARY/GRAMMAR (layout câu đơn, có thể có AUDIO). Điểm vào: O2. |
| R2, S2 | `AttemptStatus = IN_PROGRESS \| SUBMITTED \| ABANDONED` |
| R4 | Hộp thoại `OPEN_ATTEMPT_LIMIT_REACHED` (không có nút hủy) |
| R6 | Render node `blank`/`insert`; cấm `dangerouslySetInnerHTML` và cú pháp `{{…}}` |
| R8 | Đọc metadata có kiểu: `activeAttemptIds`, `currentVersion`, `currentSelectedOptionId`, `attemptStatus` |
| ADR-08 | P1/P2 chỉ hiện nhãn (`optionDisplay = LABEL_ONLY`) |
| ADR-09 | CAS `expectedVersion`; `selectedOptionId = null` là bỏ chọn |
| ADR-11 | Kết quả là số câu đúng, không có "điểm", không quy đổi 990 |
| ADR-13, S11 | Media qua URL ổn định `/api/v1/attempts/{id}/media/{mediaAssetId}` → 302 (TTL 5 phút) |

### 1.3. Explicitly Out-of-Scope

- Hàng rào YAGNI (PREAMBLE §4): Exam Mode, đổi tốc độ audio, gạch đáp án, highlight, cắm cờ, karaoke transcript, dịch song ngữ, tô sáng dẫn chứng P7, lưu từ từ bài đọc, quy đổi 990, luyện lại câu sai, cache audio offline, `abandon`.
- Đồng hồ đếm ngược ép giờ: thuộc Exam Mode. Khi `deadlineAt ≠ null`, UI chỉ hiện giờ hạn nộp tĩnh.
- Lưu đáp án chưa gửi vào `localStorage`. Hàng đợi chỉ sống trong bộ nhớ tab; có cảnh báo `beforeunload` (3.M2).
- Lịch sử lượt làm `GET /api/v1/attempts` (M06), thống kê theo chủ điểm.
- Dark mode: repo chưa có theme tối (T6-D10).
- Hiển thị `evidence` (dẫn chứng) ở trang kết quả. Gần với "tô sáng dẫn chứng P7" nên để sau.
- Điểm vào VOCABULARY/GRAMMAR (O2).

---

## 2. Repo Audit & Drift Check

### 2.1. File đã đọc

| Nhóm | File (repo `masterenglish-web`) |
|---|---|
| Quy ước | `AGENTS.md` (khối Next.js 16, hai build target, vòng kiểm tra), `docs/UI_STYLE_GUIDE.md` §1.3, §2, §6.1, §7.3–7.4, §8, §9.1–9.3 |
| Cấu hình | `package.json` (Next 16.4.6, React 19.3.0, Tailwind 4, `lucide-react`, không có thư viện state/validation), `next.config.ts`, `eslint.config.mjs` (danh sách feature dòng 11–18, `max-lines` 350), `postcss.config.mjs`, `src/app/globals.css`, `src/styles/base.css` (token `:root`) |
| HTTP | `src/lib/http.ts`, `src/lib/session.ts`, `src/lib/user-result.ts`, `src/lib/latest-request.ts`, `functions/api/[[path]].ts` (proxy Pages) |
| Route | Toàn bộ `src/app/**` (không có route động `[param]`; id truyền qua query như `?deck=`) |
| Feature | `src/features/toeic/**` (demo Part 5 tĩnh), `learning/components/grammar-preview.tsx` (minh họa, chưa gọi API), `learning/data/navigation.ts`, `vocabulary/components/{vocabulary-view,pronunciation-player}.tsx`, `src/components/{dialog,status-badge,save-status,sample-tag}.tsx` |
| Test | `tests/helpers/load-hook.mjs`, `tests/vocabulary-practice.test.mjs`, `src/features/vocabulary/lib/vocabulary-client.test.ts`, `e2e/helpers.ts`, `playwright.config.ts` |
| Hợp đồng | T3 §5.1, T4 §3.M1, §3.M5–M6, §5.1, T2 §5 (catalog), `docs/m05/02` §3 (`$defs`), §7, §8 |

Lệnh kiểm chứng đã chạy (chỉ đọc):
- `grep` trong `src/`: 0 kết quả cho `attempts`, `practice-sets`, `grammar/topics`, `grammar/attempts`, `statistics/topics`. Frontend **không** gọi 3 endpoint grammar cũ (R5 an toàn).
- `grep` trong `src/`: 0 `dangerouslySetInnerHTML`, 0 thẻ `<img>`.
- `grep` `prefers-color-scheme|data-theme|color-scheme`: 0 kết quả, tức repo không có dark mode.

### 2.2. Drift

| Mã | Đề T6 | Thực tế / hợp đồng đã duyệt | Xử lý |
|---|---|---|---|
| T6-D01 | `409 MAX_ACTIVE_ATTEMPTS_REACHED` | T3 O1 (đã duyệt): `OPEN_ATTEMPT_LIMIT_REACHED` (`openAttemptCount`, `maxOpenAttempts`, `activeAttemptIds`) | Dùng tên đã duyệt |
| T6-D02 | Type có `public_snapshot`, `question_snapshot`, `private_snapshot`, `answer_key_snapshot`, `optionText` | DTO T3/T4 là projection camelCase: `groups[].materials` (từ public), `groups[].reviewMaterials` (từ private), `questions[]` (từ question_snapshot), `questions[].review` (từ answer_key), `options[].body` | Type TS theo đúng JSON T3 §5.1 và T4 §5.1. Ánh xạ tên ở 3.M1. |
| T6-D03 | Inline node `insertion_slot`; node `blank` mang `display_number` | 02 §3: `{"type":"insert","position":1..4}` và `{"type":"blank","blank_key":"b1"}`. `displayNumber` nằm ở câu hỏi có cùng `blankKey` trong nhóm (02 §7). | Dùng `insert`. Client tự dựng map `blankKey → displayNumber` theo nhóm (3.M3). |
| T6-D04 | Fallback theo `schema_version` | DTO không có trường `schemaVersion` (T3/T4). `materials[].body` không có `schema_version`. | Guard theo **cấu trúc**: `materialType`, `type` của node, `document_kind`, `questionKind` lạ → fallback. Nếu sau này DTO thêm `schemaVersion ≠ 1` thì cả nhóm/câu render fallback (3.M1). |
| T6-D05 | Cookie `credentials: 'include'` | `session.ts`: `credentials: "same-origin"`, `cache: "no-store"`. API cùng origin qua rewrite (standalone) hoặc Pages Function. CSRF lấy qua `GET /api/v1/auth/csrf`, ưu tiên cookie `XSRF-TOKEN`, gửi header theo `headerName`. | Giữ `same-origin`. Không đổi `session.ts`. |
| T6-D06 | Wrapper fetch đọc R8 | `requestJson` **không** nhận header riêng; `Problem` chỉ có `code/detail/message/title/errors`. Nhưng `res.json()` giữ mọi trường. | `[MODIFY]` `http.ts`: thêm `headers?: Record<string, string>`. Metadata R8 đọc bằng guard riêng của feature (3.M2). Không đổi interface `Problem` dùng chung. |
| T6-D07 | Route phòng thi | Pages build là static export. Repo không có route động; id đi qua query (`?deck=`, `?word=`). `useSearchParams` phải bọc `<Suspense>` (UI_STYLE_GUIDE §9.2). | `/learn/practice/attempt?id=` và `/learn/practice/result?id=` |
| T6-D08 | Một feature cho TOEIC và Vocab/Grammar | `eslint.config.mjs`: feature chỉ import chính nó hoặc `@/features/auth`. Chỉ `src/app/` được ghép nhiều feature. | Feature mới `practice`, thêm vào danh sách feature của ESLint. `app/learn/toeic/page.tsx` ghép `ToeicHome` với `PracticeSetCatalog`. (O1) |
| T6-D09 | 02 §8 có `selectedOptionId`/`answerVersion` trên từng câu, `byPart`, `mediaUrlExpiresAt` (TTL 30 phút) | T3/T4 (thắng): `savedAnswers` map; `result.partBreakdown`; `mediaUrl` ổn định, không có `mediaUrlExpiresAt`; `answer` + `review` trên từng câu ở `/results`. | Theo T3/T4. Delta 02 §8 đã ghi ở T3 4.10 và T4 4.4. |
| T6-D10 | TABLE chuẩn tương phản light/dark | Repo chỉ có token sáng ở `:root`, không có dark mode | Chỉ dùng token `var(--…)`, không mã màu cứng, để thêm dark mode sau không phải sửa component. Không thêm theme tối ở T6. |
| T6-D11 | Phần tử `refs`/`evidence` | T3/T4 chỉ có ví dụ `"refs": []`, chưa chốt casing phần tử | Theo luật 02 §8 (cấp DTO dùng camelCase, chỉ `materials[].body` nguyên văn): `{materialKey, anchor}`. Thêm dòng delta cho backend (4.9). **Đã áp ở rà soát chéo T0–T7 (AD-2):** 02 §8 và T3 §5.1 (`RefResponse`). |
| T6-D12 | Không có endpoint danh sách lượt | `GET /api/v1/attempts` là M06 (hoãn). Với 5 `activeAttemptIds`, muốn có tiêu đề phải gọi 5 lần `GET /attempts/{id}`, mỗi lần tới 400 KB. | Hộp thoại chỉ hiện "Lượt đang mở 1…5" kèm giờ không rõ. Không gọi thêm. (O4) |
| T6-D13 | `ToeicHome` ghi Part 1–4, 6–7 "Sắp ra mắt" | Sau T6 các Part chạy được nếu có nội dung xuất bản (Luật trung thực §1.3) | Đổi nhãn thành `rolling` (`Đang mở dần`). Câu Part 5 mẫu giữ `<SampleTag />`. |
| T6-D14 | Ảnh lazy, giữ khung | `ImageBody` chỉ có `alt_text`, `caption`; không có kích thước | Khung `aspect-ratio` cố định theo vị trí: P1 `4/3`, graphic P3/P4 và ảnh P7 `16/10`, `object-fit: contain`. |
| T6-D15 | — | Media URL là đường dẫn cùng origin trả 302 sang R2. Proxy Pages dùng `redirect: "manual"` và **giữ nguyên** `Location` khác origin (`functions/api/[[path]].ts:90-96`). Header `Idempotency-Key` không bị lọc (dòng 47–62). | Không sửa proxy. `<audio>`/`<img>` dùng thẳng `mediaUrl`, không đặt `crossOrigin`. |

---

## 3. Mandatory Design Resolutions

### M1. TypeScript types và runtime guard

**File `src/features/practice/types/attempt.ts` `[CREATE]`** (khớp T3 §5.1 và T4 §5.1):

```ts
export type AttemptStatus = "IN_PROGRESS" | "SUBMITTED" | "ABANDONED";
export type PracticeCategory = "VOCABULARY" | "GRAMMAR" | "TOEIC";
export type KnownMaterialType = "AUDIO" | "IMAGE" | "RICH_TEXT" | "CHAT" | "TABLE" | "TRANSCRIPT";

export interface MaterialDto {
  materialKey: string;
  materialType: string;            // KnownMaterialType, or an unknown value -> fallback
  position: number;
  mediaUrl: string | null;         // stable same-origin path; 302 to R2 (5 min)
  body: unknown;                   // snake_case, versioned document (02 §3); narrowed by each renderer
}

export interface RefDto { materialKey: string; anchor: string | null }

export interface GroupDto {
  groupSnapshotId: string;
  ordinal: number;
  partNumber: number | null;
  groupType: string;               // CONVERSATION | TALK | TEXT_COMPLETION | PASSAGE
  title: string | null;
  speakerCount: number | null;
  materials: MaterialDto[];
}

export interface OptionDto { optionId: string; label: string; body: string | null }

export interface QuestionDto {
  snapshotId: string;
  ordinal: number;
  displayNumber: number;
  groupSnapshotId: string | null;
  partNumber: number | null;
  questionKind: string;            // STANDARD | GRAPHIC | INTENT | SENTENCE_INSERTION | INSERTION_POSITION | NOT_EXCEPT
  optionDisplay: "TEXT" | "LABEL_ONLY";
  prompt: string | null;
  blankKey: string | null;
  insertSentence: string | null;
  refs: RefDto[];
  materials: MaterialDto[];
  options: OptionDto[];
}

export interface AttemptSource {
  type: "PRACTICE_SET" | "GRAMMAR_TOPIC" | "MIXED_GRAMMAR";
  practiceSetId?: string | null;
  topicId?: string | null;
  topicIds: string[];
}

export interface SavedAnswerDto { selectedOptionId: string | null; version: number }

/** GET /attempts/{id}, POST /attempts. Never contains answers or transcripts (T4 O2). */
export interface ActiveAttemptDto {
  id: string;
  category: PracticeCategory;
  attemptMode: "PRACTICE";
  status: AttemptStatus;
  source: AttemptSource;
  totalCount: number;
  startedAt: string;
  lastActiveAt: string;
  deadlineAt: string | null;
  groups: GroupDto[];
  questions: QuestionDto[];
  savedAnswers: Record<string, SavedAnswerDto>;
}

export interface PartBreakdownDto {
  partNumber: number; totalCount: number; correctCount: number; wrongCount: number; unansweredCount: number;
}

export interface ResultSummaryDto {
  totalCount: number; correctCount: number; wrongCount: number; unansweredCount: number;
  accuracyPercent: number;
  listeningCorrect: number | null; readingCorrect: number | null;
  partBreakdown: PartBreakdownDto[];
  submittedAt: string; effectiveFinishedAt: string;
  submitReason: "MANUAL" | "TIME_LIMIT"; durationSeconds: number;
}

export interface SpokenLineDto { speaker: string; text: string }
export interface OptionTranscriptDto extends SpokenLineDto { label: string }

export interface ReviewDto {
  correctOptionId: string;
  correctOptionLabel: string;
  explanation: string;
  evidence: RefDto[];
  optionTranscripts: OptionTranscriptDto[];   // P1: 4, P2: 3, otherwise []
  promptTranscript: SpokenLineDto | null;     // P2 only
}

/** GET /attempts/{id}/results, only when SUBMITTED. */
export interface AttemptResultDto {
  id: string;
  category: PracticeCategory;
  attemptMode: "PRACTICE";
  status: "SUBMITTED";
  source: AttemptSource;
  result: ResultSummaryDto;
  groups: (GroupDto & { reviewMaterials: MaterialDto[] })[];
  questions: (QuestionDto & {
    answer: { selectedOptionId: string | null; isCorrect: boolean };
    review: ReviewDto;
  })[];
}

export interface SavedAnswerResponse { snapshotId: string; selectedOptionId: string | null; version: number; answeredAt: string | null }
export interface AttemptSubmitResponse { attemptId: string; status: "SUBMITTED"; result: ResultSummaryDto; resultsUrl: string }
```

Hai type tách hẳn nhau, không có union và không có trường tùy chọn. `ActiveAttemptDto` không có `review`, `reviewMaterials` hay `answer`, nên phòng làm bài **không thể** vô tình đọc dữ liệu private.

Bảng ánh xạ đề → DTO (T6-D02):

| Đề | DTO |
|---|---|
| `public_snapshot` | `groups[].materials` |
| `private_snapshot` | `groups[].reviewMaterials` (chỉ `/results`) |
| `question_snapshot` | trường của `questions[]` (trừ `options`) |
| `options[].optionText` | `options[].body` (`null` với `LABEL_ONLY`) |
| `answer_key_snapshot` | `questions[].review` (chỉ `/results`) |

**Runtime guard `src/features/practice/lib/attempt-guards.ts` `[CREATE]`** (không thêm thư viện):

| Hàm | Kiểm | Lỗi |
|---|---|---|
| `parseActiveAttempt(json: unknown): ActiveAttemptDto \| null` | `id`, `status` ∈ 3 giá trị, `category`, mảng `groups`/`questions`, object `savedAnswers`. Mỗi câu: `snapshotId`, `displayNumber`, `options[]` có `optionId`+`label`. `savedAnswers[snapshotId]` có cho **mọi** câu. Có `review` hoặc `reviewMaterials` → `null`. | `null` → màn lỗi "Không đọc được dữ liệu bài làm" + `Thử lại` |
| `parseAttemptResult(json): AttemptResultDto \| null` | Như trên, cộng `result`, `answer`, `review` cho mọi câu; `status === "SUBMITTED"` | Như trên |
| `readProblemMeta(problem)` | Đọc an toàn `activeAttemptIds: string[]`, `openAttemptCount`, `maxOpenAttempts`, `currentVersion: number`, `currentSelectedOptionId: string \| null`, `attemptStatus: AttemptStatus`, `requestedCount`, `availableCount`. Phân biệt "không có khóa" (`undefined`) với `null`. | Sai kiểu → `undefined` |

Body của học liệu **không** được kiểm ở guard. Mỗi renderer tự thu hẹp `body` (3.M3). Body sai thì chỉ học liệu đó rơi vào fallback, câu hỏi vẫn trả lời được.

**Fallback an toàn** (component `UnsupportedContent`, chỉ hiện chữ, không bao giờ in JSON thô):

| Gặp | Hiển thị |
|---|---|
| `materialType` lạ, hoặc body không khớp shape | "Phần học liệu này chưa hiển thị được trên phiên bản hiện tại." |
| Block hoặc inline `type` lạ | Bỏ qua node; nếu là inline thì in `[…]` |
| `document_kind` lạ | Render như `OTHER` (02 §1) |
| `questionKind` lạ | Layout câu đơn, vẫn trả lời được |
| `partNumber` TOEIC ngoài 1–7 | Layout câu đơn |
| Sau này DTO có `schemaVersion` khác 1 | Cả nhóm/câu dùng `UnsupportedContent`, nhưng vẫn hiện nút A–D để trả lời |

### M2. Start, autosave và submit

#### Start (`POST /api/v1/attempts`), hook `useStartAttempt`

| Bước | Hành vi |
|---|---|
| Bấm "Bắt đầu" | Nếu chưa có `pending`: tạo `pending = { key: crypto.randomUUID(), body: { source: { type: "PRACTICE_SET", practiceSetId }, mode: "PRACTICE" } }`. Nút vào trạng thái `Đang chuẩn bị đề…`, `disabled`. |
| Gửi | `requestJson("/api/v1/attempts", { method: "POST", body, headers: { "Idempotency-Key": key } })` |
| `201` hoặc `200` | `parseActiveAttempt` → `attemptHandoff.put(dto)` → `router.push("/learn/practice/attempt?id=" + id)`. `200` là replay (bấm lại sau mất mạng); xử lý giống hệt. Xóa `pending`. |
| Lỗi mạng, 5xx, `csrf` | Giữ `pending` (cùng key, cùng body). Hiện "Không kết nối được. Thử lại". Bấm lại sẽ gửi **cùng key**, server trả `200` nếu lần trước đã tạo. |
| `409 OPEN_ATTEMPT_LIMIT_REACHED` | Mở `OpenAttemptsDialog` với `activeAttemptIds`. Giữ `pending`. |
| `409 IDEMPOTENCY_CONFLICT` | Không xảy ra khi body cố định. Nếu gặp: xóa `pending` và báo "Yêu cầu bị trùng. Vui lòng bấm Bắt đầu lại." |
| `409 NO_ELIGIBLE_QUESTIONS` / `INSUFFICIENT_QUESTIONS` | "Bộ đề chưa đủ câu hỏi khả dụng ({availableCount}/{requestedCount})." Xóa `pending`. |
| `404` | "Bộ đề không còn khả dụng." Tải lại danh mục. |
| `session` | "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại." (theo `toUserResult`) |

**`OpenAttemptsDialog`** (dùng `<Dialog>` có sẵn; không có nút hủy, R4):
- Tiêu đề: "Bạn đang có {openAttemptCount}/{maxOpenAttempts} lượt chưa nộp".
- Mô tả: "Hoàn thành hoặc nộp một lượt cũ để bắt đầu đề mới."
- Mỗi `activeAttemptId` là một dòng "Lượt đang mở {i}" với 2 nút:
  - `Tiếp tục làm`: link `/learn/practice/attempt?id=…`.
  - `Nộp bài này`: xác nhận một bước ("Các câu đã lưu sẽ được chấm. Câu chưa làm tính là bỏ trống."), rồi `POST /attempts/{id}/submit`. `200` hoặc `409 ATTEMPT_NOT_OPEN` thì bỏ dòng đó khỏi danh sách.
- Khi danh sách ngắn hơn `maxOpenAttempts`, nút chính `Bắt đầu đề mới` hiện ra và gửi lại **cùng `pending.key`**. Lượt trước bị từ chối nên chưa có dòng `attempts` nào giữ key này (T3).
- Đóng hộp thoại thì xóa `pending`.

#### Autosave: `createAnswerQueue` (thuần, không phụ thuộc React) và hook `useAttemptAutosave`

`src/features/practice/lib/answer-queue.ts` `[CREATE]`. Trạng thái mỗi `snapshotId`:

```ts
interface SlotState {
  confirmedVersion: number;          // last version acknowledged by the server
  confirmedOptionId: string | null;  // last selection acknowledged by the server
  desiredOptionId: string | null;    // what the learner sees as selected
  inFlight: { key: string; optionId: string | null; expectedVersion: number } | null;
  phase: "idle" | "saving" | "retrying" | "failed";
  failure: "network" | "session" | "rejected" | null;
}
```

Hàm tạo: `createAnswerQueue({ attemptId, initial: Record<snapshotId, SavedAnswerDto>, save, newKey, sleep, onChange, onClosed, onResync })`.
- `save` là hàm gọi client (`PUT`), được inject.
- `newKey` mặc định `crypto.randomUUID`; `sleep` mặc định `setTimeout`. Test thay cả hai.

| Thao tác | Quy tắc |
|---|---|
| `select(snapshotId, optionId)` | `desired = (desired === optionId) ? null : optionId`. Bấm lại lựa chọn đang chọn là **bỏ chọn** (ADR-09). Gọi `pump(snapshotId)`. |
| `pump(s)` | Nếu `inFlight ≠ null` thì dừng; lượt phản hồi sẽ gọi lại `pump`. Nếu `desired === confirmed` thì `phase = idle`. Ngược lại tạo `inFlight = { key: newKey(), optionId: desired, expectedVersion: confirmedVersion }` và gửi. |
| Tuần tự theo câu | Mỗi câu có tối đa **1** request đang bay. Đổi nhanh `A → B → C` khi A đang bay: gửi A, rồi gửi **C** (B bị gộp). Đây là FIFO có gộp: thứ tự trên server luôn đúng thứ tự người học bấm, và trạng thái cuối luôn là lựa chọn cuối. Các câu khác nhau chạy song song. |
| `200` | `confirmed* = response`, `inFlight = null`, rồi `pump` (có thể gửi tiếp nếu `desired` đã đổi). |
| Mạng, 5xx, `csrf`, 429 | `phase = retrying`; gửi lại **đúng `inFlight`** (cùng key, cùng `expectedVersion`, cùng `optionId`) sau 1 s, 2 s, 4 s (hoặc `Retry-After`). Hết 3 lần → `phase = failed`, `failure = network`. **Không** tạo request mới cho câu này khi `inFlight` chưa có kết quả, vì server có thể đã ghi. |
| `409 VERSION_CONFLICT` | Tab khác đã ghi. `confirmedVersion = currentVersion`, `confirmedOptionId = currentSelectedOptionId`, **`desired = currentSelectedOptionId`** (server thắng, O3), `inFlight = null`. Gọi `onChange` với `notice = { snapshotId, kind: "conflict" }` để hiện thông báo nhẹ. |
| `409 IDEMPOTENCY_CONFLICT`, `404`, `400 OPTION_NOT_IN_SNAPSHOT` | `phase = failed`, `failure = rejected`; gọi `onResync()`. Hook tải lại `GET /attempts/{id}` và `reset(savedAnswers)`. |
| `409 ATTEMPT_NOT_OPEN` | `stop()` mọi câu; gọi `onClosed(attemptStatus)` (3.M5). |
| `session` | `phase = failed`, `failure = session`. Banner "Phiên đăng nhập đã hết hạn…". Lựa chọn vẫn giữ trong bộ nhớ. |
| `retryFailed()` | Mọi câu `failed` có `inFlight` thì gửi lại **cùng key**; còn lại thì `pump`. |
| `flush(timeoutMs = 15000): Promise<"saved" \| "failed" \| "closed">` | Chờ tới khi mọi câu `idle` hoặc `failed`. Có câu `failed`, hoặc hết giờ, → `"failed"`. |
| `pendingCount()` | Số câu `desired ≠ confirmed` hoặc `inFlight ≠ null` |

`useAttemptAutosave(attempt: ActiveAttemptDto)`:
- Tạo queue một lần (`useRef`), đồng bộ state bằng `useSyncExternalStore`.
- Trả `{ selectedOf(snapshotId), phaseOf(snapshotId), select, flush, retryFailed, pendingCount, failedCount, notice, closed }`.
- `beforeunload`: chặn rời trang khi `pendingCount() > 0`. Chỉ đăng ký listener khi có việc đang chờ.

Request `PUT /api/v1/attempts/{attemptId}/answers/{snapshotId}`:
- Header `Idempotency-Key: <inFlight.key>` (CSRF do `requestJson` lo).
- Body `{ "selectedOptionId": <uuid|null>, "expectedVersion": <int> }`.

#### Rào trước khi nộp (`SubmitDialog`)

1. Bấm `Nộp bài` → mở `<Dialog>`:
   - "Bạn đã trả lời {answered}/{total} câu. {unanswered} câu chưa làm sẽ tính là bỏ trống."
   - `answered` đếm theo `desiredOptionId ≠ null`.
2. Bấm `Xác nhận nộp`:
   - khóa mọi nút đáp án (`busy`), hiện "Đang lưu các câu cuối…";
   - `await flush()`.
3. Theo kết quả `flush()`:
   - `"failed"`: **không gọi submit**. Hiện "{failedCount} câu chưa lưu được." cùng nút `Thử lưu lại` (`retryFailed()` rồi `flush()` lại) và `Đóng`. Mở khóa đáp án khi đóng.
   - `"closed"`: chuyển theo 3.M5.
   - `"saved"`: `POST /api/v1/attempts/{id}/submit` (không body, không `Idempotency-Key`; submit idempotent theo trạng thái, T4 M5).
4. Kết quả submit:
   - `200`: `resultHandoff.put(summary)`, rồi `router.replace("/learn/practice/result?id=" + id)`.
   - Mạng/5xx: "Chưa nộp được. Thử lại". Gửi lại an toàn: nếu lần trước đã nộp, server trả `200` với kết quả đã lưu.
   - `409 ATTEMPT_NOT_OPEN` (`ABANDONED`): "Lượt này đã đóng do bỏ dở quá 7 ngày."

### M3. `StructuredContentRenderer` (R6, zero XSS)

**Luật cứng** (kiểm bằng test 11.1 `noUnsafeRendering`):
- Không `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `new Function` trong `src/features/practice/**`.
- Không regex hay `split` trên `{{`. Không có chuỗi `"{{"` trong mã nguồn feature.
- Mọi text đi qua JSX children (React tự escape).
- URL media lấy **duy nhất** từ `mediaUrl`, và phải khớp `^/api/v1/attempts/[0-9a-f-]{36}/media/[0-9a-f-]{36}$`. Không khớp thì render fallback, không đặt `src`.

**Dispatch** (`components/content/material-view.tsx`):

```tsx
export function MaterialView({ material, context }: { material: MaterialDto; context: RenderContext }) {
  switch (material.materialType) {
    case "AUDIO": return <PracticeAudioPlayer material={material} />;
    case "IMAGE": return <ImageMaterial material={material} aspect={context.imageAspect} />;
    case "RICH_TEXT": return <RichTextMaterial body={material.body} context={context} />;
    case "CHAT": return <ChatMaterial body={material.body} />;
    case "TABLE": return <TableMaterial body={material.body} />;
    case "TRANSCRIPT": return <TranscriptMaterial body={material.body} />;   // review only
    default: return <UnsupportedContent />;
  }
}

interface RenderContext {
  blankNumbers: ReadonlyMap<string, number>;   // blankKey -> displayNumber of the same group
  activeBlankKey: string | null;
  onBlankClick?: (blankKey: string) => void;
  imageAspect: "4/3" | "16/10";
}
```

Mỗi renderer có hàm thu hẹp riêng (`asRichTextBody(body): RichTextBody | null`, …) trong `lib/material-bodies.ts`. Thu hẹp thất bại → `UnsupportedContent`.

| Loại | Đặc tả |
|---|---|
| **AUDIO** `PracticeAudioPlayer` | Mỗi màn chỉ mount **một** `<audio preload="metadata" src={mediaUrl}>`. Không có `controls` gốc (tránh menu tốc độ của trình duyệt). Điều khiển: nút `Phát`/`Tạm dừng` (`aria-pressed`); `<input type="range">` để tua (`aria-label="Vị trí phát"`, bước 1 s, cập nhật `currentTime`); nhãn `mm:ss / mm:ss` (`body.duration_seconds` làm tổng trước khi có metadata); nghe lại không giới hạn (hết bài thì `Phát` bắt đầu lại từ 0). Lỗi (`error` event): hiện `Tải lại âm thanh`. Bấm thì nhớ `currentTime`, gọi `audio.load()`; URL ổn định nên server ký lại URL R2 mới; tiếp tục từ vị trí cũ. Khi unmount (đổi màn hoặc đổi nhóm): `pause()` và gỡ `src`. Có khóa module `currentPracticeAudio` như `PronunciationPlayer`: bắt đầu phát thì dừng player khác. `body.directions_text` (nếu có) là đoạn văn phía trên. **Không** có `playbackRate`. |
| **IMAGE** | `<img loading="lazy" decoding="async" alt={alt_text}>`. Thêm `eslint-disable-next-line @next/next/no-img-element` với lý do "static export + media 302". Khung `.pr-media-frame` có `aspect-ratio` theo `context.imageAspect` và `object-fit: contain`, để không xô layout. Có `caption` thì dùng `<figure>` + `<figcaption>`. Nút `Phóng to` (icon `Maximize2`) mở `<Dialog>` chứa ảnh cỡ lớn (lightbox). Áp cho graphic P3/P4 và ảnh P7. |
| **RICH_TEXT** | `document_kind` → class `pr-doc pr-doc--{kind}` và nhãn nhỏ (`EMAIL` → "Email", `ARTICLE` → "Bài báo", …; lạ → không nhãn). `title` → `<h3>`. `header` (EMAIL/MEMO) → `<dl class="pr-doc-header">` với các dòng `Từ`/`Đến`/`Đồng gửi`/`Chủ đề`/`Ngày` cho `from/to/cc/subject/date` có mặt. `blocks`: `heading` (`level` 1/2/3 → `<h4>`/`<h5>`/`<h6>`; dưới `<h3>` của tiêu đề), `paragraph` → `<p>`, `list` → `<ol>`/`<ul>` + `<li>`. |
| Inline `text` | Chuỗi; `marks` lồng theo thứ tự `BOLD` → `<strong>`, `ITALIC` → `<em>`, `UNDERLINE` → `<u>` |
| Inline `blank` (P6) | `<button type="button" class="pr-blank" data-blank-key={blank_key} aria-label="Ô trống câu {n}">{n}</button>`. `n = blankNumbers.get(blank_key)`. Không có `n` thì in `[…]` (fallback). Bấm thì gọi `onBlankClick` để focus câu tương ứng. `activeBlankKey` khớp thì thêm `aria-current="true"` và class nổi bật. |
| Inline `insert` (P7) | `<span class="pr-insert" aria-label="Vị trí [{position}]">— [{position}] —</span>`. Không click được. |
| Inline `line_break` | `<br />` |
| **CHAT** | `<ol class="pr-chat" aria-label={title ?? "Tin nhắn"}>`. Mỗi `message` là `<li data-anchor={anchor}>`, gồm `<strong>{author}</strong>`, `<time>{time}</time>` (giữ nguyên `12:10` hoặc `2:15 P.M.`), `<p>{text}</p>`. `channel` `TEXT_MESSAGE` hay `ONLINE_CHAT` chỉ đổi class. |
| **TABLE** | `<div class="pr-table-scroll" role="region" tabIndex={0} aria-label={title ?? "Bảng"}>` (cuộn ngang độc lập, §9.3 mốc 360 px) chứa `<table>`, `<caption>` (từ `title` hoặc `caption`), `<thead>` từ `headers`, `<tbody>` từ `rows`. Màu chỉ dùng token (`--ink` trên `--glass-bg-solid`, viền `--line`), đạt WCAG AA (§2.2). |
| **TRANSCRIPT** (chỉ trang kết quả) | Đoạn `speech` → `<p class="pr-line"><span class="pr-speaker" title={speakerName}>({speaker})</span> {text}</p>`. `speakerName` theo `lib/speaker.ts`: `N` "Người dẫn"; `W-`/`M-` là "Nữ"/"Nam"; `Am`/`Br`/`Cn`/`Au` là "giọng Mỹ"/"Anh"/"Canada"/"Úc". Đoạn `pause` → `<p class="pr-pause">(tạm dừng {seconds} giây)</p>`. Bỏ qua `start_ms`/`end_ms` (karaoke thuộc YAGNI). |

**Map ô trống:** `blankNumbers(groupSnapshotId) = new Map(questions.filter(q => q.groupSnapshotId === g && q.blankKey).map(q => [q.blankKey, q.displayNumber]))`. Hàm thuần trong `lib/screens.ts`.

### M4. 6 layout và `QuestionNavigator`

**Màn (screen):**
- Câu có `groupSnapshotId` thì gom theo nhóm (P3, P4, P6, P7).
- Câu không có nhóm thì mỗi câu một màn (P1, P2, P5, VOCABULARY, GRAMMAR).
- `buildScreens(attempt)` sắp màn theo `ordinal` nhỏ nhất của câu trong màn. Câu trong màn sắp theo `ordinal`.
- Chỉ **màn hiện tại** được mount, nên mỗi lúc có tối đa 1 `<audio>` (3.M6).

Chọn layout trong `layoutOf(screen)`:

| Điều kiện | Layout | Bố cục |
|---|---|---|
| `category = TOEIC`, Part 1 | `PhotoLayout` | Ảnh câu (`IMAGE`, khung 4/3) trên; `PracticeAudioPlayer` (AUDIO của câu); hàng 4 nút `A B C D`, chỉ có nhãn (`optionDisplay = LABEL_ONLY`, `body = null`). |
| Part 2 | `ResponseLayout` | Audio; hàng 3 nút `A B C`; dòng hướng dẫn "Nghe câu hỏi và chọn câu trả lời phù hợp nhất." |
| Part 3, 4 | `ListeningGroupLayout` | Header nhóm `position: sticky` (dưới topbar, `z-index: var(--z-sticky)`): audio nhóm, kèm graphic `IMAGE`/`TABLE` nếu có. Bên dưới là 3 câu con (`prompt` + 4 lựa chọn có chữ), hiển thị cùng lúc. Mỗi câu lưu riêng. Câu `GRAPHIC` có `refs[0].materialKey` thì ghi chú "Xem {bảng/hình} phía trên". |
| Part 5, VOCABULARY, GRAMMAR, hoặc fallback | `SingleQuestionLayout` | Học liệu của câu (nếu có; AUDIO cho M03.15 `LISTEN_AND_CHOOSE`), `prompt`, 4 lựa chọn có chữ |
| Part 6 | `TextCompletionLayout` | Từ 850 px: chia đôi. Trái là văn bản `RICH_TEXT` có 4 `blank`; phải là 4 câu con. Bấm ô trống thì cuộn và focus câu (`#q-{snapshotId}`); focus câu thì đánh dấu ô trống tương ứng (`activeBlankKey`). `SENTENCE_INSERTION`: lựa chọn xếp dọc rộng 100%, line-height 1.6. |
| Part 7 | `ReadingLayout` | Từ 850 px: chia đôi, **mỗi cột cuộn độc lập** (`height: calc(100dvh - var(--pr-room-offset))`, `overflow-y: auto`). Trái: 1–3 tài liệu. Có từ 2 tài liệu thì dùng tab (`role="tablist"`, nhãn "Tài liệu 1/2/3" + nhãn `document_kind`). Focus câu có `refs[0]` thì tự chuyển tab tới tài liệu đó. Phải: 2–5 câu con. `INSERTION_POSITION` hiện `insertSentence` trong khung trích dẫn, trên các lựa chọn `[1]–[4]`. |

Dưới 850 px mọi layout chia đôi chuyển thành 1 cột (tài liệu trước, câu sau), dùng cuộn trang. Dưới 600 px tắt `backdrop-filter` (§9.3).

**`OptionList`** (dùng chung):
- `<fieldset>` + `<legend class="sr-only">Chọn đáp án câu {n}</legend>`.
- Mỗi lựa chọn là `<button type="button" aria-pressed={selected}>`:
  - `LABEL_ONLY` chỉ hiện chữ cái;
  - `TEXT` hiện chữ cái + `body`.
- Bấm lựa chọn đang chọn là bỏ chọn (gợi ý `title="Bấm lần nữa để bỏ chọn"`).
- Trạng thái lưu cạnh số câu: component riêng `SaveIndicator` (`role="status"`, chữ nhỏ). `saving` "Đang lưu…", `retrying` "Đang thử lại…", `failed` "Chưa lưu"; `idle` không hiện gì. Không dùng `src/components/save-status.tsx`: file đó là `StatusLine` cho form tài khoản (`kind: saved | error`), không hợp trạng thái theo câu.
- Vùng chạm ≥ 44×44 px.

**`QuestionNavigator`:**
- Panel bên phải từ 1100 px; dưới 1100 px là nút `Danh sách câu ({answered}/{total})` mở `<Dialog>`.
- Nhóm theo Part ("Part 1" … "Part 7"). Category khác TOEIC: 1 nhóm "Câu hỏi".
- Mỗi số câu là `<button>` với đúng 3 trạng thái:
  - `Đang xem`: câu thuộc màn hiện tại; có `aria-current="true"`, viền `--indigo`;
  - `Đã trả lời`: `desired ≠ null`, nền `--indigo-wash`;
  - `Chưa trả lời`: nền trong suốt.
- Có chú thích (legend) 3 trạng thái. **Không** có cờ, highlight hay gạch.
- Bấm số câu: chuyển tới màn chứa câu và focus `#q-{snapshotId}`.
- Thanh dưới: `← Màn trước` / `Màn sau →` (`disabled` ở đầu/cuối) và `Nộp bài`.

### M5. Trang kết quả (`/learn/practice/result?id=`)

- Tải `GET /api/v1/attempts/{id}/results` sau hydrate.
- `resultHandoff` (nếu vừa nộp xong) chỉ dùng để hiện ngay khối tổng quan trong lúc chờ. Phần xem lại luôn tải từ `/results`.

| Lỗi | Hành vi |
|---|---|
| `409 ATTEMPT_NOT_SUBMITTED` (`attemptStatus = IN_PROGRESS`) | Chuyển tới `/learn/practice/attempt?id=` |
| `409 ATTEMPT_NOT_SUBMITTED` (`attemptStatus = ABANDONED`) | Thẻ "Lượt này đã đóng do bỏ dở quá 7 ngày" |
| `404` | Thẻ lỗi "Không tìm thấy bài làm" |

**Tổng quan** (`ResultSummary`):

| Ô | Nội dung |
|---|---|
| Số câu đúng | `{correctCount}/{totalCount}` |
| Sai | `wrongCount` |
| Bỏ trống | `unansweredCount` |
| Tỉ lệ chính xác | `{accuracyPercent}%` |
| Listening, số câu đúng (TOEIC) | `{listeningCorrect}/{tổng totalCount của Part 1–4}` |
| Reading, số câu đúng (TOEIC) | `{readingCorrect}/{tổng totalCount của Part 5–7}` |
| Thời gian làm | `durationSeconds` dạng `hh:mm:ss`; `submitReason = TIME_LIMIT` thì thêm "(tự nộp khi hết giờ)" |

- Ghi chú cố định: "Đây là số câu đúng của bài luyện, không quy đổi sang điểm TOEIC."
- Bảng theo Part (`PartBreakdownTable`), chỉ khi `partBreakdown.length > 0`. Cột: Part, Số câu, Đúng, Sai, Bỏ trống.
- **Cấm** các chuỗi "Điểm", "điểm TOEIC", "990", "score" trong UI kết quả (test 11.2).

**Xem lại:**
- Cùng `buildScreens`, cùng layout, ở chế độ chỉ đọc (`mode="review"`).
- Navigator ở chế độ review: 3 trạng thái `Đúng` / `Sai` / `Bỏ trống` từ `answer`.
- Mỗi câu:
  - lựa chọn của học viên (nhãn "Bạn chọn") và đáp án đúng (`review.correctOptionId`, nhãn "Đáp án đúng"). Không chỉ dùng màu: thêm icon ✓/✕ và chữ;
  - P1/P2: dưới mỗi nhãn A–D in `optionTranscripts[label]` dạng `({speaker}) {text}`. P2 in thêm `promptTranscript` ở đầu;
  - `explanation`: đoạn văn thuần (render như text, không parse).
- Nhóm: render `materials` rồi `reviewMaterials` (TRANSCRIPT P3/P4, tài liệu mật) dưới tiêu đề "Lời thoại".
- Audio vẫn phát được ở chế độ xem lại (URL ổn định, lượt `SUBMITTED` vẫn có quyền).

### M6. Bộ nhớ (Web 384 MiB, `--max-old-space-size=256`)

| Biện pháp | Chi tiết |
|---|---|
| Không SSR payload | `page.tsx` của hai route chỉ render shell + `<Suspense>`. Component con `"use client"` gọi API trong `useEffect` sau hydrate. Static export (Pages) không có server; standalone chỉ trả HTML tĩnh của shell. **Không** dùng `fetch` ở Server Component, không `generateStaticParams` cho id. |
| Không giữ 2 bản payload | `attemptHandoff`/`resultHandoff`: `Map` cấp module, `take(id)` lấy ra là xóa. Không đưa vào `sessionStorage`. |
| Mount ít | Chỉ mount màn hiện tại: tối đa 1 `<audio>` (thay vì 42), ảnh của màn đó, ≤ 5 câu. Navigator 200 `<button>` nhẹ, không mount nội dung. |
| Audio | `preload="metadata"`. Unmount thì `pause()`, `removeAttribute("src")`, `load()` để trình duyệt giải phóng buffer. |
| Ảnh | `loading="lazy"`, `decoding="async"`. Lightbox chỉ mount khi mở. |
| Dữ liệu | State giữ DTO gốc (bất biến) cộng `Map` slot của queue. Không clone sâu. `useMemo` cho `buildScreens` và `blankNumbers` theo `attempt.id`. |

---

## 4. Thiết kế chi tiết

### 4.1. Cây file `src/features/practice/` `[CREATE]`

```
index.ts                         export { PracticeSetCatalog, AttemptRoomPage, AttemptResultPage }
types/attempt.ts                 3.M1
types/practice-set.ts            PracticeSetSummary, PracticeSetDetail (T2 §5)
lib/practice-client.ts           6 hàm API (4.2)
lib/practice-client.test.ts
lib/attempt-guards.ts            parseActiveAttempt, parseAttemptResult, readProblemMeta
lib/attempt-guards.test.ts
lib/material-bodies.ts           asAudioBody, asImageBody, asRichTextBody, asChatBody, asTableBody, asTranscriptBody
lib/material-bodies.test.ts
lib/answer-queue.ts              createAnswerQueue (3.M2)
lib/answer-queue.test.ts
lib/screens.ts                   buildScreens, layoutOf, blankNumbers, navigatorModel
lib/screens.test.ts
lib/handoff.ts                   attemptHandoff, resultHandoff
lib/speaker.ts                   speakerName(label)
hooks/use-practice-sets.ts       danh mục + createLatestRequestTracker
hooks/use-start-attempt.ts
hooks/use-attempt-room.ts        tải GET, màn hiện tại, onClosed, onResync
hooks/use-attempt-autosave.ts
hooks/use-attempt-result.ts
components/catalog/practice-set-catalog.tsx
components/catalog/open-attempts-dialog.tsx
components/room/attempt-room-page.tsx      đọc ?id=, Suspense, trạng thái tải/lỗi
components/room/attempt-room.tsx
components/room/question-navigator.tsx
components/room/submit-dialog.tsx
components/room/option-list.tsx
components/room/question-card.tsx
components/layouts/{photo,response,listening-group,single-question,text-completion,reading}-layout.tsx
components/content/{material-view,rich-text,inline-nodes,chat-material,table-material,image-material,practice-audio-player,transcript-material,unsupported-content}.tsx
components/result/{attempt-result-page,attempt-result,result-summary,part-breakdown-table,review-answer}.tsx
styles/practice.css              tiền tố pr-*
```

Mỗi file ≤ 350 dòng (`max-lines`), mục tiêu < 300. File trong `lib/` dùng import tương đối có đuôi `.ts` để `node --test` chạy trực tiếp (§9.2).

### 4.2. `lib/practice-client.ts`

| Hàm | Request | Trả về |
|---|---|---|
| `fetchPracticeSets({ category, partNumber?, page })` | `GET /api/v1/practice-sets?category=TOEIC&page=0&size=20` | `HttpOutcome<PageResponse<PracticeSetSummary>>` |
| `startAttempt(practiceSetId, key)` | `POST /api/v1/attempts`, header `Idempotency-Key` | `HttpOutcome<unknown>` (guard ở hook) |
| `fetchAttempt(id)` | `GET /api/v1/attempts/{id}` | `HttpOutcome<unknown>` |
| `saveAnswer(id, snapshotId, { selectedOptionId, expectedVersion }, key)` | `PUT …/answers/{snapshotId}`, header `Idempotency-Key` | `HttpOutcome<SavedAnswerResponse>` |
| `submitAttempt(id)` | `POST …/submit` | `HttpOutcome<AttemptSubmitResponse>` |
| `fetchAttemptResult(id)` | `GET …/results` | `HttpOutcome<unknown>` |

- `id`/`snapshotId` phải là UUID: kiểm bằng regex trước khi ghép URL, sai thì trả lỗi `http` 400 giả lập và không gọi mạng.
- Ghép bằng `encodeURIComponent`.

### 4.3. `src/lib/http.ts` `[MODIFY]`

```ts
interface RequestJsonOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
  /** Extra request headers, e.g. Idempotency-Key. Cannot override CSRF or Content-Type. */
  headers?: Record<string, string>;
}
// trong vòng lặp:
const headers: Record<string, string> = { ...requestOptions.headers, ...extraHeaders };
// rồi mới gán CSRF + Content-Type (ghi đè nếu trùng tên)
```

Header riêng được gửi lại y nguyên ở lần thử lại sau khi refresh phiên (cùng vòng lặp). Đây chính là điều idempotency cần.

### 4.4. Route `[CREATE]`

`src/app/learn/practice/attempt/page.tsx`:

```tsx
import type { Metadata } from "next";
import { Suspense } from "react";
import { AttemptRoomPage } from "@/features/practice";

export const metadata: Metadata = { title: "Làm bài | MasterEnglish" };

export default function Page() {
  return (
    <Suspense fallback={<main className="learning-content page-loading" role="status">Đang mở bài làm…</main>}>
      <AttemptRoomPage />
    </Suspense>
  );
}
```

`src/app/learn/practice/result/page.tsx` tương tự, dùng `AttemptResultPage` và title "Kết quả bài làm | MasterEnglish".

`AttemptRoomPage` đọc `useSearchParams().get("id")`. Không phải UUID thì hiện thẻ lỗi "Liên kết bài làm không hợp lệ" + link `/learn/toeic`.

### 4.5. `src/app/learn/toeic/page.tsx` `[MODIFY]`

```tsx
import { ToeicHome } from "@/features/toeic";
import { PracticeSetCatalog } from "@/features/practice";

export default function ToeicPage() {
  return <ToeicHome catalog={<PracticeSetCatalog category="TOEIC" />} />;
}
```

`ToeicHome` `[MODIFY]` nhận `catalog?: ReactNode` và render nó giữa khối Part và câu mẫu. Nhờ slot này, `toeic` không import `practice`, nên ESLint vẫn xanh.

**`PracticeSetCatalog`:**
- Thẻ `.glass-card` cho mỗi bộ: `title`, nhãn Part (`partNumber = null` → "Nhiều Part"), `questionCount` câu, `difficulty` nếu có.
- Nút `Bắt đầu` (`btn-primary` có sẵn).
- Có các trạng thái tải (skeleton), rỗng ("Chưa có bộ đề nào được xuất bản." + Mascot `thinking`), lỗi (+ `Thử lại`), và phân trang `Pagination` có sẵn khi `totalPages > 1` (UI 1-indexed ↔ API 0-indexed).

### 4.6. `eslint.config.mjs` `[MODIFY]`

Thêm `"practice"` vào mảng `features` (dòng 11–18, xếp theo alphabet).

### 4.7. `styles/practice.css`

- Tiền tố `pr-*`.
- Chỉ dùng token `:root` có sẵn. Thêm **một** biến cục bộ `--pr-room-offset` (chiều cao topbar + thanh dưới) trong `.pr-room`.
- Không định nghĩa lại `.glass-card`, `.btn-*`, `<Dialog>`.
- `@media (prefers-reduced-motion: reduce)`: tắt mọi `transition` của `pr-*`.
- Mốc responsive: 1100 (navigator), 850 (chia đôi), 600 (tắt blur), 360 (không tràn ngang).

### 4.8. Chuỗi giao diện chính (tiếng Việt)

| Vị trí | Chuỗi |
|---|---|
| Nút bắt đầu | `Bắt đầu` / `Đang chuẩn bị đề…` |
| Thanh phòng | `Câu {n}/{total}` · `Đã trả lời {a}/{total}` · `Hạn nộp {HH:mm}` (chỉ khi có `deadlineAt`) |
| Xung đột đa tab | `Câu {n} vừa được đổi ở một tab khác. Đã cập nhật theo lựa chọn mới nhất.` (`role="status"`, tự ẩn sau 6 s) |
| Lưu lỗi | `Chưa lưu được {k} câu. Kiểm tra mạng rồi bấm Thử lưu lại.` |
| Bị đóng khi đang làm | `Bài đã được nộp (hết giờ hoặc từ một tab khác).` → nút `Xem kết quả` |
| Lượt bỏ dở | `Lượt này đã đóng do bỏ dở quá 7 ngày. Hãy bắt đầu một lượt mới.` |

### 4.9. Delta tài liệu

| File | Thay đổi |
|---|---|
| `masterenglish-web/docs/UI_STYLE_GUIDE.md` §8 mục 5 (TOEIC) | Mô tả danh mục, phòng làm bài, trang kết quả, luật "không điểm". §9.1: thêm `practice/styles/practice.css` → tiền tố `pr-*`. |
| `masterenglish-api/docs/m05/02-snapshot-json-schemas-v1.md` §8 | Ghi rõ `refs[]` và `review.evidence[]` ở DTO là `{materialKey, anchor}` camelCase (T6-D11). Backend T3 map theo. **Đã áp** (rà soát chéo T0–T7). |
| `masterenglish-api/docs/practice/CONTRACT.md` §3.1 | "Consumer: `masterenglish-web` feature `practice` (T6)". Bỏ "PENDING FE REVIEW" khi T6 merge. |

---

## 5. Hợp đồng giao tiếp

Frontend chỉ tiêu thụ. Không thêm endpoint. Mọi request đi qua `requestJson`:
- `credentials: "same-origin"`, `cache: "no-store"`;
- CSRF cho `POST`/`PUT`;
- tự refresh khi 401 một lần.

| Gọi | Header thêm | Thành công | Lỗi xử lý (code / metadata R8) |
|---|---|---|---|
| `GET /api/v1/practice-sets` | — | 200 `PageResponse` | 400 (bug, hiện lỗi chung) |
| `POST /api/v1/attempts` | `Idempotency-Key` | 201 / 200 `ActiveAttemptDto` | 400, 404, 409 `IDEMPOTENCY_CONFLICT`, 409 `OPEN_ATTEMPT_LIMIT_REACHED` (`openAttemptCount`, `maxOpenAttempts`, `activeAttemptIds`), 409 `NO_ELIGIBLE_QUESTIONS`/`INSUFFICIENT_QUESTIONS` (`requestedCount`, `availableCount`) |
| `GET /api/v1/attempts/{id}` | — | 200 `ActiveAttemptDto` (mọi trạng thái) | 404 |
| `PUT /api/v1/attempts/{id}/answers/{snapshotId}` | `Idempotency-Key` | 200 `SavedAnswerResponse` (cả replay) | 400 `VALIDATION_ERROR`/`OPTION_NOT_IN_SNAPSHOT`, 404, 409 `ATTEMPT_NOT_OPEN` (`attemptStatus`), 409 `IDEMPOTENCY_CONFLICT`, 409 `VERSION_CONFLICT` (`currentVersion`, `currentSelectedOptionId`) |
| `POST /api/v1/attempts/{id}/submit` | — | 200 `AttemptSubmitResponse` (cả khi đã nộp) | 404, 409 `ATTEMPT_NOT_OPEN` (`ABANDONED`) |
| `GET /api/v1/attempts/{id}/results` | — | 200 `AttemptResultDto` | 404, 409 `ATTEMPT_NOT_SUBMITTED` (`attemptStatus`) |
| `GET /api/v1/attempts/{id}/media/{mediaAssetId}` | (thẻ `<audio>`/`<img>`, cookie cùng origin) | 302 → R2 (TTL 5 phút); Range/206 do R2 | 404/503 → sự kiện `error` → nút `Tải lại` |

Ví dụ lời gọi lưu đáp án (đúng thứ tự header của `requestJson`):

```
PUT /api/v1/attempts/7f1c2a90-3b4d-4e5f-8a6b-0c1d2e3f4a50/answers/9a0b1c2d-6666-4a71-8c83-000000000042
Accept: application/json, application/problem+json
Idempotency-Key: 3f8a5c1e-2b7d-4c9a-8e10-6d2f4b1a9c77
X-XSRF-TOKEN: <cookie XSRF-TOKEN>
Content-Type: application/json

{"selectedOptionId":null,"expectedVersion":3}
```

Bỏ chọn gửi `null` tường minh (`JSON.stringify` giữ `null`), không bỏ khóa.

---

## 6. Chống rò rỉ, bảo mật và toàn vẹn đồng thời

| Rủi ro | Biện pháp |
|---|---|
| XSS từ nội dung đề | Chỉ JSX text; cấm `dangerouslySetInnerHTML` và parse `{{`; test tĩnh 11.1. Backend cũng chặn HTML (02 §7). |
| URL media độc | Chỉ nhận `mediaUrl` khớp regex đường dẫn API cùng origin (3.M3). Không dùng `body` để dựng URL. |
| Lộ đáp án trước khi nộp | Type `ActiveAttemptDto` không có trường private. Guard trả `null` nếu JSON có `review`/`reviewMaterials`. Phòng làm bài **không gọi** `/results`. Server cũng không trả (T4 O2). |
| IDOR | Backend kiểm chủ (404). Client không suy đoán quyền. Id lạ thì hiện "Không tìm thấy bài làm". |
| CSRF | Giữ cơ chế `fetchCsrfToken`. Không đọc cookie ngoài `session.ts`. |
| Ghi đúp do retry | Mỗi bước ghi giữ 1 `Idempotency-Key` cho tới khi có kết quả. Start giữ key theo lần bấm. |
| Ghi đè chéo tab | CAS `expectedVersion`; `VERSION_CONFLICT` → server thắng + thông báo (O3) |
| Đổi nhanh A→B→C | ≤ 1 request đang bay mỗi câu; gộp tới lựa chọn cuối |
| Mất đáp án khi đóng tab | `beforeunload` khi `pendingCount > 0`. Rào trước khi nộp chặn nộp nếu còn câu chưa lưu. |
| Thông tin trong log | Không `console.log` payload. Lỗi chỉ ghi `code`. |

---

## 7. Ranh giới module frontend và tài nguyên

### 7.1. Ranh giới (`eslint.config.mjs`)

- `src/app/learn/toeic/page.tsx` ghép `@/features/toeic` và `@/features/practice`, được phép vì là tầng `app`.
- `@/features/practice` chỉ import `@/components/*`, `@/lib/*`, chính nó và (nếu cần) `@/features/auth`.
- `@/features/toeic` không import `practice`, chỉ nhận slot `catalog`.
- `src/lib/http.ts` không biết gì về `practice`.

### 7.2. Lời gọi mạng

| Luồng | Lời gọi |
|---|---|
| Mở `/learn/toeic` | 1 `GET practice-sets` |
| Bắt đầu | 1 `GET auth/csrf` (nếu chưa có) + 1 `POST attempts`. Không gọi lại `GET` nhờ handoff. |
| Tải lại trang phòng | 1 `GET attempts/{id}` |
| Mỗi lần chọn | 1 `PUT` (hoặc 0 nếu bị gộp) |
| Nộp | flush (0–n `PUT`) + 1 `POST submit` |
| Trang kết quả | 1 `GET results` |

### 7.3. Bộ nhớ

Xem 3.M6. Ước lượng client với Full Test 200 câu:
- DTO khoảng 0,3–0,4 MB JSON, khoảng 2–3 MB object trong heap;
- 1 audio đang mount, ảnh của một màn.

Server standalone chỉ phục vụ shell tĩnh. Không có payload bài làm trong SSR, nên không ảnh hưởng mục tiêu 256 MB old-space.

---

## 8. Edge Cases & Failure Modes Matrix

| # | Tình huống | Hành vi mong đợi |
|---|---|---|
| 1 | Bấm "Bắt đầu" hai lần liên tiếp | Lần hai bị chặn (`disabled` khi có `pending`); chỉ một `POST` |
| 2 | Mất mạng sau khi server đã tạo lượt | Bấm `Thử lại` với cùng key → `200` replay → vào đúng lượt cũ |
| 3 | Đủ 5 lượt mở | Hộp thoại liệt kê; nộp một lượt cũ rồi `Bắt đầu đề mới` với cùng key → `201` |
| 4 | Đổi A→B→C trong 300 ms | PUT A, rồi PUT C (`expectedVersion` = version sau A). Server cuối là C. B không bao giờ được gửi. |
| 5 | PUT timeout, server đã ghi | Thử lại cùng key → `200` replay. Không có `409` giả. |
| 6 | Tab khác đổi câu 12 | `409 VERSION_CONFLICT` → giao diện nhảy sang `currentSelectedOptionId` + thông báo nhẹ |
| 7 | Bấm lại lựa chọn đang chọn | Gửi `selectedOptionId: null`; navigator về `Chưa trả lời` |
| 8 | Bấm "Nộp" khi 2 câu đang lưu | Đợi flush; thành công thì nộp |
| 9 | Bấm "Nộp" khi 1 câu lưu lỗi mạng | Chặn nộp; hiện `Thử lưu lại` |
| 10 | Hết `deadlineAt` (server tự nộp) | PUT tiếp theo nhận `409 ATTEMPT_NOT_OPEN (SUBMITTED)` → dừng queue → banner `Xem kết quả` |
| 11 | Nộp ở tab khác | Như #10 |
| 12 | Mở link phòng của lượt đã nộp | `GET` trả `status = SUBMITTED` → chuyển `/learn/practice/result?id=` |
| 13 | Lượt `ABANDONED` | Phòng chỉ đọc, không có nút đáp án, có thông báo và link danh mục |
| 14 | Audio 302 hết hạn khi tua sau 5 phút | Trình duyệt gọi lại URL ổn định → 302 mới. Nếu lỗi thì `Tải lại âm thanh` giữ vị trí. |
| 15 | Đổi sang nhóm khác khi audio đang phát | Unmount → `pause()`. Không có hai audio phát cùng lúc. |
| 16 | `materialType` mới chưa hỗ trợ | `UnsupportedContent`; câu vẫn trả lời được |
| 17 | Văn bản chứa `<script>alert(1)</script>` hoặc `{{blank:b1}}` | Hiện nguyên văn dạng chữ |
| 18 | `blank_key` không có câu tương ứng | In `[…]`, không click được |
| 19 | Phiên hết hạn giữa chừng | `requestJson` refresh một lần. Thất bại thì banner đăng nhập lại; lựa chọn giữ trong bộ nhớ, câu ở trạng thái `failed`. |
| 20 | `?id=` không phải UUID | Thẻ lỗi, không gọi API |
| 21 | Màn 360 px, bảng 6 cột | Bảng cuộn ngang trong vùng của nó; trang không tràn (`scrollWidth ≤ innerWidth`) |
| 22 | `prefers-reduced-motion` | Không có transition ở `pr-*` |

---

## 9. Kế hoạch chỉnh sửa file (repo `masterenglish-web`)

| File | Nhãn |
|---|---|
| `src/features/practice/**` (4.1) | CREATE |
| `src/app/learn/practice/attempt/page.tsx`, `src/app/learn/practice/result/page.tsx` | CREATE |
| `src/app/learn/toeic/page.tsx` | MODIFY (ghép catalog) |
| `src/features/toeic/components/toeic-home.tsx` | MODIFY (slot `catalog`; nhãn `rolling`; sửa câu giới thiệu cho đúng sự thật) |
| `src/lib/http.ts`, `src/lib/http.test.ts` | MODIFY (header riêng + test) |
| `eslint.config.mjs` | MODIFY (thêm `practice`) |
| `tests/practice-autosave.test.mjs`, `tests/structured-content.test.mjs`, `tests/practice-result.test.mjs` | CREATE |
| `e2e/practice.spec.ts`, `e2e/fixtures/practice/*.json` | CREATE |
| `docs/UI_STYLE_GUIDE.md` | MODIFY (4.9) |
| `src/lib/session.ts`, `functions/api/[[path]].ts`, `next.config.ts` | DO NOT TOUCH |
| `e2e/{app,auth-flows,landing,learning,transitions,vocabulary}.spec.ts`, `e2e/helpers.ts` | DO NOT TOUCH (đang có thay đổi chưa commit của người dùng) |
| `src/features/{vocabulary,learning,admin,auth,marketing}/**` | DO NOT TOUCH |
| `src/features/learning/data/navigation.ts` | DO NOT TOUCH (không thêm mục nav; phòng làm bài mở từ `/learn/toeic`) |

---

## 10. Lộ trình commit (repo `masterenglish-web`)

Mỗi bước chạy `npm run lint` + `npm test` + `npm exec -- tsc --noEmit`. Chỉ commit khi người dùng yêu cầu; không có dòng ghi công.

| Bước | Nội dung | Kiểm tra thêm | Commit đề xuất |
|---|---|---|---|
| 1 | `http.ts` header riêng | `node --test src/lib/http.test.ts` | `feat(http): cho phép gửi header riêng như Idempotency-Key` |
| 2 | Types, guards, `material-bodies`, client, `screens`, ESLint | `node --test src/features/practice/lib/*.test.ts` | `feat(practice): kiểu dữ liệu, guard và client API bài luyện` |
| 3 | `answer-queue` + `useAttemptAutosave` | `node --test src/features/practice/lib/answer-queue.test.ts tests/practice-autosave.test.mjs` | `feat(practice): hàng đợi lưu đáp án tuần tự theo từng câu` |
| 4 | Renderer + audio player | `node --test tests/structured-content.test.mjs` | `feat(practice): hiển thị nội dung có cấu trúc và trình phát âm thanh` |
| 5 | Phòng làm bài, 6 layout, navigator, route attempt | `npm run build:pages` | `feat(practice): phòng làm bài với bố cục Part 1–7` |
| 6 | Danh mục, start, hộp thoại 5 lượt, `/learn/toeic` | `npm run build:pages` | `feat(practice): bắt đầu bài luyện TOEIC từ danh mục` |
| 7 | Trang kết quả, route result | `node --test tests/practice-result.test.mjs` | `feat(practice): trang kết quả và xem lại lời giải` |
| 8 | E2E với API giả lập | `npm run test:e2e -- e2e/practice.spec.ts` | `test(practice): kiểm thử luồng luyện TOEIC đầu cuối` |
| 9 | `UI_STYLE_GUIDE` | `npm exec -- prettier --check docs/UI_STYLE_GUIDE.md` | `docs(web): mô tả khu luyện đề trong hướng dẫn giao diện` |
| 10 | Cổng cuối | `npm run lint`, `npm test`, `npm run build:pages`, `BACKEND_URL=http://127.0.0.1:9 npm run build` | — |

---

## 11. Test Matrix

### 11.1. Unit (`node --test`, file `.test.ts` cạnh lib)

| Test | Assert |
|---|---|
| `http.test.ts › sends extra headers and keeps them after session refresh` | Lần 1 nhận 401, refresh, lần 2 vẫn có `Idempotency-Key` cùng giá trị. CSRF không bị ghi đè bởi header riêng. |
| `attempt-guards › parses T3 active example` | Fixture đúng JSON T3 §5.1 → object; `savedAnswers` đủ |
| `attempt-guards › rejects active payload carrying review` | Có `review` hoặc `reviewMaterials` → `null` |
| `attempt-guards › parses T4 results example` | Fixture T4 §5.1 → object |
| `attempt-guards › readProblemMeta` | `currentSelectedOptionId: null` → `null` (không phải `undefined`); `activeAttemptIds` không phải mảng → `undefined` |
| `material-bodies › narrows each body; rejects wrong shapes` | 6 loại đúng và sai |
| `screens › groups P3/4/6/7, singles P1/2/5, orders by ordinal` | Seed S01 (24 câu, 5 nhóm) → số màn = 24 − (câu có nhóm) + 5 |
| `screens › layoutOf` | Bảng 3.M4, kể cả VOCABULARY/GRAMMAR và Part 9 (fallback) |
| `screens › blankNumbers` | `b1..b4` → `displayNumber` của đúng 4 câu P6 cùng nhóm trong fixture; câu nhóm khác không lọt vào |
| `answer-queue › coalesces A→B→C into two PUTs` | `save` gọi với A rồi C; `expectedVersion` 0 rồi 1 |
| `answer-queue › retries with the same key` | 2 lỗi mạng rồi 200: cả 3 lần cùng key, cùng `expectedVersion` |
| `answer-queue › becomes failed after 3 retries; retryFailed reuses key` | — |
| `answer-queue › version conflict: server wins` | `desired = currentSelectedOptionId` (cả `null`); có `notice` |
| `answer-queue › deselect sends null` | — |
| `answer-queue › attempt not open stops all slots` | `onClosed("SUBMITTED")`; `select` sau đó không gọi `save` |
| `answer-queue › flush` | `"saved"` khi hết việc; `"failed"` khi còn câu lỗi; `"failed"` khi quá hạn chờ |
| `practice-client › rejects non-UUID ids without network` | `fetch` không được gọi |
| `noUnsafeRendering` (`src/features/practice/lib/static-safety.test.ts`) | Đọc mọi file `src/features/practice/**/*.tsx?`: không có `dangerouslySetInnerHTML`, `innerHTML`, `"{{"`, `eval(`, `new Function` |

### 11.2. Component và hook (`tests/*.test.mjs`, `react-test-renderer`, `loadHook`)

| Test | Assert |
|---|---|
| `structured-content › renders text as text` | Text `<script>alert(1)</script>` và `{{blank:b1}}` xuất hiện nguyên văn trong cây; không có element `script` |
| `structured-content › blank shows display number and calls back` | `blankNumbers = {b2 → 14}`, node `blank b2` → nút "14"; bấm thì gọi `onBlankClick("b2")` |
| `structured-content › insert marker` | `— [3] —` |
| `structured-content › unknown material and unknown inline` | `UnsupportedContent`; inline lạ → `[…]` |
| `structured-content › chat and table` | `data-anchor`, `<time>`; `<thead>`/`<tbody>` đúng số ô |
| `structured-content › media url allowlist` | `mediaUrl = "https://evil/x.mp3"` → không có `src` |
| `practice-autosave › hook exposes desired selection immediately` | `select` → `selectedOf` đổi ngay, trước khi `save` trả |
| `practice-result › never shows score words` | Render với fixture T4: không có chuỗi `Điểm`, `990`, `score`; có `Số câu đúng`, `Tỉ lệ chính xác` |
| `practice-result › TOEIC listening/reading totals` | Tổng Part 1–4 và 5–7 từ `partBreakdown` |
| `practice-result › P1 option transcripts` | Hiện `(M-Au) They're carrying a table.` dưới nhãn C |

### 11.3. E2E (Playwright, `page.route` giả lập; fixture dựng từ ví dụ T3/T4 và seed T1)

| Test | Kịch bản |
|---|---|
| `practice › start, answer, submit, see result` | `/learn/toeic` → Bắt đầu → phòng làm bài. Chọn đáp án P1, P3 (3 câu), P6 (bấm ô trống thì focus câu), P7 (tab tài liệu). Nộp → trang kết quả. Assert thứ tự PUT, header `Idempotency-Key` có mặt và khác nhau giữa các bước ghi. |
| `practice › limit dialog` | `POST` trả 409 `OPEN_ATTEMPT_LIMIT_REACHED` → hộp thoại 5 dòng → `Nộp bài này` → `Bắt đầu đề mới` gửi **cùng** `Idempotency-Key` |
| `practice › network retry keeps key` | PUT lần 1 `route.abort()`, lần 2 200 → hai request cùng key |
| `practice › conflict from another tab` | PUT trả 409 `VERSION_CONFLICT` với `currentSelectedOptionId = null` → nút bỏ chọn, hiện thông báo |
| `practice › submit barrier blocks on failed save` | PUT luôn abort → `Xác nhận nộp` hiện `Thử lưu lại`, không có request `submit` |
| `practice › closed by deadline` | PUT trả 409 `ATTEMPT_NOT_OPEN` `SUBMITTED` → banner `Xem kết quả` |
| `practice › only one audio element mounted` | `page.locator("audio")` count = 1 ở màn P3; đổi màn vẫn 1 |
| `practice › 360px no horizontal overflow` | Viewport 360 × 740, màn P7 và trang kết quả: `scrollWidth <= innerWidth` |
| `practice › keyboard` | Tab tới lựa chọn, `Enter` chọn, `Enter` lần nữa bỏ chọn; focus ring hiển thị |

### 11.4. Kiểm tra tay (ghi kết quả khi bàn giao)

- Với backend thật (T3/T4 đã deploy) và seed T1:
  - Range/206 khi tua audio qua R2 (T3 kiểm phía server);
  - 302 đi qua rewrite standalone và Pages Function;
  - S02 (VOCABULARY, câu `LISTEN_AND_CHOOSE` có audio) và S03 (GRAMMAR) mở được bằng cách gọi `POST /attempts` trực tiếp, rồi vào URL phòng.
- Đo heap Chrome (Memory tab) khi làm Full Test: không tăng tuyến tính khi chuyển 42 màn nghe.

---

## 12. Definition of Done và Open Decisions

### 12.1. DoD

| # | Tiêu chí | ☐ |
|---|---|---|
| 1 | Type `ActiveAttemptDto` và `AttemptResultDto` tách biệt, khớp JSON T3/T4; guard từ chối payload active có trường private | ☐ |
| 2 | Status chỉ `IN_PROGRESS \| SUBMITTED \| ABANDONED` | ☐ |
| 3 | 0 `dangerouslySetInnerHTML`, 0 parse `{{` (test tĩnh xanh) | ☐ |
| 4 | Hàng đợi: ≤ 1 request bay mỗi câu, gộp lựa chọn, cùng key khi thử lại, server thắng khi xung đột, bỏ chọn bằng `null` | ☐ |
| 5 | Rào nộp: không gọi `submit` khi còn câu chưa lưu | ☐ |
| 6 | Start: key theo lần bấm, replay 200, hộp thoại 5 lượt không có nút hủy | ☐ |
| 7 | 6 layout đúng 3.M4; navigator đúng 3 trạng thái; không có flag, highlight, gạch, tốc độ audio | ☐ |
| 8 | Trang kết quả: chỉ "số câu đúng", có Listening/Reading và bảng Part; transcript theo người nói; không có "điểm"/990 | ☐ |
| 9 | Một `<audio>` mỗi màn; payload chỉ tải sau hydrate | ☐ |
| 10 | `npm run lint`, `npm test`, `tsc --noEmit`, `npm run build:pages`, `npm run build` (có `BACKEND_URL`) xanh. Ghi rõ nếu không chạy được. | ☐ |
| 11 | E2E 11.3 xanh với API giả lập | ☐ |
| 12 | Màn 360/600/850/1100 px không tràn; focus ring; vùng chạm ≥ 44 px | ☐ |

### 12.2. Câu hỏi cần duyệt

| # | Câu hỏi | Đề xuất |
|---|---|---|
| O1 | Đặt phòng làm bài ở feature mới `practice` (dùng chung TOEIC/VOCABULARY/GRAMMAR), hay trong `toeic`? | **Feature mới `practice`.** ESLint cấm import chéo feature, và phòng làm bài phục vụ 3 category. |
| O2 | Điểm vào cho bài VOCABULARY (M03.12–15) và GRAMMAR (M04.06/10/11): làm trong T6, hay để task frontend của M03/M04? | **Để task M03/M04.** UI ngữ pháp hiện chỉ là bản minh họa, chưa gọi API. T6 bảo đảm phòng làm bài và trang kết quả chạy đúng với 2 category này (unit test và kiểm tra tay qua `POST /attempts`). |
| O3 | Khi `VERSION_CONFLICT` (đa tab): server thắng và báo nhẹ, hay client ghi đè lại? | **Server thắng.** Khớp đề; tránh hai tab ghi đè qua lại. |
| O4 | Hộp thoại 5 lượt chỉ hiện "Lượt đang mở 1…5" (không tiêu đề), vì chưa có API danh sách lượt? | **Đồng ý.** Gọi 5 lần `GET /attempts/{id}` có thể tốn tới 2 MB. Thêm tiêu đề khi có M06. |
| O5 | Không làm dark mode ở T6 (repo chưa có), chỉ dùng token? | **Đồng ý.** Thêm theme tối là việc riêng cho toàn site. |
