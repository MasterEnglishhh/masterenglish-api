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
| CSRF/CORS | Cookie/header CSRF cho SPA, bootstrap public, allowlist origin và cookie Secure theo môi trường |
| Backend local environment | API có .env/.env.example và Compose local; local profile đọc file của backend |

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

Mã 413 được ánh xạ khi MVC xử lý lỗi tương ứng. Bước JSON tiếp theo đã được người
dùng tạo theo hướng dẫn: JsonRequestSizeFilter kiểm tra header và byte thực tế,
đọc tối đa 1 MiB + 1 byte; chỉ chuyển request <=1 MiB tới Spring MVC. Các request
JSON và subtype +json đều áp dụng; multipart giữ đường kiểm tra riêng của Media.
Response 413 có code/traceId/errors và X-Request-Id tương ứng.

application.properties đã bật fail-on-unknown-properties và max-nesting-depth=16.
Kiểm thử dùng mapper auto-configured từ file cấu hình thật: DTO hợp lệ đọc được,
field ngoài DTO bị 400, 16 container lồng nhau hợp lệ và cấp 17 bị 400. Bộ lọc
được thiết kế cho blocking body reads của Spring MVC; không cung cấp non-blocking
ReadListener. Đây chưa phải giới hạn byte hoặc validation cho upload ảnh/audio.

## Phần còn thiếu và nơi triển khai

| Phần | Hiện trạng | Bước phù hợp |
|---|---|---|
| Security filter errors | Handler đã gắn vào SecurityFilterChain; có test 401/403, CSRF và method security qua chain | Duy trì khi nối JWT/đăng nhập và thu hồi phiên |
| Request/JSON budgets | Đã có filter 1 MiB, depth 16 và từ chối field ngoài DTO; có test biên, UTF-8 và luồng không Content-Length | Giữ test cùng DTO thực; xác minh thêm qua HTTP/proxy khi tích hợp môi trường triển khai |
| Password và token | Đã có PasswordConfiguration ở api.config cung cấp BCrypt cost 12; chưa có DTO/service kiểm tra toàn bộ policy hoặc token generation/HMAC/rotation | Identity dùng encoder chung; secret ở môi trường |
| Rate limit/idempotency | Có thiết kế DB và ErrorCode, chưa có service sử dụng giao dịch thực | Identity/Practice/Media theo bảng sở hữu; giữ cùng giao thức khóa đã chốt |
| OpenAPI | Có dependency springdoc; chưa có metadata API, tài liệu lỗi/phân trang hoặc scheme auth theo cơ chế đã triển khai | Cấu hình ứng dụng; scheme auth sau khi hoàn thiện auth thực |
| HTTP/CORS theo môi trường | WebSecurityProperties đọc origin/cookie policy từ môi trường; cấu hình local ở API .env, FE tiếp tục dùng proxy | Kiểm thử browser/proxy/HTTPS khi deploy; origin public cấu hình theo môi trường |
| Upload/mail/worker | Có dependency và thiết kế; chưa có storage/mail adapter, byte validation, claim/retry hoặc job chạy thật | Các module Media/Notifications và cấu hình hạ tầng tương ứng |
| Audit nghiệp vụ | Có schema đề xuất, chưa có service ghi actor/before-after an toàn cùng transaction | Administration và các module phát sinh sự kiện; tách với request logging |

Những phần này không được coi là hoàn thành vì có enum, dependency hoặc SQL
tham chiếu. Common chỉ chứa hợp đồng/kỹ thuật dùng qua nhiều module; UserStatus,
OTP purpose, content lifecycle và grading policy ở module sở hữu nghiệp vụ.

## Cấu hình CSRF và môi trường đã hoàn thành

Chi tiết ở [LOCAL_SECURITY_SETUP.md](LOCAL_SECURITY_SETUP.md). Có endpoint GET
`/api/v1/auth/csrf`, cookie XSRF-TOKEN, header X-XSRF-TOKEN, SameSite=Lax,
host-only, Path=/ và Secure theo môi trường. Token JSON lấy từ DeferredCsrfToken
gốc để khớp plain header của cấu hình SPA. Cookie CSRF không phải cookie đăng nhập.
CSRF giữ bật cho request ghi, kể cả trước khi user đăng nhập; session HTTP stateless.

