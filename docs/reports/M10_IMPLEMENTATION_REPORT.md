# M10 Implementation Report — Notification & Reminder

## 1. Baseline Commit
- **Official baseline (`origin/main`):** `4afb63163163f0db4a17b572d1815587a1320680` (`Merge pull request #15 from MasterEnglishhh/feat/m04-grammar`)
- **Ported M10 core source:** `251dce4573933e1060a6b8e84cdae49f02ac2b0c` / `f8fea7cf73d11b03dc8d7600881c5f672d042b3e` (`origin/feat/thong-bao-nhac-hoc-v11`)

## 2. Branch triển khai
- **Branch:** `feat/m10-notification-complete`
- **Pull Request:** https://github.com/MasterEnglishhh/masterenglish-api/pull/16

## 3. Danh sách & Trạng thái chức năng M10.01–M10.11

| Mã | Tên chức năng | Trạng thái | Bằng chứng chính |
|---|---|---|---|
| M10.01 | Xem thông báo & đếm chưa đọc (có lọc `type`, `unreadOnly`) | **IMPLEMENTED** | `NotificationController.getFeed`, `countUnread`, `NotificationRepository.findFeedFiltered` |
| M10.02 | Đánh dấu đã đọc (1 / tất cả) | **IMPLEMENTED** | `NotificationController.markRead`, `markAllRead`, `NotificationRepository.findInFeed`, `markFeedRead` |
| M10.03 | Thiết lập kênh nhận (`inAppEnabled`, `emailEnabled`) | **IMPLEMENTED** | `NotificationController.getPreferences`, `updatePreferences` |
| M10.04 | Thiết lập giờ nhắc, IANA timezone, quiet hours & xử lý đồng thời | **IMPLEMENTED** | `UpdateNotificationPreferencesRequest`, `NotificationPreferenceRepository.upsertPreference` (`ON CONFLICT DO UPDATE`) |
| M10.05 | Scheduler nhắc Flashcard đến hạn (`REVIEW_DUE`) | **IMPLEMENTED** | `ReminderPlannerImpl`, `ReminderWriter`, `LearningEvents.countDueCards`, `NotificationJobs` |
| M10.06 | Nhắc mục tiêu hằng ngày (`DAILY_GOAL`) | **BLOCKED** | Phụ thuộc bảng `study_sessions` (cần migration mới) và chưa xác minh được `flyway_schema_history` trên DB dùng chung |
| M10.07 | Thông báo nội dung mới (`CONTENT_NEW`) | **BLOCKED** | Chưa có business rule xác định nhóm người nhận, loại nội dung kích hoạt và kênh gửi (chờ chủ dự án xác nhận) |
| M10.08 | Gửi email reminder qua outbox | **IMPLEMENTED** | `EmailDeliveryWorkerImpl`, `DeliveryClaimStore` (`FOR UPDATE SKIP LOCKED`, lease 60s, backoff 1/5/30m), `SmtpReminderMailer` |
| M10.09 | Theo dõi trạng thái gửi (Admin/Ops) | **IMPLEMENTED** | `AdminNotificationController.getDeliveries`, `NotificationDeliveryRepository.findAdminDeliveries` |
| M10.10 | Quản trị `reminder_rules` + Audit log | **IMPLEMENTED** | `AdminNotificationController.listRules`, `getRule`, `updateRule`, `AdminNotificationServiceImpl` + `ContentEvents.recordAuditLog` |
| M10.11 | Kiểm tra lịch sử nhắc (Learner & Admin) | **IMPLEMENTED** | `NotificationController.getReminderHistory`, `AdminNotificationController.getReminderHistory`, `NotificationRepository.findLearnerReminderHistory`, `findAdminReminderHistory` |

