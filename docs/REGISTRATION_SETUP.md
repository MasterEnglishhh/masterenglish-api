# Đăng ký và xác minh OTP

Luồng đã triển khai: nhập thông tin → nhận OTP email → xác minh → chuyển về đăng nhập. Xác minh không tự tạo phiên hoặc cấp JWT. Đăng nhập sau đó vẫn dùng cookie HttpOnly của luồng auth hiện có.

## Chạy thử local

Backend đọc `.env` riêng của repo API với profile `local`. File này được Git bỏ qua; `.env.example` chỉ là mẫu, không chứa khóa thật.

```powershell
docker compose -f compose.local.yaml --profile mail up -d postgres mailpit
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

Frontend dùng `BACKEND_URL=http://127.0.0.1:8080` trong `.env.local`, chạy `npm run dev`, mở [đăng ký](http://localhost:3000/register). Lấy mã tại [hộp thư Mailpit local](http://127.0.0.1:8025). Mailpit chỉ nhận email trên máy, không chuyển email ra Internet. SMTP và giao diện hộp thư đều chỉ bind `127.0.0.1`.

Khi chạy JAR trực tiếp trên Windows, đặt JVM timezone UTC giống `bootRun`/test để PostgreSQL không nhận alias timezone của hệ điều hành:

```powershell
java '-Duser.timezone=UTC' -jar build/libs/app.jar --spring.profiles.active=local
```

## Biến môi trường

| Biến | Local | Bảo mật / khi gửi email thật |
| --- | --- | --- |
| `JWT_SECRET` | Khóa ngẫu nhiên riêng, tối thiểu 32 byte | Bí mật; không có khóa mặc định trong source |
| `OTP_HMAC_SECRET` | Khóa ngẫu nhiên khác JWT | Bí mật; HMAC ràng buộc purpose/challenge/mã |
| `RATE_LIMIT_SECRET` | Khóa ngẫu nhiên độc lập | Bí mật; HMAC email/IP trong các bucket giới hạn |
| `JWT_ISSUER` | `masterenglish-api` | Phải khớp giữa các instance |
| `JWT_ACCESS_TOKEN_DURATION` | `15m` | Phiên hiện có vẫn giới hạn access token tối đa 15 phút |
| `MAIL_ENABLED` | `true` với Mailpit | Chỉ bật khi đã có cấu hình gửi thật |
| `MAIL_FROM` | `no-reply@masterenglish.local` | Địa chỉ gửi được nhà cung cấp cho phép/xác minh |
| `SMTP_HOST` / `SMTP_PORT` | `127.0.0.1` / `1025` | Host/port do nhà cung cấp SMTP cấp |
| `SMTP_USERNAME` | Trống | Tài khoản SMTP, giữ trong env |
| `SMTP_PASSWORD` | Trống | Bí mật: app password, SMTP password hoặc SMTP API key |
| `SMTP_AUTH` | `false` | Thường `true` khi dùng nhà cung cấp thật |
| `SMTP_STARTTLS_ENABLE` / `SMTP_STARTTLS_REQUIRED` | `false` / `false` | Thường `true` / `true` với port 587 |
| `SMTP_SSL_ENABLE` | `false` | Bật cho SMTP implicit TLS, thường port 465; tắt STARTTLS tương ứng |
| `PASSWORD_BREACH_CHECK_ENABLED` | `true` | Mặc định kiểm tra HIBP; không đặt `false` khi phát hành production |

Tạo **ba khóa riêng**, mỗi khóa 64 byte ngẫu nhiên dạng hex; ghi trực tiếp vào `.env`, không dán giá trị vào chat/log/commit:

```powershell
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
```

JWT/OTP/rate-limit secret, mật khẩu SMTP và database chỉ nằm ở backend. Không thêm `NEXT_PUBLIC_*` cho các giá trị này. Giữ khóa ổn định giữa các lần khởi động và dùng cùng khóa cho các API instance trong cùng môi trường. Đổi JWT secret làm JWT cũ không còn hợp lệ; đổi OTP secret làm mã đang chờ không còn hợp lệ. Dùng khóa khác giữa local và production.

Production nhận biến từ `masterenglish-infra/compose.production.yaml` và `.env.prod` trên máy chủ; không bật profile `local`, không dùng Mailpit hoặc mật khẩu thử. Template `.env.prod.example` đã có các tên biến nhưng để trống bí mật. `MAIL_ENABLED=false` vẫn cho phép đăng nhập hiện có, nhưng API đăng ký/gửi lại trả 503 thay vì giả vờ đã gửi email. Không có thao tác deploy tự động trong thay đổi này.

## API

Các endpoint đều yêu cầu cặp cookie CSRF/header `X-XSRF-TOKEN`, kể cả khi chưa đăng nhập. Cả response và browser fetch dùng `no-store`.

| Endpoint | Body | Kết quả |
| --- | --- | --- |
| `POST /api/v1/auth/register` | `email`, `firstName`, `lastName`, `password` | 202: `registrationId`, `expiresAt`, `resendAfterSeconds`, thông báo chung |
| `POST /api/v1/auth/register/resend` | `registrationId`, `email` | 202 với hạn mã mới; ID lượt đăng ký giữ nguyên |
| `POST /api/v1/auth/register/verify` | `registrationId`, `email`, `otp` dạng chuỗi 6 số | 200: xác minh xong; không có cookie đăng nhập |

