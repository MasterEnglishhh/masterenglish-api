# Kiến trúc modular monolith của backend

Spring Modulith quản lý bảy module nghiệp vụ Identity, Content, Vocabulary,
Learning, Practice, Grammar và Admin. Mỗi module sở hữu controller, DTO, nghiệp vụ và dữ liệu của mình.

```text
modules/<module>/
  controller/           HTTP mapping, xác thực đầu vào và quyền endpoint
  dto/request/          Payload đầu vào
  dto/response/         Payload đầu ra và kết quả của use case
  entity/               Mô hình lưu trữ và quy tắc trạng thái
  repository/           Interface @Repository kế thừa JpaRepository
  repository/fragment/  Interface cho thao tác lưu trữ đặc thù
  service/              Hợp đồng nghiệp vụ nội bộ và policy
  service/impl/         Điều phối use case, transaction và event listener

common/
  api/                  DTO dùng chung, gồm dữ liệu thẻ từ vựng
  events/               Hợp đồng event và cổng phát event nội bộ

config/                Cấu hình Spring, Jackson, bảo mật và kết nối R2

infrastructure/
  events/               Adapter phát Spring ApplicationEvent
  persistence/          Triển khai fragment JPA cho refresh có khóa và detach
  readmodel/            Ánh xạ các view đọc dùng chung
  runner/               Khởi tạo dữ liệu local, --content-import và kiểm tra R2
  security/             JWT, cookie và adapter bảo mật
  storage/              Adapter lưu trữ audio trên R2
```

Controller khai báo đường dẫn chung ở cấp lớp. Service khai báo
`@Transactional` theo method và dùng `@Slf4j` cho log nghiệp vụ. Riêng đăng ký
và đặt lại mật khẩu dùng `TransactionTemplate` vì lưu dữ liệu và gửi email
có thứ tự commit riêng. Mã nghiệp vụ truy cập PostgreSQL qua Spring Data
JPA repository. Hibernate dùng driver PostgreSQL JDBC để kết nối database.
Truy vấn đặc thù của PostgreSQL như `ON CONFLICT` và
`FOR SHARE` được khai báo bằng `@Query`; câu lệnh ghi có `@Modifying`.
Spring Data nạp hai fragment dùng `EntityManager` để refresh có khóa
và detach từ `src/main/resources/META-INF/spring.factories`.

Content sở hữu endpoint phát âm tại URL đã công bố trong nhóm
`/api/v1/vocabulary/pronunciations`. Các frontend tiếp tục dùng cùng hợp đồng
HTTP và cùng JSON payload.

## Giao tiếp giữa module

Admin sở hữu API quản trị, điều phối use case và lưu feature flags. Identity
sở hữu tài khoản, vai trò, phiên và mục tiêu học. Content sở hữu vòng đời nội
dung, nguồn, audit và dữ liệu media. Learning sở hữu lịch sử ôn và tiến độ,
đọc thẻ qua hợp đồng sự kiện của Vocabulary.

Thao tác quản trị đồng bộ và audit cùng tham gia một transaction. Controller và
service thực thi phân quyền nghiệp vụ. Swagger UI và OpenAPI được bật với
profile `local`.

Các module dùng kiểu dữ liệu trong `common/events` để trao đổi với nhau.
Mỗi listener gọi service của chính module mình để xử lý event.

- Content phát `RevisionPublished` sau khi cập nhật trạng thái trong
  transaction xuất bản. Vocabulary cập nhật bảng đọc trong chính transaction
  đó. Nếu xử lý projection thất bại, lượt xuất bản rollback.
- Các yêu cầu cần kết quả ngay, như lấy snapshot, đọc thẻ hoặc khóa người
  dùng, dùng event có phản hồi với một listener đồng bộ. Lời gọi nhận
  kết quả hoặc lỗi trước khi tiếp tục. `EventReply` kiểm tra mỗi yêu cầu
  có đúng một phản hồi.
- Dùng `@ApplicationModuleListener` cho tác vụ hậu xử lý được phép chạy sau
  commit. Các listener đang phục vụ kiểm tra quyền, khóa dữ liệu và publish
  projection sử dụng `@EventListener` đồng bộ.
  [Tài liệu event của Spring Modulith](https://docs.spring.io/spring-modulith/reference/events.html)

## Dữ liệu đọc liên module

Các truy vấn phân trang và đếm dùng các view `catalog_visible_items`,
`catalog_visible_senses` và `catalog_visible_deck_senses`. Flyway V8.1 tạo
view trên PostgreSQL hiện tại. Các view phản ánh ngay trạng thái archive,
thu hồi và phiên bản đang xuất bản, kể cả khi phải lọc trước phân trang.

## Nâng cấp từ dev đã áp dụng V9

Nếu lịch sử Flyway có V9 và thiếu V8.1, diễn tập trên DB cô lập từ bản sao
trước khi cập nhật môi trường. Với V8.1 là migration duy nhất còn thiếu, chạy
Flyway một lần với `outOfOrder=true`. Sau đó bỏ override, chạy validate và kiểm
tra ba view catalog cùng dữ liệu feature flags. Với DB theo lịch sử main hoặc
DB mới, chạy Flyway theo thứ tự mặc định.

## Kiểm tra

`ArchitectureTests` kiểm tra module đã khai báo và ranh giới Spring Modulith.
`LayerArchitectureTests` kiểm tra cây package, cấm phụ thuộc Java trực tiếp
giữa module nghiệp vụ và xác nhận các quy tắc controller, DTO, repository
và transaction.

```text
gradlew.bat spotlessCheck check bootJar --no-daemon
```

Bật Docker để Testcontainers khởi tạo PostgreSQL.