## 4. Danh sách file tạo mới
- `docs/notifications/CONTRACT.md`
- `src/main/resources/db/migration/V11__create_notifications.sql` *(nguyên bản từ `origin/feat/thong-bao-nhac-hoc-v11`)*
- `src/main/java/com/masterenglish/api/common/events/LearningEvents.java`
- `src/main/java/com/masterenglish/api/common/events/NotificationEvents.java`
- `src/main/java/com/masterenglish/api/config/NotificationConfiguration.java`
- `src/main/java/com/masterenglish/api/config/NotificationProperties.java`
- `src/main/java/com/masterenglish/api/config/NotificationSchedulingConfiguration.java`
- `src/main/java/com/masterenglish/api/modules/learning/service/impl/LearningEventHandler.java`
- `src/main/java/com/masterenglish/api/modules/notifications/**` (controller, dto, entity, repository, service, service/impl, `package-info.java`)
- `src/main/java/com/masterenglish/api/modules/admin/controller/AdminNotificationController.java`
- `src/main/java/com/masterenglish/api/modules/admin/dto/request/UpdateReminderRuleRequest.java`
- `src/main/java/com/masterenglish/api/modules/admin/dto/response/{AdminNotificationDeliveryDto,AdminReminderHistoryDto,ReminderRuleDto}.java`
- `src/main/java/com/masterenglish/api/modules/admin/service/AdminNotificationService.java`
- `src/main/java/com/masterenglish/api/modules/admin/service/impl/AdminNotificationServiceImpl.java`
- Test suites: `NotificationCrossModuleEventsTests`, `NotificationsDatabaseMigrationTests`, `NotificationsUpgradeTests`, `NotificationPropertiesTests`, `NotificationControllerIntegrationTests`, `NotificationPreferencesConcurrencyTests`, `NotificationServiceImplTests`, `ReminderPlannerIntegrationTests`, `EmailDeliveryGateTests`, `EmailDeliveryWorkerIntegrationTests`, `NotificationAdminServiceImplTests`, `NotificationJobsTests`, `AdminNotificationServiceImplTests`, `AdminNotificationControllerIntegrationTests`.

## 5. Danh sách file chỉnh sửa từ baseline `origin/main`
- `build.gradle`
- `src/main/resources/application.properties`
- `src/main/resources/application-local.properties`
- `src/main/java/com/masterenglish/api/common/events/IdentityEvents.java`
- `src/main/java/com/masterenglish/api/modules/identity/repository/UserRepository.java`
- `src/main/java/com/masterenglish/api/modules/identity/service/UserService.java`
- `src/main/java/com/masterenglish/api/modules/identity/service/impl/IdentityEventHandler.java`
- `src/main/java/com/masterenglish/api/modules/identity/service/impl/UserServiceImpl.java`
- `src/main/java/com/masterenglish/api/modules/learning/repository/UserWordProgressRepository.java`
- `src/main/java/com/masterenglish/api/modules/learning/service/DueQueueService.java`
- `src/main/java/com/masterenglish/api/modules/learning/service/impl/DueQueueServiceImpl.java`
- `src/test/java/com/masterenglish/api/ArchitectureTests.java`

## 6. API mới và API mở rộng

| Method & Path | Role | Chức năng |
|---|---|---|
| `GET /api/v1/notifications` *(mở rộng filter `type`, `unreadOnly`)* | `LEARNER` | M10.01 Feed cá nhân (`USER` + `IN_APP`) |
| `GET /api/v1/notifications/unread-count` | `LEARNER` | M10.01 Đếm chưa đọc |
| `POST /api/v1/notifications/{id}/read` | `LEARNER` | M10.02 Đánh dấu đã đọc (idempotent, 404 ownership) |
| `POST /api/v1/notifications/read-all` | `LEARNER` | M10.02 Đánh dấu tất cả đã đọc |
| `GET /api/v1/notifications/preferences` | `LEARNER` | M10.03–04 Xem cài đặt nhắc |
| `PUT /api/v1/notifications/preferences` | `LEARNER` | M10.03–04 Lưu cài đặt (atomic upsert) |
| `GET /api/v1/notifications/reminders/history` | `LEARNER` | M10.11 Tra cứu lịch sử nhắc cá nhân + trạng thái kênh gửi |
| `GET /api/v1/admin/notifications/deliveries` | `SYSTEM_ADMIN` | M10.09 Theo dõi trạng thái outbox delivery |
| `GET /api/v1/admin/notifications/rules` | `SYSTEM_ADMIN` | M10.10 Danh sách `reminder_rules` |
| `GET /api/v1/admin/notifications/rules/{ruleId}` | `SYSTEM_ADMIN` | M10.10 Chi tiết `reminder_rules` |
| `PUT /api/v1/admin/notifications/rules/{ruleId}` | `SYSTEM_ADMIN` | M10.10 Cập nhật `enabled`, `minimumGapMinutes` + ghi `audit_logs` |
| `GET /api/v1/admin/notifications/history` | `SYSTEM_ADMIN` | M10.11 Tra cứu lịch sử nhắc toàn hệ thống |

