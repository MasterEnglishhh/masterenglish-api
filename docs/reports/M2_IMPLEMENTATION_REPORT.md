# M2 Implementation Report — Learner Dashboard & Practice/Results

## 1. Baseline & Branch Status
- **Official baseline (`origin/main`):** `4afb63163163f0db4a17b572d1815587a1320680` (`Merge pull request #15 from MasterEnglishhh/feat/m04-grammar`)
- **Branch base (`feat/m10-notification-complete`):** `fd4f5f5e7ec3bdebf45e98a48e0999fae5406ef8` (CI `45539073298` **PASS**)
- **Feature branch:** `feat/m2-learner-dashboard`
- **Pull Request:** https://github.com/MasterEnglishhh/masterenglish-api/pull/17 (`base: feat/m10-notification-complete`)

## 2. Trạng thái M02.01–M02.06 & Phân tách 4 khái niệm (Phương án C)

| Mã | Chức năng | Trạng thái | Bằng chứng & Giới hạn thực tế |
|---|---|---|---|
| M02.01 | Xem số từ đã học | **IMPLEMENTED** | `LearningEvents.getVocabularyProgress` -> `LearningProgressService.getProgress` (`studiedSenses`, `masteredSenses`), khớp 100% `GET /api/v1/vocabulary/progress` |
| M02.02 | Xem bài đã làm (Grammar + TOEIC Listening/Reading) | **PARTIAL** *(Grammar hoàn chỉnh; TOEIC bị chặn V14)* | `PracticeStatisticsSummary` tách rõ `grammarLessons` (`V12` `lesson_progress`), `grammarPractice` (`V13` `attempts`, `averagePracticeScorePercent`), `toeicListening` (`parts=[1,2,3,4]`, `available=false`, `UNAVAILABLE_PENDING_MIGRATION`), `toeicReading` (`parts=[5,6,7]`, `available=false`, `UNAVAILABLE_PENDING_MIGRATION`). Không giả lập số liệu TOEIC |
| M02.03 | Xem lịch ôn hôm nay | **IMPLEMENTED** | `dueNow`, `savedSenses` từ `LearningProgressService`, kết hợp `countEligibleDueByDeck` + `VocabularyEvents.findActiveDecksByIds` -> `dueDecks` |
| M02.04 | Xem điểm yếu nổi bật | **PARTIAL** *(Grammar 4 trạng thái hoàn chỉnh; TOEIC/Mistake bị chặn V14)* | `DashboardServiceImpl.buildWeaknessAnalysis` phân biệt chặt 4 trạng thái cho Grammar topics (cửa sổ 30 ngày, mẫu >= 20 câu, ngưỡng < 70% theo `UC06`/`B25`): **`NO_DATA`** (0 câu), **`INSUFFICIENT_DATA`** (>0 câu nhưng chưa chủ điểm nào >= 20), **`NO_WEAKNESS_DETECTED`**, **`READY`**. `toeicListening` (Parts 1–4) và `toeicReading` (Parts 5–7) trả `UNAVAILABLE_PENDING_MIGRATION` |
| M02.05 | Nhận gợi ý luyện tập | **PARTIAL** *(Grammar & Vocabulary truy vết; TOEIC bị chặn V14)* | `DashboardRecommendationBuilder`: quy tắc xác định, gắn rõ `skill` (`VOCABULARY`/`GRAMMAR`) và `reasonCode` (`DUE_FLASHCARDS`, `DUE_DECK_CARDS`, `WEAK_GRAMMAR_TOPIC`, `RESUME_GRAMMAR_LESSON`, `NEXT_GRAMMAR_LESSON`, `SAVED_FLASHCARDS`). Không phụ thuộc M7 AI Tutor và không gợi ý TOEIC khi endpoint/dữ liệu TOEIC chưa tồn tại |
| M02.06 | Theo dõi mục tiêu & kế hoạch học tập | **PARTIAL** *(Goal + Streak + tách 4 khái niệm hoàn chỉnh; `study_schedules` / `study_sessions` / TOEIC baseline bị chặn migration)* | `DashboardResponse.studyPlan` tách bạch 4 khái niệm: **`StudyGoalSummary`** (`plannedDailyMinutes`), **`ToeicTargetSummary`** (`targetScore`, `targetDate`, `currentScore=null`, `status=NOT_CONFIGURED|TARGET_ONLY_BASELINE_UNAVAILABLE`), **`StudyScheduleSummary`** (`available=false`, `supportedModes=[MANUAL, TOEIC_GOAL_PROPOSED]`, `UNAVAILABLE_PENDING_MIGRATION`), **`StudySessionSummary`** (`trackingAvailable=false`, `todayActualMinutes=null`, `plannedDailyMinutes` riêng biệt, `dailyGoalCompleted=null`, `streakDays`, `activeToday`). Tuyệt đối không lấy thời lượng dự kiến thay cho thời lượng học thực tế |

