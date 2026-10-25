# ERD — database đã duyệt

Ngày 25/10/2026. Sơ đồ chia theo module để dễ đọc; FK/nullable/unique chính xác nằm ở DATA_DICTIONARY.md và SQL.
Các cạnh biểu diễn tham chiếu entity; quan hệ 1:1 được ràng buộc bằng PK/UNIQUE trong dictionary. ID projection dùng cùng UUID với content_items.

## Luồng chính

```mermaid
flowchart LR
  I[Content item] --> R[Revision bất biến sau công bố]
  R --> D[Revision con được ghim]
  R --> M[Media và nguồn có FK]
  R --> A[Attempt snapshot]
  A --> G[Ngữ cảnh nhóm dùng chung]
  G --> Q[Câu con và option snapshot]
  Q --> S[Autosave có version]
  S --> O[Nộp và chấm atomic]
  O --> H[Kết quả và lỗi]
```

## Identity

```mermaid
erDiagram
    ROLES {
        string primary_key
    }
    MEDIA_ASSETS |o--o{ USERS : "avatar_media_id"
    USERS ||--o{ USER_ROLES : "user_id"
    ROLES ||--o{ USER_ROLES : "role_id"
    USERS |o--o{ USER_ROLES : "assigned_by"
    USERS ||--o{ AUTH_SESSIONS : "user_id"
    AUTH_SESSIONS ||--o{ REFRESH_TOKENS : "session_id"
    REFRESH_TOKENS |o--o| REFRESH_TOKENS : "session_id_replaced_by_id"
    USERS ||--o{ OTP_CHALLENGES : "user_id"
    USERS ||--o{ REGISTRATION_REQUESTS : "user_id"
    OTP_CHALLENGES ||--o| REGISTRATION_REQUESTS : "user_id_otp_purpose_otp_challenge_id"
    USERS ||--o{ PASSWORD_CHANGE_REQUESTS : "user_id"
    OTP_CHALLENGES ||--o| PASSWORD_CHANGE_REQUESTS : "user_id_otp_purpose_otp_challenge_id"
    AUTH_SESSIONS ||--o{ PASSWORD_CHANGE_REQUESTS : "user_id_session_id"
    USERS ||--o{ PASSWORD_RESET_GRANTS : "user_id"
    OTP_CHALLENGES ||--o| PASSWORD_RESET_GRANTS : "user_id_otp_purpose_otp_challenge_id"
    AUTH_RATE_LIMITS {
    AUTH_RATE_LIMITS ||--o{ AUTH_RATE_LIMIT_EVENTS : "rate_limit_id"
```

## Content

```mermaid
erDiagram
    USERS ||--o{ CONTENT_SOURCES : "created_by"
    USERS ||--o{ CONTENT_ITEMS : "created_by"
    CONTENT_REVISIONS |o--o| CONTENT_ITEMS : "id_published_revision_id"
    CONTENT_ITEMS ||--o{ CONTENT_REVISIONS : "content_item_id"
    USERS ||--o{ CONTENT_REVISIONS : "created_by"
    USERS |o--o{ CONTENT_REVISIONS : "reviewed_by"
    CONTENT_REVISIONS ||--o{ CONTENT_SOURCE_LINKS : "revision_id"
    CONTENT_SOURCES ||--o{ CONTENT_SOURCE_LINKS : "source_id"
    CONTENT_REVISIONS ||--o{ REVISION_DEPENDENCIES : "parent_revision_id"
    CONTENT_REVISIONS ||--o{ REVISION_DEPENDENCIES : "child_revision_id"
    CONTENT_REVISIONS ||--o{ REVISION_MEDIA_REFS : "revision_id"
    MEDIA_ASSETS ||--o{ REVISION_MEDIA_REFS : "media_id"
    USERS ||--o{ IMPORT_JOBS : "created_by"
    IMPORT_JOBS ||--o{ IMPORT_JOB_ROWS : "job_id"
    CONTENT_ITEMS |o--o{ IMPORT_JOB_ROWS : "result_content_item_id"
```

## Media

```mermaid
erDiagram
    CONTENT_SOURCES |o--o{ MEDIA_ASSETS : "source_id"
    USERS ||--o{ MEDIA_ASSETS : "uploaded_by"
    MEDIA_ASSETS ||--o| MEDIA_UPLOAD_REQUESTS : "media_id"
    USERS |o--o{ STORAGE_POOLS : "owner_user_id"
    STORAGE_POOLS ||--o{ STORAGE_ALLOCATIONS : "pool_id"
    MEDIA_ASSETS |o--o{ STORAGE_ALLOCATIONS : "media_id"
    MEDIA_UPLOAD_REQUESTS |o--o{ STORAGE_ALLOCATIONS : "upload_request_id"
    IMPORT_JOBS |o--o{ STORAGE_ALLOCATIONS : "import_job_id"
```

## Vocabulary

