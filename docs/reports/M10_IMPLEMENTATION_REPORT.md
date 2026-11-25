# M10 Implementation Report — Notification & Reminder

## 1. Baseline & Branch Status
- **Official baseline (`origin/main`):** `4afb63163163f0db4a17b572d1815587a1320680` (`Merge pull request #15 from MasterEnglishhh/feat/m04-grammar`)
- **Ported M10 core source:** `251dce4573933e1060a6b8e84cdae49f02ac2b0c` / `f8fea7cf73d11b03dc8d7600881c5f672d042b3e` (`origin/feat/thong-bao-nhac-hoc-v11`)
- **Feature branch:** `feat/m10-notification-complete`
- **Pull Request:** https://github.com/MasterEnglishhh/masterenglish-api/pull/16 (`base: main`)
- **PR #16 -> PR #17 dependency:** PR #17 (`feat/m2-learner-dashboard`) branches off `feat/m10-notification-complete`. Không tự merge, không deploy, không push lên `main`.

## 2. Trạng thái chức năng M10.01–M10.11

| Mã | Tên chức năng | Trạng thái | Bằng chứng & Giới hạn thực tế |
|---|---|---|---|
| M10.01 | Xem thông báo & đếm chưa đọc (lọc `type`, `unreadOnly`) | **IMPLEMENTED** | `NotificationController.getFeed`, `countUnread`, `NotificationRepository.findFeedFiltered` |
| M10.02 | Đánh dấu đã đọc (1 / tất cả) | **IMPLEMENTED** | `NotificationController.markRead`, `markAllRead`, `NotificationRepository.findInFeed`, `markFeedRead` |
| M10.03 | Thiết lập kênh nhận (`inAppEnabled`, `emailEnabled`) | **IMPLEMENTED** | `NotificationController.getPreferences`, `updatePreferences` |
| M10.04 | Thiết lập giờ nhắc, IANA timezone, quiet hours & upsert đồng thời | **IMPLEMENTED** | `UpdateNotificationPreferencesRequest`, `NotificationPreferenceRepository.upsertPreference` (`ON CONFLICT DO UPDATE`) |
| M10.05 | Scheduler nhắc Flashcard đến hạn (`REVIEW_DUE`) | **IMPLEMENTED** | `ReminderPlannerImpl`, `ReminderWriter`, `LearningEvents.countDueCards`, `NotificationJobs` |
| M10.06 | Nhắc mục tiêu & lịch học hằng ngày (`DAILY_GOAL`) | **BLOCKED** | Thiếu bảng `study_sessions` + `study_schedules`, thiếu seed row `DAILY_GOAL` trong `reminder_rules` và chưa truy cập được `flyway_schema_history` trên DB dùng chung để mở migration mới |
| M10.07 | Thông báo nội dung mới (`CONTENT_NEW`) | **BLOCKED (Awaiting Owner Rules & Rule Seed)** | `ContentPublication.revisionPublished` phát `ContentEvents.RevisionPublished(itemId, revisionId, type, snapshot)` sau duyệt/publish; chưa có quyết định nghiệp vụ được phê duyệt về `content_type` kích hoạt, lần publish đầu vs sửa revision, đối tượng người học nhận, kênh gửi (`IN_APP`/`EMAIL`) và seed `reminder_rules` |
| M10.08 | Gửi email reminder qua outbox | **IMPLEMENTED** | `EmailDeliveryWorkerImpl`, `DeliveryClaimStore` (`FOR UPDATE SKIP LOCKED`, lease 60s, backoff 1/5/30m), `SmtpReminderMailer` |
| M10.09 | Theo dõi trạng thái gửi (Admin/Ops) | **IMPLEMENTED** | `AdminNotificationController.getDeliveries`, `NotificationDeliveryRepository.findAdminDeliveries` |
| M10.10 | Quản trị `reminder_rules` + Audit log | **IMPLEMENTED** | `AdminNotificationController.listRules`, `getRule`, `updateRule`, `AdminNotificationServiceImpl` + `ContentEvents.recordAuditLog` |
| M10.11 | Kiểm tra lịch sử nhắc (Learner & Admin) | **IMPLEMENTED** | `NotificationController.getReminderHistory`, `AdminNotificationController.getReminderHistory`, `NotificationRepository.findLearnerReminderHistory`, `findAdminReminderHistory` |

