# Rà soát base backend

> Báo cáo sửa base ở thời điểm trước lượt duyệt database cuối cùng. Thiết kế
> hiện hành: [database/APPROVED_BASELINE.md](database/APPROVED_BASELINE.md).
> Không hiểu các kiểm thử/schema cũ trong báo cáo này là code đã triển khai theo
> toàn bộ quyết định D01–D29/B01–B37.

Ngày 25/10/2026. Sửa base hiện có và chuẩn bị database MVP; AI để sau.

## Đã sửa

| Trước | Sau |
|---|---|
| Import com.smartevent, OrganizerProfile/RefreshToken chưa có, 7 lỗi compile | Bỏ tham chiếu dự án khác; User giữ các quan hệ cần thiết |
| Role UUID kế thừa BaseEntity | Role SMALLINT seed bằng Flyway |
| UserRole ID riêng, chưa unique user/role | Khóa ghép UserRoleId + MapsId + PK |
| Code nhiều role nhưng schema cũ một role | M01 7 bảng và principal nhiều authorities |
| Phone/avatar có trong code nhưng chưa có trong tài liệu | Giữ theo xác nhận mới: phone và avatar_media_id; FK media được thêm cùng M08 |
| Common phụ thuộc infrastructure principal | Common dùng AuthenticatedUser; principal ở identity |
| BaseEntity public setter ID/timestamps | Abstract superclass, getters, ID/timestamps do JPA quản lý |
| Soft delete chung cho User | ACTIVE/LOCKED/DISABLED theo lifecycle account |
| Success/error wrapper khác đặc tả | Success DTO trực tiếp; MVC dùng ProblemDetail + code/traceId/errors |
| Page content/totalElements/last | items/totalItems/hasNext |
| Sort tùy ý, silently clamp input sai | Allowlist, trả lỗi input sai, tie-break id nếu được phép |
| Package modules.identity chưa có Modulith metadata | Detection explicitly-annotated, common/identity, named interfaces |

UserRepository load roles bằng EntityGraph cho auth và query lower(email) khớp
biểu thức index. UserPrincipal dùng authorities bất biến, không giữ JPA entity.
Password không serialize từ principal/entity; controller vẫn phải dùng DTO.

Enum dùng chung: RoleCode ở common.security để các module dùng trong kiểm tra
quyền; ErrorCode ở common.error. UserStatus thuộc Identity, không đưa vào common.
Các enum riêng của nội dung, media hoặc lượt làm thuộc module sở hữu dữ liệu khi
được triển khai; không gom toàn bộ enum nghiệp vụ vào một package chung.

Các file gốc được sao lưu cục bộ ở `build/design-review/base-before-refactor/api`
trước khi sửa. Build bị Git bỏ qua; bản sao này không thay một commit/backup lâu dài.
Các controller/service trống được giữ để tiếp tục triển khai.

Trong lúc rà soát có thêm bản UserRole.java ngay thư mục gốc repo. File này nằm
ngoài source set của Gradle và được giữ nguyên. Entity đang dùng nằm trong
src/main/java/com/masterenglish/api/modules/identity/entity/UserRole.java.

## Hợp đồng và giới hạn hiện tại

- Nghiệp vụ `/api/v1`, response thành công là DTO, pagination theo đặc tả.
- MVC error `application/problem+json` với code, traceId, errors dạng array.
- X-Request-Id hợp lệ được giữ; input sai thay UUID; có MDC trong request.
- Không echo giá trị password sai, JSON lỗi hoặc exception message nội bộ.
- Danh tính đổi sang `roles` array theo quyết định nhiều role mới.
- Security đã có SecurityFilterChain, handler 401/403 và CSRF cookie/header cho
  SPA; cấu hình env local ở backend. Xem docs/LOCAL_SECURITY_SETUP.md. JWT và
  các luồng đăng nhập/thu hồi phiên tiếp tục triển khai trong Identity.
- Thay role/status/password tăng auth_version. Service auth phải thu hồi phiên/token
  cùng transaction, kiểm tra phiên/version; bảo vệ SYSTEM_ADMIN cuối ở quản trị.

## Database và xác minh

Xem `docs/database/BASELINE_v3.md` và SQL proposals. V2 là migration thực của M01.
36 bảng còn lại chỉ được tạo trong transaction test để kiểm tra SQL/constraint;
chưa được đưa vào Flyway runtime.

Chạy `.\gradlew.bat test bootJar`. Test tập trung vào PostgreSQL constraints, mapping
nhiều role, rotation, JSON contract, sort allowlist và module boundaries.
Chưa benchmark hoặc hoàn thiện nghiệp vụ đăng ký/đăng nhập/publish.
