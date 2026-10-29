# Kế hoạch backend cho đăng nhập MasterEnglish

**Ngày:** 29/10/2026  
**Phạm vi:** Đăng nhập bằng email và mật khẩu, đọc người dùng hiện tại, làm mới phiên, đăng xuất và bảo vệ API bằng phiên.  
**Kết quả mong muốn:** Frontend có thể đăng nhập thật qua `/api/*`, nhận thông tin và quyền người dùng, duy trì phiên khi tải lại trang, đồng thời xử lý sai mật khẩu, giới hạn lượt thử, hết hạn và thu hồi phiên.

Kế hoạch này đi cùng `masterenglish-web/docs/superpowers/plans/2026-10-29-login-design.md`. Đăng ký và xác minh OTP là luồng riêng; để thử đăng nhập đầu cuối trước khi luồng đó hoàn tất, dùng tài khoản kiểm thử được tạo an toàn trong môi trường thử nghiệm.

## 1. Hiện trạng đã kiểm tra

- API hiện có `GET /api/v1/auth/csrf`, bộ lọc CSRF cho SPA, CORS cho origin đã cấu hình, `PasswordEncoder` BCrypt, `User`, `UserPrincipal`, `UserRepository` và định dạng lỗi Problem Detail.
- Chưa có controller/service cho login, refresh, logout hoặc `GET /api/v1/users/me`. `UserController` hiện rỗng; mọi request ngoài health và CSRF đều yêu cầu xác thực nhưng chưa có bước dựng principal từ cookie.
- Migration V2 đang có `display_name`, trạng thái mặc định `ACTIVE`, `auth_sessions.device_label` và bảng reset cũ. Database baseline đã duyệt dùng `first_name`/`last_name`, trạng thái chờ xác minh, `email_verified_at`, `locked_until`, rate limit và phiên/token theo hợp đồng mới. Không lấy SQL tham chiếu `docs/database/proposals/approved-baseline.sql` chạy trực tiếp như migration.
- Baseline tài khoản đã duyệt quy định access JWT tối đa 15 phút, phiên tối đa 7 ngày, tối đa 5 phiên, refresh token ngẫu nhiên được băm và xoay sau mỗi lần dùng. Khi tạo phiên thứ sáu, thu hồi **phiên được tạo lâu nhất** theo baseline database; câu chữ “ít hoạt động nhất” ở SRS cần được đồng bộ lại trong tài liệu trước khi nghiệm thu.

## 2. Hợp đồng API cần chốt cùng frontend

| Endpoint | Request | Kết quả thành công |
| --- | --- | --- |
| `GET /api/v1/auth/csrf` | Không cần đăng nhập | Giữ endpoint hiện có; trả token dùng cho header `X-XSRF-TOKEN` |
| `POST /api/v1/auth/login` | JSON `email`, `password`; cookie và CSRF header | `200`, đặt access/refresh cookie, trả thông tin người dùng công khai và role hoặc cho frontend gọi `/users/me` ngay sau đó |
| `GET /api/v1/users/me` | Access cookie | `200` với ID, email, tên hiển thị/họ tên, role và thông tin profile được phép công khai; `401` nếu không còn phiên |
| `POST /api/v1/auth/refresh` | Refresh cookie và CSRF header | `200`, xoay refresh token, đặt lại cookie; không kéo dài hạn tuyệt đối của phiên |
| `POST /api/v1/auth/logout` | Cookie và CSRF header | `204`, thu hồi phiên hiện tại và xóa cookie dù client đang giữ token cũ |

