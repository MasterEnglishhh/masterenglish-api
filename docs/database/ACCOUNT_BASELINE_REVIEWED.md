# Account/Identity — tổng hợp quyết định đã duyệt

Ngày tổng hợp: 25/10/2026.

Bản chốt đầy đủ: [APPROVED_BASELINE.md](APPROVED_BASELINE.md),
[DATA_DICTIONARY.md](DATA_DICTIONARY.md), [SCHEMA_CONTRACTS.md](SCHEMA_CONTRACTS.md).
B36/B37 đã duyệt thêm giới hạn phiên, reset ACTIVE, mail bảo mật, BCrypt cost và
rate limit IP. Tài liệu này giữ các quyết định Account đã duyệt theo thời điểm.

Trạng thái: đã duyệt nghiệp vụ, danh sách dữ liệu và các ràng buộc ở D03–D20
trong REVIEW_OPEN_ISSUES.md. Đây là bản tổng hợp để triển khai sau khi rà soát;
không xác nhận SQL/entity hiện tại đã đồng bộ hoặc migration đã được áp dụng.
Nếu khác với BASELINE_v3.md hoặc SQL đề xuất cũ, dùng các quyết định đã duyệt
trong REVIEW_OPEN_ISSUES.md làm căn cứ.

## Danh sách bảng

Có 11 bảng riêng của nhóm và audit_logs dùng chung. Đây không phải số bảng
cuối cùng của toàn dự án.

| Bảng | Dữ liệu chính | Quyết định chi tiết |
|---|---|---|
| users | Email, họ/tên, password hash, profile, trạng thái và phiên bản bảo mật | D09, D20 |
| roles | Ba role cố định | D07, D10 |
| user_roles | Các role hiện có của user, thời điểm/người gán | D10 |
| auth_sessions | Phiên đăng nhập và hạn/thu hồi phiên | D06, D11 |
| refresh_tokens | Hash refresh token và lịch sử xoay token của phiên | D12 |
| otp_challenges | OTP theo đúng user/mục đích, hạn và trạng thái sử dụng | D13 |
| registration_requests | Họ/tên và hash mật khẩu của lượt đăng ký đang chờ OTP | D14, D20 |
| password_change_requests | Thao tác đổi mật khẩu đã kiểm tra mật khẩu hiện tại, đang chờ OTP | D15 |
| password_reset_grants | Quyền đặt lại mật khẩu tạm sau OTP quên mật khẩu | D16 |
| auth_rate_limits | Trạng thái giới hạn theo email/quy tắc, độc lập user ID | D17 |
| auth_rate_limit_events | Những lần gửi/thử được tính vào giới hạn | D17 |
| audit_logs | Nhật ký thay đổi quyền/trạng thái tài khoản, dùng chung hệ thống | D18 |

## Tài khoản và hồ sơ

- Đăng ký nhập riêng first_name, last_name, email, mật khẩu và xác nhận mật khẩu.
  Xác nhận mật khẩu chỉ dùng trên biểu mẫu, không có cột riêng.
- first_name và last_name bắt buộc, mỗi trường tối đa 30 ký tự sau trim, cho
  phép tiếng Việt và khoảng trắng trong tên, không cần duy nhất. Tên đệm có thể
  nằm trong first_name. Tên đầy đủ được ghép khi hiển thị, không lưu display_name.
- phone tùy chọn, tối đa 30 ký tự, dạng chuỗi, không unique, chưa dùng đăng nhập/
  SMS. avatar_media_id tùy chọn, chỉ ảnh sẵn sàng thuộc user; các dữ liệu này
  cập nhật ở profile sau đăng ký.
- Email tối đa 254 ký tự sau trim, giữ cố định trong bản đầu. Dùng chính sách
  so sánh không phân biệt hoa/thường nhất quán, không bỏ dấu chấm hoặc +tag.
- users gồm: id, email, first_name, last_name, password_hash, phone,
  avatar_media_id, status, email_verified_at, locked_until, password_changed_at,
  auth_version, version, created_at, updated_at.