## 3. Practice/Results (Tối thiểu trên `V13`) & Phần còn thiếu cho TOEIC
- **Đã triển khai trên `V13` (`attempts` + `attempt_source_topics`):**
  - Bổ sung API truy vấn lịch sử bài làm: `GET /api/v1/attempts?status=&sourceType=&page=&size=` (`AttemptController.listAttempts` -> `PracticeService.listAttempts` -> `AttemptRepository.findHistory` + `AttemptSourceTopicRepository.findByIdAttemptIdInOrderByIdAttemptIdAscIdTopicIdAsc`), trả `PageResponse<AttemptSummaryResponse>`.
  - Kiểm tra chặt `LEARNER` ownership (`a.userId = :userId`), validate `status` (`IN_PROGRESS`, `SUBMITTED`) và `sourceType` (`GRAMMAR_TOPIC`, `MIXED_GRAMMAR`) trả `400 VALIDATION_ERROR` khi sai, sắp xếp mới nhất trước (`COALESCE(a.submittedAt, a.startedAt) DESC, a.id DESC`), batch-load `topicIds` cho `MIXED_GRAMMAR` tránh N+1 và không lộ `answer_key`.
  - Bổ sung `PracticeService.getPracticeSummary` + `PracticeEventHandler` (`PracticeEvents`) phục vụ `dashboard`.
- **Chưa triển khai (bị chặn bởi Section 8 Flyway Gate & `docs/practice/CONTRACT.md` D8):**
  - Mở rộng `V14__practice_results_expand.sql`: `questions.category` thêm `'TOEIC'`, `part_number` (1–7), `question_groups` (audio/passage Part 3, 4, 6, 7), `practice_sets`, `attempts` vượt ràng buộc `attempts_c3 CHECK (category = 'GRAMMAR')` và `attempts_c7 CHECK (total_count BETWEEN 1 AND 50)` (Part/Full test 100–200 câu), và `mistake_records`.

## 4. Kế hoạch học tập Phương án C (`Study Schedule`, `Study Session`, `TOEIC Target`) — Phần bị chặn migration
- **Hiện trạng `learning_goals` (`V2`):** chỉ có `target_score` (10..990, bội 5), `daily_minutes` (5..240), `target_date`. Được `masterenglish-web` (`goal-client.ts`) tiêu thụ tại `GET|PUT /api/v1/users/me/goal` — hợp đồng này được giữ nguyên 100% tương thích ngược.
- **Lý do dừng tạo bảng/cột mới:**
  1. `spring.jpa.hibernate.ddl-auto=validate` bắt buộc mọi entity/cột mới phải có Flyway migration khớp schema.
  2. Chưa truy cập được `flyway_schema_history` trên DB dùng chung (Staging/EC2) và nhánh `origin/feat/m08-content-ops` đã sử dụng version `V15`, trong khi `V14` bị đóng băng trong `docs/practice/CONTRACT.md`.
  3. Cần chủ dự án xác nhận schema & công thức đề xuất cho Phương án C (Cách 1 tự thiết lập: ngày/khung giờ/thời lượng; Cách 2 đề xuất theo điểm TOEIC hiện tại/mục tiêu/thời hạn/năng lực L&R ở trạng thái `PROPOSED` -> `ACCEPTED`/`REJECTED`).

## 5. API & Event Contracts
- **API mới / mở rộng:**
  - `GET /api/v1/dashboard` (`LEARNER`) — bổ sung `studyPlan` (`studyGoal`, `toeicTarget`, `studySchedule`, `studySession`), breakdown kỹ năng trong `practiceStatistics` và `weaknessAnalysis` (`NO_DATA` / `INSUFFICIENT_DATA` / `NO_WEAKNESS_DETECTED` / `READY` + TOEIC L/R `UNAVAILABLE_PENDING_MIGRATION`), và `skill`/`reasonCode` trong `recommendations`.
  - `GET /api/v1/attempts` (`LEARNER`) — truy vấn lịch sử lượt làm bài có lọc `status`, `sourceType`, phân trang `page`, `size`.