- DTO thành công trả trực tiếp, không bọc `{ success, data }`. Lỗi theo `application/problem+json` với `code`, `traceId`, `errors` như API hiện có.
- Chốt tên cookie, Path, Max-Age, payload `me` và mã lỗi trước khi frontend tích hợp; ghi ví dụ request/response trong tài liệu API. Cookie xác thực có `HttpOnly`, `Secure` ở production, `SameSite=Lax`, host-only. Local dùng chính sách `Secure` theo profile hiện có.
- Sai email/mật khẩu hoặc tài khoản không đủ điều kiện đăng nhập trả thông báo công khai thống nhất, tránh tiết lộ email tồn tại. `429` kèm `Retry-After` cho trường hợp bị giới hạn; `403` cho CSRF sai/thiếu; `401` cho phiên không hợp lệ; `5xx` không lộ bí mật. Quy tắc xử lý tài khoản chưa xác minh/đã khóa được ghi rõ trong hợp đồng lỗi.
- Frontend đang đi qua Next.js rewrite `/api/*`; kiểm tra cookie và CSRF trên cùng hostname mà trình duyệt sử dụng, đặc biệt không trộn `localhost` với `127.0.0.1` trong local.

## 3. Thiết kế bên trong module Identity

```text
modules/identity/controller/      AuthController, UserController, DTO request/response
modules/identity/service/         Login, refresh, logout và đọc hồ sơ hiện tại
modules/identity/entity/          User, AuthSession, RefreshToken, rate limit
modules/identity/repository/      Truy vấn user, phiên, token và bucket có khóa phù hợp
modules/identity/security/        Xác thực JWT từ cookie và dựng UserPrincipal
config/                           Cấu hình security, cookie, JWT và secret
common/error/                     Mã lỗi công khai dùng chung
```

- **Đăng nhập:** chuẩn hóa email theo chính sách đã duyệt; kiểm tra rate limit; so khớp BCrypt; chỉ cho tài khoản `ACTIVE` đã xác minh. Đường xử lý email không tồn tại cần tránh tạo chênh lệch thời gian dễ nhận thấy. Khi thành công tạo session và refresh token trong một transaction, bảo đảm giới hạn 5 phiên và đặt lại bộ đếm lỗi tương ứng.
- **Phiên:** session có hạn cố định từ lúc đăng nhập, tối đa 7 ngày; access JWT chứa định danh user/session và `auth_version`, hạn tối đa 15 phút và không vượt hạn session. Mỗi request cần kiểm tra chữ ký/hạn JWT, trạng thái user, `auth_version` và session chưa bị thu hồi để thay đổi bảo mật có hiệu lực ngay.
- **Refresh:** token opaque entropy cao, chỉ lưu SHA-256 trong DB. Trong transaction khóa theo thứ tự user → session → token, thu hồi token cũ rồi tạo token mới; một phiên chỉ có một token chưa thu hồi. Quy định rõ cách trả lỗi khi hai request refresh hợp lệ chạy đồng thời; không coi mọi lần đua là dấu hiệu bị đánh cắp.
- **Đăng xuất:** thu hồi session hiện tại, khiến cả access/refresh cũ vô hiệu; xóa cookie trên response. Không để logout phụ thuộc vào việc access JWT còn hạn nếu refresh cookie còn đủ để định danh phiên hợp lệ theo hợp đồng đã chốt.
- **Giới hạn thử:** theo baseline, 5 lần sai trong 15 phút theo email thì chờ 15 phút; giới hạn IP khởi điểm 300 lượt/15 phút. Dùng bucket/event và HMAC định danh để email chưa tồn tại cũng được tính; chỉ lấy IP từ proxy tin cậy. Lần thử sai phải được ghi bền dù endpoint trả lỗi; request đã bị chặn không kéo dài hạn chờ.
- **Bí mật và log:** khóa ký JWT, HMAC và cấu hình cookie lấy từ môi trường/secret manager; không ghi mật khẩu, hash, JWT, refresh token hoặc cookie vào response lỗi, log hay audit. Chỉ ghi sự kiện và mã truy vết cần thiết.

## 4. Trình tự triển khai

