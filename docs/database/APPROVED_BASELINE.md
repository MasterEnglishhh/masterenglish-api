# MasterEnglish — database đã chốt

Ngày chốt: **25/10/2026**. Căn cứ: các quyết định D01–D29 và toàn bộ
**B01–B37 đã được người dùng duyệt** bằng câu “Ok chốt đi”. D30 được gộp vào B01.

Đây là bản thiết kế thống nhất cho BE/FE. SQL đã được thử trên PostgreSQL riêng
bằng dữ liệu giả; chưa áp dụng vào database dự án, chưa sửa entity hay migration
hiện hành. Không cần duyệt lại tên bảng, cột hoặc index thông thường.

## Hồ sơ chính

| Tài liệu | Nội dung |
|---|---|
| [ERD.md](ERD.md) | Sơ đồ quan hệ theo module và luồng nội dung → lượt làm → kết quả |
| [DATA_DICTIONARY.md](DATA_DICTIONARY.md) | Toàn bộ bảng, cột, kiểu, nullable, khóa và index |
| [SCHEMA_CONTRACTS.md](SCHEMA_CONTRACTS.md) | Giao dịch, snapshot, validation, SRS, upload và worker |
| [approved-baseline.sql](proposals/approved-baseline.sql) | SQL tham chiếu cho database trống, nằm ngoài Flyway |
| [MIGRATION_PLAN.md](MIGRATION_PLAN.md) | Chuyển schema cũ theo lịch sử migration và dữ liệu thực tế |
| [SCHEMA_VALIDATION.md](SCHEMA_VALIDATION.md) | Kết quả kiểm thử đã chạy và giới hạn của kiểm thử |
| [DATABASE_BATCH_REVIEW.md](DATABASE_BATCH_REVIEW.md) | Toàn văn 37 quyết định đã duyệt |

`BASELINE_v3.md` và `proposals/remaining-mvp.sql` được giữ để truy vết, đã được
thay thế về thiết kế. Các đoạn “chưa duyệt” trong lịch sử thảo luận không mở lại
những điểm đã chốt qua B01–B37.

## Kiến trúc dữ liệu

Một PostgreSQL 17, UUID cho tài nguyên, khóa ghép cho liên kết, TIMESTAMPTZ/UTC
cho thời điểm. Ngày thống kê giữ thêm timezone và local_day theo lúc ghi nhận.
Enum nghiệp vụ dùng VARCHAR + CHECK; không thêm Redis hoặc partition ở bản đầu.

Ba lớp nội dung có mục đích riêng:

1. `content_items` là định danh ổn định; `content_revisions` giữ bản có schema,
   workflow duyệt và snapshot bất biến sau công bố. Mỗi item chỉ có một nhánh
   đang soạn/chờ duyệt.
2. Các bảng `words`, `questions`, `decks`… là projection quan hệ của bản công bố
   hiện hành để tìm kiếm/lọc. **ID của projection chính là content_items.id**;
   không tạo hai UUID khác nhau cho cùng tài nguyên. Draft chưa hoàn chỉnh ở JSON,
   không đẩy dữ liệu thiếu vào projection công bố.
3. Lượt làm chụp riêng câu/nhóm/lựa chọn/key/lời giải và revision đã ghim. Sau seal
   không thay đề; sau nộp không thay kết quả. Sửa ngân hàng không đổi điểm cũ.

Revision cha ghim revision con bằng `revision_dependencies`. Snapshot giữ ID nghĩa,
câu, lựa chọn và material ổn định; `revision_media_refs`/`attempt_media_refs` giữ FK
đến mọi file cần bảo toàn, gồm file xuất hiện trong JSON. Không chỉ dựa vào tìm chuỗi
trong JSON để quyết định xóa file.

## Danh mục cuối cùng

Có **61 bảng nghiệp vụ/hỗ trợ + 1 bảng native Modulith = 62 bảng**. Không tính
`flyway_schema_history`; số bảng phát sinh từ quan hệ và lịch sử cần giữ, không
phải một chỉ tiêu tính năng. Chi tiết đếm được sinh ở `schema-manifest.json`.