- **Event Contracts (`common/events`):** giữ nguyên không tạo phụ thuộc chéo trực tiếp giữa các business module:
  - `IdentityEvents.findLearningGoal(UUID)`
  - `NotificationEvents.findLearnerTimezone(UUID)`, `countUnreadInFeed(UUID)`
  - `LearningEvents.getVocabularyProgress(UUID)`, `getLessonProgressSummary(UUID)`, `countDueCardsByDeck(UUID, Instant)`, `findActivityLocalDays(UUID, String)`
  - `VocabularyEvents.findActiveDecksByIds(Set<UUID>)`
  - `GrammarEvents.listVisibleTopics()`, `listVisibleLessons()`
  - `PracticeEvents.getPracticeSummary(UUID, Instant, String)`

## 6. Danh sách file tạo mới & chỉnh sửa trên `feat/m2-learner-dashboard` (so với `feat/m10-notification-complete`)
- **Tạo mới:**
  - `docs/reports/M2_IMPLEMENTATION_REPORT.md`
  - `docs/reports/M10_M2_FINAL_REPORT.md`
  - `src/main/java/com/masterenglish/api/modules/dashboard/package-info.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/controller/DashboardController.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/dto/response/DashboardResponse.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/service/DashboardService.java`
  - `src/main/java/com/masterenglish/api/modules/dashboard/service/impl/{DashboardRecommendationBuilder,DashboardServiceImpl}.java`
  - `src/main/java/com/masterenglish/api/modules/practice/dto/response/AttemptSummaryResponse.java`
  - `src/main/java/com/masterenglish/api/modules/practice/service/impl/PracticeEventHandler.java`
  - `src/test/java/com/masterenglish/api/DashboardCrossModuleEventsTests.java`
  - `src/test/java/com/masterenglish/api/modules/dashboard/controller/{DashboardControllerUnitTests,DashboardControllerIntegrationTests}.java`
  - `src/test/java/com/masterenglish/api/modules/dashboard/service/DashboardServiceImplTests.java`
  - `src/test/java/com/masterenglish/api/modules/practice/service/impl/PracticeServiceHistoryUnitTests.java`
- **Chỉnh sửa:**
  - `common/events/{IdentityEvents,NotificationEvents,LearningEvents,VocabularyEvents,GrammarEvents,PracticeEvents}.java`
  - `modules/identity/service/{UserService,impl/UserServiceImpl,impl/IdentityEventHandler}.java`
  - `modules/notifications/service/{NotificationService,impl/NotificationServiceImpl,impl/NotificationEventHandler}.java`
  - `modules/learning/repository/{UserWordProgressRepository,WordReviewRepository,LessonProgressRepository}.java`
  - `modules/learning/service/{LearningProgressService,LessonProgressService,impl/LearningProgressServiceImpl,impl/LessonProgressServiceImpl,impl/LearningEventHandler}.java`
  - `modules/vocabulary/repository/DeckRepository.java`, `service/{VocabularyQueryService,impl/VocabularyQueryServiceImpl,impl/VocabularyEventHandler}.java`
  - `modules/grammar/repository/GrammarLessonRepository.java`, `service/{GrammarQueryService,impl/GrammarQueryServiceImpl,impl/GrammarEventHandler}.java`
  - `modules/practice/package-info.java` (mở rộng `common::api`, `common::pagination`), `controller/AttemptController.java`, `repository/{AttemptRepository,AttemptSourceTopicRepository}.java`, `service/{PracticeService,impl/PracticeServiceImpl}.java`
  - `src/test/java/com/masterenglish/api/ArchitectureTests.java`
  - `src/test/java/com/masterenglish/api/modules/practice/controller/PracticeIntegrationTests.java`

## 7. Migration
- **Không tạo migration mới** trong PR #17 (tuân thủ điều kiện cổng mục 8 trước khi xác minh `flyway_schema_history`).

## 8. Kết quả kiểm thử cục bộ
- **Local (`gradlew.bat spotlessApply spotlessCheck test ... bootJar --no-daemon` trên Windows, không có Docker Desktop):**
  - `spotlessCheck`: **PASS**
  - `bootJar`: **PASS**
  - `ArchitectureTests` (1/1), `LayerArchitectureTests` (13/13): **PASS**
  - `DashboardControllerUnitTests` (1/1), `DashboardServiceImplTests` (6/6), `PracticeServiceHistoryUnitTests` (2/2), `QuestionSnapshotValidatorTests` (17/17), `SelectionPlannerTests` (9/9), `LearningProgressServiceTests` (3/3), `LessonProgressServiceTests` (5/5): **PASS** (`0 failures`, `0 errors`, `0 skipped`)
  - Testcontainers integration suites (`DashboardControllerIntegrationTests`, `PracticeIntegrationTests`, `DashboardCrossModuleEventsTests`, v.v.): **NOT RUN cục bộ** do Docker Desktop không chạy; chạy đầy đủ trên GitHub Actions `ubuntu-24.04`.