1. **Đối chiếu database thật:** đọc Flyway history và schema của từng môi trường dự định triển khai. Lập migration mới theo `docs/database/MIGRATION_PLAN.md`; giữ nguyên V1/V2 nếu đã áp dụng, dùng expand/backfill/validate/contract. Đồng bộ `User`/`UserPrincipal` với baseline đã duyệt. Không giả định dữ liệu ACTIVE cũ đã xác minh email.
2. **Chốt hợp đồng API:** thống nhất DTO, mã lỗi, cookie và chính sách refresh với frontend; bổ sung tài liệu endpoint và ví dụ Problem Detail. Cập nhật chênh lệch quy tắc chọn phiên bị thu hồi giữa SRS và baseline.
3. **Làm session và token:** entity/repository, giao dịch tạo phiên, giới hạn 5 phiên, JWT, refresh rotation, thu hồi và cấu hình khóa. Thêm cách dựng principal từ access cookie vào Spring Security; giữ CSRF cho mọi request ghi.
4. **Làm login và me:** kiểm tra mật khẩu/trạng thái/rate limit; trả phiên và dữ liệu công khai; áp dụng role thực tế từ DB. Không mở quyền học dựa riêng vào dữ liệu frontend gửi lên.
5. **Làm refresh và logout:** đặt/xóa cookie đúng thuộc tính, xử lý hết hạn và request đồng thời; kiểm tra CSRF mới sau thay đổi trạng thái xác thực nếu Spring xoay token.
6. **Tích hợp web:** chạy qua `/api/*`, thử đăng nhập bằng tài khoản test, tải lại trang, xem `/users/me`, refresh, đăng xuất và quay lại route hợp lệ.

## 5. Kiểm thử và tiêu chí hoàn thành

- Unit/integration test cho sai và đúng mật khẩu, email không tồn tại, chưa xác minh, LOCKED/DISABLED, role và dữ liệu trả về; phản hồi công khai không làm lộ tài khoản.
- Test PostgreSQL cho migration từ database trống và bản sao schema V2; kiểm tra ràng buộc, giao dịch, 5 phiên và thu hồi phiên cũ. Không dùng dữ liệu production trong log test.
- Test refresh tuần tự, hai refresh đồng thời, token cũ dùng lại, session hết hạn/đã thu hồi, đổi `auth_version`, đăng xuất và việc tải lại trang vẫn nhận user đúng.
- Test CSRF trên login/refresh/logout: thiếu hoặc sai cookie/header bị từ chối; cookie có đúng `HttpOnly`, `Secure` theo môi trường, `SameSite` và Path. Test `401`/`403`/`429`, `Retry-After` và `traceId`.
- Chạy `./gradlew.bat test spotlessCheck` trên Windows và kiểm tra trực tiếp browser với frontend. Chỉ coi đăng nhập hoàn thành khi người dùng thật có thể vào phiên, làm mới phiên và đăng xuất ổn định; không dựa vào việc endpoint trả `200` đơn lẻ.

## 6. Phụ thuộc và giới hạn phạm vi

- Đăng ký và xác minh OTP vẫn cần triển khai để người dùng mới tự tạo tài khoản. Kế hoạch này tập trung vào đăng nhập và vòng đời phiên.
- Việc nâng schema thật phụ thuộc Flyway history và dữ liệu từng môi trường; tài liệu này không xác nhận migration đã chạy hay dữ liệu đã được chuyển.
- OAuth Google/Apple không nằm trong hợp đồng API hiện có; bổ sung thành công việc riêng sau khi thống nhất luồng, cấu hình nhà cung cấp và liên kết tài khoản.

## Tài liệu căn cứ

- `D:/MasterEnglish/docs/EnglishMaster_SRS_SDD_v5_0.md`: M01.02, API và mục 6.4.
- `docs/database/APPROVED_BASELINE.md`, `ACCOUNT_BASELINE_REVIEWED.md`, `SCHEMA_CONTRACTS.md`, `MIGRATION_PLAN.md`: quyết định dữ liệu và giao dịch đã duyệt.
- `docs/LOCAL_SECURITY_SETUP.md`: CSRF và cách frontend gọi API qua rewrite.
