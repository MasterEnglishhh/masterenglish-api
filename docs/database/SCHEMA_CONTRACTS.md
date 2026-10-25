# Hợp đồng dữ liệu và giao dịch

Ngày 25/10/2026. Đồng hành với [baseline đã chốt](APPROVED_BASELINE.md).
Các quy tắc dưới đây cụ thể hóa D01–D29/B01–B37, không thêm tính năng ngoài phạm vi.

## 1. Phân chia trách nhiệm

SQL tham chiếu có FK/unique/CHECK, một số deferred constraint/trigger và hàm giao
dịch để kiểm tra thiết kế. Chúng **chưa được tích hợp vào ứng dụng**. Backend có
thể gọi hàm hoặc thực hiện giao thức tương đương, nhưng phải giữ cùng thứ tự khóa,
idempotency và kết quả. Không áp dụng trigger một nửa rồi tiếp tục ghi bằng code
cũ chưa hiểu workflow.

| SQL bảo vệ | Backend/worker vẫn phải làm |
|---|---|
| Kiểu, FK, độ dài cột, nhiều unique/CHECK | Xác thực, phân quyền từng request, ownership, hạn chế dữ liệu DTO |
| OTP consume một lần, refresh rotation, phiên, admin cuối | Sinh mã/token an toàn, HMAC, BCrypt, throttle toàn luồng, chống lộ email |
| Revision Published bất biến, pointer, dependency/source/media FK | Validate schema đầy đủ, nội dung, tập phụ thuộc/refs đầy đủ, công bố projection atomic |
| Câu/option, group cardinality và seal đầy đủ | Part 6 placeholder xuất hiện đúng một lần, template học liệu, tránh lộ option chữ TOEIC |
| CAS đáp án, snapshot, chấm/submit lặp, lỗi tổng hợp | Lựa chọn đề đủ điều kiện, quyền truy cập tài liệu, scheduler hết giờ |
| Khóa pool và accounting từ ledger | Đếm đối tượng thực, kiểm tra bytes/MIME, giữ chỗ mọi bản sao, delete receipt |
| Dedupe/lease columns, counter bounds | Thực hiện claim/retry, SMTP, cipher, DryRun/import atomic từng dòng |
| Một study session đang mở, giây không vượt thời gian đã qua | Heartbeat hợp lệ, max 30 giây/nhịp, không cộng lại, chia ngày/timezone |

SQL không parse nội dung Markdown thành công thức nghiệp vụ và không thay thế
kiểm tra JSON Schema/DTO. Tài khoản DB quản trị có thể thay đổi schema; các guard
không phải cơ chế phân quyền theo user ứng dụng. API không cấp kết nối DB cho người học.

## 2. Quy ước đầu vào và thời gian

- Tất cả ID từ client phải qua truy vấn có user/quyền phù hợp; client không gửi
  score, is_correct, auth_version, published status, role, object_key hoặc credited seconds.
- Phân trang mặc định 20, tối đa 100, sort/filter allowlist và thứ tự ổn định có ID.
  Độ dài dựa trên ký tự cho tên/text; giới hạn BCrypt dựa trên **byte UTF-8**.
- Tên user 30/trường, email 254, tên nội dung 200, slug 120, lemma/IPA 150,
  prompt 10.000, explanation 20.000, material text 50.000, Grammar body 100.000.
  JSON thường tối đa 1 MiB; giới hạn body trước parse. Import dùng hạn mức riêng.
- JSON chỉ nhận field đã biết, tối đa 16 mức lồng; danh sách mỗi trường có trần
  tương ứng (option 2–6, câu tối đa 100, material tối đa 100, nghĩa/ví dụ tối đa
  100 mỗi cấp). Giới hạn tổng byte vẫn áp dụng dù chưa chạm số phần tử.
- Không nhận HTML tùy ý. Markdown/khối text theo allowlist và sanitize khi render;
  URL nguồn giới hạn HTTPS/HTTP hợp lệ, target thông báo chỉ đường dẫn nội bộ.
- Dùng server clock cho hạn và ghi nhận. UTC cho timestamp; IANA timezone phải
  được backend kiểm tra (VARCHAR không chứng minh tên timezone hợp lệ).
- Lưu local_day và timezone tại sự kiện. Đổi timezone sau này không viết lại lịch
  sử; chia study session tại ranh ngày địa phương và khi đổi ngữ cảnh/timezone.
