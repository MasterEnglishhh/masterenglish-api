# Backend .env, CORS và CSRF

Ngày 25/10/2026. Người dùng yêu cầu cấu hình môi trường ở backend và giữ Infra
cho triển khai. Lượt này thiết lập CSRF/CORS và cấu hình local; không áp dụng
migration, tạo tài khoản hoặc triển khai EC2.

## 1. Một nguồn cấu hình local ở API

`masterenglish-api/.env` là file private. `.env.example` là mẫu được commit.
`application-local.properties` đọc file này khi chạy profile `local`:

```properties
spring.config.import=file:${MASTERENGLISH_ENV_FILE:.env}[.properties]
```

Working directory mặc định là repo API. Nếu chạy từ nơi khác, đặt biến
`MASTERENGLISH_ENV_FILE` thành đường dẫn file `.env` của API. Giá trị có cú pháp
Java properties (`KEY=value`), không thêm `export` hoặc dấu nháy kiểu shell.
Spring hỗ trợ import file không có phần mở rộng bằng hint `[.properties]`.
[Tài liệu cấu hình ngoài của Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html).

Các thông tin database của môi trường cũ đã được chép sang backend `.env` nếu
file đích chưa tồn tại. Không in chúng vào log, không thay credential trong DB.
File Infra cũ không bị xóa; ứng dụng mới đọc đường dẫn backend ở trên.

| Biến | Mục đích |
|---|---|
| POSTGRES_HOST/PORT/DB/USER/PASSWORD | Kết nối PostgreSQL của backend local |
| SERVER_ADDRESS/PORT | Địa chỉ/port lắng nghe local |
| APP_SECURITY_ALLOWED_ORIGINS | Origin trình duyệt được phép gọi khác origin, cách nhau bằng dấu phẩy |
| APP_SECURITY_COOKIE_SECURE | Cookie CSRF chỉ gửi qua HTTPS khi true |

Compose local nằm tại `masterenglish-api/compose.local.yaml`, tự đọc `.env` bên
cạnh. Tên project/volume giữ nguyên để dùng lại database trước đây. Lượt cấu hình
này chỉ kiểm tra Compose, không chạy up/down, reset volume hay đổi dữ liệu.

## 2. Origin theo môi trường

Local mặc định:

```properties
APP_SECURITY_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000
APP_SECURITY_COOKIE_SECURE=false
```

Origin gồm scheme + host + port tùy chọn, không có path hoặc dấu `/` cuối.
Không cho wildcard, userinfo, query hay fragment. CORS cho phép credentials và
header Content-Type, Authorization, X-XSRF-TOKEN, X-Request-Id, Idempotency-Key,
If-Match. Các header X-Request-Id/ETag/Retry-After được expose cho frontend.

Ngoài profile local, mặc định cookie Secure=true và CORS không mở origin ngoài.
Khi deploy HTTPS, đặt origin public chính xác và giữ APP_SECURITY_COOKIE_SECURE=true;
truyền biến môi trường vào process/container bằng cấu hình deploy. Không cần
đóng gói `.env` private vào JAR. Cấu hình hiện tại hướng tới FE/BE cùng site hoặc
proxy `/api`; cookie SameSite=Lax không phải cấu hình cho mọi mô hình khác site.

Frontend hiện dùng Next.js rewrite `/api/*` tới BACKEND_URL. Browser gọi URL
tương đối `/api/...`, cookie không có Domain nên được giữ trên host mà browser
đang dùng. Dùng nhất quán localhost hoặc 127.0.0.1 trong một lượt trình duyệt.
Chưa sửa source frontend hoặc tạo màn hình đăng nhập trong lượt này.

## 3. Luồng CSRF

1. Client gọi `GET /api/v1/auth/csrf` với credentials và cache no-store.
2. Backend cấp cookie `XSRF-TOKEN`, Path=/, SameSite=Lax, host-only và Secure theo
   môi trường. Cookie CSRF cho JavaScript đọc được; đây không phải cookie đăng nhập.
3. Response JSON gồm `headerName: X-XSRF-TOKEN` và `token` tương ứng với cookie,
   có Cache-Control: no-store.
4. Với POST/PUT/PATCH/DELETE, client gửi cả cookie và header X-XSRF-TOKEN chứa
   token nhận được. Thiếu hoặc sai cặp token bị 403 trước khi vào controller.
