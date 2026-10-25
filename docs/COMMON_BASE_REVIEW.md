# Rà soát phần backend dùng chung

Ngày 25/10/2026. Phạm vi: nền dùng chung của API theo baseline database đã chốt.
Không áp dụng migration hoặc triển khai chức năng auth/media trong lượt này.

## Đã có trong code

| Phần | Trạng thái |
|---|---|
| Entity nền | BaseEntity: UUID, created_at/updated_at; entity cụ thể giữ @Version nếu cần |
| Phân trang | PageResponse, mặc định 20/tối đa 100, sort allowlist, ID tie-break |
| Lỗi MVC | ProblemDetail có code/traceId/errors, không trả rejected value hoặc thông tin lỗi nội bộ |
| Request ID | X-Request-Id được kiểm tra, đưa vào response và MDC trong request |
| Security view | AuthenticatedUser, CurrentUser, SecurityUtils; common không phụ thuộc entity Identity |
| Enum dùng chung | RoleCode trong common.security, ErrorCode trong common.error |
| Ranh giới module | ApplicationModule, NamedInterface và ArchitectureTests |
| Validation groups | OnCreate/OnUpdate có sẵn; dùng theo nhu cầu của từng DTO |

## Bổ sung trong lượt rà soát này

### UTF-8 byte length

`common.validation.Utf8ByteLength` kiểm tra số byte sau mã hóa UTF-8, có min/max,
để DTO áp dụng ngân sách byte chính xác. Không cắt chuỗi để làm vừa giới hạn.
Ví dụ mật khẩu dùng `@Utf8ByteLength(max = 72)`; yêu cầu tối thiểu 15 ký tự và
kiểm tra mật khẩu thuộc policy Identity, cần ghép riêng khi tạo DTO.

Validator chấp nhận NULL để việc bắt buộc do @NotNull/@NotBlank của DTO quyết
định. Không thay thế giới hạn kích thước HTTP body. Đã kiểm thử biên ASCII,
tiếng Việt và ký tự ngoài BMP (emoji).

### IANA timezone

`common.validation.IanaTimeZone` kiểm tra tên timezone trong tzdb của JVM.
Chấp nhận Asia/Ho_Chi_Minh và UTC; không nhận chuỗi offset +07:00 hay tên sai.
NULL hợp lệ với trường tùy chọn; trường bắt buộc cần annotation phù hợp.
Các module profile/learning/notifications có thể dùng cùng validator.

### Clock

`common.time.TimeConfiguration` cung cấp bean `Clock.systemUTC()`. Service tính
OTP deadline, thời gian bài hoặc lịch nhắc nên nhận Clock qua constructor và dùng
`Instant.now(clock)`, để test bằng Clock.fixed mà không chờ thời gian thật.
`common::time` được công khai; module dùng nó phải khai báo allowedDependencies.

Lượt này chưa thay toàn bộ Instant.now() hiện có hoặc đổi lifecycle callback JPA.
JVM/Gradle và Hibernate đã có cấu hình UTC trước đó; Clock bổ sung khả năng kiểm
soát thời gian ở service, không phải cấu hình múi giờ trình bày của người dùng.

### Lỗi conflict và payload

ErrorCode có thêm CONFLICT (409) và PAYLOAD_TOO_LARGE (413). Optimistic locking
exception được Spring dịch trả ProblemDetail 409 với thông báo an toàn, thay vì
500 chung. Khi CAS ở service thất bại, dùng BusinessException(CONFLICT, message
an toàn) theo hợp đồng. Không chuyển mọi lỗi ràng buộc DB thành conflict vì có
những lỗi là sai sót mapping hoặc chương trình.

Mã 413 được ánh xạ khi MVC xử lý lỗi tương ứng; **chưa cưỡng chế** ngân sách JSON
1 MiB, depth 16 hay byte upload chỉ bằng việc thêm ErrorCode.

## Phần còn thiếu và nơi triển khai

| Phần | Hiện trạng | Bước phù hợp |
|---|---|---|
| Security filter errors | MVC advice không xử lý toàn bộ lỗi trước controller; chưa có entry point/access denied handler trả cùng ProblemDetail | Làm cùng SecurityFilterChain/JWT/cookie/CSRF của Identity, tránh cấu hình hai lần |
| Request/JSON budgets | Hạn 1 MiB, depth 16, unknown fields mới có trong contract; chưa thực thi bằng cấu hình/filter/mapper | Trước khi mở các endpoint nhận JSON; giới hạn cả request chunked, không chỉ Content-Length |
| Password và token | Chưa có encoder bean/policy, token generation/HMAC/rotation service | Identity; secret ở môi trường, không đưa vào constants hoặc common tùy tiện |
| Rate limit/idempotency | Có thiết kế DB và ErrorCode, chưa có service sử dụng giao dịch thực | Identity/Practice/Media theo bảng sở hữu; giữ cùng giao thức khóa đã chốt |
| OpenAPI | Có dependency springdoc; chưa có metadata API, tài liệu lỗi/phân trang hoặc scheme auth theo cơ chế đã triển khai | Cấu hình ứng dụng; scheme auth sau khi hoàn thiện auth thực |
| HTTP/CORS theo môi trường | Chưa có cấu hình riêng; FE hiện dùng proxy | Kiểm tra đường gọi thực khi tích hợp FE; cấu hình origin cụ thể nếu cần gọi khác origin |
| Upload/mail/worker | Có dependency và thiết kế; chưa có storage/mail adapter, byte validation, claim/retry hoặc job chạy thật | Các module Media/Notifications và cấu hình hạ tầng tương ứng |
| Audit nghiệp vụ | Có schema đề xuất, chưa có service ghi actor/before-after an toàn cùng transaction | Administration và các module phát sinh sự kiện; tách với request logging |

Những phần này không được coi là hoàn thành vì có enum, dependency hoặc SQL
tham chiếu. Common chỉ chứa hợp đồng/kỹ thuật dùng qua nhiều module; UserStatus,
OTP purpose, content lifecycle và grading policy ở module sở hữu nghiệp vụ.

## Kiểm tra đã chạy

Lệnh: `gradlew.bat test --tests com.masterenglish.api.ArchitectureTests --tests com.masterenglish.api.common.*`.

21 tests đạt: module boundaries, lỗi MVC (gồm conflict), phân trang, security view
và validation. Đây là kiểm thử JVM/MVC, không kết nối database dự án, không kiểm
thử mail/S3, JWT, request-size enforcement hoặc migration mới.

Nội dung commit gợi ý cho thay đổi lượt này:

```text
feat(common): add shared validation, clock and conflict handling
```