- updated_at/version chỉ được cập nhật trong các giao thức tương ứng. Không phải
  mọi bảng trong SQL đều tự tăng version bằng trigger; ORM/service phải làm rõ.

## 3. Identity và OTP

Email trim; dùng `me_email_key(email)` làm khóa so sánh ở PostgreSQL và duy nhất
`lower(email)`. Không xóa dấu chấm/+tag. Không trộn quy tắc lowercase ở JVM với
collation DB cho những ký tự có thể khác nhau. Email cố định sau tạo.

Đăng ký tạo `PENDING_VERIFICATION` và `registration_requests` giữ credential/profile
đang chờ. Bản ghi pending không có quyền học. Xác minh OTP và áp dụng credential,
email_verified_at/status phải cùng transaction; kết thúc chuyển sang đăng nhập,
không tự tạo phiên. Giữ nguyên created_at của chu kỳ đăng ký dở để hết hạn sau 7 ngày.

Mật khẩu tối thiểu 15 ký tự, tối đa 72 byte UTF-8, xác nhận chỉ ở biểu mẫu. BCrypt
cost khởi điểm 12, đo trên hạ tầng thực tế, không dưới 10. Không truncate mật khẩu;
regex hash trong DB chỉ kiểm tra hình dạng, không chứng minh đã băm đúng thuật toán.

OTP 6 chữ số sinh ngẫu nhiên bằng CSPRNG; lưu HMAC với secret ngoài DB và purpose/
challenge binding. Mỗi mã 5 phút, resend tối thiểu 60 giây; tối đa 5 lần gửi/email/
giờ cộng mọi purpose. Sai 5 lần theo email thì tạm dừng 15 phút, độc lập challenge
và không reset khi resend. IP khởi điểm: gửi 300/giờ; thử OTP và đăng nhập 300/15 phút.
IP chỉ từ proxy tin cậy. Bucket/event dùng HMAC định danh, tính cả email chưa tồn tại;
không tự khóa tài khoản vì một IP vượt ngưỡng.

Giao thức: khóa bucket theo thứ tự rule/key cố định → kiểm tra/cộng rate event →
khóa user/request/challenge → kiểm tra expiry/purpose/payload → consume OTP → áp
dụng đúng request → vô hiệu yêu cầu cạnh tranh → ghi sự kiện. Lỗi OTP phải được
commit bộ đếm dù endpoint trả lỗi; không throw exception rollback luôn lần thử sai.
`me_consume_otp` minh họa bước consume atomic; không tự thực hiện toàn bộ throttle.

Đổi mật khẩu: đã kiểm tra mật khẩu hiện tại, request gắn session và auth_version,
OTP đúng purpose/request, hiệu lực 5 phút. Reset: OTP đúng → grant ngẫu nhiên 5 phút,
lưu hash, dùng một lần để đặt mật khẩu; không phải access token. Chỉ ACTIVE đã xác
minh được hoàn tất, OTP không mở LOCKED/DISABLED. Security change tăng auth_version,
thu hồi mọi phiên/refresh và yêu cầu bảo mật còn sống, gửi email báo thành công.

Mỗi phiên hạn tuyệt đối tối đa 7 ngày, access tối đa 15 phút và không vượt phiên.
Tối đa 5 phiên, phiên mới thu hồi phiên được tạo lâu nhất. Refresh opaque token có
entropy cao, lưu SHA-256 và rotate; khóa user → session → token. Hai refresh đồng
thời cùng token: một thắng, request thua tải lại/đăng nhập theo chính sách; không
tự thu hồi cả family chỉ vì race bình thường. Trường hợp phát hiện token bị đánh
cắp cần quy trình bảo mật riêng, không giả lập bằng race detector.

Metadata user_agent/IP tùy chọn, để audit/hỗ trợ bảo mật; không có device_label,
không dùng metadata làm bằng chứng thiết bị hay phút học. Mỗi user có ít nhất một
role; thay role/status không được làm mất SYSTEM_ADMIN đang ACTIVE cuối cùng.

## 4. Snapshot và công bố nội dung

Envelope revision version 1:

