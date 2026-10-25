# Kế hoạch chuyển sang schema đã chốt

Ngày 25/10/2026. Đây là kế hoạch; chưa chạy thay đổi trên database dự án hoặc EC2.
SQL `proposals/approved-baseline.sql` chỉ cho database trống cô lập, **không phải
migration nâng cấp**, không chạy nối cùng V1/V2 hiện có.

## Hiện trạng đã biết từ source

- V1 tạo native `event_publication` của Modulith.
- V2 trong source tạo Identity cũ: display_name, password_hash NOT NULL,
  ACTIVE mặc định, auth_sessions.device_label và password_reset_challenges.
- Baseline mới dùng first_name/last_name, pending verification, nhiều loại OTP/
  request/grant, ip_address, session limit và bảng media/quota/content mới.
- `ProposedDatabaseSchemaTests` hiện kiểm đề xuất cũ 44 bảng. Các kết quả kiểm
  thử cũ không chứng minh entity/API đã tuân thủ baseline mới.
- Chưa đọc `flyway_schema_history` hoặc dữ liệu DB đang dùng ở lượt này. Không
  suy luận V2 đã áp dụng chỉ từ việc file tồn tại.

## 1. Kiểm kê trước khi viết migration thực thi

Trong đúng môi trường dự định nâng cấp, lấy read-only: PostgreSQL/version/extensions,
Flyway history gồm version/checksum/success, schema-only dump, số dòng từng bảng,
foreign keys/index, cấu hình datasource/Flyway locations và module đang dùng thực tế.
Xác định bảng nào do migration chính thức tạo và bảng nào từng được tạo thủ công.
Không in password/token/OTP/hash cá nhân vào log rà soát.

Đối chiếu môi trường Windows local và EC2 độc lập. Database trong volume local
không tự đi theo Git, image hoặc JAR; triển khai app lên EC2 không tự chuyển dữ liệu.
Nếu cần chuyển dữ liệu, dùng backup/restore có kiểm tra và manifest file đi kèm.

Phân loại theo bằng chứng:

| Trường hợp | Cách xử lý |
|---|---|
| Database mới hoàn toàn | Viết chuỗi migration chính thức tạo schema theo dependency, kiểm thử từ zero; không đồng thời chạy SQL tham chiếu |
| V1/V2 đã áp dụng | Giữ nguyên checksum, thêm version mới để expand/backfill/validate/contract |
| V2 chưa áp dụng ở đâu và có bằng chứng | Có thể quyết định tổ chức lại baseline migration khi bắt đầu code; vẫn kiểm tra mọi môi trường trước |
| Schema thủ công/khác lịch sử | Lập bản đồ sai khác và migration reconcile cụ thể; không dùng repair/baseline để che sai khác |

Không sửa file migration đã áp dụng, không DROP database để làm test xanh,
không `clean` trên datasource thật. Chưa gán số V3/V4… trước khi đọc history thật.

## 2. Expand và chuyển Identity

1. Sao lưu và xác minh bản sao trước. Thêm cột mới theo giai đoạn nullable,
   bổ sung bảng OTP/request/rate-limit/audit chưa tồn tại.
2. Backfill firstname/lastname từ nguồn đáng tin. **Không tự chia display_name
   theo khoảng trắng rồi coi là chính xác**, không cắt âm thầm tên >30 ký tự.
   Danh sách cần bổ sung/chuẩn hóa được xử lý rõ trước khi bật NOT NULL nghiệp vụ.
3. Không bịa email_verified_at cho tài khoản ACTIVE cũ. Xác định chứng cứ xác minh
   và kế hoạch yêu cầu xác minh lại với tài khoản chưa đủ căn cứ. Tránh bật CHECK
   mới trước khi xử lý các row không đạt.
4. Kiểm tra hình dạng/thuật toán hash cũ; không băm lại hash như mật khẩu rõ.
   Hash không tương thích cần reset có xác minh, không tự chuyển bằng SQL.
5. Thu hồi phiên/token/grant cũ không có đủ ràng buộc mới trong đợt chuyển bảo mật;
   không chuyển challenge cũ thành grant đã xác minh. Giữ metadata cũ cần audit
   theo retention, dọn sau khi hết hạn; triển khai UI đăng nhập lại tương ứng.
6. Đối chiếu role độc lập, đảm bảo mỗi user >=1 role và có SYSTEM_ADMIN ACTIVE
   hợp lệ. Bootstrap admin bằng quy trình môi trường riêng, không seed credential
   vào Git/SQL tham chiếu. Dọn duplicate email theo quyết định dữ liệu, không auto-merge.
