# Hợp đồng API và dữ liệu: Thông báo và lời nhắc học tập (M10)

Hợp đồng kỹ thuật cho module `notifications`. Ngày ban hành: 19/11/2026.

Trạng thái (nhánh `feat/m10-notification-complete` / PR #16):

- Đã triển khai đầy đủ **M10.01–M10.11**:
  - **M10.01–M10.04:** feed + lọc `type`/`unreadOnly`, đếm chưa đọc, đánh dấu đã đọc 1/tất cả, kênh nhận (`inAppEnabled`, `emailEnabled`, `contentNotificationsEnabled`), giờ nhắc/múi giờ/quiet hours.
  - **M10.05:** scheduler nhắc thẻ đến hạn `REVIEW_DUE`.
  - **M10.06:** scheduler nhắc mục tiêu hằng ngày `DAILY_GOAL` (đối chiếu `learning_goals.daily_minutes` và `study_sessions` thực tế theo múi giờ/ngày địa phương).
  - **M10.07:** thông báo nội dung mới `CONTENT_NEW` qua Spring Modulith `event_publication` + `@ApplicationModuleListener` khi công bố thủ công lần đầu một `DECK` mới (opt-in `contentNotificationsEnabled`, chỉ `ACTIVE` verified `LEARNER`, loại trừ admin).
  - **M10.08–M10.11:** worker gửi email qua outbox, theo dõi delivery, quản trị `reminder_rules` + `audit_logs`, và lịch sử nhắc Learner & Admin.
- Ngoài phạm vi M10: chuyển email OTP/bảo mật sang outbox, browser push, bảng `system_settings`, bảng `study_schedules`.

## 1. Dữ liệu

Flyway `V11__create_notifications.sql` tạo bốn bảng lấy nguyên văn từ
`docs/database/proposals/approved-baseline.sql`: `notification_preferences`, `reminder_rules`,
`notifications`, `notification_deliveries`. Bốn cột OTP của `notification_deliveries`
(`otp_challenge_id`, `encrypted_payload`, `encryption_key_id`, `sensitive_expires_at`) được giữ
theo baseline nhưng entity chưa ánh xạ, vì email OTP vẫn gửi trực tiếp.

Flyway `V16__add_content_notifications_and_daily_goal_rule.sql` bổ sung cột
`content_notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE` trên `notification_preferences` và
seed quy tắc `DAILY_GOAL`. Flyway `V17__create_study_sessions.sql` tạo bảng `study_sessions`
(module `learning`) theo chuẩn `approved-baseline.sql`.

Seed `reminder_rules`:

| code | enabled | minimum_gap_minutes |
|---|---|---|
| `REVIEW_DUE` | `TRUE` | `720` |
| `DAILY_GOAL` | `TRUE` | `720` |

**Lệch có chủ đích so với baseline.** Baseline đặt default `minimum_gap_minutes = 1440`. Seed dùng
720 vì scheduler chạy 15 phút/lần và được gửi bù trong cùng ngày địa phương. Với khoảng cách 24 giờ,
một lần gửi bù lúc 20:14 sẽ chặn lần nhắc 20:00 hôm sau tới 20:14, rồi 20:15 hôm sau nữa, và giờ
nhắc trôi dần mỗi ngày. Dedupe theo ngày (mục 6) vẫn bảo đảm tối đa một nhắc mỗi ngày; 720 phút chỉ
chặn trường hợp đổi múi giờ làm phát sinh hai "ngày" trong vòng nửa ngày.

## 2. Quyền và quy ước chung

- Mọi endpoint dưới `/api/v1/notifications` yêu cầu đăng nhập (cookie phiên như các module khác) và
  vai trò `LEARNER`. Chưa đăng nhập: `401`. Thiếu vai trò: `403`.
- `POST`/`PUT` cần header CSRF theo luồng hiện có; thiếu CSRF: `403`.
- Phản hồi thành công là DTO trực tiếp, danh sách dùng `PageResponse`. Lỗi là `ProblemDetail`
  (RFC 7807).
- Người học chỉ thấy và sửa dữ liệu của mình. Truy cập thông báo không thuộc feed của mình trả
  `404`, không trả `403`.

### Điều kiện feed

Feed, số chưa đọc, đánh dấu một và đánh dấu tất cả dùng **cùng một điều kiện**
(`NotificationRepository.FEED`):

- `notifications.user_id` = người gọi;
- `visibility = 'USER'` (bản ghi `INTERNAL` dành cho mail bảo mật, không bao giờ vào feed);
- có một dòng `notification_deliveries` với `channel = 'IN_APP'`.

Thông báo chỉ có kênh `EMAIL` (người học tắt in-app) không xuất hiện trong feed.

## 3. Endpoint

| Mã | Method và path | Kết quả |
|---|---|---|
| M10.01 | `GET /api/v1/notifications?type=&unreadOnly=&page=0&size=20` | `200 PageResponse<NotificationResponse>` |
| M10.01 | `GET /api/v1/notifications/unread-count` | `200 {"count": 3}` |
| M10.02 | `POST /api/v1/notifications/{id}/read` | `204` |
| M10.02 | `POST /api/v1/notifications/read-all` | `204` |
| M10.03–04 | `GET /api/v1/notifications/preferences` | `200 NotificationPreferencesResponse` |
| M10.03–04 | `PUT /api/v1/notifications/preferences` | `200 NotificationPreferencesResponse` |
| M10.11 | `GET /api/v1/notifications/reminders/history` | `200 PageResponse<ReminderHistoryResponse>` |
| M10.09 | `GET /api/v1/admin/notifications/deliveries` (`SYSTEM_ADMIN`) | `200 PageResponse<AdminNotificationDeliveryDto>` |
| M10.10 | `GET /api/v1/admin/notifications/rules` (`SYSTEM_ADMIN`) | `200 List<ReminderRuleDto>` |
| M10.10 | `GET /api/v1/admin/notifications/rules/{ruleId}` (`SYSTEM_ADMIN`) | `200 ReminderRuleDto` |
| M10.10 | `PUT /api/v1/admin/notifications/rules/{ruleId}` (`SYSTEM_ADMIN`) | `200 ReminderRuleDto` (+ `audit_logs`) |
| M10.11 | `GET /api/v1/admin/notifications/history` (`SYSTEM_ADMIN`) | `200 PageResponse<AdminReminderHistoryDto>` |

### 3.1. Feed

- Sắp xếp `created_at DESC, id DESC`; không nhận tham số sort.
- `page` mặc định 0, `size` mặc định 20, tối đa 100. Giá trị sai: `400`.

```json
{
  "items": [
    {
      "id": "0b6f…",
      "type": "REVIEW_DUE",
      "title": "Bạn có 12 thẻ cần ôn hôm nay",
      "body": "12 thẻ Flashcard đã đến hạn ôn tập. Ôn ngay để giữ nhịp ghi nhớ.",
      "targetUrl": "/learn/review",
      "readAt": null,
      "createdAt": "2026-11-19T13:00:04Z"
    }
  ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1, "hasNext": false
}
```

`readAt` là `null` khi chưa đọc. `targetUrl` luôn là đường dẫn nội bộ bắt đầu bằng `/` (CHECK
`notifications_c4`) hoặc `null`.

### 3.2. Đánh dấu đã đọc

- `POST /{id}/read` idempotent: gọi lại giữ mốc `readAt` đầu tiên và vẫn trả `204`.
- `404` khi `id` không tồn tại, thuộc người khác, là `INTERNAL` hoặc không có kênh `IN_APP`.
- `POST /read-all` chỉ cập nhật thông báo chưa đọc trong feed của người gọi, trả `204` kể cả khi
  không có gì để cập nhật. Web tự đặt số chưa đọc về 0 sau khi nhận `204`.

### 3.3. Cài đặt nhắc

```json
{
  "inAppEnabled": true,
  "emailEnabled": false,
  "contentNotificationsEnabled": false,
  "dailyReminderTime": "20:00",
  "timezone": "Asia/Ho_Chi_Minh",
  "quietHoursStart": "22:30",
  "quietHoursEnd": "07:00",
  "updatedAt": "2026-11-19T09:00:00Z"
}
```

- `GET` khi chưa lưu lần nào trả mặc định và **không tạo bản ghi**: `inAppEnabled=true`,
  `emailEnabled=notification.learning-email-default` (mặc định `false`),
  `contentNotificationsEnabled=false`,
  `timezone=notification.default-timezone` (mặc định `Asia/Ho_Chi_Minh`), các giờ `null`,
  `updatedAt=null`.
- `PUT` thay toàn bộ cài đặt (gửi đủ trường bao gồm `contentNotificationsEnabled`; trường giờ có thể `null`) và trả bản đã lưu.
- `dailyReminderTime = null` nghĩa là chưa đặt lịch: hệ thống không tự nhắc `REVIEW_DUE` và `DAILY_GOAL`.
- Quiet hours `[start, end)`; `start > end` nghĩa là khoảng vắt qua nửa đêm.

Kiểm tra đầu vào (lỗi trả `400 ProblemDetail`):

| Trường | Quy tắc |
|---|---|
| `inAppEnabled`, `emailEnabled`, `contentNotificationsEnabled` | Bắt buộc, boolean |
| `timezone` | Bắt buộc, ≤ 60 ký tự, tên múi giờ IANA chuẩn tương thích PostgreSQL `pg_timezone_names` (hỗ trợ các backward alias tương đương lịch sử như `Asia/Saigon` → `Asia/Ho_Chi_Minh`, `US/Eastern` → `America/New_York`; từ chối offset như `+07:00`, `UTC+07:00`, `SystemV/*` và các chuỗi POSIX `EST5EDT`, `CST6CDT`, `MST7MDT`, `PST8PDT`) |
| `dailyReminderTime`, `quietHoursStart`, `quietHoursEnd` | `HH:mm` hoặc `null`; giờ có giây như `20:00:30` bị từ chối |
| `quietHoursStart` / `quietHoursEnd` | Cùng `null` hoặc cùng có giá trị, và hai mốc khác nhau |
| Trường lạ | Bị từ chối (`fail-on-unknown-properties`) |

Hai người cùng `PUT` lần đầu đồng thời có thể làm một request lỗi khóa chính; client tải lại cài đặt
và gửi lại.

## 4. Cấu hình

Khóa đặt theo allowlist `notification.*` của `system_settings` để sau này chuyển sang bảng đó. Mọi
khóa có giá trị mặc định nên app và CI khởi động được khi thiếu biến môi trường.

| Property | Mặc định | Ghi chú |
|---|---|---|
| `notification.default-timezone` | `${NOTIFICATION_DEFAULT_TIMEZONE:Asia/Ho_Chi_Minh}` | Phải là múi giờ IANA, sai thì app không khởi động |
| `notification.learning-email-default` | `false` | Giá trị `emailEnabled` khi chưa lưu cài đặt |
| `notification.scheduler.enabled` | `${NOTIFICATION_SCHEDULER_ENABLED:true}` | Bật cả hai job. Profile `local` đặt `false`; task `test` của Gradle đặt `false` |
| `notification.scheduler.interval` | `PT15M` | Chu kỳ quét nhắc (ISO-8601) |
| `notification.worker.interval` | `PT1M` | Chu kỳ worker gửi email |
| `notification.scheduler.initial-delay` | `PT1M` | Độ trễ trước lần chạy đầu sau khi khởi động |
| `notification.max-retries` | `3` | 0–3; tổng số lần thử = `max-retries + 1` ≤ 4 |
| `notification.lease-seconds` | `60` | Lease cố định của một lần claim (`>= 15` giây, không gia hạn heartbeat khi đang gửi SMTP) |
| `notification.retry-backoff` | `1m,5m,30m` | Các khoảng chờ dương (`> 0`) cho từng lần thử lại |
| `notification.scheduler-batch-size` | `200` | Số tùy chọn đọc mỗi trang khi quét |
| `notification.worker-batch-size` | `20` | Số email tối đa mỗi lượt worker |
| `notification.mail.enabled` | `${MAIL_ENABLED:false}` | Bật email nhắc; chỉ có hiệu lực khi có đủ `from` và `web-base-url` |
| `notification.mail.from` | `${MAIL_FROM:}` | Trống thì không tạo delivery EMAIL và worker không gửi |
| `notification.mail.web-base-url` | `${NOTIFICATION_WEB_BASE_URL:}` | Gốc URL tuyệt đối cho link trong email. Trống thì không tạo delivery EMAIL và worker không gửi, để email không bao giờ mang link hỏng. Có giá trị thì phải là URL `http(s)://` tuyệt đối |
| `notification.mail.settings-path` | `${NOTIFICATION_SETTINGS_PATH:}` | Đường dẫn tương đối bắt đầu bằng `/` cho link cài đặt trong email (vd `/learn/notifications/settings`). Mặc định trống — bỏ qua link cài đặt trong email cho tới khi frontend hoàn thiện route |

Cấu hình sai (múi giờ không hợp lệ, `max-retries` ngoài 0–3, thiếu khoảng chờ, `web-base-url` không
phải URL tuyệt đối) làm app dừng ngay khi khởi động thay vì chạy sai. Khi `MAIL_ENABLED=true` mà thiếu
`MAIL_FROM` hoặc `NOTIFICATION_WEB_BASE_URL`, job ghi một cảnh báo lúc khởi động và nhắc chỉ đi in-app.

### Checklist deploy

1. Chạy Flyway tuần tự tới V17 (`out-of-order=true` bảo đảm áp dụng sạch `V11`, `V16`, `V17` trên cả schema đang ở `V10`/`V11`/`V13`, đồng thời chừa trống `V14` cho Practice và `V15` cho M08 Content Ops).
2. Giữ `NOTIFICATION_SCHEDULER_ENABLED` mặc định (`true`) trên một hoặc nhiều instance; dedupe và
   `SKIP LOCKED` cho phép chạy song song.
3. Muốn gửi email nhắc: đặt `MAIL_ENABLED=true`, `MAIL_FROM`, cấu hình SMTP (`SMTP_*`) và
   `NOTIFICATION_WEB_BASE_URL` là địa chỉ web thật, ví dụ `https://app.example.com`.
4. Thiếu một trong các biến ở bước 3 thì nhắc chỉ đi in-app, không có email và không có link hỏng.
5. Kiểm tra log khởi động không có cảnh báo `Reminder email is enabled but ...`.

## 5. Bảo mật và log

- Không log email, tiêu đề, nội dung thông báo hay token. Log chỉ chứa ID delivery, số lượng, mã lỗi
  đã làm sạch hoặc tên lớp exception. `ClaimedDelivery.toString()` chỉ in ID; exception của mailer chỉ
  mang mã lỗi. Hai sự kiện request/reply chỉ mang ID người dùng và không được lưu vào
  `event_publication` (listener đồng bộ `@EventListener`).
- `recipient_address` chỉ được đọc trong worker gửi email, không trả qua API.
- `title`/`body` là nội dung công khai, không chứa OTP, token hay địa chỉ email.

## 6. Nhắc thẻ Flashcard đến hạn (M10.05)

- **Thẻ đến hạn:** `due_at <= now` và nghĩa còn hiển thị trong `catalog_visible_senses`, cùng định
  nghĩa với hàng ôn của module `learning`. Module `notifications` không đọc bảng của module khác; nó
  gọi hai hợp đồng request/reply trong `common/events`:
  - `LearningEvents.countDueCards(Set<UUID>, Instant)`: số thẻ đến hạn theo người học, người không
    có thẻ không có trong kết quả.
  - `IdentityEvents.findActiveEmails(Set<UUID>)`: email của tài khoản `ACTIVE`, tài khoản khác bị bỏ.
- **Điều kiện tạo nhắc** ở mỗi lần quét: có `dailyReminderTime`; có ít nhất một kênh bật; giờ địa
  phương ≥ giờ nhắc; ngoài quiet hours; rule `REVIEW_DUE` bật và đã qua `minimum_gap_minutes`; tài
  khoản `ACTIVE`; số thẻ đến hạn > 0. Lỡ một lần quét thì gửi bù trong cùng ngày địa phương, không
  bù ngày đã qua. Bộ quét đọc tùy chọn theo trang (keyset trên `user_id`), lọc giờ theo múi giờ trong
  Java, gọi identity và learning một lần cho mỗi trang, và khóa bi quan theo từng `user_id` trên
  `notification_preferences` (`FOR UPDATE`) khi ghi nhắc để kiểm tra `minimum_gap_minutes` an toàn qua
  mốc 00:00 địa phương.
- **Dedupe:** `REVIEW_DUE:{userId}:{ngày địa phương yyyy-MM-dd}` với unique `(user_id, dedupe_key)`.
  Chạy lặp hoặc nhiều instance không tạo trùng: thông báo được ghi bằng
  `INSERT … ON CONFLICT (user_id, dedupe_key) DO NOTHING`, mỗi người học trong một transaction riêng.
- **Nội dung đã chốt:**
  - `title`: `Bạn có {n} thẻ cần ôn hôm nay`
  - `body`: `{n} thẻ Flashcard đã đến hạn ôn tập. Ôn ngay để giữ nhịp ghi nhớ.`
  - `target_url`: `/learn/review`
  - Email: tiêu đề như `title`; thân thư gồm `body`, liên kết tới `/learn/review` và chỉ chèn liên kết
    cài đặt thông báo (vd `/learn/notifications/settings`, ghép từ `notification.mail.web-base-url` +
    `notification.mail.settings-path`) khi `settings-path` được cấu hình khác rỗng.
- Kênh `IN_APP` ghi delivery `SENT` ngay khi tạo thông báo. Kênh `EMAIL` ghi `PENDING` khi người học
  bật email và hệ thống bật mail, với `recipient_address` là email tại thời điểm tạo.

## 7. Gửi email qua outbox (M10.08)

- Worker claim bằng transaction ngắn với `FOR UPDATE SKIP LOCKED`: dòng `PENDING` đã tới
  `next_retry_at`, hoặc `PROCESSING` đã hết `lease_until`. Mỗi lần claim tăng `attempt_count`, đặt
  `claim_token` và lease cố định (mặc định 60 giây, tối thiểu 15 giây, không gia hạn heartbeat khi đang
  gửi SMTP), commit rồi mới gửi SMTP ngoài transaction. Kết quả chỉ được ghi khi `claim_token` còn
  khớp; trong cùng một lượt chạy, worker bỏ qua các `deliveryId` đã xử lý để tránh claim lại lặp tức thì.
  Worker claim từng delivery một, nên mỗi email có lease mới; thời gian chờ SMTP (tối đa khoảng 15 giây)
  luôn ngắn hơn lease.
- Trước khi gửi, worker hỏi lại identity; tài khoản không còn `ACTIVE` thì delivery chuyển `FAILED`
  với `USER_INACTIVE`, không thử lại. Nếu tra cứu identity lỗi runtime, delivery được ghi nhận
  `IDENTITY_LOOKUP_FAILED` để retry an toàn và không làm gián đoạn các delivery khác trong batch.
- Mã lỗi đã làm sạch: `MAIL_NOT_CONFIGURED`, `MAIL_AUTH_FAILED`, `MAIL_SEND_FAILED`, `MAIL_ERROR`,
  `IDENTITY_LOOKUP_FAILED`, `USER_INACTIVE`, `LEASE_EXPIRED`, `RETRIES_EXHAUSTED`. Trong đó phản hồi
  SMTP `5xx` hoặc địa chỉ nhận không hợp lệ từ `MailSendException` được phân loại là lỗi vĩnh viễn
  (`MAIL_SEND_FAILED`, không thử lại).
- Thử lại sau 1, 5 và 30 phút (một lần đầu và tối đa ba lần thử lại, CHECK `attempt_count` 0–4).
  Thất bại ở lần thứ tư chuyển `FAILED`.
- Dòng `PROCESSING` hết lease mà `attempt_count = 4` chuyển `FAILED` với
  `last_failure_code = LEASE_EXPIRED` và không được claim lại.
- Gửi email lỗi không rollback thông báo in-app.
- **Không bảo đảm gửi đúng một lần.** Nếu nhà cung cấp SMTP đã nhận thư nhưng phản hồi bị mất, hoặc
  lease hết hạn sau khi thư đã đi, worker có thể gửi lại. Hệ thống chỉ bảo đảm tối đa một bản ghi
  thông báo và một delivery cho mỗi kênh.

## 8. Lệch so với SRS v5.0 (SEQ-07)

Sơ đồ SEQ-07 trong `EnglishMaster_SRS_SDD_v5_0` khác baseline đã duyệt. Triển khai theo baseline:

| SEQ-07 | Baseline và triển khai |
|---|---|
| Trạng thái `DELIVERED`, cột `delivered_at` | `SENT`, `sent_at` (CHECK `notification_deliveries_c3`, `c8`) |
| `retry_count` | `attempt_count` 0–4 |
| Lease 5 phút | Lease 60 giây |
| Chỉ quét người học có `email_enabled = true` | Quét cả kênh in-app; email là kênh tùy chọn |
| Backoff lũy thừa không giới hạn rõ | 1/5/30 phút, tối đa 4 lần thử |