```json
{
  "schema_version": 1,
  "content_type": "QUESTION",
  "data": {
    "category": "GRAMMAR",
    "part_number": null,
    "question_type_code": "SINGLE_CHOICE",
    "practice_mode": "STANDARD",
    "prompt": "She ___ English every day.",
    "group": null,
    "options": [
      {"id": "uuid-A", "position": 1, "body": "studies"},
      {"id": "uuid-B", "position": 2, "body": "study"}
    ],
    "answer_key": {"correctOptionId": "uuid-A"},
    "explanation": "Chủ ngữ số ít.",
    "materials": [],
    "topic_ids": ["uuid-topic"],
    "word_sense_ids": []
  }
}
```

Các chuỗi `uuid-*` là ký hiệu minh họa, phải là UUID thật ở dữ liệu. Draft được
thiếu field nghiệp vụ; publish yêu cầu schema đầy đủ. schema_version ngoài cột và
trong envelope phải khớp; content_type phải khớp item. Không lưu public DTO bằng
cách trả nguyên envelope này vì có key/lời giải.

| content_type | data bắt buộc khi công bố |
|---|---|
| WORD | lemma, IPA tùy chọn, audio tùy chọn; senses có ID, sense_no, part_of_speech, cefr_level, meaning_vi bắt buộc/definition_en tùy chọn, examples có ID/thứ tự/câu EN và VI tùy chọn |
| DECK | title/description/level; members có word_id, word_sense_id, pinned_word_revision_id, position, không trùng nghĩa |
| GRAMMAR_TOPIC | name/slug/description, thứ tự nếu cần |
| GRAMMAR_LESSON | topic_id, pinned_topic_revision_id, title/slug/position, body.blocks có schema và material refs |
| QUESTION_GROUP | category, part_number, group_type, title; materials có ID/thứ tự/loại/usage_role/document_key; children ghim question_id/revision/position/blank_key |
| QUESTION | category/Part/type/practice_mode/difficulty; prompt/option/key/explanation; context, material và liên kết kiến thức |
| PRACTICE_SET | category/name/slug, FIXED hoặc DYNAMIC, thời lượng tùy chọn; danh sách câu XOR nhóm hoặc bộ lọc có version |

`data` ở trên là hợp đồng nghiệp vụ cho bộ validator/mapper tiếp theo, không phải
tuyên bố DB đã có đầy đủ JSON Schema validator. Tên cột projection chính xác xem
dictionary; mapper dùng một phiên bản cố định, không tự đoán mapping từ JSON.

Workflow DRAFT → IN_REVIEW → APPROVED → PUBLISHED; REJECTED cần lý do rồi về
DRAFT để sửa. Sửa nội dung APPROVED phải về DRAFT và duyệt lại. CONTENT_ADMIN
được tự duyệt trong bản đầu, vẫn giữ riêng hành động review và publish/audit.

Transaction công bố:

1. Kiểm tra role, khóa item và revision đang soạn, so version để chống ghi đè.
2. Validate toàn bộ typed payload, source ACTIVE có khai báo quyền và source_snapshot.
   Chốt đầy đủ dependency đến Published child; không có chu trình.
3. Khóa media theo UUID; xác nhận đủ điều kiện và khớp IMAGE/AUDIO. So sánh tập ID
   media trích từ JSON với `revision_media_refs` chính xác, kể cả transcript/lời giải.
4. Ghi projection và các bảng con cùng snapshot/dependency/refs; retire membership
   cũ bằng cờ hoặc archive thích hợp. Không chuyển câu sang ngữ cảnh khác bằng sửa ID.
5. Đánh dấu Published, đổi published_revision_id, ghi audit/event cùng transaction.
   Deferred constraints được kiểm tra trước commit. Bất kỳ lỗi nào rollback toàn bộ.

Đọc thư viện hiện hành từ projection có pointer khớp; render bản đã ghim từ snapshot,
không join option/material đang thay đổi. Muốn cha nhận revision con mới phải công
bố revision cha mới. Group có thể dùng child revision cũ còn Published; không tự
lấy lại các câu mới nhất làm đổi group đã ghim.

Archive item ngừng cấp bài mới nhưng giữ lịch sử. Thu hồi quyền truy cập dùng
access_revoked_at/reason riêng, có audit và chặn cấp tài liệu kể cả lịch sử khi
cần; kết quả điểm vẫn giữ. Media ARCHIVED không cho authoring gắn mới, nhưng
attempt có thể giữ media từ đúng đồ thị revision Published đã ghim. File đó phải
chưa được claim xóa. Archive media không tự archive nội dung đang dùng file.

