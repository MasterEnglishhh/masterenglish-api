# M2 Implementation Report — Learner Dashboard

## 1. Baseline Commit
- **Branch base (`feat/m10-notification-complete`):** `0bb46c3` (trên nền `origin/main` `4afb63163163f0db4a17b572d1815587a1320680`)

## 2. Branch triển khai
- **Branch:** `feat/m2-learner-dashboard`
- **Pull Request:** https://github.com/MasterEnglishhh/masterenglish-api/pull/17 (base: `feat/m10-notification-complete`)

## 3. Trạng thái M02.01–M02.06

| Mã | Chức năng | Trạng thái | Bằng chứng & Ghi chú |
|---|---|---|---|
| M02.01 | Xem số từ đã học | **IMPLEMENTED** | `LearningEvents.getVocabularyProgress` -> `LearningProgressService.getProgress` (`studiedSenses`, `masteredSenses`), khớp 100% `GET /api/v1/vocabulary/progress` |
| M02.02 | Xem bài đã làm | **PARTIAL (Grammar hoàn chỉnh; TOEIC bị chặn schema)** | `lesson_progress` (`V12`) + `attempts` Grammar (`V13`): `completedGrammarLessons`, `inProgressGrammarLessons`, `submittedPracticeAttempts`, `averagePracticeScorePercent`, `recentAttempts`. TOEIC Part 1–7 chưa có do `V14` bị chặn |
| M02.03 | Xem lịch ôn hôm nay | **IMPLEMENTED** | `dueNow`, `savedSenses` từ `LearningProgressService`, kết hợp `countEligibleDueByDeck` + `VocabularyEvents.findActiveDecksByIds` -> `dueDecks` |
| M02.04 | Xem điểm yếu nổi bật | **PARTIAL (Grammar hoàn chỉnh theo rule B25/UC06; TOEIC/Mistake notebook bị chặn schema)** | `AttemptRepository.topicStatisticsSince` lọc 30 ngày gần nhất, yêu cầu `questionCount >= 20` và `accuracyPercent < 70`; thiếu mẫu trả `INSUFFICIENT_DATA` và không sinh điểm yếu giả |
| M02.05 | Nhận gợi ý luyện tập | **IMPLEMENTED** | `DashboardRecommendationBuilder`: quy tắc xác định từ thẻ đến hạn (`FLASHCARD_REVIEW`, `DUE_DECK`), chủ điểm yếu (`WEAK_TOPIC_PRACTICE`), bài ngữ pháp tiếp theo (`GRAMMAR_LESSON`), từ đã lưu (`SAVED_VOCABULARY`). Không dùng AI Tutor |
| M02.06 | Theo dõi mục tiêu học tập | **PARTIAL (Goal + Streak hoàn chỉnh; `study_sessions` bị chặn migration)** | `IdentityEvents.findLearningGoal` (`learning_goals`) + `streakDays` tính theo IANA timezone từ hoạt động hợp lệ (`word_reviews`, `lesson_progress`, `attempts` `SUBMITTED` theo `UC06 A8`). `todayActiveMinutes = null`, `studyTimeTrackingAvailable = false` do chưa tạo migration `study_sessions` |

## 4. Practice/Results được bổ sung & sử dụng
- Tái sử dụng schema `V13__create_practice_engine.sql` đã có trên `origin/main` (`attempts`, `attempt_question_snapshots`, `attempt_answers`, `attempt_snapshot_options`, `questions`, `question_topics`).
- Bổ sung truy vấn tổng hợp trong `AttemptRepository`: `topicStatisticsSince(userId, since)`, `countByUserIdAndStatus`, `findTop5ByUserIdAndStatusOrderBySubmittedAtDescIdDesc`, `averageScoreByUserId`, `findSubmittedLocalDays(userId, timezone)`.
- Bổ sung `PracticeService.getPracticeSummary` và `PracticeEventHandler` để `dashboard` giao tiếp qua `PracticeEvents`.
- **Chưa triển khai (bị chặn bởi chưa xác minh `flyway_schema_history` & `docs/practice/CONTRACT.md` mục 16):** mở rộng `V14` cho TOEIC Part 1–7, `VOCABULARY` quizzes, `practice_sets` và `mistake_records`.

## 5. Study Sessions (Phạm vi bị chặn)
- Chưa tạo bảng `study_sessions` và API heartbeat vì tạo migration mới (`V14`/`V16`) có nguy cơ xung đột với `docs/practice/CONTRACT.md` (`V14`) và `origin/feat/m08-content-ops` (`V15`) khi chưa đọc được `flyway_schema_history` của môi trường dùng chung.
- `DashboardResponse.learningGoal` phản ánh trung thực `studyTimeTrackingAvailable = false`, `todayActiveMinutes = null`, tuyệt đối không ước lượng giả từ `last_active_at` hoặc `duration_seconds`.