```mermaid
erDiagram
    CONTENT_ITEMS ||--o| WORDS : "id_content_type"
    CONTENT_REVISIONS ||--o| WORDS : "id_published_revision_id"
    MEDIA_ASSETS |o--o{ WORDS : "audio_media_id"
    WORDS ||--o{ WORD_SENSES : "word_id"
    CONTENT_REVISIONS ||--o{ WORD_SENSES : "word_id_word_revision_id"
    MEDIA_ASSETS |o--o{ WORD_SENSES : "audio_media_id"
    WORD_SENSES ||--o{ WORD_SENSE_EXAMPLES : "word_sense_id"
    CONTENT_ITEMS ||--o| DECKS : "id_content_type"
    CONTENT_REVISIONS ||--o| DECKS : "id_published_revision_id"
    DECKS ||--o{ DECK_WORDS : "deck_id"
    WORD_SENSES ||--o{ DECK_WORDS : "word_sense_id"
```

## Learning

```mermaid
erDiagram
    USERS ||--o{ USER_SAVED_WORDS : "user_id"
    WORD_SENSES ||--o{ USER_SAVED_WORDS : "word_sense_id"
    USERS ||--o{ USER_WORD_PROGRESS : "user_id"
    WORD_SENSES ||--o{ USER_WORD_PROGRESS : "word_sense_id"
    USERS ||--o{ WORD_REVIEWS : "user_id"
    WORD_SENSES ||--o{ WORD_REVIEWS : "source_word_id_word_sense_id"
    CONTENT_REVISIONS ||--o{ WORD_REVIEWS : "source_word_id_source_revision_id"
    USERS ||--o{ LESSON_PROGRESS : "user_id"
    GRAMMAR_LESSONS ||--o{ LESSON_PROGRESS : "lesson_id"
    CONTENT_REVISIONS |o--o{ LESSON_PROGRESS : "lesson_id_last_completed_revision_id"
    USERS ||--o| LEARNING_GOALS : "user_id"
    USERS ||--o{ STUDY_SESSIONS : "user_id"
    WORD_SENSES |o--o{ STUDY_SESSIONS : "word_sense_id"
    GRAMMAR_LESSONS |o--o{ STUDY_SESSIONS : "lesson_id"
    ATTEMPTS |o--o{ STUDY_SESSIONS : "user_id_attempt_id"
```

## Grammar

```mermaid
erDiagram
    CONTENT_ITEMS ||--o| GRAMMAR_TOPICS : "id_content_type"
    CONTENT_REVISIONS ||--o| GRAMMAR_TOPICS : "id_published_revision_id"
    CONTENT_ITEMS ||--o| GRAMMAR_LESSONS : "id_content_type"
    CONTENT_REVISIONS ||--o| GRAMMAR_LESSONS : "id_published_revision_id"
    GRAMMAR_TOPICS ||--o{ GRAMMAR_LESSONS : "topic_id"
```

## Practice

```mermaid
erDiagram
    QUESTION_TYPES {
        string primary_key
    }
    CONTENT_ITEMS ||--o| QUESTION_GROUPS : "id_content_type"
    CONTENT_REVISIONS ||--o| QUESTION_GROUPS : "id_published_revision_id"
    CONTENT_ITEMS ||--o| QUESTIONS : "id_content_type"
    CONTENT_REVISIONS ||--o| QUESTIONS : "id_published_revision_id"
    QUESTION_TYPES ||--o{ QUESTIONS : "question_type_code"
    QUESTION_GROUPS |o--o{ QUESTIONS : "group_id"
    QUESTION_GROUPS |o--o{ QUESTIONS : "group_id_category"
    QUESTION_GROUPS |o--o{ QUESTIONS : "group_id_category_part_number"
    QUESTIONS ||--o{ QUESTION_OPTIONS : "question_id"
    QUESTION_GROUPS ||--o{ GROUP_MATERIALS : "group_id"
    MEDIA_ASSETS |o--o{ GROUP_MATERIALS : "media_id"
    QUESTIONS ||--o{ QUESTION_MATERIALS : "question_id"
    MEDIA_ASSETS |o--o{ QUESTION_MATERIALS : "media_id"
    CONTENT_ITEMS ||--o| PRACTICE_SETS : "id_content_type"
    CONTENT_REVISIONS ||--o| PRACTICE_SETS : "id_published_revision_id"
    DECKS |o--o{ PRACTICE_SETS : "deck_id"
    GRAMMAR_TOPICS |o--o{ PRACTICE_SETS : "topic_id"
    PRACTICE_SETS ||--o{ SET_ITEMS : "set_id"
    QUESTIONS |o--o{ SET_ITEMS : "question_id"
    QUESTION_GROUPS |o--o{ SET_ITEMS : "group_id"
    QUESTIONS ||--o{ QUESTION_WORDS : "question_id"
    WORD_SENSES ||--o{ QUESTION_WORDS : "word_sense_id"
    QUESTIONS ||--o{ QUESTION_TOPICS : "question_id"
    GRAMMAR_TOPICS ||--o{ QUESTION_TOPICS : "topic_id"
```

## Results