Không nhận role hoặc xác nhận mật khẩu từ client. Request có trường lạ bị từ chối. Đăng ký công khai chỉ cấp `LEARNER`. Email đã hoạt động/khóa/vô hiệu hóa nhận phản hồi chung; không ghi đè thông tin hoặc phát OTP để kích hoạt lại tài khoản đó.

## Chính sách và transaction

- Theo baseline Account đã duyệt: NFC, ít nhất 15 Unicode code point, tối đa 72 byte UTF-8, không trim/cắt mật khẩu hoặc ép nhóm ký tự. BCrypt cost 12. Đăng nhập cũng chuẩn hóa NFC để kiểm đúng hash đăng ký.
- Kiểm tra mật khẩu phổ biến cục bộ và HIBP Pwned Passwords. Chỉ gửi 5 ký tự đầu của SHA-1 kèm `Add-Padding: true`, không gửi mật khẩu hoặc toàn bộ hash. Kiểm tra từ server, có timeout và giới hạn kích thước response; dịch vụ kiểm tra lỗi thì trả 503, không lặng lẽ bỏ kiểm tra. SHA-1 chỉ dùng cho giao thức range, không dùng lưu mật khẩu.
- OTP CSPRNG 6 số, giữ số 0 đầu, TTL 5 phút, dùng một lần. DB chỉ lưu HMAC gắn `EMAIL_VERIFICATION` và challenge ID. Resend thay mã mới, vô hiệu mã cũ, không đổi profile/hash của lượt đăng ký hoặc kéo dài chu kỳ chờ 7 ngày.
- Gửi: cách nhau 60 giây, tối đa 5 lần/email/giờ, cộng chung bucket `OTP_SEND_EMAIL`; IP 300 lần/giờ. Hạn mức được giữ qua tạo yêu cầu mới/resend và qua lỗi gửi mail hoặc kiểm tra mật khẩu bị lộ. Vì quota được giữ chỗ trước các tác vụ mạng, một yêu cầu thất bại có thể vẫn phải chờ trước khi thử lại.
- Sai OTP: 5 lần/email/mục đích thì chờ 15 phút; tổng thử theo IP 300/15 phút. Không chuyển user sang LOCKED vì thử sai. Các phản hồi 429 có `Retry-After`. Controller lấy IP do Servlet cung cấp, không tự tin header `X-Forwarded-For`; proxy production phải giới hạn đúng nguồn được tin cậy.
- Khóa bucket theo thứ tự cố định rồi khóa user. Xác minh, consume và áp dụng **đúng** thông tin trong `registration_requests` là một transaction. Lỗi OTP được trả sau commit nên bộ đếm không bị rollback. Hai xác minh đồng thời chỉ một lần thành công.
- `users` chờ xác minh có credential/profile chính thức rỗng. Credential BCrypt đang chờ nằm ở request riêng. Khi hoàn tất/vô hiệu request, xóa hash đang chờ và HMAC. Đăng ký lại sau chu kỳ pending 7 ngày có thể dọn chu kỳ chưa từng hoạt động; không xóa tài khoản đã xác minh, không xóa các bucket giới hạn.
- SMTP gửi ngoài transaction, có timeout 5 giây; raw OTP chỉ tồn tại tạm trong bộ nhớ khi gửi, không có trong event/outbox/log/API. Lỗi SMTP làm vô hiệu đúng challenge vừa phát và giữ quota. Chưa triển khai queue/outbox email hoặc retry worker; SMTP không bảo đảm exactly-once khi máy chủ đã nhận nhưng mất phản hồi. Người dùng dùng resend theo hạn mức để yêu cầu mã mới.
- Frontend có nhập/dán 6 số, đếm ngược, lỗi mã sai/hết hạn, gửi lại có cooldown và chuyển về đăng nhập sau xác minh. Không lưu password/OTP/challenge vào localStorage/sessionStorage; tải lại trang cần bắt đầu lại, các giới hạn backend vẫn giữ nguyên.

## Kiểm tra

```powershell
.\gradlew.bat check bootJar
```

Test dùng PostgreSQL tạm và khóa fixture chỉ được cấp cho task `test`, không dùng database hoặc SMTP thật. Có kiểm tra pending → ACTIVE, không JWT trước login, sai/hết hạn/dùng lại mã, verify đồng thời, resend/cooldown/quota, credential binding, email đã hoạt động, lỗi gửi mail, CSRF và validation. Kiểm tra browser + SMTP local được thực hiện thêm với Mailpit.

Tham khảo: [baseline Account](database/ACCOUNT_BASELINE_REVIEWED.md), [Spring Boot email](https://docs.spring.io/spring-boot/reference/io/email.html), [Mailpit Docker](https://mailpit.axllent.org/docs/install/docker/), [HIBP Pwned Passwords](https://haveibeenpwned.com/API/v3#PwnedPasswords), [OWASP về mã dùng một lần](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).