## 5. Câu hỏi, nhóm và chọn bài

Chỉ SINGLE_CHOICE có renderer/grader ban đầu. Đúng một key; Vocab/Grammar 2–6
option, TOEIC Part 2 có 3, các Part khác 4. Type catalogue enabled không thay thế
kiểm tra capability của backend/frontend. Free text, multi choice, video/chat cần
thêm schema/grader/renderer ở đợt mở rộng.

Một question thuộc tối đa một group. Part 3 CONVERSATION/Part 4 TALK mỗi nhóm 3
câu và common AUDIO; Part 6 TEXT_COMPLETION 4 câu và common TEXT; Part 7 PASSAGE
1–3 document_key khác nhau. Một tài liệu có nhiều material block. Part 6 parser
dùng placeholder `{{blank:<key>}}`: mỗi blank_key của câu xuất hiện đúng một lần,
không có placeholder mồ côi; so sánh cả tập key và tần suất trước publish.

Material có id/position/type/usage_role. TEXT có body, không media_id; IMAGE/AUDIO
có media_id, không body. Stimulus công khai; transcript và explanation riêng.
Part 1/2 lựa chọn được nhận dạng bằng ID/vị trí A/B/C(/D), public_body NULL;
nội dung lựa chọn nằm trong audio được cấp, không trả bản chép chữ trước nộp.

FIXED set_items trỏ câu độc lập XOR nhóm. DYNAMIC chọn một category với filter
allowlist theo deck/mode, topic hoặc Part/difficulty. Quá trình chọn chỉ dùng
Published revision có toàn bộ dependency chưa archive/thu hồi. Chọn nguyên nhóm,
giữ thứ tự câu/option; 1–100 câu con. Thiếu số lượng hoặc không ghép đủ nhóm thì
báo số hợp lệ cho chọn lại, không tự cắt hoặc lấy ngoài filter.

## 6. Start/resume, đáp án và submit

Start nhận request_id và fingerprint canonical payload. Khóa user trước, tìm
`(user_id,request_id)` đã có: cùng hash trả attempt cũ, khác hash trả conflict.
Sau đó xử lý bài quá hạn/abandon và giới hạn 5 bài mở; khóa nguồn theo thứ tự ID,
chọn đề, chụp source/config/revision và mọi dependency. Không để khoảng trống giữa
kiểm tra archive và chụp snapshot nếu thao tác quản trị đang cạnh tranh.

Trong cùng transaction tạo attempts → group snapshots → question snapshots →
snapshot options → answer slots rỗng → source topic/mistake/media refs → seal.
Snapshot count, ordinal liên tiếp và whole group phải khớp. Không commit attempt
chưa seal, không gửi đề cho client trước khi transaction thành công. Resume chỉ
đọc snapshot; không bốc lại đề. Same Idempotency-Key phải quay lại cùng bài kể cả
hiện tại nguồn đã đổi hoặc bị archive (quyền đọc tài liệu vẫn kiểm tra riêng).

Snapshot câu tách `question_snapshot` (public), `answer_key_snapshot` và
`explanation_snapshot` (private). Group tương tự public_snapshot/private_snapshot.
Response trước nộp chỉ gồm ID/type/version, prompt, public stimulus/options,
selected_option_id/version, deadline và trạng thái; không có key, correctness,
giải thích, transcript hoặc đường dẫn có thể bypass kiểm tra quyền. Quyền cấp
media dựa usage_role, chủ bài và trạng thái, không chỉ việc biết media_id.

Lưu đáp án yêu cầu attempt_id, snapshot_id, selected_option_id hoặc NULL và
expected_version. Khóa user → attempt → answer; kiểm tra chủ/trạng thái/deadline.
UPDATE đúng version mới thành công và tăng version, gồm cả bỏ chọn. Stale version
trả conflict kèm trạng thái hiện tại; không tự ghi đè. Option có FK kép đến chính
snapshot, không nhận đáp án của câu khác. SQL reference có `me_save_answer`.

Submit dùng cùng thứ tự khóa với autosave; đã SUBMITTED trả cùng kết quả. Chấm
key đã ghim, đúng 1/sai hoặc bỏ trống 0, ghi result và trạng thái atomic; sau đó
không nhận đáp án mới. Khi deadline đã qua, save/resume/submit và scheduler đều
đưa về giao thức tự nộp: effective_finished_at = deadline, submitted_at = giờ
server thực xử lý, không nhận đáp án đến muộn. SQL reference có `me_submit_attempt`.

