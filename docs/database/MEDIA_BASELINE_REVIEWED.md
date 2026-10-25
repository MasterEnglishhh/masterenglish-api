# Media — tổng hợp quyết định đã duyệt

Bản hợp nhất sau duyệt toàn bộ B01–B37:
[APPROVED_BASELINE.md](APPROVED_BASELINE.md) và
[SCHEMA_CONTRACTS.md](SCHEMA_CONTRACTS.md). Bổ sung storage_pools/allocations,
revision/attempt media refs, deletion claim và object delete receipt trong
dictionary. Các chi tiết từng ghi chờ thiết kế bên dưới đã được cụ thể hóa trong
hồ sơ hợp nhất; chưa áp dụng vào database hoặc triển khai dịch vụ lưu file.

Ngày tổng hợp: 25/10/2026. Nguồn quyết định: D21–D27 trong
REVIEW_OPEN_ISSUES.md. D21 được duyệt tạm thời với hướng mở rộng video/chat;
hiện tại chỉ triển khai phạm vi ảnh, audio và text đã thống nhất. Bản này tổng
hợp dữ liệu/quy tắc đã duyệt, không xác nhận SQL/entity đã được đồng bộ.

## Hai bảng chính

| Bảng | Mục đích |
|---|---|
| media_assets | Thông tin file dùng lâu dài, trạng thái và liên kết nguồn |
| media_upload_requests | Ngữ cảnh upload tạm, giới hạn được cấp và xử lý xác minh |

media_assets gồm id, media_type, purpose, bucket, object_key, original_filename,
mime_type, file_size_bytes, duration_ms, width, height, checksum_sha256,
source_id, uploaded_by, status, failure_code, archived_at, created_at, updated_at.

media_upload_requests gồm id, media_id, temp_bucket, temp_object_key,
declared_size_bytes, declared_mime_type, max_size_bytes, status, expires_at,
verification_started_at, verification_deadline_at, claim_token, lease_until,
completed_at, failure_code, created_at, updated_at.

Mỗi upload gắn một media, mỗi lần upload lại sau thất bại/hết hạn tạo yêu cầu và
media mới. Người sở hữu yêu cầu lấy qua media_assets.uploaded_by, không lặp thêm
cột người dùng. Các metadata chưa biết có thể trống trước xác minh; READY phải
có thông tin file thực tế hợp lệ. Chi tiết nullability/CHECK sẽ đồng bộ khi viết SQL.

## Cách lưu và quyền truy cập

- Text lưu trong database của nội dung; không ép thành file upload.
- File ảnh/audio nằm trong kho lưu trữ đối tượng; dùng media ID ổn định, lưu
  bucket/object_key, không giữ signed URL làm định danh lâu dài.
- media_type hiện có IMAGE/AUDIO; purpose CONTENT/AVATAR là chiều khác. Avatar
  chỉ dùng IMAGE thuộc tài khoản của người dùng. Upload CONTENT cần CONTENT_ADMIN;
  SYSTEM_ADMIN không tự kế thừa quyền này.
- Một media có thể được nhiều nội dung tham chiếu theo quyền; không gắn một
  question_id bắt buộc vào media_assets. Không unique checksum để tự gộp file
  khác phạm vi quyền sử dụng chỉ vì nội dung byte giống nhau.
- Thay file tạo media/key mới, giữ file cũ nếu còn tham chiếu. File đã xác nhận
  phải bất biến, không để URL upload cũ ghi đè được.
- File học liệu mặc định riêng tư. Backend kiểm tra quyền theo nội dung/lịch sử
  trước khi cấp URL đọc có hạn; URL có thể được dùng lại tới khi hết hạn.
- source_id là nguồn chính của file; bảng nguồn đã duyệt ở D28, liên kết theo
  bản nội dung đang được duyệt tiếp ở D29. READY
  về kỹ thuật không thay cho các điều kiện công bố nội dung/quyền sử dụng.

## Upload và xác minh

1. Kiểm tra tài khoản, quyền, loại file, mục đích và dữ liệu khai báo.
2. Tạo media PENDING, yêu cầu upload và vị trí tạm riêng tư do backend quản lý.
3. Upload vào vùng tạm bằng quyền có hạn, cưỡng chế giới hạn byte trong đường nhận.
4. Complete được nhận trong 10 phút từ lúc tạo; kiểm tra chủ sở hữu/quyền hiện tại.
5. Backend xác minh đúng bản file: dung lượng, MIME, giải mã, thông số và checksum.
6. Chuyển đúng dữ liệu đã kiểm tra sang vị trí cuối bất biến; cập nhật metadata/
   media READY và yêu cầu COMPLETED an toàn khi có retry/request đồng thời.

- Xác minh tối đa 5 phút từ lúc bắt đầu nhận xử lý, retry không gia hạn. Lease
  60 giây được gia hạn trong hạn xử lý; claim_token ngăn lượt cũ ghi kết quả
  sau tiếp quản. Tối đa 2 tác vụ xác minh chạy đồng thời toàn backend lúc đầu.