## 3. Danh sách file tạo mới & chỉnh sửa trên `feat/m10-notification-complete`
- **Tạo mới:**
  - `docs/notifications/CONTRACT.md`
  - `docs/reports/M10_IMPLEMENTATION_REPORT.md`
  - `src/main/resources/db/migration/V11__create_notifications.sql` *(nguyên bản từ `origin/feat/thong-bao-nhac-hoc-v11`)*
  - `src/main/java/com/masterenglish/api/common/events/{LearningEvents,NotificationEvents}.java`
  - `src/main/java/com/masterenglish/api/config/{NotificationConfiguration,NotificationProperties,NotificationSchedulingConfiguration}.java`
  - `src/main/java/com/masterenglish/api/modules/learning/service/impl/LearningEventHandler.java`
  - `src/main/java/com/masterenglish/api/modules/notifications/**`
  - `src/main/java/com/masterenglish/api/modules/admin/controller/AdminNotificationController.java`
  - `src/main/java/com/masterenglish/api/modules/admin/dto/request/UpdateReminderRuleRequest.java`
  - `src/main/java/com/masterenglish/api/modules/admin/dto/response/{AdminNotificationDeliveryDto,AdminReminderHistoryDto,ReminderRuleDto}.java`
  - `src/main/java/com/masterenglish/api/modules/admin/service/{AdminNotificationService,impl/AdminNotificationServiceImpl}.java`
  - Test suites: `NotificationCrossModuleEventsTests`, `NotificationsDatabaseMigrationTests`, `NotificationsUpgradeTests`, `NotificationPropertiesTests`, `NotificationControllerIntegrationTests`, `NotificationPreferencesConcurrencyTests`, `NotificationServiceImplTests`, `ReminderPlannerIntegrationTests`, `EmailDeliveryGateTests`, `EmailDeliveryWorkerIntegrationTests`, `NotificationAdminServiceImplTests`, `NotificationJobsTests`, `AdminNotificationServiceImplTests`, `AdminNotificationControllerIntegrationTests`.
- **Chỉnh sửa từ `origin/main`:**
  - `build.gradle`, `src/main/resources/application.properties`, `src/main/resources/application-local.properties`
  - `common/events/IdentityEvents.java`, `modules/identity/**` (`UserRepository`, `UserService`, `IdentityEventHandler`, `UserServiceImpl`)
  - `modules/learning/**` (`UserWordProgressRepository`, `DueQueueService`, `DueQueueServiceImpl`)
  - `src/test/java/com/masterenglish/api/ArchitectureTests.java`

## 4. Database Migration & Flyway Audit
- Giữ nguyên **100% nguyên bản** `V11__create_notifications.sql` từ `origin/feat/thong-bao-nhac-hoc-v11` (Git blob SHA-1: `2d6136fc3fb5c8c17a6f9fe1b1bd0f359f5b70bf`), khớp khe `V11` còn trống giữa `V10__create_grammar.sql` và `V12__create_lesson_progress.sql` trên `origin/main` (với `spring.flyway.out-of-order=true`).
- **Phát hiện xung đột lịch sử với nhánh `dev`:** file `V11__create_notifications.sql` trên nhánh local `dev` (`5953194`) có Git blob SHA-1 khác (`124e9d640517754c40ff98b1e0d348b0ca6884b3`), cho thấy có hai biến thể V11 khác checksum từng tồn tại ngoài `origin/main`.
- **Không tạo migration mới (`V14`/`V16`) và không sửa `V11`** vì không thể kết nối PostgreSQL staging/EC2 để truy vấn `SELECT version, description, checksum, success FROM flyway_schema_history`.