Untimed không hoạt động 7 ngày → ABANDONED, không có điểm giả; timed luôn ưu tiên
auto-submit. Scheduler claim bài đến hạn theo deadline index và batch nhỏ, sau
đó khóa user/attempt theo giao thức; không khóa attempt trước user gây đảo thứ tự.

Lỗi được tính từ lịch sử đã chấm, theo `(effective_finished_at, attempt_id)` để
xử lý cả bài tự nộp muộn. Sai/bỏ trống → OPEN; một bài đúng tiếp theo → IMPROVING;
hai bài đúng liên tiếp → RESOLVED. Sai tiếp theo reset. Bài chỉ submit một lần,
không cộng lỗi khi retry. SQL reference dựng lại projection lỗi từ dữ liệu gốc.
Quy tắc điểm và snapshot lịch sử không tự sửa khi ngân hàng đổi đáp án.

Luyện MISTAKES kiểm tra lỗi thuộc chủ user, lấy nguồn hiện hành còn hợp lệ, mở
rộng toàn nhóm và báo số câu thực tế; không gán điểm chỉ cho câu sai được chọn.
MIXED_GRAMMAR có ít nhất 2 topic được ghim bằng bảng liên kết có FK.

## 7. SRS, Grammar và hoạt động

Đơn vị SRS là `(user_id,word_sense_id)`. Bỏ saved word không xóa progress/review;
đổi hẳn nghĩa phải tạo sense ID mới, không mang tiến độ cũ sang. Review có
request_id/hash, word/sense/revision, trạng thái trước/sau và policy_version.

Chính sách **SM2_3BTN_V1** được cụ thể hóa như sau:

- Khởi tạo repetitions=0, interval_days=0, ease_factor=2.5; EF sàn 1.3.
- AGAIN/HARD/GOOD ánh xạ q=1/3/5. EF mới = max(1.3, EF cũ +
  `0.1 - (5-q)*(0.08 + (5-q)*0.02)`), làm tròn 3 chữ số thập phân.
- AGAIN: repetitions=0, interval_days=0, due_at=reviewed_at+10 phút.
- HARD/GOOD: repetitions tăng 1; lần đạt đầu interval=1 ngày, lần hai=6 ngày,
  lần sau `max(1, floor(interval_cũ * EF_cũ + 0.5))` ngày. Tính khoảng bằng EF cũ
  rồi mới ghi EF mới. due_at tăng số ngày × 24 giờ từ server reviewed_at.
- Chỉ nhận review sau hành động lật thẻ và rating rõ. Đây là điều chỉnh ba nút
  của dự án; không tuyên bố nguyên bản SM-2 có chính sách AGAIN 10 phút.

Transaction review: khóa user/progress (tạo nếu chưa có với unique + khóa user),
tìm request đã có trước khi tính; cùng hash trả kết quả cũ, khác hash conflict;
validate revision/sense được cấp, tính policy → ghi event review + progress atomic.
Không tính lại due_at khi request mạng được thử lại. Thuật toán và boundary
tests cần triển khai ở service tiếp theo; SQL hiện chỉ giữ dữ liệu/ràng buộc.

Lesson chưa có progress = NOT_STARTED; bắt đầu tạo IN_PROGRESS; người học bấm
hoàn thành mới COMPLETED và ghim last_completed_revision_id. Có bản mới không
xóa thành tích; UI có thể báo có cập nhật. Không suy ra hoàn thành từ scroll.

Heartbeat khoảng 15 giây; mỗi user chỉ một dòng đang mở/credit token. Khóa user
và session, yêu cầu đúng token/version. Nếu màn hình học active hoặc audio trong
màn hình học đang phát: cộng `min(max(server_now-last_credited_at,0),30 giây)`,
cập nhật mốc sang server_now; nếu không active chỉ chuyển mốc, không cộng khoảng
ngắt mạng. Request cũ không cộng lại. Client không được chọn mốc/quãng giây.

