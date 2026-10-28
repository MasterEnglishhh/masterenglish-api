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
Workflow `Backend CI` cũng build image sau khi các bước kiểm tra thành công.
Khi push `main`, workflow tự xuất bản đúng image vừa build lên GHCR như bên dưới.

Khi chạy container, cấu hình kết nối PostgreSQL qua `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`; cấu hình
`APP_SECURITY_ALLOWED_ORIGINS` và `APP_SECURITY_COOKIE_SECURE` theo môi trường.
Profile `local` dành cho chạy local và không được bật khi triển khai production.
Cấu hình Docker Compose production và quản lý biến môi trường sẽ nằm trong repo Infra.

## Xuất bản image lên GHCR

Sau mỗi push lên `main`, **Backend CI** tự kiểm tra format, chạy toàn bộ tests
(gồm Testcontainers), tạo JAR, build Docker image và xuất bản lên GHCR. Job
`publish` chỉ chạy nếu job `verify` thành công. Nó nhận nguyên image từ job
kiểm tra trong cùng workflow run qua artifact, không build lại image.
Artifact trung chuyển giữ 1 ngày; image đã xuất bản nằm trên GHCR độc lập.

Pull request và lần chạy thủ công `Backend CI` chỉ kiểm tra/build, không xuất
bản. Không cần bấm workflow Publish sau mỗi push nữa. Workflow **Publish backend
image** thủ công vẫn giữ để xuất bản lại khi cần; workflow đó chạy lại tests và
build, nên lần build mới có thể cho digest khác dù cùng commit.

Image có dạng `ghcr.io/masterenglishhh/masterenglish-api:sha-<commit SHA đầy đủ>`.
Job xuất bản dùng `GITHUB_TOKEN` do GitHub cấp cùng quyền `packages: write`; job
kiểm tra chỉ có `contents: read`. Không cần
tạo PAT hoặc thêm secret cho bước xuất bản này. Label source liên kết package với
repo API. Nếu tổ chức hạn chế tạo package hoặc package đã có nhưng chưa liên kết
repo, cần kiểm tra quyền tại GitHub Packages khi workflow báo lỗi quyền truy cập.

Trong trang Summary của lượt chạy thành công, lấy dòng **Deploy by digest** dạng
`ghcr.io/masterenglishhh/masterenglish-api@sha256:...` để xác định đúng image khi
triển khai. Tag theo commit giúp truy vết source; dùng digest để cố định nội dung
image, kể cả khi chạy lại workflow cho cùng commit. Sau khi **Backend CI** xanh,
lấy dòng **Deploy by digest** từ Summary của job Publish, rồi chọn image này ở
workflow **Deploy production** trong repo Infra. Production tiếp tục triển khai
bằng nút chạy thủ công, không tự cập nhật khi push code.

## Cloudflare R2

Backend có cấu hình S3 client/presigner cho bucket R2 private, bật bằng
`R2_ENABLED=true`. Mặc định tắt để local/CI không cần credential thật.
Các biến môi trường cần thiết khi bật:

| Biến | Nội dung |
| --- | --- |
| `R2_ENDPOINT` | HTTPS S3 endpoint từ Cloudflare, không thêm tên bucket vào path |
| `R2_REGION` | `auto` |
| `R2_BUCKET` | `masterenglish-media-production` |
| `R2_ACCESS_KEY_ID` | Access Key ID của token giới hạn đúng bucket |
| `R2_SECRET_ACCESS_KEY` | Secret Access Key; chỉ lưu trong cấu hình môi trường |

Nếu bật mà thiếu thông tin hoặc endpoint không phải HTTPS origin, ứng dụng
từ chối khởi động. Cấu hình không dùng default AWS credential chain/EC2 IAM role;
không cần IAM/OIDC AWS cho R2. Client dùng path-style, tắt chunked encoding và
giới hạn checksum về WHEN_REQUIRED cho khả năng tương thích S3 của R2. Khi khởi
động chỉ kiểm tra cấu hình và tạo client, không chứng minh credential kết nối được.

`R2SmokeCheck` là công cụ kiểm tra riêng trong JAR, không tự chạy khi khởi động
và không mở HTTP endpoint. Sau khi image mới cùng các biến R2 đã được chuyển lên
EC2 qua cấu hình Infra, chạy từ `/srv/masterenglish`:

```bash
docker compose --env-file .env.prod -f compose.production.yaml -f compose.https.yaml \
  run --rm --no-deps --entrypoint java api \
  -Dloader.main=com.masterenglish.api.config.R2SmokeCheck \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Lệnh tạo file text vài chục byte tại `_checks/<UUID>.txt`, tải lại, so sánh bytes
và xóa chính file này. Nó dùng cùng S3 client factory của ứng dụng, không chạy
Spring/Flyway hoặc kết nối database. Nếu lỗi sẽ trả exit code khác 0; nếu cleanup
lỗi, kiểm tra object `_checks/` trong bucket để xóa file thử còn lại. Lượt kiểm
tra sử dụng một số thao tác R2 theo mức tính phí của tài khoản.

Đây là nền tảng kết nối storage; API upload, phân quyền file, metadata, presigned
URL cho trình duyệt và CORS bucket chưa được triển khai. Không bật public access
cho bucket hoặc đưa credential vào frontend. Tests dùng credential giả và S3
giả lập; kiểm tra R2 thật chỉ thực hiện trên EC2 bằng lệnh rõ ràng ở trên.