Ứng dụng local đọc `masterenglish-api/.env`, có `.env.example` trong repo, không
còn đọc file Infra để chạy. Compose local ở backend giữ tên project/volume cũ.
Chưa chạy up/down hoặc áp dụng migration lên database của người dùng.

## Kiểm tra đã chạy

Lệnh đầy đủ hiện tại: `gradlew.bat spotlessCheck check bootJar`. Kết quả mới nhất: **94 tests đạt**, JAR
đóng gói thành công. Có 66 tests nền/config, 20 tests database và 8 tests startup/HTTP thật.
Test database dùng PostgreSQL tạm của Testcontainers; không dùng DB dự án.

Lệnh tập trung: `gradlew.bat test --tests com.masterenglish.api.ArchitectureTests --tests com.masterenglish.api.common.* --tests com.masterenglish.api.config.*`.

Nhóm nền/config: module boundaries, lỗi MVC (gồm conflict), phân trang, security view,
validation, 13 ca JSON và 3 ca PasswordConfiguration. Các ca JSON gồm đúng/vượt ngưỡng, header báo quá lớn,
không có hoặc báo thấp Content-Length, byte UTF-8, +json, bỏ qua multipart,
DTO/unknown fields, depth 16/17 và 413 khớp traceId. Ba ca password kiểm tra bean
BCrypt cost 12/matches, salt ngẫu nhiên và encode tại/vượt 72 byte UTF-8. Yêu cầu
tối thiểu 15 ký tự và validation ở endpoint đăng ký/đổi/reset/đăng nhập cần được
triển khai cùng DTO/service; có encoder bean không thay thế các kiểm tra này.

Bốn ca SecurityProblemHandler kiểm tra contract 401/403, trace ID dự phòng và
không ghi đè response đã committed. Có thêm 21 ca filter chain/CSRF/CORS/role,
4 ca cấu hình env/cookie/origin. Principal trong các test chain là fixture, không
phải JWT thực. Các test database cũ kiểm tra schema đang có và proposal cũ, không
chứng minh schema 62 bảng đã được migrate vào ứng dụng.

Nhóm nền dùng JVM/MockMvc; nhóm servlet bổ sung khởi động Tomcat thật và gửi
HTTP/1.1 bằng JDK HttpClient, gồm body streamed không có độ dài biết trước.
Các test dùng PostgreSQL tạm, không kết nối DB dự án. Chưa kiểm thử mail/S3,
JWT, HTTPS/proxy deploy hoặc migration mới.

## Khắc phục lifecycle filter với Modulith — 26/10/2026

Log local chỉ ra lỗi requestIdFilter khi Tomcat gọi phương thức final
GenericFilterBean.init trên CGLIB proxy, dẫn tới logger null. Test startup trước
đây dùng môi trường MOCK nên chưa gọi lifecycle servlet thật; số 87 tests ở lượt
trước không chứng minh Tomcat đã khởi động được.

Đã tái hiện cùng lỗi bằng RANDOM_PORT trước khi sửa. Đánh dấu RequestIdFilter,
JsonRequestSizeFilter và GlobalExceptionHandler là ROLE_INFRASTRUCTURE để
ModuleObservabilityBeanPostProcessor bỏ qua các bean kỹ thuật này. Module nghiệp
vụ vẫn được observability theo dõi; không đổi cấu hình AOP toàn ứng dụng.

MasterenglishApiApplicationTests hiện chạy Tomcat thật cùng PostgreSQL tạm và
có 8 ca: startup, bean lifecycle không bị proxy, health/request ID, cookie CSRF
SameSite trên HTTP, 413 với body biết/không biết độ dài, CSRF tách biệt xác thực,
và lỗi MVC 400. Tất cả đã đạt. JAR được build lại và đã xác minh không chứa .env
private hoặc controller probe của test.

## Format Java — 26/10/2026

Đã thiết lập EditorConfig/Git attributes và Spotless 8.10.4, formatter
google-java-format 1.28.0/AOSP. Đã format 48/59 file Java thuộc src, lưu bản sao
trước format và diff riêng trong build/format-review. Spotless check, toàn bộ
94 tests và bootJar đều đạt sau format. Xem [FORMATTING.md](FORMATTING.md) để
chạy lệnh và chia commit; các file có thay đổi chức năng từ trước không tự động
được phân loại thành diff chỉ format.

Nội dung commit gợi ý cho thay đổi lượt này:

```text
feat(common): add shared validation, clock and conflict handling
```