- Người đăng ký chưa xác minh có thể chưa có họ/tên/hash chính thức trong users;
  dữ liệu của lượt đăng ký nằm trong registration_requests. Sau khi xác minh,
  tài khoản phải có các giá trị chính thức hợp lệ dù sau này bị khóa/vô hiệu hóa.
- Bốn trạng thái: PENDING_VERIFICATION, ACTIVE, LOCKED, DISABLED. Chờ do thử
  sai OTP/đăng nhập không tự chuyển trạng thái user sang LOCKED.

## Xác minh và mật khẩu

- Đăng ký xác minh bằng OTP email; thành công chuyển về đăng nhập, không tự
  đăng nhập. Hoàn tất phải dùng họ/tên/hash của đúng lượt đăng ký đã xác minh.
- OTP 6 chữ số, có thể có số 0 đầu, hạn 5 phút, dùng một lần. Chỉ mã mới nhất
  còn hiệu lực; ba mục đích EMAIL_VERIFICATION, PASSWORD_CHANGE, PASSWORD_RESET
  không dùng thay cho nhau.
- Gửi OTP cách nhau tối thiểu 60 giây, tối đa 5 lần trong 60 phút gần nhất mỗi
  email, cộng chung cả ba mục đích. Gửi lại/tạo yêu cầu mới không xóa bộ đếm.
- Nhập sai OTP 5 lần thì chờ 15 phút. Bộ đếm thử OTP tách theo mục đích; trạng
  thái chờ được giữ qua thay challenge. Giới hạn theo IP còn cần cấu hình kỹ thuật.
- Hash mật khẩu dùng BCrypt, cột 255 ký tự. Mật khẩu sau chuẩn hóa NFC tối thiểu
  15 Unicode code point và tối đa 72 byte UTF-8; không trim/cắt ngắn hoặc đổi hoa/
  thường. Không ép phối hợp nhóm ký tự; chặn mật khẩu phổ biến/đã lộ.
- Mật khẩu mới khác hiện tại, không bắt đổi định kỳ hoặc lưu lịch sử N mật khẩu.
  Cost BCrypt phải đo khi triển khai, không thấp hơn 10; chưa chốt giá trị cụ thể.
- Đổi mật khẩu: phải có phiên hợp lệ, kiểm tra mật khẩu hiện tại rồi gửi OTP.
  Yêu cầu gắn đúng phiên, user, auth_version và hash mật khẩu mới; tối đa 5 phút,
  không vượt hạn phiên. Resend không kéo dài hạn yêu cầu.
- Quên mật khẩu: phản hồi công khai nhất quán. OTP hợp lệ cấp quyền reset riêng
  5 phút, dùng một lần, không tạo phiên đăng nhập. Cấp grant mới sau xác minh
  thì hủy grant cũ; chỉ xin gửi OTP không hủy grant đang có.
- Đổi/reset thành công tăng auth_version, thu hồi mọi phiên/token và yêu cầu
  đăng nhập lại trong cùng transaction. OTP/reset không tự mở LOCKED/DISABLED.

## Vai trò và phiên

- Mỗi user có nhiều role độc lập: LEARNER, CONTENT_ADMIN, SYSTEM_ADMIN. Không
  có kế thừa quyền hoặc chức năng tạo role/permission tùy ý trong bản đầu.
- Đăng ký công khai có LEARNER. SYSTEM_ADMIN cấp thêm quyền cho tài khoản đã
  xác minh/ACTIVE; SYSTEM_ADMIN đầu tiên được cấp qua bootstrap riêng.
- Không để user mất hết role hoặc vô hiệu hóa/thu hồi SYSTEM_ADMIN hoạt động
  cuối cùng. Kiểm tra điều kiện trong transaction khi có thao tác đồng thời.
- Mỗi lần đăng nhập tạo phiên riêng; được có nhiều phiên. Phiên tối đa 7 ngày
  cố định từ lúc đăng nhập; refresh/hoạt động không gia hạn.
- Access JWT 15 phút và không vượt hạn phiên; refresh token ngẫu nhiên, chỉ lưu
  hash, xoay mỗi lần refresh, không vượt hạn phiên. Một phiên tối đa một token
  chưa thu hồi; vẫn phải kiểm tra thời gian hết hạn.