```mermaid
erDiagram
    USERS ||--o{ ATTEMPTS : "user_id"
    PRACTICE_SETS |o--o{ ATTEMPTS : "source_set_id"
    DECKS |o--o{ ATTEMPTS : "source_deck_id"
    GRAMMAR_TOPICS |o--o{ ATTEMPTS : "source_topic_id"
    CONTENT_REVISIONS |o--o{ ATTEMPTS : "source_revision_id"
    ATTEMPTS ||--o{ ATTEMPT_SOURCE_TOPICS : "attempt_id"
    GRAMMAR_TOPICS ||--o{ ATTEMPT_SOURCE_TOPICS : "topic_id"
    CONTENT_REVISIONS ||--o{ ATTEMPT_SOURCE_TOPICS : "topic_id_source_revision_id"
    ATTEMPTS ||--o{ ATTEMPT_GROUP_SNAPSHOTS : "attempt_id"
    QUESTION_GROUPS ||--o{ ATTEMPT_GROUP_SNAPSHOTS : "group_id"
    ATTEMPTS ||--o{ ATTEMPT_GROUP_SNAPSHOTS : "attempt_id_category"
    QUESTION_GROUPS ||--o{ ATTEMPT_GROUP_SNAPSHOTS : "group_id_category"
    CONTENT_REVISIONS ||--o{ ATTEMPT_GROUP_SNAPSHOTS : "group_id_source_revision_id"
    ATTEMPTS ||--o{ ATTEMPT_QUESTION_SNAPSHOTS : "attempt_id"
    QUESTIONS ||--o{ ATTEMPT_QUESTION_SNAPSHOTS : "question_id"
    ATTEMPTS ||--o{ ATTEMPT_QUESTION_SNAPSHOTS : "attempt_id_category"
    QUESTIONS ||--o{ ATTEMPT_QUESTION_SNAPSHOTS : "question_id_category"
    CONTENT_REVISIONS ||--o{ ATTEMPT_QUESTION_SNAPSHOTS : "question_id_source_revision_id"
    ATTEMPT_GROUP_SNAPSHOTS |o--o{ ATTEMPT_QUESTION_SNAPSHOTS : "attempt_id_group_snapshot_id"
    QUESTION_TYPES ||--o{ ATTEMPT_QUESTION_SNAPSHOTS : "question_type_code"
    ATTEMPT_QUESTION_SNAPSHOTS ||--o{ ATTEMPT_SNAPSHOT_OPTIONS : "snapshot_id"
    ATTEMPT_QUESTION_SNAPSHOTS ||--o| ATTEMPT_ANSWERS : "attempt_id_snapshot_id"
    ATTEMPT_SNAPSHOT_OPTIONS |o--o| ATTEMPT_ANSWERS : "snapshot_id_selected_option_id"
    ATTEMPTS ||--o{ ATTEMPT_MEDIA_REFS : "attempt_id"
    MEDIA_ASSETS ||--o{ ATTEMPT_MEDIA_REFS : "media_id"
    USERS ||--o{ MISTAKE_RECORDS : "user_id"
    QUESTIONS ||--o{ MISTAKE_RECORDS : "question_id"
    ATTEMPTS ||--o{ MISTAKE_RECORDS : "user_id_source_attempt_id"
    ATTEMPT_QUESTION_SNAPSHOTS ||--o{ MISTAKE_RECORDS : "source_attempt_id_source_snapshot_id"
    ATTEMPT_QUESTION_SNAPSHOTS ||--o{ MISTAKE_RECORDS : "source_snapshot_id_question_id"
    ATTEMPTS ||--o{ ATTEMPT_SOURCE_MISTAKES : "user_id_attempt_id"
    MISTAKE_RECORDS ||--o{ ATTEMPT_SOURCE_MISTAKES : "user_id_mistake_id"
```

## Notifications

```mermaid
erDiagram
    USERS ||--o| NOTIFICATION_PREFERENCES : "user_id"
    REMINDER_RULES {
        string primary_key
    }
    USERS ||--o{ NOTIFICATIONS : "user_id"
    REMINDER_RULES |o--o{ NOTIFICATIONS : "reminder_rule_id"
    NOTIFICATIONS ||--o{ NOTIFICATION_DELIVERIES : "notification_id"
    OTP_CHALLENGES |o--o{ NOTIFICATION_DELIVERIES : "otp_challenge_id"
```

## Administration

```mermaid
erDiagram
    USERS |o--o{ SYSTEM_SETTINGS : "updated_by"
    USERS |o--o{ AUDIT_LOGS : "actor_user_id"
```

## Infrastructure

```mermaid
erDiagram
    EVENT_PUBLICATION {
        string primary_key
    }
```

## Quan hệ đáng chú ý

- users N:N roles; min một role và bảo vệ admin cuối có guard riêng.
- questions.group_id là N:1; không có bảng group_questions N:N. Cờ in_current_group chỉ là membership của projection hiện hành, không đổi context gốc.
- content_items.published_revision_id trỏ về revision của chính item; quan hệ vòng được deferred.
- attempt_answers chọn option bằng FK kép (snapshot_id, option_id), không tham chiếu option hiện hành.
- storage_allocations ghi giữ chỗ/commit/release và cập nhật pool có khóa, không tính quota bằng SUM thiếu phối hợp.
- Nguồn attempt nhiều topic/lỗi và media trong JSON có bảng FK để kiểm tra ownership và giữ lịch sử.
