# Format Java và kiểm tra build

Ngày thiết lập: 26/10/2026. Repo: masterenglish-api.

## Công cụ và phạm vi

- Java 21, Gradle Wrapper của dự án.
- Spotless 8.10.4, google-java-format 1.28.0 với kiểu AOSP (4 khoảng trắng).
- Chỉ format `src/main/java/**/*.java` và `src/test/java/**/*.java`.
- Tự chuẩn hóa format/import, bỏ import không dùng, khoảng trắng cuối dòng và
  thêm newline cuối file. `.gitattributes` quy định Java/Gradle dùng LF, `.bat`
  dùng CRLF; `.editorconfig` ghi lại quy ước cho editor hỗ trợ.

`.env`, SQL thiết kế, tài liệu sinh tự động và file Java ngoài source set không
nằm trong target của formatter. Không thêm git hook tự sửa file khi commit/push.

## Lệnh hằng ngày

Chạy từ thư mục repo API, không cần cài Gradle riêng:

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat check bootJar
```

- `spotlessApply` sửa format các file thuộc target.
- `spotlessCheck` chỉ kiểm tra; lệch quy ước sẽ trả lỗi build.
- `check` bao gồm Spotless và tests; tests tích hợp cần Docker/Testcontainers.
- `bootJar` đóng gói ứng dụng; CI nên dùng `check bootJar` để kiểm tra trước khi
  nhận artifact. Chỉ chạy `test bootJar` không thay thế việc kiểm tra format.

Lần đầu Gradle tải plugin/formatter từ các repository đã cấu hình, cần mạng.
Phiên bản được ghim trong build.gradle để các máy và CI dùng cùng formatter.

## Kết quả lần thiết lập

Đã chạy `spotlessApply`, sau đó `spotlessCheck check bootJar` thành công.
59 file Java được xét; 48 file có thay đổi do formatter, các file còn lại đã
đúng format. 94 tests đạt, gồm Tomcat/HTTP thật và PostgreSQL tạm; JAR đóng gói
thành công. Không áp dụng migration hoặc thay đổi database local.

Bản sao source trước format và bản diff riêng được lưu trong thư mục build bị
Git bỏ qua. `build/format-review/result.json` chỉ ra thư mục backup và
`format-only.patch` của lượt thực hiện. Patch này dùng so sánh **trước/sau format**,
khác với `git diff` có thể bao gồm các thay đổi chức năng chưa commit từ trước.
Bản sao trong build là hỗ trợ review cục bộ, không thay thế một commit lâu dài.

## Gợi ý chia commit của working tree hiện tại

Không stage/commit tự động trong lượt thiết lập. Một số file Security đã thay đổi
trước khi chạy formatter; chúng hiện chứa cả thay đổi chức năng và format.
Nếu cần tách tuyệt đối theo hunk, đối chiếu patch riêng ở trên.

### 1. Security và cấu hình local

```text
feat(security): configure CSRF and backend local environment
```

Chọn các thay đổi chức năng theo nhóm:

- `src/main/java/com/masterenglish/api/config/`.
- `common/security/SecurityProblemHandler.java`, `common/web/`.
- `common/error/RequestIdFilter.java` và `GlobalExceptionHandler.java` với
  ROLE_INFRASTRUCTURE đã sửa lỗi startup Modulith/Tomcat.
- `application.properties`, `application-local.properties`.
- Tests SecurityProblemHandler, common/web, config và MasterenglishApiApplicationTests.
- `.env.example`, `compose.local.yaml`, `README.md`, các báo cáo base/security local.

Các đường dẫn common/tests ở trên thuộc source tree của repo API. `.env` thật
được Git ignore và không nằm trong commit.

### 2. Công cụ và thay đổi format còn lại

```text
chore(build): enforce Java formatting with Spotless
```

Chọn `build.gradle`, `.editorconfig`, `.gitattributes`, tài liệu này và các diff
Java chỉ còn chuẩn hóa style/import. Với file trùng nhóm 1, dùng diff theo hunk
nếu muốn tách style khỏi nội dung chức năng.

Việc xóa bản nháp `UserRole.java` ngoài source set đã có trước lượt format và
được giữ nguyên theo working tree của người dùng; không phải formatter xóa file.
Nếu muốn lưu riêng việc dọn bản nháp, dùng commit:

```text
chore: remove obsolete UserRole draft
```