Phút học = tổng active_seconds/60, tách duration_seconds của attempt. Streak là
ngày có hoạt động học hợp lệ. Không coi đọc trang là học nếu không có nhịp hợp lệ;
review/complete/submit thành công cũng là sự kiện học có thể xác nhận hoạt động,
nhưng không tự suy diễn số phút. Mục tiêu hiện tại không viết lại đánh giá ngày cũ.
Điểm yếu chỉ báo khi >=20 câu đã chấm trong 30 ngày và accuracy<70%, kèm cỡ mẫu/kỳ.
Không phân bổ một câu nhiều topic thành nhiều câu khi tính tổng toàn hệ thống.

## 8. Upload, dung lượng và dọn file

| Loại | Format được cấp | Trần thực tế |
|---|---|---:|
| Avatar | JPEG/PNG/WebP | 2 MiB |
| Ảnh nội dung | JPEG/PNG/WebP | 5 MiB |
| Audio | MP3/M4A | 20 MiB |

Đọc signature/MIME bằng parser phù hợp, xác minh decode/dimension/duration và hash,
không tin extension/Content-Type. Không giải nén hoặc tải vào RAM không giới hạn;
stream có hard byte cap. CSV/JSON import dùng đường riêng, không giả làm ảnh/audio.

Upload URL tối đa 10 phút tới key **tạm** riêng tư; không cấp quyền ghi đè key cuối.
Complete được nhận trước expiry; verification có deadline 5 phút từ lần nhận đầu,
lease 60 giây có claim token, tối đa 2 worker nặng toàn hệ thống. Retry không kéo
dài deadline gốc. Không giữ transaction mở khi đọc file/copy/gọi kho lưu trữ.

Đóng băng chính xác bytes đã kiểm: dùng version ID/generation nếu nhà cung cấp hỗ
trợ, hoặc stream bản đã xác minh sang key cuối mới do server sở hữu. Không kiểm
object tạm rồi copy bản mới bị client ghi đè; ETag không được xem mặc nhiên là SHA-256.
Trước commit READY kiểm tra lại claim, deadline và identity/hash/size của bản cuối.

Quota: avatar 50 MiB/user, content 10 GiB chung, TEMP 1 GiB chung. Mỗi lần cấp
phải giữ chỗ cho lượng byte tối đa thực có thể ghi theo cơ chế upload được chọn;
nếu cấp URL không cưỡng chế được trần thì dùng backend proxy hoặc chính sách upload
có trần. Kiểm tra sau upload không thay thế giới hạn trước khi tiêu tốn tài nguyên.
Avatar 10 request/giờ/user, tối đa 2 mở; content 100/admin/giờ, tối đa 3 mở.

`storage_allocations`: mỗi byte đang giữ/chờ dọn có allocation_key ổn định;
RESERVED → COMMITTED → RELEASED, hoặc RESERVED → RELEASED khi chứng minh không còn
object/khả năng ghi. Khóa pool UUID tăng dần khi động tới nhiều pool để tránh
deadlock. Pool trigger duy trì reserved_bytes/used_bytes; API không sửa hai counter
trực tiếp. Hạ quota dưới usage hiện tại chặn cấp mới, không buộc xóa dữ liệu.

Temp upload và bản copy/staging phải được tính riêng khi đồng thời tồn tại. Khi
copy sang file cuối, chuyển allocation đích thành COMMITTED, giữ allocation nguồn
tới khi xóa xong; không release chỉ vì request FAILED/EXPIRED hay media ARCHIVED.
Reconcile đối tượng thực, ledger và receipt trước release. Lỗi/crash ở giữa cần
retry theo claim/allocation_key, không tạo vô hạn bản sao chưa có giữ chỗ.

Garbage collection: file tạm ít nhất 24h sau kết thúc request/job, URL hết hạn,
không lease hợp lệ; metadata upload/import/delivery kết thúc giữ 30 ngày. Avatar
READY bỏ dở chưa dùng 7 ngày có thể archive; CONTENT READY chưa gắn giữ thư viện.
Media ARCHIVED đủ 30 ngày, hết profile/revision/attempt/projection/backup refs mới
được claim xóa. Khóa media, đặt deletion_started_at để ngăn refs mới; xóa object
ngoài transaction, ghi object_deleted_at sau xác nhận; rồi release allocation/
dọn metadata theo FK. Xóa không thành công không giả lập receipt. Không bật GC
file cuối trước khi audit được tính đầy đủ của reference graph và backup manifest.

## 9. Thông báo và import

