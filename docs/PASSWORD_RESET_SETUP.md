# Quên mật khẩu qua OTP

Luồng `/forgot-password`: nhập email → xác minh OTP 6 số → đặt mật khẩu mới → đăng nhập lại. Dùng cùng SMTP/Gmail, `OTP_HMAC_SECRET`, `RATE_LIMIT_SECRET` và kiểm tra HIBP đã cấu hình cho đăng ký. Không cần thêm biến môi trường hoặc khóa mới. Xem [cấu hình gửi email](REGISTRATION_SETUP.md).

## API

Tất cả endpoint là POST cùng origin, cần CSRF cookie/header và dùng `Cache-Control: no-store`.

| Endpoint | Request | Response |
| --- | --- | --- |
| `/api/v1/auth/password/forgot` | `email` | 202: `challengeId`, `expiresAt`, `resendAfterSeconds`, thông báo chung |
| `/api/v1/auth/password/verify` | `email`, `challengeId`, `otp` dạng chuỗi 6 số | 200: `resetToken` opaque và `expiresAt` |
| `/api/v1/auth/password/reset` | `email`, `resetToken`, `newPassword` | 200: cập nhật mật khẩu, xóa cookie phiên hiện tại |

Email không tồn tại, chưa xác minh hoặc bị khóa/vô hiệu hóa nhận cùng thông báo công khai. Chỉ ACTIVE đã xác minh được nhận OTP và hoàn tất reset; quy trình không tự mở khóa tài khoản. Yêu cầu không làm đổi mật khẩu hoặc thu hồi phiên trước khi hoàn tất bước cuối.

## Quy tắc

- OTP CSPRNG 6 số, 5 phút, dùng một lần, HMAC bind `PASSWORD_RESET` + challenge ID. OTP đăng ký không dùng thay được. Resend gọi lại endpoint `forgot`, thay challenge mới và vô hiệu mã cũ.
- Gửi cách nhau 60 giây, tối đa 5/email/giờ cộng chung đăng ký và các mục đích OTP khác. Sai 5 lần theo email/mục đích chờ 15 phút, không reset khi resend. Hạn mức IP dùng chung với đăng ký; 429 có `Retry-After`.
- Sau OTP đúng, server cấp 32 byte ngẫu nhiên dưới dạng token hex. DB chỉ giữ SHA-256; grant bind user, OTP đúng purpose đã consume, `auth_version`, TTL tối đa 5 phút và dùng một lần. Không phải JWT, không tạo phiên đăng nhập.
- Chỉ cấp grant mới sau xác minh thành công mới hủy grant cũ. Xin OTP khác không tự hủy quyền reset đã được xác minh. Reset kiểm lại trạng thái tài khoản, grant, phiên bản và expiry trong transaction cuối, kể cả sau tác vụ HIBP/BCrypt.
- Mật khẩu mới NFC, ít nhất 15 code point, tối đa 72 byte UTF-8, BCrypt cost 12, không trim/cắt/ép nhóm ký tự; chặn phổ biến/đã lộ. Mật khẩu mới phải khác hiện tại; lỗi nhập mật khẩu không tiêu thụ grant.
- Hoàn tất trong một transaction: consume grant và xóa hash, đổi password/hash + `password_changed_at`, tăng `auth_version`, thu hồi toàn bộ session/refresh token của user và vô hiệu các yêu cầu auth chưa kết thúc. Grant replay hoặc reset đồng thời chỉ một lần thành công. Khóa theo user giúp phối hợp với refresh/role changes.
- Frontend xóa thông tin user đã cache sau reset, chuyển về `/login?reset=1&redirect=...`. OTP, mật khẩu và reset token chỉ nằm trong bộ nhớ; không đưa vào URL, log hoặc localStorage/sessionStorage. Tải lại trang cần xin mã mới.

## Gửi thư

OTP và thông báo thay mật khẩu được gửi trên virtual thread **sau transaction**. HTTP không chờ SMTP, tránh để thời gian kết nối SMTP trực tiếp tiết lộ email nào có tài khoản. Raw OTP chỉ tồn tại tạm trong bộ nhớ, không lưu plaintext vào DB/event/log. Nếu gửi thất bại, chỉ vô hiệu challenge đang chờ tương ứng; không hủy grant đã xác minh. Quota vẫn giữ nguyên. Người dùng có thể xin mã mới theo hạn mức.

Sau reset có email thông báo bảo mật. Lỗi gửi thông báo không rollback hoặc báo thất bại giả cho việc đổi mật khẩu đã commit. Log chỉ ghi loại lỗi an toàn, không ghi email/password/OTP/token.

Hiện chưa có outbox/durable retry worker: app dừng trước khi gửi có thể làm mất thư đang chờ trong bộ nhớ; dùng resend nếu không nhận được mã. Không triển khai tính năng đổi mật khẩu chủ động trong tài khoản ở thay đổi này.

## Kiểm thử

`gradlew check bootJar` gồm kiểm thử PostgreSQL tạm: OTP/grant hết hạn, single-use, race, quota chung, purpose binding, email không tồn tại, không tự mở DISABLED, credential cũ/mới, thu hồi phiên và grant cũ sau thay `auth_version`. Browser smoke test dùng tài khoản/database riêng và Mailpit: đang đăng nhập → OTP → reset → cookie/user cache được xóa → mật khẩu cũ bị từ chối → mật khẩu mới đăng nhập được → email thông báo được nhận. Không thay mật khẩu hoặc gửi thư thử tới tài khoản thật.
