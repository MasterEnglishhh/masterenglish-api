# Fixture baseline tích hợp M08

Ba migration V11/V16/V17 sao nguyên văn từ commit `f1fc00330b76d30e7f1eb6b4011027bb52b27fe7` (`origin/dev` local ngày 2026-11-27). Không nằm trong đường Flyway production; test AC26 kết hợp fixture này với `classpath:db/migration`.

Người dùng xác nhận migration M08 chưa áp dụng trên DB dùng chung. M08 đổi số V15–V21 thành V18–V24, giữ nguyên nội dung SQL và thứ tự phụ thuộc. Không dùng `outOfOrder`, `repair`, `baseline` hoặc merge `origin/dev` để làm test xanh. DB local từng chạy version M08 cũ không dùng lại cho chuỗi mới; test tạo PostgreSQL 17 riêng qua Testcontainers.

`MediaDatabaseMigrationTests.upgradeIntegratedV17PreservesHistoryAndChecksums` kiểm migration V17 tới V24, giữ checksum/script cũ, published revision/snapshot/media refs và dữ liệu notification. Kết quả chỉ chứng minh fixture local, không xác nhận DB tích hợp hoặc production đã nâng cấp.
