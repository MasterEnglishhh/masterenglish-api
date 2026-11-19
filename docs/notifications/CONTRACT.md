# Hợp đồng API và dữ liệu: Thông báo và lời nhắc học tập (M10)

Hợp đồng kỹ thuật cho module `notifications`. Ngày ban hành: 19/11/2026.

Trạng thái:

- Đã có: M10.01–M10.04 (feed, đánh dấu đã đọc, kênh nhận, giờ nhắc/múi giờ/quiet hours).
- Dự kiến ở pha sau: M10.05 (scheduler nhắc thẻ đến hạn) và M10.08 (worker gửi email qua outbox).
  Mục 6–7 ghi các quy tắc đã chốt cho hai chức năng này.
- Ngoài phạm vi: M10.06 nhắc mục tiêu hằng ngày, M10.07 thông báo nội dung mới, M10.09–M10.11,
  chuyển email OTP/bảo mật sang outbox, browser push, bảng `system_settings`.

## 1. Dữ liệu

Flyway `V11__create_notifications.sql` tạo bốn bảng lấy nguyên văn từ
`docs/database/proposals/approved-baseline.sql`: `notification_preferences`, `reminder_rules`,
`notifications`, `notification_deliveries`. Bốn cột OTP của `notification_deliveries`
(`otp_challenge_id`, `encrypted_payload`, `encryption_key_id`, `sensitive_expires_at`) được giữ
theo baseline nhưng entity chưa ánh xạ, vì email OTP vẫn gửi trực tiếp.

Seed `reminder_rules`:

| code | enabled | minimum_gap_minutes |
|---|---|---|
| `REVIEW_DUE` | `TRUE` | `720` |

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
| M10.01 | `GET /api/v1/notifications?page=0&size=20` | `200 PageResponse<NotificationResponse>` |
| M10.01 | `GET /api/v1/notifications/unread-count` | `200 {"count": 3}` |
| M10.02 | `POST /api/v1/notifications/{id}/read` | `204` |
| M10.02 | `POST /api/v1/notifications/read-all` | `204` |
| M10.03–04 | `GET /api/v1/notifications/preferences` | `200 NotificationPreferencesResponse` |
| M10.03–04 | `PUT /api/v1/notifications/preferences` | `200 NotificationPreferencesResponse` |

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
  "dailyReminderTime": "20:00",
  "timezone": "Asia/Ho_Chi_Minh",
  "quietHoursStart": "22:30",
  "quietHoursEnd": "07:00",
  "updatedAt": "2026-11-19T09:00:00Z"
}
```

- `GET` khi chưa lưu lần nào trả mặc định và **không tạo bản ghi**: `inAppEnabled=true`,
  `emailEnabled=notification.learning-email-default` (mặc định `false`),
  `timezone=notification.default-timezone` (mặc định `Asia/Ho_Chi_Minh`), các giờ `null`,
  `updatedAt=null`.
- `PUT` thay toàn bộ cài đặt (gửi đủ trường; trường giờ có thể `null`) và trả bản đã lưu.
- `dailyReminderTime = null` nghĩa là chưa đặt lịch: hệ thống không tự nhắc.
- Quiet hours `[start, end)`; `start > end` nghĩa là khoảng vắt qua nửa đêm.

Kiểm tra đầu vào (lỗi trả `400 ProblemDetail`):

| Trường | Quy tắc |
|---|---|
| `inAppEnabled`, `emailEnabled` | Bắt buộc, boolean |
| `timezone` | Bắt buộc, ≤ 60 ký tự, tên múi giờ IANA trong tzdb của JVM (không nhận offset như `+07:00`) |
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

Pha scheduler/worker sẽ thêm `notification.scheduler.enabled` (tắt ở profile `local` và trong test),
`notification.scheduler.interval` (15 phút), `notification.max-retries` (3),
`notification.lease-seconds` (60), `notification.mail.enabled=${MAIL_ENABLED:false}` và
`notification.mail.from=${MAIL_FROM:}`.

## 5. Bảo mật và log

- Không log email, tiêu đề, nội dung thông báo hay token. Log chỉ chứa ID và mã lỗi đã làm sạch.
- `recipient_address` chỉ được đọc trong worker gửi email, không trả qua API.
- `title`/`body` là nội dung công khai, không chứa OTP, token hay địa chỉ email.

## 6. Nhắc thẻ Flashcard đến hạn (M10.05, pha sau)

- **Thẻ đến hạn:** `due_at <= now` và nghĩa còn hiển thị trong `catalog_visible_senses`, cùng định
  nghĩa với hàng ôn của module `learning`. Số thẻ lấy qua sự kiện request/reply trong `common/events`.
- **Điều kiện tạo nhắc** ở mỗi lần quét: có `dailyReminderTime`; có ít nhất một kênh bật; giờ địa
  phương ≥ giờ nhắc; ngoài quiet hours; rule `REVIEW_DUE` bật và đã qua `minimum_gap_minutes`; tài
  khoản `ACTIVE`; số thẻ đến hạn > 0. Lỡ một lần quét thì gửi bù trong cùng ngày địa phương, không
  bù ngày đã qua.
- **Dedupe:** `REVIEW_DUE:{userId}:{ngày địa phương yyyy-MM-dd}` với unique `(user_id, dedupe_key)`.
  Chạy lặp hoặc nhiều instance không tạo trùng.
- **Nội dung đã chốt:**
  - `title`: `Bạn có {n} thẻ cần ôn hôm nay`
  - `body`: `{n} thẻ Flashcard đã đến hạn ôn tập. Ôn ngay để giữ nhịp ghi nhớ.`
  - `target_url`: `/learn/review`
  - Email: tiêu đề như `title`; thân thư gồm `body`, liên kết tới `/learn/review` và liên kết tắt
    hoặc đổi giờ nhắc tới `/learn/notifications/settings` (URL tuyệt đối ghép từ địa chỉ web cấu hình).
- Kênh `IN_APP` ghi delivery `SENT` ngay khi tạo thông báo. Kênh `EMAIL` ghi `PENDING` khi người học
  bật email và hệ thống bật mail.

## 7. Gửi email qua outbox (M10.08, pha sau)

- Worker claim bằng transaction ngắn với `FOR UPDATE SKIP LOCKED`: dòng `PENDING` đã tới
  `next_retry_at`, hoặc `PROCESSING` đã hết `lease_until`. Mỗi lần claim tăng `attempt_count`, đặt
  `claim_token` và lease 60 giây, commit rồi mới gửi SMTP ngoài transaction. Kết quả chỉ được ghi khi
  `claim_token` còn khớp.
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