- Trạng thái media: PENDING, READY, FAILED, ARCHIVED. Trạng thái upload request:
  PENDING, VERIFYING, COMPLETED, FAILED, EXPIRED.
- Complete lặp trả trạng thái/kết quả hiện có theo quyền. Mất phản hồi mạng
  không tự kết luận thất bại. Link hết hạn không hủy tác vụ xác minh đã nhận hợp lệ.
- Kho file và database không chung transaction; không giữ transaction khi
  truyền/giải mã file. Cần phục hồi/đối chiếu file mồ côi khi có crash giữa các bước.

## Kiểm tra đầu vào và hạn mức

| Mục đích | Định dạng | Trần mỗi file |
|---|---|---|
| Avatar | JPEG, PNG, WebP | 2 MiB |
| Ảnh học liệu | JPEG, PNG, WebP | 5 MiB |
| Audio học liệu | MP3, M4A | 20 MiB |

1 MiB = 1.048.576 byte. Từ chối file rỗng, tên file quá 255 ký tự hoặc đầu vào
không hợp lệ. Frontend báo sớm; backend kiểm tra trước cấp quyền, đường nhận
cưỡng chế byte và backend kiểm tra lại file thực tế trước READY. Không chỉ tin
đuôi file, MIME/Content-Length do client khai báo. Khi giải mã cần giới hạn tài nguyên.

| Giới hạn khởi điểm | Avatar | CONTENT |
|---|---|---|
| Tạo yêu cầu trong 60 phút gần nhất | 10/user | 100/CONTENT_ADMIN |
| Yêu cầu đang mở | 2/user | 3/CONTENT_ADMIN |
| Quota thư viện | 50 MiB/user | 10 GiB chung toàn thư viện |

- Tính cả upload thất bại vào lượt tạo; complete lặp không tạo lượt mới.
- Quota thư viện gồm file cuối READY/ARCHIVED còn giữ và phần giữ chỗ cho
  upload hợp lệ, không tính đôi khi chuyển sang READY. Một file dùng nhiều bài
  chỉ tính một lần. ARCHIVED không tự giải phóng quota.
- Chạm giới hạn thì từ chối upload mới với lý do phù hợp, vẫn đọc được nội dung
  đã có. Các giá trị đặt ở cấu hình backend, đồng bộ frontend.
- Quota thư viện khác tổng dung lượng vật lý/hóa đơn: vùng tạm, file lỗi/mồ côi,
  bản sao và backup có chi phí riêng. Cần ngân sách/kiểm soát kho tạm và giám sát
  khi chọn kho lưu trữ trước triển khai; chưa quyết định nhà cung cấp ở lượt rà soát.

## Archive và dọn dữ liệu

- ARCHIVED ngừng cho gắn mới, giữ khả năng đọc tham chiếu cũ còn hợp lệ. Có
  archived_at ổn định. Người dùng quản lý avatar của mình; CONTENT_ADMIN archive
  học liệu theo quyền, không có nút xóa vật lý trực tiếp trong bản đầu.
- File tạm đủ 24 giờ sau kết thúc yêu cầu mới đủ điều kiện dọn, cần URL đã hết
  hạn và không còn xử lý hợp lệ. File mồ côi phải được đối chiếu và đủ 24 giờ.
- Metadata upload đã kết thúc và media FAILED giữ 30 ngày, dọn theo thứ tự FK.
- File READY còn tham chiếu hiện tại/lịch sử được giữ. CONTENT READY chưa gắn
  vẫn ở thư viện. Avatar cũ được archive khi không còn tham chiếu; avatar mới
  bỏ dở chưa gắn profile được archive sau 7 ngày không dùng.
- File ARCHIVED đủ 30 ngày mới được xét xóa vật lý nếu hết mọi tham chiếu cần
  giữ, gồm profile, nội dung, draft, revision và snapshot, kể cả trong JSON.
  Chưa truy xuất đủ tham chiếu lịch sử thì chưa bật tự động xóa file cuối.
- Dọn hằng ngày, kiểm tra lại trạng thái/tham chiếu/lease và ngăn tham chiếu mới
  khi đã nhận xóa. Xóa phải retry được, không xóa file đang dùng hoặc công bố
  READY cho file đã mất. Backup/phục hồi cần xét đồng thời database và file.

## Đối chiếu với bản hợp nhất và bước triển khai

- Dữ liệu nguồn/quyền sử dụng và liên kết nguồn theo revision đã duyệt D28/D29,
  vòng đời nội dung đã duyệt B01–B05.
- Snapshot và tham chiếu media lịch sử phải đồng bộ trước bật dọn file cuối.
- Chọn nhà cung cấp, cách cưỡng chế upload, giao thức pin đúng bản file, index,
  khóa/giữ chỗ quota và phục hồi lỗi cần cụ thể hóa khi viết schema/API.
- Video kết hợp nhập chat là hướng mở rộng: cần thêm validation, metadata,
  dữ liệu tương tác/hội thoại, API/UI và quy tắc chấm; chưa có trong bản đầu.

Việc ghi nhận này không tạo bảng, mở bucket, upload/xóa file, thay cấu hình
hoặc áp dụng migration vào hệ thống đang chạy.