## 7. Migration
- Giữ nguyên `V11__create_notifications.sql` từ `origin/feat/thong-bao-nhac-hoc-v11`, khớp chính xác khe `V11` còn trống giữa `V10__create_grammar.sql` và `V12__create_lesson_progress.sql` trên `origin/main` (với `spring.flyway.out-of-order=true`).
- **Không tạo migration mới** (`V14`/`V16`) vì chưa truy cập được `flyway_schema_history` của môi trường dùng chung.

## 8. Event Contracts
- `IdentityEvents.findActiveEmails(Set<UUID>)`
- `LearningEvents.countDueCards(Set<UUID>, Instant)`
- `NotificationEvents`: `countUnreadInFeed`, `findDeliveries`, `listReminderRules`, `getReminderRule`, `updateReminderRule`, `findReminderHistory`
- `ContentEvents.recordAuditLog(...)` (được `AdminNotificationServiceImpl` gọi đồng bộ trong cùng transaction khi cập nhật `reminder_rules`)

## 9. Security & Authorization
- Endpoint `/api/v1/notifications/**` yêu cầu `LEARNER` + CSRF cho `POST`/`PUT`, lọc chặt `userId` + `visibility = USER` + kênh `IN_APP` (feed), trả `404` nếu truy cập thông báo của người khác hoặc `INTERNAL`.
- Endpoint `/api/v1/admin/notifications/**` yêu cầu `SYSTEM_ADMIN` (`LEARNER` và `CONTENT_ADMIN` đều bị `403`).
- Tuyệt đối không trả `recipientAddress`, `claimToken`, `encryptedPayload` qua bất kỳ API hay log nào; lịch sử nhắc và delivery monitor loại trừ hoặc không lộ dữ liệu nhạy cảm.

## 10. Kết quả kiểm thử & Build
- **Local (`gradlew.bat spotlessApply spotlessCheck test ... bootJar --no-daemon`):**
  - `spotlessCheck`: **PASS**
  - `bootJar`: **PASS**
  - `ArchitectureTests` & `LayerArchitectureTests`: **PASS**
  - Unit tests (`NotificationPropertiesTests`, `NotificationServiceImplTests`, `NotificationAdminServiceImplTests`, `NotificationJobsTests`, `EmailDeliveryGateTests`, `AdminNotificationServiceImplTests`, `AdminControllerUnitTests`): **PASS**
  - Integration / Migration Testcontainers tests (`NotificationControllerIntegrationTests`, `NotificationPreferencesConcurrencyTests`, `ReminderPlannerIntegrationTests`, `EmailDeliveryWorkerIntegrationTests`, `NotificationsDatabaseMigrationTests`, `NotificationsUpgradeTests`, `NotificationCrossModuleEventsTests`, `AdminNotificationControllerIntegrationTests`): **NOT RUN cục bộ** do Docker Desktop không chạy trên máy hiện tại; chạy qua GitHub Actions CI.
- **GitHub Actions CI (lần chạy đầu `45456289457`):** Lỗi tạm thời HTTP `429 Too Many Requests` từ `repo.maven.apache.org` khi tải Gradle plugin; sẽ chạy lại ngay trên lần push báo cáo này.

## 11. Known Limitations & Blockers
1. Chưa xác minh được `flyway_schema_history` trên staging/EC2 DB.
2. **M10.06 (`DAILY_GOAL`)** chưa triển khai vì phụ thuộc bảng `study_sessions` (cần migration mới).
3. **M10.07 (`CONTENT_NEW`)** chưa triển khai vì chưa có quy tắc nghiệp vụ chốt nhóm learner nhận, phạm vi `content_type` và kênh gửi.
4. Kênh email outbox đảm bảo *at-least-once* chứ không *exactly-once* nếu SMTP nhận thư nhưng mất phản hồi trước khi commit `SENT`.

## 12. Commit & PR
- **Feature commit:** `f5f63476ef2d91962ad437488669259cfd535115`
- **Branch:** `feat/m10-notification-complete`
- **Pull Request:** https://github.com/MasterEnglishhh/masterenglish-api/pull/16