- Đăng xuất thông thường thu hồi phiên hiện tại. Đổi mật khẩu, đổi role, khóa/
  vô hiệu hóa thu hồi toàn bộ phiên của user theo các quy tắc đã duyệt.
- Chỉ giữ user_agent/IP dạng metadata tùy chọn, không lưu device_label; không
  coi UA/IP là định danh thiết bị duy nhất. last_active_at không gia hạn phiên
  hoặc được dùng thay cho thời gian học thực tế.
- Không tự coi mọi refresh lặp/đồng thời là tấn công; xử lý race/retry hợp lệ
  cần được cụ thể hóa khi thiết kế API.

## Khóa, nhật ký và dọn dữ liệu

- Sai mật khẩu 5 lần trong 15 phút thì chờ 15 phút; không thu hồi phiên hợp lệ
  chỉ vì thử sai. Thành công hợp lệ đặt lại bộ đếm lỗi tương ứng, không xóa quota
  gửi email. Request bị chặn không kéo dài thời gian chờ.
- LOCKED là khóa tạm bởi SYSTEM_ADMIN, có lý do/hạn, tự mở khi hết hạn hoặc
  được mở sớm. DISABLED cần quản trị mở lại. User chưa xác minh không được mở
  thành ACTIVE chỉ vì hết hạn khóa. Phiên đã thu hồi không được phục hồi.
- audit_logs ghi actor USER/SYSTEM, thao tác, đối tượng, thay đổi trước/sau,
  lý do, request/IP nếu có và thời điểm. Ghi cùng transaction với thay đổi quản
  trị; chỉ SYSTEM_ADMIN xem qua chức năng quản trị, không có sửa/xóa từng dòng.
- Không sao chép password hash, OTP/HMAC, token hoặc cookie vào audit. Tham chiếu
  entity_type/entity_id dùng chung không phải FK đa hình tự kiểm tra bởi database.
- Tài khoản đã hoạt động được giữ khi khóa/vô hiệu hóa; chưa có luồng tự xóa.
  Đăng ký chưa từng xác minh/hoạt động được dọn sau 7 ngày nếu đủ điều kiện D03.
- Phiên/token và metadata yêu cầu xác thực đã kết thúc giữ 30 ngày; giá trị
  xác thực tạm được xóa khi đã dùng/hủy hoặc tác vụ dọn phát hiện hết hạn.
  Đăng ký dở bị dọn sau 7 ngày là ngoại lệ cho nhánh dữ liệu liên quan.
- Audit giữ 365 ngày, dọn theo chính sách. Dữ liệu rate limit chỉ dọn khi không
  còn cần cho bộ đếm/thời gian chờ. Tác vụ dọn hằng ngày, tôn trọng FK và kiểm tra
  lại trạng thái để không xóa yêu cầu còn hợp lệ hoặc tài khoản vừa xác minh.
- Kiểm tra hiệu lực không phụ thuộc tác vụ dọn. Chính sách backup/phục hồi là
  phần vận hành cần thiết kế riêng trước triển khai.

## Công việc khi chuyển sang triển khai

1. Kiểm tra Flyway history thực tế, đối chiếu SQL cũ; chọn migration phù hợp,
   không sửa ngược một migration đã được áp dụng.
2. Chốt nullability/constraint/unique index cho trạng thái chờ xác minh, giá trị
   bí mật đã dọn và chuỗi token. avatar_media_id phụ thuộc phần media còn đang duyệt.
3. Đồng bộ entity, DTO, API và biểu mẫu theo first_name/last_name cùng các quy
   tắc trên. Xử lý cookie, CSRF, transaction, idempotency và retry ở mức triển khai.
4. Đo cost BCrypt, cấu hình giới hạn theo IP, tác vụ dọn và chính sách backup.
   Giới hạn số phiên đồng thời/giao diện quản lý phiên chưa tự động thành yêu cầu.
5. Kiểm thử các luồng chính và tình huống cạnh tranh trước khi áp dụng thay đổi.

Không có SQL, entity, migration hoặc dữ liệu đang chạy nào được đổi bởi việc
ghi nhận bản tổng hợp này. Tiếp tục duyệt học liệu/nội dung theo D21 trở đi.
