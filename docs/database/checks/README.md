# Kiểm thử SQL thiết kế trên PostgreSQL riêng

Đây là kiểm thử tài liệu thiết kế, không phải migration ứng dụng. Script không
nhận connection string và không chạy vào database dự án. Cần Docker Desktop
đang chạy và Python 3.10+ (chỉ thư viện chuẩn).

## Môi trường có rào chắn

Driver chỉ chấp nhận container tên `me-schema-20261025-01a16da4`, có nhãn sở hữu
đúng bên dưới, network `none`, không port binding và không host bind mount.
`--reset` chỉ drop/recreate `approved_review` **trong container đã kiểm tra này**.
Không dùng tên, volume, container hoặc database của Compose dự án.

Ví dụ tạo lại môi trường kiểm thử từ thư mục repo API (PowerShell):

```powershell
docker run --detach --name me-schema-20261025-01a16da4 `
  --label com.masterenglish.schema-review=01a16da4-503d-7143-bd11-8cac51f6110d `
  --network none --memory 768m --cpus 1 --pids-limit 128 --shm-size 128m `
  --tmpfs /var/lib/postgresql/data:rw,nosuid,size=512m `
  --env POSTGRES_PASSWORD=schema-review-only --env POSTGRES_DB=approved_review postgres:17
docker exec me-schema-20261025-01a16da4 pg_isready -U postgres -d approved_review
```

Chờ đến khi `pg_isready` báo nhận kết nối; đây là credential giả chỉ dùng trong
container không có mạng, không đưa vào `.env` dự án. Nếu tên đã tồn tại, kiểm tra
nhãn/cấu hình trước, không xóa container lạ để nhường tên.

## Sinh và kiểm tra

```powershell
python -B docs/database/tools/build_approved_schema.py
python -B docs/database/checks/verify_approved_schema.py --reset
```

Nếu dùng Python bundled của Codex, thay `python` bằng đường dẫn Python được
`load_workspace_dependencies` trả về; không cần cài psycopg/pytest. Dữ liệu trong
fixtures.sql là dữ liệu giả; hash có dạng BCrypt được tạo có chủ đích không dùng
được để đăng nhập. Schema `review_checks` không nằm trong SQL bàn giao ứng dụng.

Các ca thường chạy trong transaction rollback; ca cạnh tranh dùng hai kết nối
thật và commit. Vì vậy mỗi lượt kiểm tra đầy đủ dùng `--reset` để fixture sạch.
Kết quả và fingerprint SQL được ghi vào `SCHEMA_VALIDATION.md/.json`. Generator
không kết nối database, sinh SQL/dictionary/ERD từ cùng một mô hình.

SQL tham chiếu có function/trigger cho một số giao thức transaction. Những hàm
`review_checks.*` chỉ là fixture hỗ trợ kiểm thử, không được đưa vào backend.
Ca idempotent start dùng fixture biểu diễn giao thức đã đặc tả; chưa chứng minh
API start/resume đã hoạt động. Các kiểm thử API/FE, file thật, SMTP, thuật toán
SRS, scheduler và migration dữ liệu thật cần thực hiện khi viết module tương ứng.

## Dọn sau khi thử

Đối chiếu tên/nhãn đúng trước khi dừng và xóa container riêng này. Dữ liệu test
ở tmpfs bị hủy theo container, không có volume dự án cần xóa. Không dùng lệnh
prune toàn Docker và không thao tác container PostgreSQL của Compose dự án.