Ghi event nghiệp vụ cùng transaction bằng native event_publication; listener ghi
notification/delivery với unique dedupe. In-app mặc định bật, email nhắc học tắt,
chưa chọn giờ thì không tự gửi. Security mail/OTP độc lập lựa chọn nhắc học;
record INTERNAL không vào feed người học. Quiet hours hỗ trợ qua nửa đêm; tối đa
một email nhắc học/ngày và dedupe theo user/ngày/ngữ cảnh. Timezone được validate.

Worker claim PENDING/lease hết hạn bằng transaction ngắn + `FOR UPDATE SKIP LOCKED`,
đặt claim_token/lease 60s, commit rồi gửi ngoài transaction. Ghi kết quả chỉ nếu
claim còn thuộc mình. Thử đầu + 3 retry sau 1/5/30 phút; provider receipt nếu có.
Không bảo đảm exactly-once SMTP khi nhà cung cấp nhận rồi mất phản hồi; ưu tiên
idempotency key của provider nếu hỗ trợ, không giữ transaction chờ SMTP.

Không thể gửi lại OTP chỉ từ HMAC. Delivery OTP giữ **ciphertext tạm** có key ID
và sensitive_expires_at, khóa giải mã ngoài DB; authenticated encryption có nonce
riêng và bind delivery/user/purpose. Event, notification body, audit và log chỉ
chứa ID/mã lỗi an toàn. Worker bỏ challenge hết hạn/đã dùng/đã invalidated, xóa
ciphertext khi kết thúc hoặc hết hạn. DB có cột/kiểm tra cặp cipher-key, chưa có
crypto/SMTP worker; phải kiểm thử tích hợp trước đưa vào sử dụng.

Import private <=5 MiB, <=5.000 dòng/item. Parse stream với hạn mức, reject schema
sai, DryRun điền row ledger rồi người dùng xác nhận. Khóa row/claim job; mỗi dòng
hợp lệ tạo content item + Draft + row SUCCESS/result IDs atomic. Dòng lỗi giữ
errors theo field và tiếp tục dòng khác; cập nhật counter từ ledger, không đếm
lại SUCCESS khi retry. Group JSON là một đơn vị atomic gồm group và các câu con.

Unique `(job_id,row_number)` chống retry trong job. Namespace + external_key có
unique trên content_items cho chống trùng xuyên job; mặc định create-only, báo/skip
trùng. Không dùng lemma/tên để tự ghép, không ghi đè Published. File import là
allocation TEMP riêng, dọn sau retention; xóa job ledger không được xóa content
đã tạo thành công. Retry tạo job dùng request_id/hash như start attempt.

## 10. Retention và kiểm chứng

| Dữ liệu | Chính sách |
|---|---|
| Đăng ký dở | 7 ngày từ chu kỳ gốc, không kéo dài bằng resend |
| Phiên/token và yêu cầu auth đã kết thúc | Metadata giữ 30 ngày theo D19; xóa secret khi kết thúc/hết hạn theo chính sách |
| Rate event/state | Chỉ dọn khi không còn tác dụng trong cửa sổ/blocked_until; không reset throttle bằng cleanup |
| Notification | 90 ngày, dọn delivery phụ thuộc đúng thứ tự |
| Delivery/import/upload kết thúc | Metadata 30 ngày từ finished_at ổn định |
| Temp/import file | Tối thiểu 24h, thêm điều kiện URL/lease/reconciliation |
| Attempt ABANDONED | Ít nhất 30 ngày và không còn tham chiếu cần giữ |
| Submitted, SRS/review/progress | Giữ trong vòng đời tài khoản, không áp dụng mốc auth 30 ngày |
| Audit | Ít nhất 365 ngày theo D19; append-only trong thời hạn giữ |
| Published/snapshot/media lịch sử | Giữ theo tham chiếu và quyền truy cập; không xóa dây chuyền kết quả |
| Backup | Hằng ngày/giữ 7 ngày và file manifest tương ứng, phải thử restore |

Các điều kiện phụ thuộc tường minh trong dictionary là căn cứ chọn thứ tự dọn.
Không mặc nhiên bật CASCADE trên user/content để giảm công việc cleanup.
[Báo cáo kiểm thử](SCHEMA_VALIDATION.md) ghi đúng những ca đã chạy; API/FE,
throttle, JSON validator, file scanning, SRS, worker, migration dữ liệu thực và
restore là các kiểm thử của bước triển khai tiếp theo.