| Nhóm | Số bảng | Trách nhiệm |
|---|---:|---|
| Identity | 11 | Tài khoản, role, OTP, đăng ký/đổi/reset mật khẩu, phiên, rate limit |
| Content | 8 | Revision, nguồn, phụ thuộc, tham chiếu file, import và row ledger |
| Media | 4 | File, yêu cầu upload, pool và ledger dung lượng |
| Vocabulary | 5 | Từ, nhiều nghĩa/ví dụ, deck theo nghĩa |
| Learning | 6 | Từ đã lưu, SRS/review, hoàn thành bài, mục tiêu, thời gian hoạt động |
| Grammar | 2 | Topic và lesson |
| Practice | 10 | Type, câu/nhóm/material, option, liên kết kiến thức và bộ luyện |
| Results | 9 | Lượt làm, snapshot, đáp án, lỗi và nguồn luyện |
| Notifications | 4 | Tùy chọn, quy tắc nhắc, thông báo và delivery |
| Administration | 2 | Cấu hình có kiểu/allowlist và audit |
| Infrastructure | 1 | `event_publication` theo V1, không tạo event registry thứ hai |

Các view `learning_daily_activity`, `learning_review_summary`,
`learning_attempt_summary`, `media_quota_usage` là truy vấn tổng hợp; không phải
bảng sao chép dữ liệu hoặc bảo đảm các API thống kê đã được triển khai.

## Những quyết định ảnh hưởng trực tiếp đến code

- **Tài khoản:** nhiều role độc lập; first_name/last_name tối đa 30 ký tự mỗi
  trường; email cố định; phone/avatar cập nhật sau. Đăng ký xác minh bằng OTP,
  BCrypt, đổi mật khẩu cần mật khẩu hiện tại và OTP; reset có grant ngắn hạn.
  Giữ tối đa 5 phiên, mỗi phiên tối đa 7 ngày, thu hồi khi thay đổi bảo mật.
- **Câu hỏi:** tách category, TOEIC Part, practice_mode, response type và context.
  Chỉ `SINGLE_CHOICE` được bật trong bản đầu. Một câu thuộc tối đa một nhóm;
  bộ chọn nguyên nhóm, các câu con có đáp án riêng, không có `group_questions` N:N.
- **TOEIC:** luyện Part 1–7; Part 2 có 3 option, các Part khác 4. Part 3/4 có
  3 câu/nhóm; Part 6 có 4 câu/nhóm và blank_key; Part 7 có 1–3 tài liệu. Không
  trả transcript, key, lời giải trước nộp; Part 1/2 không trả option chữ.
- **Luyện và kết quả:** 1–100 câu con, một category, tối đa 5 bài đang làm;
  start idempotent, autosave có version, deadline do server giữ. Đúng 1/sai hoặc
  bỏ trống 0; không quy đổi bài ngắn thành điểm TOEIC chính thức.
- **Tiến độ:** SRS theo nghĩa, bỏ lưu không xóa SRS; Grammar hoàn thành bằng
  thao tác rõ ràng. Lỗi giữ OPEN → IMPROVING → RESOLVED sau hai lượt đúng liên
  tiếp; sai lại về OPEN. Thời gian học lấy heartbeat, không lấy thời gian mở tab.
- **Upload:** kiểm tra byte/định dạng thật; avatar 2 MiB, ảnh nội dung 5 MiB,
  audio 20 MiB; quota có giữ chỗ khi xử lý đồng thời. Archive không tự giải phóng
  dung lượng; không xóa file còn trong revision, bài làm hoặc backup còn giữ.
- **Import/thông báo:** import 5 MiB/5.000 item có DryRun và ledger chống trùng,
  chỉ tạo Draft. Delivery theo kênh có dedupe, lease và retry; dữ liệu OTP cần
  gửi được mã hóa tạm, event/log không chứa OTP rõ.

Các ngưỡng, dữ liệu và giao thức đầy đủ nằm trong dictionary/contracts; bảng này
không thay thế những thông số chi tiết đã duyệt ở Account/Media và B01–B37.

## Phạm vi đã chốt và hướng mở rộng

AI, video kết hợp nhập chat, nhập đáp án tự do, Speaking/Writing, đề mô phỏng đủ
200 câu, deck cá nhân và bộ permission động để sau. Có thể thêm bằng migration,
schema/version, renderer và grader tương ứng; thêm mã question type chưa đủ để
coi một dạng luyện mới đã hoạt động.

## Bước triển khai tiếp theo

Chuyển Identity trước, rồi Content/Media → Vocabulary/Grammar → Questions/Practice
→ Attempts/Results → Learning/Notifications. Mỗi module gồm migration nâng cấp,
entity/DTO/service, kiểm thử phù hợp và màn hình FE tương ứng. Trước migration
đầu tiên cần kiểm tra Flyway history thực tế theo MIGRATION_PLAN.md. Bản tham
chiếu này không được dùng để ghi đè database hiện có.