## 6. Dashboard API
- **Endpoint:** `GET /api/v1/dashboard` (`@PreAuthorize("hasRole('LEARNER')")`)
- **Ownership:** Chỉ đọc theo `currentUser.getId()` từ JWT principal; không nhận `userId` từ query/path.
- **Nhóm phản hồi (`DashboardResponse`):** `overview`, `vocabularyProgress`, `todayReview`, `learningGoal`, `practiceStatistics`, `weaknessAnalysis`, `recommendations`, `notificationSummary`.

## 7. Event Contracts
- `IdentityEvents.findLearningGoal(UUID)`
- `NotificationEvents.findLearnerTimezone(UUID)` & `countUnreadInFeed(UUID)`
- `LearningEvents.getVocabularyProgress(UUID)`, `getLessonProgressSummary(UUID)`, `countDueCardsByDeck(UUID, Instant)`, `findActivityLocalDays(UUID, String)`
- `VocabularyEvents.findActiveDecksByIds(Set<UUID>)`
- `GrammarEvents.listVisibleTopics()`, `listVisibleLessons()`
- `PracticeEvents.getPracticeSummary(UUID, Instant, String)`

## 8. Migration
- **Không tạo migration mới** trong phạm vi M2 (giữ nguyên chuỗi `V1–V13` với `V11` của M10).

## 9. Danh sách file thay đổi / tạo mới
- **Tạo mới:**
  - `src/main/java/com/masterenglish/api/modules/dashboard/package-info.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/controller/DashboardController.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/dto/response/DashboardResponse.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/service/DashboardService.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/service/impl/DashboardRecommendationBuilder.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/service/impl/DashboardServiceImpl.java`
  - `src/main/java/com/masterenglish/api/modules/practice/service/impl/PracticeEventHandler.java`
  - `src/test/java/com/masterenglish/api/DashboardCrossModuleEventsTests.java`
  - `src/test/java/com/masterenglish/api/modules/dashboard/controller/DashboardControllerUnitTests.java`
  - `src/test/java/com/masterenglish/api/modules/dashboard/controller/DashboardControllerIntegrationTests.java`
  - `src/test/java/com/masterenglish/api/modules/dashboard/service/DashboardServiceImplTests.java`
- **Chỉnh sửa:**
  - `common/events/{IdentityEvents,NotificationEvents,LearningEvents,VocabularyEvents,GrammarEvents,PracticeEvents}.java`
  - `modules/identity/service/{UserService,impl/UserServiceImpl,impl/IdentityEventHandler}.java`
  - `modules/notifications/service/{NotificationService,impl/NotificationServiceImpl,impl/NotificationEventHandler}.java`
  - `modules/learning/repository/{UserWordProgressRepository,WordReviewRepository,LessonProgressRepository}.java`
  - `modules/learning/service/{LearningProgressService,LessonProgressService,impl/LearningProgressServiceImpl,impl/LessonProgressServiceImpl,impl/LearningEventHandler}.java`
  - `modules/vocabulary/repository/DeckRepository.java`, `service/{VocabularyQueryService,impl/VocabularyQueryServiceImpl,impl/VocabularyEventHandler}.java`
  - `modules/grammar/repository/GrammarLessonRepository.java`, `service/{GrammarQueryService,impl/GrammarQueryServiceImpl,impl/GrammarEventHandler}.java`
  - `modules/practice/repository/AttemptRepository.java`, `service/{PracticeService,impl/PracticeServiceImpl}.java`
  - `src/test/java/com/masterenglish/api/ArchitectureTests.java`

## 10. Kết quả kiểm thử & Đối chiếu dữ liệu nguồn
- **Local (không Docker):** `spotlessCheck`, `ArchitectureTests`, `LayerArchitectureTests`, `DashboardControllerUnitTests`, `DashboardServiceImplTests`, `LearningProgressServiceTests`, `LessonProgressServiceTests`, `SelectionPlannerTests`, `QuestionSnapshotValidatorTests`, `bootJar` -> **PASS**.
- **GitHub Actions CI (`run 45461522865`):** `spotlessCheck check bootJar --no-daemon` + Docker build -> **PASS** (`4m27s`).
- `DashboardControllerIntegrationTests` đã đối chiếu trực tiếp `GET /api/v1/dashboard` với `GET /api/v1/vocabulary/progress`, `GET /api/v1/users/me/goal`, `GET /api/v1/notifications/unread-count` trên PostgreSQL 17 Testcontainers, xác nhận khớp 100% và cô lập quyền sở hữu giữa hai learner.

## 11. Known Limitations & Blockers
1. `study_sessions` (thời gian học thực tế/ngày cho M02.06 & M10.06) chưa tạo migration do chưa xác minh `flyway_schema_history`.
2. TOEIC Part 1–7, `VOCABULARY` quizzes và `mistake_records` chưa triển khai do phụ thuộc migration mở rộng `V14` đang bị chặn.

## 12. Commit & PR
- **Feature commits:** `b2c9f6e`, `f622f74`
- **Branch:** `feat/m2-learner-dashboard`
- **Pull Request:** https://github.com/MasterEnglishhh/masterenglish-api/pull/17
