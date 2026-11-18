# AGENTS.md — MasterEnglish API

Nếu có [hướng dẫn chung ở workspace](../AGENTS.md), đọc cùng tệp này. Repo API dùng Java 21, Gradle Wrapper, Spring Boot, Spring Modulith và PostgreSQL 17.

## Ranh giới backend

- Mã Java nằm dưới `src/main/java/com/masterenglish/api/`. Trong `modules/`, `identity` sở hữu tài khoản và phiên, `content` sở hữu nội dung và phiên bản, `vocabulary` sở hữu danh mục từ vựng và thẻ, `learning` sở hữu tiến độ và SRS, `admin` sở hữu nghiệp vụ quản trị. URL công khai có thể khác module sở hữu, như API review nằm dưới `/api/v1/vocabulary` nhưng do `learning` xử lý.
- Soát `package-info.java`, `@ApplicationModule(allowedDependencies = ...)` và `@NamedInterface` trước khi thêm import qua module. Gọi hợp đồng công khai được phép. Giữ repository, entity và các implementation nội bộ trong module sở hữu. Chạy `ArchitectureTests` khi thay đổi dependency giữa module.
- Controller nhận request, xác thực đầu vào theo pattern hiện có và trả DTO. Service thực thi nghiệp vụ và quản lý transaction. Repository phụ trách truy vấn/persistence. Dùng `common/` cho hạ tầng hoặc hợp đồng thực sự dùng chung, giữ nghiệp vụ trong module.
- Với API công khai, soát HTTP status, JSON, `ProblemDetail`, phân trang và consumer ở web. Đọc hợp đồng [Identity](docs/identity/CONTRACT.md), [Admin](docs/admin/CONTRACT.md) hoặc [Vocabulary](docs/vocabulary/CONTRACT.md) theo phạm vi. Đối chiếu controller và test vì tài liệu cũng có endpoint dự kiến.
- Khi sửa auth, vai trò hoặc phiên, lần theo `SecurityConfiguration`, quyền ở đối tượng đích và các ca 401/403. Bảo toàn cookie HttpOnly, CSRF và vòng đời refresh token. Dùng provider giả/local cho mail và storage trong test.
- Với review SRS hoặc thao tác có retry, kiểm tra tính idempotent và transaction theo invariant của module.

## Môi trường và vòng kiểm tra

Chạy từ gốc repo với JDK 21. Trên PowerShell dùng `.\gradlew.bat`, trên Linux dùng `./gradlew`. Xem [README](README.md) và [LOCAL_SETUP.md](LOCAL_SETUP.md) để chạy profile `local` với cấu hình mẫu. Test tích hợp dùng PostgreSQL 17 riêng qua Testcontainers, vì vậy kiểm tra Docker bằng `docker info`. Task `test` cấp sẵn các secret giả cần cho xác thực.

| Tầng | Lệnh |
| --- | --- |
| Trong | `.\gradlew.bat test --tests '*UserServiceTests'` hoặc lọc một method bằng `--tests '*UserServiceTests.findProfileById_userExists_returnsProfile'`. |
| Giữa | Lọc package bằng `.\gradlew.bat test --tests 'com.masterenglish.api.modules.identity.*'` hoặc `--tests 'com.masterenglish.api.common.*'`. Chọn thêm test ở package gốc khi thay đổi chạm luồng xuyên module, ví dụ `--tests '*VocabularyApiIntegrationTests'`. Đổi phụ thuộc module thì chạy `.\gradlew.bat test --tests '*ArchitectureTests'`, có gọi `modules.verify()`. |
| Ngoài | `.\gradlew.bat check bootJar`. `check` đã gồm Spotless và test. Khi sửa công cụ phát âm, chạy `python -B -m unittest discover -s scripts/pronunciation -p 'test_*.py' -v`. Với Dockerfile hoặc đóng gói image, chạy thêm `docker build .`. |

[Backend CI](.github/workflows/ci.yml) chạy trên Linux với Docker, test Python, `./gradlew spotlessCheck check bootJar --no-daemon` và Docker build. Khi cần `spotlessApply`, xem [hướng dẫn format](docs/FORMATTING.md) và soát diff trước và sau vì task sửa cả các file thuộc target. Lỗi ghi file khóa trong `GRADLE_USER_HOME` là lỗi môi trường. Dùng cơ chế cấp quyền của môi trường nếu cần chạy lại, hoặc ghi rõ phần kiểm tra bị chặn.

## Flyway và schema

- Schema thực thi nằm ở `src/main/resources/db/migration/`. Giữ nguyên migration đã merge vào `main` hoặc đã áp dụng trên môi trường dùng chung. Tạo `V<n>__<mô_tả>.sql` với version tiếp theo. Nếu rebase bị trùng version, đổi số migration mới của nhánh khi migration đó chưa được áp dụng trên môi trường dùng chung.
- Duy trì Hibernate `ddl-auto=validate`. Dùng PostgreSQL test riêng để kiểm tra ràng buộc và đường nâng cấp từ dữ liệu cũ. Tham khảo `VocabularyDatabaseMigrationTests`, `VocabularyUpgradeTests`, [hợp đồng schema](docs/database/SCHEMA_CONTRACTS.md) và [từ điển dữ liệu](docs/database/DATA_DICTIONARY.md).
- SQL thiết kế trong `docs/database/` nằm ngoài Flyway. Dùng [quy trình kiểm tra schema thiết kế](docs/database/checks/README.md) khi sửa phần đó.

## Phát hành

- Khi push lên `main`, job `verify` build image và job `publish` đẩy đúng image đó lên GHCR. Giữ commit SHA và image digest trong kết quả CI, chỉ cấp `packages: write` cho job publish.
- [Publish backend image](.github/workflows/publish-image.yml) là luồng chạy tay trên `main`. Chỉ kích hoạt khi được giao.

## Git và bàn giao

Giữ nguyên thay đổi có sẵn. Ghi lệnh đã chạy và phần kiểm tra bị chặn. Trao đổi, báo cáo, PR và commit bằng tiếng Việt có dấu. Giữ nguyên định danh kỹ thuật. Chỉ commit khi người dùng yêu cầu rõ ràng, với tiêu đề dạng `fix(identity): giữ trạng thái phiên hợp lệ` và phạm vi tùy chọn. Dùng danh tính đã cấu hình và giữ commit/PR không có ghi công Claude. Trước commit, soát diff đã stage, thông điệp và danh tính. Sau commit, chạy `git show -s --format=fuller HEAD`.