7. App mới ghi/đọc fields mới, DTO trước; validate constraints/FK sau backfill.
   Chỉ contract/drop display_name/device_label/bảng cũ sau khi không còn code dùng.

## 3. Media, content và projection

- Tạo media/nguồn/revisions với thứ tự xử lý FK vòng rõ ràng; pointer và projection
  được đồng bộ trong transaction. Với dữ liệu cũ, tạo revision gốc từ nội dung thực,
  ghi nguồn/quyền đúng dữ liệu, không mặc định mọi nguồn đã có giấy phép.
- Projection dùng chung UUID với content_items. Nếu dữ liệu cũ từng dùng hai UUID,
  tạo bảng mapping tạm có unique và backfill mọi FK. Không phát sinh UUID mới rồi
  bỏ mất mapping của bài làm, SRS, nguồn hoặc URL tài nguyên.
- Question một group: nếu dữ liệu cũ là group_questions N:N, thống kê các câu
  nhiều group. Nhân bản nội dung/ngữ cảnh bằng ID mới khi cần; giữ mapping lịch sử,
  không tự chọn group đầu tiên và làm mất ngữ cảnh.
- Word sense là đơn vị SRS. Dữ liệu tiến độ theo word cũ không tự gán cho tất cả
  nghĩa. Cần mapping theo nghĩa thực hoặc kế hoạch bắt đầu tiến độ nghĩa mới.
- Chụp Published revision/attempt lịch sử từ dữ liệu đã giao nếu còn bằng chứng.
  Không dùng key mới nhất để tái tạo và thay điểm cũ. Dữ liệu không thể khôi phục
  cần đánh dấu/giữ dạng legacy rõ ràng trong kế hoạch thực tế.
- Dựng đầy đủ revision_media_refs/attempt_media_refs, kiểm kê objects và ledger
  dung lượng. Chưa đủ reference graph thì **chưa bật GC file cuối**.

## 4. Chuỗi triển khai theo module

| Giai đoạn | Điều kiện hoàn tất |
|---|---|
| Identity | Signup/OTP/change/reset/session/role + FE tương ứng; kiểm thử bảo mật, throttle và mail giả |
| Content/Media | Workflow revision/source, upload thật có trần, quota/claim/crash recovery; Published bất biến |
| Vocabulary/Grammar | SRS policy có boundary tests, review idempotent, lesson completion và lịch sử revision |
| Questions/Practice | Part 1–7, whole group, placeholder, public/private DTO, chọn FIXED/DYNAMIC |
| Attempts/Results | Start/retry/CAS/resume/deadline/submit; snapshot/history/mistakes cùng ownership |
| Learning/Notifications/Import | Heartbeat đa tab, timezone/stats, outbox/delivery, DryRun/ledger và retry |

Schema có thể expand trước khi UI sử dụng. Không mở endpoint mới khi chỉ có
bảng mà chưa có validation/grader/quyền tương ứng. Tách migration ngắn khỏi job
backfill dài; dùng batch có checkpoint, có thể chạy lại mà không nhân bản dữ liệu.

## 5. Kiểm thử và phục hồi trước khi áp dụng thật

Chạy migration trên hai bản thử: database mới từ zero và bản sao sạch dữ liệu
cũ. Kiểm tra Flyway validate/checksum, counts, orphan/duplicate, projection-pointer,
snapshot/key/media refs, quota reconciliation và query plans với dữ liệu đại diện.
Thử rollback app trong cửa sổ schema expand còn tương thích; sau contract không
giả định binary cũ còn chạy được. PostgreSQL transaction không rollback được
SMTP/S3 đã thực hiện; external effect cần idempotency/compensation/receipt.

Backup hằng ngày, giữ 7 ngày theo chính sách đã duyệt; có schema/data, version
migration, timestamp và manifest objects/version/checksum tương ứng. Giữ media
cần cho mọi backup chưa hết hạn, không chỉ refs của database hiện tại. Thử restore
vào môi trường riêng và đối chiếu login/content/history/file trước triển khai.

Khi bắt đầu migration thật, ghi lại thời điểm/scope, phiên bản app, snapshot backup,
kết quả verify và phương án khôi phục. Không coi file kế hoạch này là bằng chứng
đã sao lưu, đã chuyển dữ liệu hay đã deploy EC2.
