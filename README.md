# MasterEnglish API

Backend Java 21, Spring Boot 4.1.2, Gradle và PostgreSQL 17.
Database baseline hiện hành: [APPROVED_BASELINE.md](docs/database/APPROVED_BASELINE.md).

## Chạy local trên Windows

Working directory là thư mục `masterenglish-api`. Backend đọc cấu hình local từ
**`.env` của chính repo API** qua profile `local`. Mẫu cấu hình ở [.env.example](.env.example).
File `.env` thật đã được Git bỏ qua; không đưa credential vào source hoặc commit.

Trong IDE, chọn Java 21, active profile `local` và working directory là repo API.
Nếu dùng PowerShell:

```powershell
docker compose -f compose.local.yaml up -d postgres
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

Compose local hiện nằm trong backend. Project `masterenglish-local` và volume
`postgres_local_data` giữ tên của cấu hình local trước đây để dùng lại database.
Không cần repo Infra để đọc cấu hình ứng dụng hoặc chạy database local nữa.
Các thao tác triển khai EC2 được chuẩn bị riêng trong repo Infra.

Với database đã có, giữ đúng username/password hiện tại; thay giá trị `.env`
không tự đổi password trong PostgreSQL. `.env.example` chỉ là mẫu cho môi trường mới.

Health: `http://127.0.0.1:8080/api/actuator/health`.
CSRF bootstrap: `http://127.0.0.1:8080/api/v1/auth/csrf`.

## Cấu hình và kết nối frontend

[LOCAL_SECURITY_SETUP.md](docs/LOCAL_SECURITY_SETUP.md) mô tả `.env`, origin,
cookie CSRF, header và luồng gọi qua proxy Next.js. Frontend tiếp tục dùng
`BACKEND_URL=http://127.0.0.1:8080` trong môi trường riêng của frontend.

CSRF đã hoạt động; các endpoint nghiệp vụ còn lại yêu cầu xác thực. Cơ chế phát/
kiểm JWT, đăng nhập và thu hồi phiên sẽ triển khai trong Identity; CSRF token
không phải access token.

## Kiểm thử và đóng gói

```powershell
.\gradlew.bat check bootJar
```

`check` kiểm tra format Java bằng Spotless và chạy test. Khi cần tự động format:

```powershell
.\gradlew.bat spotlessApply
```

Chỉ kiểm tra format, không sửa file: `.\gradlew.bat spotlessCheck`.
Quy ước và gợi ý chia commit: [FORMATTING.md](docs/FORMATTING.md).

Test tích hợp dùng PostgreSQL tạm của Testcontainers, cần Docker đang chạy.
Smoke test khởi động Tomcat trên cổng ngẫu nhiên và gửi request HTTP thật để kiểm
tra lifecycle filter, health, cookie/CSRF, lỗi MVC và giới hạn JSON.
Không dùng database local của người phát triển. SQL thiết kế mới vẫn nằm ngoài
Flyway; hướng dẫn/test riêng ở [docs/database/checks](docs/database/checks/README.md).

## Đóng gói Docker image

Gradle tạo executable JAR tại `build/libs/app.jar`. Dockerfile dùng JAR này cùng
Java 21 JRE; ứng dụng trong container chạy bằng user `app` (UID 10001).
Build JAR và chạy test trước khi tạo image, từ thư mục repo API:

```powershell
.\gradlew.bat spotlessCheck check bootJar
docker build --tag masterenglish-api:local .
```

Docker cần đang chạy. `.dockerignore` chỉ cho phép JAR và các file cấu hình Docker
đi vào build context; `.env`, Git history, source và tài liệu không được gửi.
CI cũng build image sau khi các bước kiểm tra thành công. Bước này mới kiểm tra
đóng gói image; image chưa được đẩy lên registry hay triển khai lên EC2.

Khi chạy container, cấu hình kết nối PostgreSQL qua `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`; cấu hình
`APP_SECURITY_ALLOWED_ORIGINS` và `APP_SECURITY_COOKIE_SECURE` theo môi trường.
Profile `local` dành cho chạy local và không được bật khi triển khai production.
Cấu hình Docker Compose production và quản lý biến môi trường sẽ nằm trong repo Infra.