## 5. API & Event Contracts
- **API Learner (`LEARNER`):**
  - `GET /api/v1/notifications` (`type`, `unreadOnly`, `page`, `size`)
  - `GET /api/v1/notifications/unread-count`
  - `POST /api/v1/notifications/{id}/read`
  - `POST /api/v1/notifications/read-all`
  - `GET /api/v1/notifications/preferences`
  - `PUT /api/v1/notifications/preferences`
  - `GET /api/v1/notifications/reminders/history` (`type`, `status`, `ruleCode`, `from`, `to`, `page`, `size`)
- **API Admin (`SYSTEM_ADMIN`):**
  - `GET /api/v1/admin/notifications/deliveries`
  - `GET /api/v1/admin/notifications/rules`
  - `GET /api/v1/admin/notifications/rules/{ruleId}`
  - `PUT /api/v1/admin/notifications/rules/{ruleId}` *(ghi `audit_logs` trong cùng transaction)*
  - `GET /api/v1/admin/notifications/history`
- **Event Contracts:** `IdentityEvents.findActiveEmails(Set<UUID>)`, `LearningEvents.countDueCards(Set<UUID>, Instant)`, `NotificationEvents` (`countUnreadInFeed`, `findDeliveries`, `listReminderRules`, `getReminderRule`, `updateReminderRule`, `findReminderHistory`), `ContentEvents.recordAuditLog(...)`.

## 6. Kết quả kiểm thử & Build
- **PR #16 commit `0bb46c3173c347af088f01501f3bffe54b3e4c41` trên GitHub Actions CI (`run 45456847599`):** `verify` job (`spotlessCheck check bootJar --no-daemon` + Docker build với PostgreSQL 17 Testcontainers) **PASS** (`6m03s`).
- Kết quả chạy lại local & CI cho commit cập nhật báo cáo lần này được ghi trực tiếp trong báo cáo tổng kết `M10_M2_FINAL_REPORT.md` và xác nhận sau khi CI hoàn tất.

## 7. Security, Ownership & Reliability
- `/api/v1/notifications/**`: bắt buộc `LEARNER` + CSRF (`POST`/`PUT`); filter chặt `userId`, `visibility = 'USER'`, kênh `IN_APP`; truy cập notification không thuộc sở hữu hoặc `INTERNAL` trả `404`.
- `/api/v1/admin/notifications/**`: bắt buộc `SYSTEM_ADMIN` (`LEARNER` và `CONTENT_ADMIN` bị `403`).
- Không lộ `recipientAddress`, `claimToken`, `encryptedPayload` trong API hay log. Outbox claim sử dụng `FOR UPDATE SKIP LOCKED`, lease 60s, backoff 1/5/30 phút (tối đa 4 lần).

## 8. Những vấn đề cần chủ dự án xác nhận
1. Truy vấn read-only `flyway_schema_history` trên staging/EC2 DB (đặc biệt xác nhận V11 có từng áp dụng bản `dev` hay chưa, và V12–V15).
2. Nghiệp vụ **M10.07 (`CONTENT_NEW`)**: danh sách `content_type` kích hoạt (`DECK`, `GRAMMAR_LESSON`, `PRACTICE_SET`...), phân biệt publish lần đầu v1 vs sửa đổi v2+, tiêu chí chọn nhóm learner nhận, kênh gửi (`IN_APP` vs `EMAIL`), và xác nhận tạo row `CONTENT_NEW` trong `reminder_rules`.
3. Nghiệp vụ **M10.06 (`DAILY_GOAL`)**: quy tắc kích hoạt đối chiếu `Study Schedule` (manual/accepted) và `Study Session` thực tế trong ngày theo timezone.