5. Endpoint nghiệp vụ vẫn cần xác thực; có CSRF hợp lệ nhưng chưa đăng nhập thì
   bị 401. CSRF không gán role hoặc chứng minh user đã đăng nhập.

Ví dụ browser phía frontend:

```javascript
const csrfResponse = await fetch('/api/v1/auth/csrf', {
  credentials: 'include',
  cache: 'no-store',
});
if (!csrfResponse.ok) throw new Error('Không lấy được CSRF token.');
const { headerName, token } = await csrfResponse.json();

// Khi endpoint nghiệp vụ và đăng nhập đã được triển khai:
// fetch('/api/v1/your-write-endpoint', {
//   method: 'POST',
//   credentials: 'include',
//   headers: { 'Content-Type': 'application/json', [headerName]: token },
//   body: JSON.stringify(payload),
// });
```

Backend dùng hỗ trợ SPA của Spring Security 7.1. `.spa()` cấu hình handler cho
plain token ở header và XOR token ở request attribute. Bootstrap lấy token gốc
từ DeferredCsrfToken do CsrfFilter cung cấp để khớp cookie, không trả nhầm token
đã XOR rồi gửi nó vào plain header.
[Tài liệu CSRF cho SPA](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-integration-javascript-spa).

Khi triển khai đăng nhập/đăng xuất, cần giữ quy tắc xóa/đổi token và bootstrap lại
sau các thay đổi xác thực. Khi thêm JWT trong cookie, tiếp tục kiểm thử CSRF cho
các request ghi; không bỏ kiểm tra chỉ vì request đã có JWT. Các luồng auth này
chưa được tuyên bố hoàn thành trong lượt CSRF hiện tại.

## 4. Kiểm thử và giới hạn

SecurityConfigurationTests chạy filter chain Spring Security/MVC thực với
principal kiểm thử và endpoint probe chỉ có trong test profile. Có kiểm tra:

- Bootstrap public, raw token khớp cookie, không tạo JSESSIONID.
- POST/PUT/PATCH/DELETE thiếu token bị 403; đúng cookie/header và có principal
  được đi tiếp; sai header/thiếu cookie bị chặn.
- Token CSRF không thay thế xác thực; role độc lập và @PreAuthorize được thực thi.
- CORS preflight hợp lệ đi qua; origin ngoài allowlist bị chặn cả bootstrap.
- Cookie Secure theo môi trường, SameSite=Lax, Path=/, không Domain.
- Profile local đọc file backend env được chỉ định; mặc định khác local đóng CORS.

Các ca dùng MockMvc và thuộc tính Cookie của Servlet API. Mock response không
serialize đầy đủ mọi thuộc tính cookie thành chuỗi Set-Cookie; nhóm này kiểm tra
SameSite trên Cookie API. Có thêm nhóm khởi động Tomcat thật, xác minh Set-Cookie
trên HTTP, health/request ID, CSRF, MVC error và JSON có/không biết trước độ dài.
Kiểm thử browser/HTTPS/proxy và JWT thật tiếp tục cùng luồng đăng nhập/deploy.
Các test dùng PostgreSQL tạm, không kết nối database dự án.

## 5. Vai trò hạ tầng của filter và MVC advice

RequestIdFilter, JsonRequestSizeFilter và GlobalExceptionHandler có annotation
`@Role(BeanDefinition.ROLE_INFRASTRUCTURE)`. Modulith bỏ qua việc tạo proxy theo
dõi module cho các bean hạ tầng này; các module nghiệp vụ vẫn được theo dõi.

Nếu coi servlet filter là module entrypoint, CGLIB proxy có thể chạy phương thức
final `GenericFilterBean.init()` trên proxy có trạng thái chưa khởi tạo, gây
`logger` null khi Tomcat start. Đây là lỗi lifecycle đã xuất hiện trong log local,
được tái hiện bằng server thật trước khi sửa. Có trường hợp tương ứng trong
[issue của Spring Modulith](https://github.com/spring-projects/spring-modulith/issues/1271).

MasterenglishApiApplicationTests chạy RANDOM_PORT cùng Testcontainers để kiểm
tra startup này và request HTTP thật. Chỉ kiểm tra MockMvc/context giả không đủ
để xác nhận servlet container đã gọi thành công lifecycle của filter.
