# Kiểm thử SQL tham chiếu đã duyệt

Ngày UTC: 2026-10-25T14:29:04.946840+00:00. Kết quả: **69/69 đạt**.

Engine: PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit

Database cô lập, chỉ dữ liệu giả; container không có mạng, cổng công khai hay volume dự án.
Không chạy SQL lên database đang dùng. Các ca cạnh tranh dùng hai kết nối PostgreSQL độc lập.

Bộ kiểm thử chứng minh những ràng buộc/giao dịch được liệt kê dưới đây; chưa kiểm thử API, giao diện, SMTP, S3, kiểm tra nội dung file, SRS hoặc worker thực tế.

Các hàm trong `review_checks` chỉ là fixture, không phải API sản phẩm. SHA-256 của SQL: `10f2047d317fb10d8f17eb6d531090a4dc3f73dd9fdd3378d569a04dc5ca949b`.

| Kiểm tra | Kết quả |
|---|---|
| schema: 62 tables, 4 reporting views | PASS |
| identity: case-insensitive email uniqueness | PASS |
| identity: immutable email | PASS |
| identity: first name max 30 characters | PASS |
| identity: reject plaintext password | PASS |
| identity: every user requires a role at commit | PASS |
| identity: independent multiple roles accepted | PASS |
| identity: protect last active system admin | PASS |
| identity: security change revokes sessions and increments version | PASS |
| identity: session deadline cannot slide | PASS |
| identity: sixth login replaces oldest session | PASS |
| identity: OTP cannot cross purpose | PASS |
| identity: OTP cannot cross owner | PASS |
| identity: locked user cannot reset by OTP | PASS |
| content: Published revision is immutable | PASS |
| content: Published source snapshot is retained | PASS |
| content: pointer cannot refer to another item | PASS |
| content: archive stops selection and retains history | PASS |
| content: only one open draft branch | PASS |
| questions: exactly one correct choice | PASS |
| questions: two correct choices rejected | PASS |
| questions: semantic context cannot change in place | PASS |
| questions: TOEIC Part 2 accepts exactly three options | PASS |
| questions: TOEIC Part 2 rejects a fourth option | PASS |
| groups: incomplete TOEIC Part 3 group rejected at commit | PASS |
| attempts: owner authorization | PASS |
| attempts: choice must belong to own snapshot | PASS |
| attempts: sealed snapshot is immutable | PASS |
| attempts: snapshot options retained after projection edit | PASS |
| attempts: clear answer increments version | PASS |
| attempts: same start request returns same ID | PASS |
| attempts: request payload mismatch is rejected | PASS |
| attempts: sixth open attempt rejected | PASS |
| attempts: blank submitted answer counts as wrong | PASS |
| attempts: correct answer scores one | PASS |
| attempts: answer cannot change after submit | PASS |
| attempts: result remains immutable | PASS |
| attempts: deadline auto-grades before accepting late answer | PASS |
| mistakes: blank result creates OPEN record | PASS |
| media: reject unverified avatar in profile | PASS |
| media: verified metadata requires positive dimensions | PASS |
| media: cannot use another user avatar | PASS |
| media: confirmed object cannot change bytes | PASS |
| media: cannot archive current avatar | PASS |
| media: avatar size hard cap 2 MiB | PASS |
| storage: ledger keeps reserved then committed bytes | PASS |
| storage: allocation cannot be deleted to bypass quota | PASS |
| goals: one target, score step five and daily limits | PASS |
| study: one active timeline across tabs | PASS |
| study: cannot credit more time than elapsed | PASS |
| study: context cannot refer to another learner attempt | PASS |
| notifications: internal target cannot be external URL | PASS |
| notifications: event dedupe is scoped to user and key | PASS |
| notifications: one delivery per event and channel | PASS |
| notifications: encrypted payload must name external key | PASS |
| import: file cap 5 MiB | PASS |
| import: row cap 5000 items | PASS |
| import: unique row ledger prevents duplicate retry | PASS |
| settings: unknown keys cannot become secret store | PASS |
| settings: BCrypt cost cannot drop below ten | PASS |
| audit: append-only record | PASS |
| concurrency: OTP consumed exactly once | PASS |
| concurrency: refresh old token rotates once | PASS |
| concurrency: two tabs cannot overwrite same answer version | PASS |
| concurrency: repeated submit is stable and idempotent | PASS |
| concurrency: quota reservation serializes, one of two 60-byte claims fits 100 | PASS |
| concurrency: quota counter equals accepted reservations | PASS |
| concurrency: cannot disable both final system admins | PASS |
| concurrency: at least one active system admin remains | PASS |
