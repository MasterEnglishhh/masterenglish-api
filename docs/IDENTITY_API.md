# API quản lý tài khoản

Các đường dẫn dưới đây bắt đầu bằng `/api/v1`. Mỗi yêu cầu thay đổi cần cookie
CSRF và header nhận từ `GET /auth/csrf`. Dùng cookie access token hoặc Bearer JWT
để xác thực phiên.

| API | Dữ liệu gửi | Kết quả |
|---|---|---|
| `PUT /users/me` | `firstName`, `lastName`, `phone` | 200 kèm hồ sơ. Họ và tên bắt buộc. Mỗi trường tối đa 30 ký tự sau khi bỏ khoảng trắng hai đầu. Để trống, bỏ trường hoặc gửi `null` để xóa số điện thoại. |
| `POST /auth/password/change` | `currentPassword`, `newPassword`, `confirmPassword` | 202 kèm `challengeId`, `expiresAt`, `resendAfterSeconds`. Gửi OTP đến email đã xác minh của tài khoản. |
| `POST /auth/password/change/resend` | `challengeId` | 202 kèm hạn ban đầu của yêu cầu. Mã mới thay thế mã trước. |
| `POST /auth/password/change/verify` | `challengeId`, `otp` | 200. Thu hồi mọi phiên và refresh token. Đăng nhập lại bằng mật khẩu mới. |
| `GET /auth/sessions` | | 200 kèm danh sách phiên còn hạn của chính mình. Mỗi phiên có `id`, `current`, `userAgent`, `ipAddress`, `createdAt`, `lastActiveAt`, `expiresAt`. |
| `DELETE /auth/sessions/{sessionId}` | | 204 sau khi thu hồi phiên thuộc tài khoản. Khi thu hồi phiên hiện tại, response xóa cookie xác thực. Phiên của tài khoản khác hoặc ID không tồn tại trả 404. |
| `PUT /admin/users/{userId}/roles` | `roles` là toàn bộ tập vai trò mong muốn | Chỉ `SYSTEM_ADMIN` được gọi. 200 kèm hồ sơ cập nhật. Thay đổi quyền buộc tài khoản đích đăng nhập lại. |

Yêu cầu đổi mật khẩu chỉ có hiệu lực trên phiên đã khởi tạo, tối đa 5 phút và
trong thời hạn phiên. Các luồng OTP cùng dùng hạn mức gửi 60 giây giữa hai lần và
5 lần mỗi giờ theo email. Nhập sai 5 mã đổi mật khẩu trong cửa sổ thử sẽ tạm chặn
xác minh 15 phút.

Ba vai trò độc lập là `LEARNER` cho học tập, `CONTENT_ADMIN` cho quản trị nội
dung và `SYSTEM_ADMIN` cho quản trị hệ thống. Đăng ký công khai tạo `LEARNER`.
Quản trị viên chỉ thay đổi vai trò của tài khoản đang hoạt động và đã xác minh.
Mỗi tài khoản giữ ít nhất một vai trò. Hệ thống trả 409 khi yêu cầu sẽ gỡ quyền
của quản trị viên hệ thống đang hoạt động cuối cùng. Thay đổi vai trò ghi lại
người thực hiện và vai trò trước/sau trong audit log.

## Cấp quản trị viên hệ thống đầu tiên

Sau khi áp dụng V7, chủ sở hữu hàm trong PostgreSQL có thể chạy
`SELECT bootstrap_system_admin('<UUID tài khoản>'::uuid);` cho một tài khoản
đang hoạt động đã xác minh email. Thao tác khởi tạo chỉ thành công khi chưa có
quyền `SYSTEM_ADMIN` nào được cấp. Tài khoản phải đăng nhập lại để nhận quyền.
