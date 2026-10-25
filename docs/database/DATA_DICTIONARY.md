# Data dictionary — database đã duyệt

Ngày 25/10/2026. Sinh từ cùng mô hình với proposals/approved-baseline.sql.
Có **61 bảng nghiệp vụ/hỗ trợ + 1 bảng native event_publication**; không tính flyway_schema_history.
Đây là thiết kế/reference SQL, chưa đồng bộ entity hoặc áp dụng migration vào DB dự án.
PRIMARY KEY ngầm NOT NULL. DEFAULT không thay cho validation/API. Các điều kiện liên bảng bổ sung ở SCHEMA_CONTRACTS.md.

## Danh mục bảng

| Module | Bảng | Mục đích |
|---|---|---|
| Identity | [roles](#roles) | Ba role độc lập, danh mục do migration quản lý. |
| Identity | [users](#users) | Tài khoản, profile và trạng thái bảo mật; đăng ký chờ xác minh được thiếu credential chính thức. |
| Identity | [user_roles](#user_roles) | Role hiện tại; lịch sử thay đổi ở audit_logs. |
| Identity | [auth_sessions](#auth_sessions) | Phiên độc lập, hạn cố định tối đa bảy ngày; không lưu device_label. |
| Identity | [refresh_tokens](#refresh_tokens) | Hash token ngẫu nhiên và chuỗi rotation trong một phiên. |
| Identity | [otp_challenges](#otp_challenges) | OTP dùng một lần; bộ đếm liên yêu cầu ở auth_rate_limits. |
| Identity | [registration_requests](#registration_requests) | Ngữ cảnh chờ OTP, không lưu mật khẩu rõ. |
| Identity | [password_change_requests](#password_change_requests) | Ngữ cảnh chờ OTP, không lưu mật khẩu rõ. |
| Identity | [password_reset_grants](#password_reset_grants) | Quyền reset giới hạn, không phải role hay phiên đăng nhập. |
| Identity | [auth_rate_limits](#auth_rate_limits) | Bộ giới hạn độc lập user/challenge ID, có cả email chưa đăng ký. |
| Identity | [auth_rate_limit_events](#auth_rate_limit_events) | Sự kiện đếm trong cửa sổ thời gian trượt, không phải audit đầy đủ. |
| Content | [content_sources](#content_sources) | Xuất xứ và khai báo quyền sử dụng; ACTIVE không phải kết luận pháp lý. |
| Media | [media_assets](#media_assets) | File lâu dài; object bất biến, xóa chỉ khi hết mọi tham chiếu. |
| Media | [media_upload_requests](#media_upload_requests) | Ngữ cảnh upload tạm; finished_at không đổi khi dọn file. |
| Content | [content_items](#content_items) | Định danh ổn định và pointer bản công bố; domain projection dùng cùng UUID. |
| Content | [content_revisions](#content_revisions) | Draft có schema; Published bất biến; một nhánh đang biên soạn mỗi item. |
| Content | [content_source_links](#content_source_links) | Nguồn theo từng revision; bản chụp nguồn lúc công bố. |
| Content | [revision_dependencies](#revision_dependencies) | Ghim revision con; formal edge chỉ tới bản đã công bố. |
| Content | [revision_media_refs](#revision_media_refs) | FK tra cứu tất cả file trong Draft/Published, kể cả ở JSON. |
| Vocabulary | [words](#words) | Projection từ hiện hành; nghĩa là đơn vị theo dõi học. |
| Vocabulary | [word_senses](#word_senses) | Nghĩa ổn định; đổi hẳn nghĩa tạo ID mới, không chuyển SRS. |
| Vocabulary | [word_sense_examples](#word_sense_examples) | Nhiều ví dụ của một nghĩa, cặp Anh/Việt có thứ tự. |
| Vocabulary | [decks](#decks) | Deck công bố, thành viên theo nghĩa và revision đã ghim. |
| Vocabulary | [deck_words](#deck_words) | Thành viên projection của deck; runtime dùng bản ghim khi giao bài. |
| Learning | [user_saved_words](#user_saved_words) | Bỏ lưu chỉ xóa liên kết này, không xóa SRS. |
| Learning | [user_word_progress](#user_word_progress) | Trạng thái SRS hiện tại, khóa theo user và nghĩa. |
| Learning | [word_reviews](#word_reviews) | Sự kiện review bất biến và idempotent, có chính sách/trạng thái trước-sau. |
| Grammar | [grammar_topics](#grammar_topics) | Chủ điểm, không thêm cây curriculum nhiều cấp ngoài phạm vi. |
| Grammar | [grammar_lessons](#grammar_lessons) | Bài học khối có schema, thuộc một topic. |
| Learning | [lesson_progress](#lesson_progress) | Hoàn thành bằng thao tác người học; giữ bản đã hoàn thành khi có bản mới. |
| Practice | [question_types](#question_types) | Catalogue cách trả lời; chỉ bật code có renderer/grader đã triển khai. |
| Practice | [question_groups](#question_groups) | Ngữ cảnh cha; group type không thay question response type. |
| Practice | [questions](#questions) | Câu chấm được; mỗi câu tối đa một nhóm; không dùng group_questions N:N. |
| Practice | [question_options](#question_options) | Lựa chọn của projection; đúng một đáp án khi commit. |
| Practice | [group_materials](#group_materials) | Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus. |
| Practice | [question_materials](#question_materials) | Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus. |
| Practice | [practice_sets](#practice_sets) | Bộ luyện hiện hành; FIXED có set_items, DYNAMIC có selection_config. |
| Practice | [set_items](#set_items) | Một vị trí chọn câu độc lập XOR cả nhóm. |
| Practice | [question_words](#question_words) | Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic. |
| Practice | [question_topics](#question_topics) | Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic. |
| Results | [attempts](#attempts) | Nguồn và kết quả lượt làm; tạo idempotent, snapshot được seal trước khi giao. |
| Results | [attempt_source_topics](#attempt_source_topics) | Nguồn nhiều topic có FK, không chỉ giấu ID trong JSON. |
| Results | [attempt_group_snapshots](#attempt_group_snapshots) | Ngữ cảnh nhóm chung lưu một lần trong mỗi lượt làm. |
| Results | [attempt_question_snapshots](#attempt_question_snapshots) | Đề giao bất biến, đáp án chuẩn/lời giải tách khỏi dữ liệu trước nộp. |
| Results | [attempt_snapshot_options](#attempt_snapshot_options) | Option thuộc đúng snapshot, không FK tới lựa chọn hiện hành có thể sửa. |
| Results | [attempt_answers](#attempt_answers) | Một đáp án hiện tại mỗi câu; version cả khi bỏ chọn. |
| Results | [attempt_media_refs](#attempt_media_refs) | File trong đề/lời giải snapshot; bảo toàn độc lập projection hiện hành. |
| Results | [mistake_records](#mistake_records) | Lỗi theo user/câu; lịch sử sự kiện gốc ở các kết quả bất biến. |
| Results | [attempt_source_mistakes](#attempt_source_mistakes) | Lỗi chọn để luyện lại phải thuộc đúng chủ lượt làm. |
| Learning | [learning_goals](#learning_goals) | Một mục tiêu hiện tại, không diễn giải thành trình độ được dự đoán. |
| Learning | [study_sessions](#study_sessions) | Đoạn thời gian hoạt động ước tính, không cộng trùng nhiều tab/thiết bị. |
| Notifications | [notification_preferences](#notification_preferences) | Nhắc học opt-in theo giờ; email bảo mật không phụ thuộc email nhắc học. |
| Notifications | [reminder_rules](#reminder_rules) | Danh mục quy tắc nhắc, không có chiến dịch quảng bá/browser push. |
| Notifications | [notifications](#notifications) | Thông báo/dedupe; bản ghi INTERNAL dùng cho mail bảo mật, không vào feed learner. |
| Notifications | [notification_deliveries](#notification_deliveries) | Queue gửi có claim/lease; payload OTP chỉ lưu mã hóa, không rõ trong event/log. |
| Content | [import_jobs](#import_jobs) | Ngữ cảnh upload/import riêng, DryRun trước khi tạo Draft. |
| Content | [import_job_rows](#import_job_rows) | Ledger từng dòng thay bảng import_job_errors đơn thuần; retry không tạo entity trùng. |
| Media | [storage_pools](#storage_pools) | Ngân sách CONTENT/TEMP toàn hệ thống và AVATAR từng user; hạ trần không xóa dữ liệu. |
| Media | [storage_allocations](#storage_allocations) | Ledger giữ chỗ/file, chỉ giải phóng sau đối chiếu, không reset quota bằng archive. |
| Administration | [system_settings](#system_settings) | Allowlist typed; không dùng bảng này để chứa secret. |
| Administration | [audit_logs](#audit_logs) | Audit append-only cho tác vụ quản trị; không sao chép secret/URL có chữ ký. |
| Infrastructure | [event_publication](#event_publication) | Schema native Modulith 2.1, giữ đúng V1; không tạo registry khác. |

## roles

Ba role độc lập, danh mục do migration quản lý.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `SMALLINT PRIMARY KEY` | Mã số nhỏ, cố định |
| `code` | `VARCHAR(30) NOT NULL UNIQUE` | Mã quyền dùng trong API/JWT |
| `name` | `VARCHAR(100) NOT NULL` | Nhãn hiển thị |

**Ràng buộc bảng**

- `CHECK (code IN ('LEARNER','CONTENT_ADMIN','SYSTEM_ADMIN'))`

## users

Tài khoản, profile và trạng thái bảo mật; đăng ký chờ xác minh được thiếu credential chính thức.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Định danh tài khoản |
| `email` | `VARCHAR(254) NOT NULL` | Email cố định, khóa so sánh lower(email) |
| `first_name` | `VARCHAR(30)` | Phần tên, có thể gồm tên đệm |
| `last_name` | `VARCHAR(30)` | Phần họ |
| `password_hash` | `VARCHAR(255)` | BCrypt, không phải mật khẩu rõ |
| `phone` | `VARCHAR(30)` | Tùy chọn, dạng chuỗi, không unique |
| `avatar_media_id` | `UUID` | Ảnh READY/AVATAR của chính user |
| `status` | `VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION'` | Vòng đời tài khoản |
| `email_verified_at` | `TIMESTAMPTZ` | Thời điểm xác minh |
| `locked_until` | `TIMESTAMPTZ` | Hạn khóa tạm |
| `password_changed_at` | `TIMESTAMPTZ` | Thời điểm gán/đổi credential |
| `auth_version` | `INTEGER NOT NULL DEFAULT 0` | Thu hồi quyền xác thực cũ |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Optimistic lock |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','LOCKED','DISABLED'))`
- `CHECK (email = btrim(email) AND email <> '')`
- `CHECK (first_name IS NULL OR (first_name = btrim(first_name) AND first_name <> ''))`
- `CHECK (last_name IS NULL OR (last_name = btrim(last_name) AND last_name <> ''))`
- `CHECK (phone IS NULL OR (phone = btrim(phone) AND phone <> ''))`
- `CHECK (password_hash IS NULL OR password_hash ~ '^(\{bcrypt\})?\$2[aby]\$(1[0-9]&#124;2[0-9]&#124;3[01])\$[./A-Za-z0-9]{53}$')`
- `CHECK (email_verified_at IS NULL OR (first_name IS NOT NULL AND last_name IS NOT NULL AND password_hash IS NOT NULL))`
- `CHECK (status <> 'ACTIVE' OR email_verified_at IS NOT NULL)`
- `CHECK (status <> 'PENDING_VERIFICATION' OR email_verified_at IS NULL)`
- `CHECK ((status = 'LOCKED') = (locked_until IS NOT NULL))`
- `CHECK (auth_version >= 0 AND version >= 0)`
- `CHECK (updated_at >= created_at)`
- FK `(avatar_media_id)` → `media_assets(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_users_email ON users(lower(email));`
- `CREATE INDEX ix_users_status_created ON users(status, created_at DESC);`

## user_roles

Role hiện tại; lịch sử thay đổi ở audit_logs.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `user_id` | `UUID NOT NULL` | Tài khoản được gán |
| `role_id` | `SMALLINT NOT NULL` | Role |
| `assigned_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc gán |
| `assigned_by` | `UUID` | Người gán; NULL cho signup/bootstrap |

**Ràng buộc bảng**

- `PRIMARY KEY (user_id, role_id)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(role_id)` → `roles(id)`; DELETE RESTRICT.
- FK `(assigned_by)` → `users(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_user_roles_role ON user_roles(role_id, user_id);`

## auth_sessions

Phiên độc lập, hạn cố định tối đa bảy ngày; không lưu device_label.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Định danh phiên/JWT sid |
| `user_id` | `UUID NOT NULL` | Chủ phiên |
| `user_agent` | `TEXT` | Metadata trình duyệt, không phải danh tính thiết bị |
| `ip_address` | `INET` | IP qua chuỗi proxy tin cậy |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Lần đăng nhập |
| `last_active_at` | `TIMESTAMPTZ` | Không dùng làm phút học |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | Không được kéo dài bởi refresh |
| `revoked_at` | `TIMESTAMPTZ` | Mốc thu hồi |
| `revoke_reason` | `VARCHAR(32)` | Lý do thu hồi |

**Ràng buộc bảng**

- `UNIQUE (user_id, id)`
- `CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days')`
- `CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))`
- `CHECK (last_active_at IS NULL OR last_active_at >= created_at)`
- `CHECK (revoke_reason IN ('LOGOUT','PASSWORD_CHANGE','PASSWORD_RESET','ROLE_CHANGE','ACCOUNT_LOCKED','ACCOUNT_DISABLED','SECURITY','SESSION_LIMIT','EXPIRED'))`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_sessions_user_live ON auth_sessions(user_id, created_at) WHERE revoked_at IS NULL;`
- `CREATE INDEX ix_sessions_expiry ON auth_sessions(expires_at);`

## refresh_tokens

Hash token ngẫu nhiên và chuỗi rotation trong một phiên.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Token ID nội bộ |
| `session_id` | `UUID NOT NULL` | Phiên sở hữu |
| `token_hash` | `VARCHAR(64) NOT NULL UNIQUE` | SHA-256 của opaque token |
| `issued_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cấp |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | Không vượt hạn phiên |
| `revoked_at` | `TIMESTAMPTZ` | Mốc thu hồi hoặc rotate |
| `replaced_by_id` | `UUID UNIQUE` | Token kế tiếp trong cùng phiên |
| `last_used_at` | `TIMESTAMPTZ` | Lần refresh thành công |

**Ràng buộc bảng**

- `UNIQUE (session_id, id)`
- `CHECK (token_hash ~ '^[0-9a-f]{64}$')`
- `CHECK (expires_at > issued_at)`
- `CHECK (replaced_by_id IS NULL OR (replaced_by_id <> id AND revoked_at IS NOT NULL))`
- FK `(session_id)` → `auth_sessions(id)`; DELETE RESTRICT.
- FK `(session_id, replaced_by_id)` → `refresh_tokens(session_id, id)`; DELETE RESTRICT; deferred đến commit.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_refresh_one_unrevoked ON refresh_tokens(session_id) WHERE revoked_at IS NULL;`
- `CREATE INDEX ix_refresh_expiry ON refresh_tokens(expires_at);`

## otp_challenges

OTP dùng một lần; bộ đếm liên yêu cầu ở auth_rate_limits.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Challenge ID |
| `user_id` | `UUID NOT NULL` | Chủ challenge |
| `purpose` | `VARCHAR(24) NOT NULL` | Không dùng chéo mục đích |
| `otp_hmac` | `VARCHAR(64)` | HMAC, được xóa khi kết thúc |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | Hạn mã hiện tại, 5 phút |
| `last_sent_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc gửi gần nhất |
| `attempt_count` | `SMALLINT NOT NULL DEFAULT 0` | Lỗi trên challenge cụ thể |
| `blocked_until` | `TIMESTAMPTZ` | Không thay bộ đếm chung |
| `consumed_at` | `TIMESTAMPTZ` | OTP đã dùng |
| `invalidated_at` | `TIMESTAMPTZ` | OTP bị thay/hủy |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc tạo |

**Ràng buộc bảng**

- `UNIQUE (user_id, purpose, id)`
- `CHECK (purpose IN ('EMAIL_VERIFICATION','PASSWORD_CHANGE','PASSWORD_RESET'))`
- `CHECK (otp_hmac IS NULL OR otp_hmac ~ '^[0-9a-f]{64}$')`
- `CHECK (attempt_count BETWEEN 0 AND 5)`
- `CHECK (expires_at > created_at AND expires_at <= last_sent_at + INTERVAL '5 minutes')`
- `CHECK (NOT (consumed_at IS NOT NULL AND invalidated_at IS NOT NULL))`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_otp_open ON otp_challenges(user_id, purpose) WHERE consumed_at IS NULL AND invalidated_at IS NULL;`
- `CREATE INDEX ix_otp_expiry ON otp_challenges(expires_at);`

## registration_requests

Ngữ cảnh chờ OTP, không lưu mật khẩu rõ.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Định danh yêu cầu |
| `user_id` | `UUID NOT NULL` | Chủ yêu cầu |
| `otp_challenge_id` | `UUID NOT NULL UNIQUE` | Challenge đúng ngữ cảnh |
| `otp_purpose` | `VARCHAR(24) GENERATED ALWAYS AS ('EMAIL_VERIFICATION'::VARCHAR) STORED` | Discriminator FK do DB sinh |
| `pending_first_name` | `VARCHAR(30) NOT NULL` | Phần tên của đúng lượt đăng ký |
| `pending_last_name` | `VARCHAR(30) NOT NULL` | Phần họ của đúng lượt đăng ký |
| `pending_password_hash` | `VARCHAR(255)` | BCrypt chờ xác nhận, được dọn khi kết thúc |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | Hạn chu kỳ 7 ngày hoặc yêu cầu đổi 5 phút |
| `completed_at` | `TIMESTAMPTZ` | Hoàn tất |
| `invalidated_at` | `TIMESTAMPTZ` | Bị hủy/thay thế |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (pending_password_hash IS NULL OR pending_password_hash ~ '^(\{bcrypt\})?\$2[aby]\$(1[0-9]&#124;2[0-9]&#124;3[01])\$[./A-Za-z0-9]{53}$')`
- `CHECK (completed_at IS NOT NULL OR invalidated_at IS NOT NULL OR pending_password_hash IS NOT NULL)`
- `CHECK (NOT (completed_at IS NOT NULL AND invalidated_at IS NOT NULL))`
- `CHECK (expires_at > created_at)`
- `CHECK (pending_first_name = btrim(pending_first_name) AND pending_first_name <> '')`
- `CHECK (pending_last_name = btrim(pending_last_name) AND pending_last_name <> '')`
- `CHECK (expires_at <= created_at + INTERVAL '7 days')`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(user_id, otp_purpose, otp_challenge_id)` → `otp_challenges(user_id, purpose, id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_registration_requests_open ON registration_requests(user_id) WHERE completed_at IS NULL AND invalidated_at IS NULL;`
- `CREATE INDEX ix_registration_requests_expiry ON registration_requests(expires_at);`

## password_change_requests

Ngữ cảnh chờ OTP, không lưu mật khẩu rõ.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Định danh yêu cầu |
| `user_id` | `UUID NOT NULL` | Chủ yêu cầu |
| `otp_challenge_id` | `UUID NOT NULL UNIQUE` | Challenge đúng ngữ cảnh |
| `otp_purpose` | `VARCHAR(24) GENERATED ALWAYS AS ('PASSWORD_CHANGE'::VARCHAR) STORED` | Discriminator FK do DB sinh |
| `session_id` | `UUID NOT NULL` | Chỉ phiên khởi tạo được hoàn tất |
| `auth_version_at_request` | `INTEGER NOT NULL` | Phiên bản xác thực lúc kiểm tra mật khẩu cũ |
| `pending_password_hash` | `VARCHAR(255)` | BCrypt chờ xác nhận, được dọn khi kết thúc |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | Hạn chu kỳ 7 ngày hoặc yêu cầu đổi 5 phút |
| `completed_at` | `TIMESTAMPTZ` | Hoàn tất |
| `invalidated_at` | `TIMESTAMPTZ` | Bị hủy/thay thế |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (pending_password_hash IS NULL OR pending_password_hash ~ '^(\{bcrypt\})?\$2[aby]\$(1[0-9]&#124;2[0-9]&#124;3[01])\$[./A-Za-z0-9]{53}$')`
- `CHECK (completed_at IS NOT NULL OR invalidated_at IS NOT NULL OR pending_password_hash IS NOT NULL)`
- `CHECK (NOT (completed_at IS NOT NULL AND invalidated_at IS NOT NULL))`
- `CHECK (expires_at > created_at)`
- `CHECK (auth_version_at_request >= 0)`
- `CHECK (expires_at <= created_at + INTERVAL '5 minutes')`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(user_id, otp_purpose, otp_challenge_id)` → `otp_challenges(user_id, purpose, id)`; DELETE RESTRICT.
- FK `(user_id, session_id)` → `auth_sessions(user_id, id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_password_change_requests_open ON password_change_requests(user_id) WHERE completed_at IS NULL AND invalidated_at IS NULL;`
- `CREATE INDEX ix_password_change_requests_expiry ON password_change_requests(expires_at);`

## password_reset_grants

Quyền reset giới hạn, không phải role hay phiên đăng nhập.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | ID grant |
| `user_id` | `UUID NOT NULL` | Chủ grant |
| `otp_challenge_id` | `UUID NOT NULL UNIQUE` | Một OTP chỉ cấp một grant |
| `otp_purpose` | `VARCHAR(24) GENERATED ALWAYS AS ('PASSWORD_RESET'::VARCHAR) STORED` | Đúng purpose của OTP |
| `grant_hash` | `VARCHAR(64) UNIQUE` | Hash opaque grant, được dọn khi hết hiệu lực |
| `auth_version_at_issue` | `INTEGER NOT NULL` | Chống dùng grant cũ sau thay bảo mật |
| `issued_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc cấp |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | Tối đa 5 phút |
| `used_at` | `TIMESTAMPTZ` | Mốc sử dụng |
| `invalidated_at` | `TIMESTAMPTZ` | Mốc hủy/thay |

**Ràng buộc bảng**

- `CHECK (grant_hash IS NULL OR grant_hash ~ '^[0-9a-f]{64}$')`
- `CHECK (used_at IS NOT NULL OR invalidated_at IS NOT NULL OR grant_hash IS NOT NULL)`
- `CHECK (expires_at > issued_at AND expires_at <= issued_at + INTERVAL '5 minutes')`
- `CHECK (auth_version_at_issue >= 0)`
- `CHECK (NOT (used_at IS NOT NULL AND invalidated_at IS NOT NULL))`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(user_id, otp_purpose, otp_challenge_id)` → `otp_challenges(user_id, purpose, id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_reset_grant_open ON password_reset_grants(user_id) WHERE used_at IS NULL AND invalidated_at IS NULL;`

## auth_rate_limits

Bộ giới hạn độc lập user/challenge ID, có cả email chưa đăng ký.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Bucket ID |
| `scope` | `VARCHAR(64) NOT NULL` | Quy tắc và mục đích, tách email/IP |
| `subject_hash` | `VARCHAR(64) NOT NULL` | HMAC định danh chuẩn hóa |
| `blocked_until` | `TIMESTAMPTZ` | Hạn chờ, không kéo dài vì request bị chặn |
| `reset_at` | `TIMESTAMPTZ` | Mốc đặt lại lỗi, không xóa quota gửi |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc cập nhật |

**Ràng buộc bảng**

- `UNIQUE (scope, subject_hash)`
- `CHECK (subject_hash ~ '^[0-9a-f]{64}$')`
- `CHECK (scope IN ('OTP_SEND_EMAIL','LOGIN_FAILURE_EMAIL','OTP_VERIFY_EMAIL_VERIFICATION','OTP_VERIFY_PASSWORD_CHANGE','OTP_VERIFY_PASSWORD_RESET','OTP_SEND_IP','OTP_VERIFY_IP','LOGIN_IP'))`

## auth_rate_limit_events

Sự kiện đếm trong cửa sổ thời gian trượt, không phải audit đầy đủ.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | ID sự kiện |
| `rate_limit_id` | `UUID NOT NULL` | Bucket |
| `occurred_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc được tính |

**Ràng buộc bảng**

- Không có CHECK/unique bổ sung ngoài cột.
- FK `(rate_limit_id)` → `auth_rate_limits(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_rate_events_window ON auth_rate_limit_events(rate_limit_id, occurred_at DESC);`

## content_sources

Xuất xứ và khai báo quyền sử dụng; ACTIVE không phải kết luận pháp lý.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Source ID |
| `title` | `VARCHAR(255) NOT NULL` | Tên nguồn |
| `source_type` | `VARCHAR(20) NOT NULL` | Tự soạn hoặc bên ngoài |
| `source_url` | `TEXT` | URL nguồn gốc, không phải signed URL |
| `author` | `VARCHAR(255)` | Tác giả/đơn vị, không tự đồng nhất người nhập |
| `license_code` | `VARCHAR(100)` | Khai báo giấy phép, có thể thiếu ở Draft |
| `license_url` | `TEXT` | URL thông tin giấy phép |
| `attribution` | `TEXT` | Nội dung ghi nguồn |
| `permission_note` | `TEXT` | Thông tin khai báo nội bộ, không tự trả learner |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` | Chọn mới hay archive |
| `created_by` | `UUID NOT NULL` | Người khai báo |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (source_type IN ('SELF_AUTHORED','EXTERNAL'))`
- `CHECK (status IN ('ACTIVE','ARCHIVED'))`
- `CHECK (btrim(title) <> '')`
- FK `(created_by)` → `users(id)`; DELETE RESTRICT.

## media_assets

File lâu dài; object bất biến, xóa chỉ khi hết mọi tham chiếu.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Media ID ổn định |
| `media_type` | `VARCHAR(16) NOT NULL` | IMAGE/AUDIO hiện tại |
| `purpose` | `VARCHAR(16) NOT NULL` | CONTENT/AVATAR |
| `bucket` | `VARCHAR(100)` | Kho file cuối |
| `object_key` | `VARCHAR(500)` | Key cuối, không phải URL |
| `original_filename` | `VARCHAR(255) NOT NULL` | Tên để hiển thị |
| `mime_type` | `VARCHAR(100)` | MIME thực tế sau xác minh |
| `file_size_bytes` | `BIGINT` | Byte thực tế |
| `duration_ms` | `INTEGER` | Thời lượng audio |
| `width` | `INTEGER` | Chiều rộng ảnh |
| `height` | `INTEGER` | Chiều cao ảnh |
| `checksum_sha256` | `VARCHAR(64)` | Hash file thực tế |
| `source_id` | `UUID` | Nguồn chính của file, avatar tùy chọn |
| `uploaded_by` | `UUID NOT NULL` | Chủ lượt upload |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'PENDING'` | Vòng đời file |
| `failure_code` | `VARCHAR(64)` | Lỗi có kiểm soát |
| `archived_at` | `TIMESTAMPTZ` | Mốc archive ổn định |
| `deletion_started_at` | `TIMESTAMPTZ` | Claim ngăn cấp/gắn mới khi đang dọn |
| `object_deleted_at` | `TIMESTAMPTZ` | Backend xác nhận đối tượng cuối đã xóa, khác việc archive |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (media_type IN ('IMAGE','AUDIO'))`
- `CHECK (purpose IN ('CONTENT','AVATAR'))`
- `CHECK (status IN ('PENDING','READY','FAILED','ARCHIVED'))`
- `UNIQUE (bucket, object_key)`
- `CHECK (purpose <> 'AVATAR' OR media_type = 'IMAGE')`
- `CHECK (file_size_bytes IS NULL OR file_size_bytes > 0)`
- `CHECK (checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$')`
- `CHECK (status NOT IN ('READY','ARCHIVED') OR (bucket IS NOT NULL AND object_key IS NOT NULL AND mime_type IS NOT NULL AND file_size_bytes IS NOT NULL AND checksum_sha256 IS NOT NULL))`
- `CHECK (status NOT IN ('READY','ARCHIVED') OR (media_type = 'IMAGE' AND width > 0 AND height > 0 AND width IS NOT NULL AND height IS NOT NULL AND duration_ms IS NULL) OR (media_type = 'AUDIO' AND duration_ms > 0 AND duration_ms IS NOT NULL AND width IS NULL AND height IS NULL))`
- `CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))`
- `CHECK (deletion_started_at IS NULL OR status IN ('ARCHIVED','FAILED'))`
- `CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)`
- `CHECK (file_size_bytes IS NULL OR file_size_bytes <= CASE WHEN purpose='AVATAR' THEN 2097152 WHEN media_type='IMAGE' THEN 5242880 ELSE 20971520 END)`
- `CHECK (mime_type IS NULL OR (media_type='IMAGE' AND mime_type IN ('image/jpeg','image/png','image/webp')) OR (media_type='AUDIO' AND mime_type IN ('audio/mpeg','audio/mp4')))`
- `CHECK (original_filename <> '' AND original_filename !~ '[[:cntrl:]]')`
- FK `(source_id)` → `content_sources(id)`; DELETE RESTRICT.
- FK `(uploaded_by)` → `users(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_media_owner_purpose ON media_assets(uploaded_by, purpose, status);`
- `CREATE INDEX ix_media_cleanup ON media_assets(status, archived_at);`

## media_upload_requests

Ngữ cảnh upload tạm; finished_at không đổi khi dọn file.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Upload request ID |
| `media_id` | `UUID NOT NULL UNIQUE` | Một media cho một yêu cầu |
| `temp_bucket` | `VARCHAR(100) NOT NULL` | Kho tạm |
| `temp_object_key` | `VARCHAR(500) NOT NULL` | Key tạm duy nhất |
| `declared_size_bytes` | `BIGINT NOT NULL` | Byte khai báo, phải khớp file thật |
| `declared_mime_type` | `VARCHAR(100) NOT NULL` | MIME khai báo |
| `max_size_bytes` | `BIGINT NOT NULL` | Trần được backend cấp |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'PENDING'` | Trạng thái yêu cầu |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | Hạn nhận complete |
| `verification_started_at` | `TIMESTAMPTZ` | Mốc nhận complete hợp lệ |
| `verification_deadline_at` | `TIMESTAMPTZ` | Hạn toàn quá trình, không kéo dài retry |
| `claim_token` | `UUID` | Lượt xử lý đang giữ quyền |
| `lease_until` | `TIMESTAMPTZ` | Hạn quyền xử lý |
| `completed_at` | `TIMESTAMPTZ` | Mốc thành công |
| `finished_at` | `TIMESTAMPTZ` | Mốc kết thúc mọi trạng thái, cho retention ổn định |
| `failure_code` | `VARCHAR(64)` | Mã lỗi |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (status IN ('PENDING','VERIFYING','COMPLETED','FAILED','EXPIRED'))`
- `UNIQUE (temp_bucket, temp_object_key)`
- `CHECK (declared_size_bytes > 0 AND declared_size_bytes <= max_size_bytes AND max_size_bytes <= 20971520)`
- `CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '10 minutes')`
- `CHECK ((verification_started_at IS NULL) = (verification_deadline_at IS NULL))`
- `CHECK (verification_started_at IS NULL OR (verification_started_at <= expires_at AND verification_deadline_at > verification_started_at AND verification_deadline_at <= verification_started_at + INTERVAL '5 minutes'))`
- `CHECK ((claim_token IS NULL) = (lease_until IS NULL))`
- `CHECK (lease_until IS NULL OR (status='VERIFYING' AND lease_until <= verification_deadline_at))`
- `CHECK ((status='COMPLETED') = (completed_at IS NOT NULL))`
- `CHECK ((status IN ('COMPLETED','FAILED','EXPIRED')) = (finished_at IS NOT NULL))`
- `CHECK (status NOT IN ('FAILED','EXPIRED') OR failure_code IS NOT NULL)`
- `CHECK (status <> 'VERIFYING' OR verification_started_at IS NOT NULL)`
- FK `(media_id)` → `media_assets(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_uploads_open ON media_upload_requests(status, expires_at);`
- `CREATE INDEX ix_uploads_claim ON media_upload_requests(lease_until) WHERE status='VERIFYING';`

## content_items

Định danh ổn định và pointer bản công bố; domain projection dùng cùng UUID.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Định danh dùng xuyên phiên bản |
| `content_type` | `VARCHAR(30) NOT NULL` | Loại nội dung, bất biến |
| `created_by` | `UUID NOT NULL` | Người khởi tạo |
| `published_revision_id` | `UUID` | Bản hiện hành, NULL khi chưa công bố |
| `is_archived` | `BOOLEAN NOT NULL DEFAULT FALSE` | Ngừng cấp mới |
| `archived_at` | `TIMESTAMPTZ` | Mốc archive |
| `access_revoked_at` | `TIMESTAMPTZ` | Thu hồi truy cập riêng với archive |
| `access_revoke_reason` | `TEXT` | Lý do thu hồi |
| `import_namespace` | `VARCHAR(120)` | Namespace khóa ngoài nếu nhập có key |
| `external_key` | `VARCHAR(200)` | Nhận diện xuyên job, không tự suy theo lemma |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Optimistic lock của item |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `UNIQUE (id, content_type)`
- `CHECK (content_type IN ('WORD','DECK','GRAMMAR_TOPIC','GRAMMAR_LESSON','QUESTION','QUESTION_GROUP','PRACTICE_SET'))`
- `CHECK ((is_archived) = (archived_at IS NOT NULL))`
- `CHECK ((access_revoked_at IS NULL) = (access_revoke_reason IS NULL))`
- `CHECK ((import_namespace IS NULL) = (external_key IS NULL))`
- `CHECK (version >= 0)`
- FK `(created_by)` → `users(id)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_content_external_key ON content_items(content_type, import_namespace, external_key) WHERE external_key IS NOT NULL;`
- `CREATE INDEX ix_content_catalog ON content_items(content_type, is_archived);`

## content_revisions

Draft có schema; Published bất biến; một nhánh đang biên soạn mỗi item.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Revision ID |
| `content_item_id` | `UUID NOT NULL` | Nội dung sở hữu |
| `version_no` | `INTEGER NOT NULL` | Số thứ tự bản, khác optimistic version |
| `schema_version` | `SMALLINT NOT NULL DEFAULT 1` | Schema payload theo content_type |
| `snapshot` | `JSONB NOT NULL DEFAULT '{}'` | Nguồn chân lý của bản, Draft có thể thiếu |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'DRAFT'` | Vòng đời biên soạn |
| `change_summary` | `TEXT` | Tóm tắt thay đổi |
| `created_by` | `UUID NOT NULL` | Người tạo bản |
| `reviewed_by` | `UUID` | Người duyệt/từ chối gần nhất |
| `review_note` | `TEXT` | Lý do khi từ chối |
| `reviewed_at` | `TIMESTAMPTZ` | Mốc quyết định duyệt |
| `published_at` | `TIMESTAMPTZ` | Mốc công bố, không phải pointer hiện hành |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Chống ghi đè Draft |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `UNIQUE (content_item_id, id)`
- `UNIQUE (content_item_id, version_no)`
- `CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED'))`
- `CHECK (jsonb_typeof(snapshot) = 'object' AND octet_length(snapshot::text) <= 1048576)`
- `CHECK (version_no > 0 AND schema_version > 0 AND version >= 0)`
- `CHECK (status NOT IN ('APPROVED','REJECTED','PUBLISHED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))`
- `CHECK (status <> 'REJECTED' OR (review_note IS NOT NULL AND btrim(review_note) <> ''))`
- `CHECK ((status='PUBLISHED') = (published_at IS NOT NULL))`
- FK `(content_item_id)` → `content_items(id)`; DELETE RESTRICT.
- FK `(created_by)` → `users(id)`; DELETE RESTRICT.
- FK `(reviewed_by)` → `users(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_revision_one_open ON content_revisions(content_item_id) WHERE status <> 'PUBLISHED';`
- `CREATE INDEX ix_revisions_item_version ON content_revisions(content_item_id, version_no DESC);`

## content_source_links

Nguồn theo từng revision; bản chụp nguồn lúc công bố.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `revision_id` | `UUID NOT NULL` | Bản nội dung |
| `source_id` | `UUID NOT NULL` | Nguồn |
| `usage_note` | `TEXT` | Đoạn/ảnh/audio dùng nguồn này |
| `source_snapshot` | `JSONB` | Bản chụp metadata; có trường nội bộ |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc gắn |

**Ràng buộc bảng**

- `PRIMARY KEY (revision_id, source_id)`
- `CHECK (source_snapshot IS NULL OR (jsonb_typeof(source_snapshot) = 'object' AND octet_length(source_snapshot::text) <= 1048576))`
- FK `(revision_id)` → `content_revisions(id)`; DELETE RESTRICT.
- FK `(source_id)` → `content_sources(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_source_links_source ON content_source_links(source_id);`

## revision_dependencies

Ghim revision con; formal edge chỉ tới bản đã công bố.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `parent_revision_id` | `UUID NOT NULL` | Bản cha |
| `relation_key` | `VARCHAR(200) NOT NULL` | Khóa thành viên ổn định trong schema, ví dụ sense:UUID |
| `child_revision_id` | `UUID NOT NULL` | Bản con được ghim |

**Ràng buộc bảng**

- `PRIMARY KEY (parent_revision_id, relation_key)`
- `CHECK (parent_revision_id <> child_revision_id)`
- FK `(parent_revision_id)` → `content_revisions(id)`; DELETE RESTRICT.
- FK `(child_revision_id)` → `content_revisions(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_revision_dependencies_child ON revision_dependencies(child_revision_id);`

## revision_media_refs

FK tra cứu tất cả file trong Draft/Published, kể cả ở JSON.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `revision_id` | `UUID NOT NULL` | Revision dùng file |
| `media_id` | `UUID NOT NULL` | File cần bảo toàn |
| `usage_role` | `VARCHAR(20) NOT NULL DEFAULT 'STIMULUS'` | Phân biệt học liệu và lời giải/transcript |

**Ràng buộc bảng**

- `PRIMARY KEY (revision_id, media_id, usage_role)`
- `CHECK (usage_role IN ('STIMULUS','EXPLANATION','TRANSCRIPT'))`
- FK `(revision_id)` → `content_revisions(id)`; DELETE RESTRICT.
- FK `(media_id)` → `media_assets(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_revision_media_asset ON revision_media_refs(media_id);`

## words

Projection từ hiện hành; nghĩa là đơn vị theo dõi học.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Cùng UUID với content_items, không tạo định danh thứ hai |
| `content_type` | `VARCHAR(30) GENERATED ALWAYS AS ('WORD'::VARCHAR) STORED` | Loại cố định, không nhận từ client |
| `published_revision_id` | `UUID NOT NULL` | Bản nguồn của projection hiện hành |
| `lemma` | `VARCHAR(150) NOT NULL` | Từ/biểu thức, không unique tuyệt đối |
| `language_code` | `VARCHAR(10) NOT NULL DEFAULT 'en'` | Ngôn ngữ |
| `ipa` | `VARCHAR(150)` | Phiên âm |
| `audio_media_id` | `UUID` | Audio chung |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (btrim(lemma) <> '')`
- FK `(id, content_type)` → `content_items(id, content_type)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.
- FK `(audio_media_id)` → `media_assets(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_words_search ON words(lower(lemma) varchar_pattern_ops);`

## word_senses

Nghĩa ổn định; đổi hẳn nghĩa tạo ID mới, không chuyển SRS.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Định danh nghĩa |
| `word_id` | `UUID NOT NULL` | Từ sở hữu |
| `word_revision_id` | `UUID NOT NULL` | Bản từ sinh projection hiện tại của nghĩa |
| `sense_no` | `SMALLINT NOT NULL` | Thứ tự nghĩa |
| `part_of_speech` | `VARCHAR(24) NOT NULL` | Từ loại |
| `cefr_level` | `VARCHAR(2) NOT NULL` | Cấp độ của nghĩa |
| `meaning_vi` | `TEXT NOT NULL` | Nghĩa Việt |
| `definition_en` | `TEXT` | Định nghĩa Anh |
| `audio_media_id` | `UUID` | Ghi đè audio chung nếu có |
| `is_archived` | `BOOLEAN NOT NULL DEFAULT FALSE` | Giữ progress cũ khi bỏ nghĩa |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `UNIQUE (word_id, id)`
- `CHECK (part_of_speech IN ('NOUN','VERB','ADJECTIVE','ADVERB','PRONOUN','PREPOSITION','CONJUNCTION','DETERMINER','INTERJECTION','NUMERAL','PHRASE'))`
- `CHECK (cefr_level IN ('A1','A2','B1','B2','C1','C2'))`
- `CHECK (sense_no > 0 AND btrim(meaning_vi) <> '')`
- FK `(word_id)` → `words(id)`; DELETE RESTRICT.
- FK `(word_id, word_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT.
- FK `(audio_media_id)` → `media_assets(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_senses_filters ON word_senses(cefr_level, part_of_speech) WHERE is_archived=FALSE;`
- `CREATE UNIQUE INDEX uq_senses_active_position ON word_senses(word_id, sense_no) WHERE is_archived=FALSE;`

## word_sense_examples

Nhiều ví dụ của một nghĩa, cặp Anh/Việt có thứ tự.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Example ID |
| `word_sense_id` | `UUID NOT NULL` | Nghĩa |
| `position` | `SMALLINT NOT NULL` | Thứ tự ví dụ |
| `example_en` | `TEXT NOT NULL` | Câu tiếng Anh |
| `example_vi` | `TEXT` | Bản dịch tùy chọn |

**Ràng buộc bảng**

- `UNIQUE (word_sense_id, position)`
- `CHECK (position > 0 AND btrim(example_en) <> '')`
- FK `(word_sense_id)` → `word_senses(id)`; DELETE RESTRICT.

## decks

Deck công bố, thành viên theo nghĩa và revision đã ghim.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Cùng UUID với content_items, không tạo định danh thứ hai |
| `content_type` | `VARCHAR(30) GENERATED ALWAYS AS ('DECK'::VARCHAR) STORED` | Loại cố định, không nhận từ client |
| `published_revision_id` | `UUID NOT NULL` | Bản nguồn của projection hiện hành |
| `title` | `VARCHAR(200) NOT NULL` | Tên deck |
| `description` | `TEXT` | Mô tả |
| `level` | `VARCHAR(2)` | Mức gợi ý, không thay CEFR ở nghĩa |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (level IN ('A1','A2','B1','B2','C1','C2'))`
- `CHECK (btrim(title) <> '')`
- FK `(id, content_type)` → `content_items(id, content_type)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.

## deck_words

Thành viên projection của deck; runtime dùng bản ghim khi giao bài.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `deck_id` | `UUID NOT NULL` | Deck |
| `word_sense_id` | `UUID NOT NULL` | Nghĩa |
| `position` | `INTEGER NOT NULL` | Thứ tự |

**Ràng buộc bảng**

- `PRIMARY KEY (deck_id, word_sense_id)`
- `UNIQUE (deck_id, position)`
- `CHECK (position > 0)`
- FK `(deck_id)` → `decks(id)`; DELETE RESTRICT.
- FK `(word_sense_id)` → `word_senses(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_deck_words_sense ON deck_words(word_sense_id);`

## user_saved_words

Bỏ lưu chỉ xóa liên kết này, không xóa SRS.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `user_id` | `UUID NOT NULL` | Người lưu |
| `word_sense_id` | `UUID NOT NULL` | Nghĩa được lưu |
| `saved_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc lưu |

**Ràng buộc bảng**

- `PRIMARY KEY (user_id, word_sense_id)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(word_sense_id)` → `word_senses(id)`; DELETE RESTRICT.

## user_word_progress

Trạng thái SRS hiện tại, khóa theo user và nghĩa.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `user_id` | `UUID NOT NULL` | Người học |
| `word_sense_id` | `UUID NOT NULL` | Nghĩa |
| `repetitions` | `INTEGER NOT NULL DEFAULT 0` | Chuỗi nhớ đạt |
| `interval_days` | `INTEGER NOT NULL DEFAULT 0` | Khoảng ngày hiện tại; học lại ngắn có thể bằng 0 |
| `ease_factor` | `NUMERIC(6,3) NOT NULL DEFAULT 2.5` | Hệ số, không dưới 1.3 |
| `due_at` | `TIMESTAMPTZ NOT NULL` | Hạn ôn do server tính |
| `last_reviewed_at` | `TIMESTAMPTZ` | Lần review gần nhất |
| `policy_version` | `VARCHAR(32) NOT NULL DEFAULT 'SM2_3BTN_V1'` | Thuật toán đã dùng |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Chống ghi đè |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `PRIMARY KEY (user_id, word_sense_id)`
- `CHECK (repetitions >= 0 AND interval_days >= 0 AND ease_factor >= 1.3 AND version >= 0)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(word_sense_id)` → `word_senses(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_progress_due ON user_word_progress(user_id, due_at);`

## word_reviews

Sự kiện review bất biến và idempotent, có chính sách/trạng thái trước-sau.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Review ID |
| `user_id` | `UUID NOT NULL` | Người học |
| `word_sense_id` | `UUID NOT NULL` | Nghĩa |
| `source_word_id` | `UUID NOT NULL` | Từ chứa nghĩa đã xem |
| `source_revision_id` | `UUID NOT NULL` | Bản từ đã xem |
| `request_id` | `UUID NOT NULL` | Retry dùng lại cùng key |
| `request_hash` | `VARCHAR(64) NOT NULL` | Phát hiện cùng key nhưng payload khác |
| `rating` | `VARCHAR(8) NOT NULL` | AGAIN/HARD/GOOD sau khi lật |
| `policy_version` | `VARCHAR(32) NOT NULL` | Bản thuật toán |
| `before_state` | `JSONB NOT NULL` | Trạng thái SRS trước review |
| `after_state` | `JSONB NOT NULL` | Trạng thái SRS sau review |
| `previous_due_at` | `TIMESTAMPTZ` | Hạn cũ |
| `next_due_at` | `TIMESTAMPTZ NOT NULL` | Hạn mới |
| `reviewed_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Server time |
| `timezone` | `VARCHAR(60) NOT NULL` | Múi giờ tại thời điểm ghi nhận |
| `local_day` | `DATE NOT NULL` | Ngày thống kê ổn định |

**Ràng buộc bảng**

- `UNIQUE (user_id, request_id)`
- `CHECK (request_hash ~ '^[0-9a-f]{64}$')`
- `CHECK (rating IN ('AGAIN','HARD','GOOD'))`
- `CHECK (jsonb_typeof(before_state) = 'object' AND octet_length(before_state::text) <= 1048576)`
- `CHECK (jsonb_typeof(after_state) = 'object' AND octet_length(after_state::text) <= 1048576)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(source_word_id, word_sense_id)` → `word_senses(word_id, id)`; DELETE RESTRICT.
- FK `(source_word_id, source_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_reviews_user_day ON word_reviews(user_id, local_day);`
- `CREATE INDEX ix_reviews_sense_time ON word_reviews(user_id, word_sense_id, reviewed_at DESC);`

## grammar_topics

Chủ điểm, không thêm cây curriculum nhiều cấp ngoài phạm vi.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Cùng UUID với content_items, không tạo định danh thứ hai |
| `content_type` | `VARCHAR(30) GENERATED ALWAYS AS ('GRAMMAR_TOPIC'::VARCHAR) STORED` | Loại cố định, không nhận từ client |
| `published_revision_id` | `UUID NOT NULL` | Bản nguồn của projection hiện hành |
| `slug` | `VARCHAR(120) NOT NULL UNIQUE` | Đường dẫn ổn định |
| `name` | `VARCHAR(200) NOT NULL` | Tên chủ điểm |
| `description` | `TEXT` | Mô tả |
| `difficulty` | `VARCHAR(16)` | Mức gợi ý |
| `sort_order` | `INTEGER NOT NULL DEFAULT 0` | Thứ tự |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (difficulty IN ('BASIC','INTERMEDIATE','ADVANCED'))`
- `CHECK (sort_order >= 0 AND btrim(name) <> '')`
- FK `(id, content_type)` → `content_items(id, content_type)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.

## grammar_lessons

Bài học khối có schema, thuộc một topic.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Cùng UUID với content_items, không tạo định danh thứ hai |
| `content_type` | `VARCHAR(30) GENERATED ALWAYS AS ('GRAMMAR_LESSON'::VARCHAR) STORED` | Loại cố định, không nhận từ client |
| `published_revision_id` | `UUID NOT NULL` | Bản nguồn của projection hiện hành |
| `topic_id` | `UUID NOT NULL` | Chủ điểm |
| `title` | `VARCHAR(200) NOT NULL` | Tiêu đề |
| `body_schema_version` | `SMALLINT NOT NULL DEFAULT 1` | Schema khối |
| `body` | `JSONB NOT NULL` | Các block được kiểm soát |
| `sort_order` | `INTEGER NOT NULL DEFAULT 0` | Thứ tự trong chủ điểm |
| `estimated_minutes` | `SMALLINT` | Thời gian gợi ý, không phải phút học thực tế |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 1048576)`
- `CHECK (char_length(body::text) <= 100000)`
- `CHECK (sort_order >= 0 AND body_schema_version > 0)`
- `CHECK (estimated_minutes IS NULL OR estimated_minutes > 0)`
- FK `(id, content_type)` → `content_items(id, content_type)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.
- FK `(topic_id)` → `grammar_topics(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_lessons_topic_order ON grammar_lessons(topic_id, sort_order);`

## lesson_progress

Hoàn thành bằng thao tác người học; giữ bản đã hoàn thành khi có bản mới.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `user_id` | `UUID NOT NULL` | Người học |
| `lesson_id` | `UUID NOT NULL` | Bài học |
| `state` | `VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS'` | Không có row là NOT_STARTED |
| `last_completed_revision_id` | `UUID` | Bản đã hoàn thành |
| `started_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mở học |
| `completed_at` | `TIMESTAMPTZ` | Lần hoàn thành |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Chống ghi đè |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc cập nhật |

**Ràng buộc bảng**

- `PRIMARY KEY (user_id, lesson_id)`
- `CHECK (state IN ('IN_PROGRESS','COMPLETED'))`
- `CHECK ((state='COMPLETED') = (completed_at IS NOT NULL))`
- `CHECK (state <> 'COMPLETED' OR last_completed_revision_id IS NOT NULL)`
- `CHECK (version >= 0)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(lesson_id)` → `grammar_lessons(id)`; DELETE RESTRICT.
- FK `(lesson_id, last_completed_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT.

## question_types

Catalogue cách trả lời; chỉ bật code có renderer/grader đã triển khai.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `code` | `VARCHAR(32) PRIMARY KEY` | SINGLE_CHOICE ban đầu |
| `name` | `VARCHAR(100) NOT NULL` | Nhãn |
| `schema_version` | `SMALLINT NOT NULL DEFAULT 1` | Phiên bản response |
| `enabled` | `BOOLEAN NOT NULL DEFAULT FALSE` | Không tự hỗ trợ type bằng thêm row |

**Ràng buộc bảng**

- `CHECK (schema_version > 0)`

## question_groups

Ngữ cảnh cha; group type không thay question response type.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Cùng UUID với content_items, không tạo định danh thứ hai |
| `content_type` | `VARCHAR(30) GENERATED ALWAYS AS ('QUESTION_GROUP'::VARCHAR) STORED` | Loại cố định, không nhận từ client |
| `published_revision_id` | `UUID NOT NULL` | Bản nguồn của projection hiện hành |
| `category` | `VARCHAR(16) NOT NULL` | Mảng kiến thức |
| `part_number` | `SMALLINT` | Part nếu TOEIC |
| `title` | `VARCHAR(200) NOT NULL` | Tên nhóm |
| `group_type` | `VARCHAR(24) NOT NULL` | Loại ngữ cảnh |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `UNIQUE (id, category)`
- `UNIQUE (id, category, part_number)`
- `CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC'))`
- `CHECK (group_type IN ('GENERAL','CONVERSATION','TALK','TEXT_COMPLETION','PASSAGE'))`
- `CHECK ((category='TOEIC' AND part_number IS NOT NULL AND ((part_number=3 AND group_type='CONVERSATION') OR (part_number=4 AND group_type='TALK') OR (part_number=6 AND group_type='TEXT_COMPLETION') OR (part_number=7 AND group_type='PASSAGE'))) OR (category<>'TOEIC' AND part_number IS NULL AND group_type='GENERAL'))`
- FK `(id, content_type)` → `content_items(id, content_type)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.

## questions

Câu chấm được; mỗi câu tối đa một nhóm; không dùng group_questions N:N.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Cùng UUID với content_items, không tạo định danh thứ hai |
| `content_type` | `VARCHAR(30) GENERATED ALWAYS AS ('QUESTION'::VARCHAR) STORED` | Loại cố định, không nhận từ client |
| `published_revision_id` | `UUID NOT NULL` | Bản nguồn của projection hiện hành |
| `category` | `VARCHAR(16) NOT NULL` | Vocabulary/Grammar/TOEIC |
| `question_type_code` | `VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE'` | Cơ chế trả lời |
| `practice_mode` | `VARCHAR(32) NOT NULL DEFAULT 'STANDARD'` | Kiểu luyện, không phải grader |
| `part_number` | `SMALLINT` | TOEIC Part |
| `prompt` | `TEXT` | Có thể trống cho câu nghe |
| `explanation` | `TEXT NOT NULL` | Lời giải, chỉ cấp sau nộp |
| `difficulty` | `VARCHAR(8) NOT NULL DEFAULT 'MEDIUM'` | Độ khó |
| `group_id` | `UUID` | Context cha nếu có |
| `group_position` | `SMALLINT` | Thứ tự câu con |
| `in_current_group` | `BOOLEAN NOT NULL DEFAULT TRUE` | Membership projection hiện hành; giữ context cũ khi thay câu |
| `blank_key` | `VARCHAR(80)` | Neo vị trí trống Part 6 |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `UNIQUE (id, group_id)`
- `UNIQUE (id, category)`
- `CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC'))`
- `CHECK (difficulty IN ('EASY','MEDIUM','HARD'))`
- `CHECK (practice_mode IN ('STANDARD','TRANSLATE_EN_VI','TRANSLATE_VI_EN','LISTEN_AND_CHOOSE','FILL_OPTION'))`
- `CHECK (char_length(prompt) <= 10000 AND char_length(explanation) <= 20000)`
- `CHECK ((category='TOEIC' AND part_number BETWEEN 1 AND 7 AND part_number IS NOT NULL) OR (category<>'TOEIC' AND part_number IS NULL))`
- `CHECK ((group_id IS NULL AND group_position IS NULL AND blank_key IS NULL) OR (group_id IS NOT NULL AND group_position > 0 AND group_position IS NOT NULL))`
- `CHECK (category<>'TOEIC' OR (part_number IN (3,4,6,7) AND group_id IS NOT NULL) OR (part_number IN (1,2,5) AND group_id IS NULL))`
- `CHECK (blank_key IS NULL OR (category='TOEIC' AND part_number=6))`
- `CHECK (category<>'TOEIC' OR part_number<>6 OR blank_key IS NOT NULL)`
- FK `(id, content_type)` → `content_items(id, content_type)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.
- FK `(question_type_code)` → `question_types(code)`; DELETE RESTRICT.
- FK `(group_id)` → `question_groups(id)`; DELETE RESTRICT.
- FK `(group_id, category)` → `question_groups(id, category)`; DELETE RESTRICT.
- FK `(group_id, category, part_number)` → `question_groups(id, category, part_number)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_questions_filters ON questions(category, part_number, question_type_code, difficulty);`
- `CREATE UNIQUE INDEX uq_group_current_position ON questions(group_id, group_position) WHERE in_current_group=TRUE;`
- `CREATE UNIQUE INDEX uq_group_current_blank ON questions(group_id, blank_key) WHERE in_current_group=TRUE;`

## question_options

Lựa chọn của projection; đúng một đáp án khi commit.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | ID lựa chọn ổn định trong revision/snapshot |
| `question_id` | `UUID NOT NULL` | Câu sở hữu |
| `position` | `SMALLINT NOT NULL` | Vị trí/nhãn hiển thị |
| `body` | `TEXT` | Có thể trống với phương án chỉ nằm trong audio TOEIC |
| `is_correct` | `BOOLEAN NOT NULL DEFAULT FALSE` | Chỉ backend/biên soạn, không DTO trước nộp |

**Ràng buộc bảng**

- `UNIQUE (question_id, id)`
- `UNIQUE (question_id, position)`
- `CHECK (position BETWEEN 1 AND 6)`
- FK `(question_id)` → `questions(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_options_one_correct ON question_options(question_id) WHERE is_correct=TRUE;`

## group_materials

Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Material ID/anchor ổn định |
| `group_id` | `UUID NOT NULL` | Nội dung sở hữu |
| `position` | `SMALLINT NOT NULL` | Thứ tự trình bày |
| `document_key` | `VARCHAR(80)` | Ghép các block cùng tài liệu; Part 7 có 1–3 tài liệu |
| `material_type` | `VARCHAR(8) NOT NULL` | TEXT/IMAGE/AUDIO |
| `usage_role` | `VARCHAR(20) NOT NULL DEFAULT 'STIMULUS'` | Quyền hiển thị theo thời điểm |
| `media_id` | `UUID` | File nếu ảnh/audio |
| `body` | `TEXT` | Text/Markdown, chứa blank marker có schema nếu cần |

**Ràng buộc bảng**

- `UNIQUE (group_id, position)`
- `CHECK (material_type IN ('TEXT','IMAGE','AUDIO'))`
- `CHECK (usage_role IN ('STIMULUS','TRANSCRIPT','EXPLANATION'))`
- `CHECK (position > 0)`
- `CHECK ((material_type='TEXT' AND body IS NOT NULL AND media_id IS NULL) OR (material_type IN ('IMAGE','AUDIO') AND media_id IS NOT NULL AND body IS NULL))`
- `CHECK (char_length(body) <= 50000)`
- FK `(group_id)` → `question_groups(id)`; DELETE RESTRICT.
- FK `(media_id)` → `media_assets(id)`; DELETE RESTRICT.

## question_materials

Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Material ID/anchor ổn định |
| `question_id` | `UUID NOT NULL` | Nội dung sở hữu |
| `position` | `SMALLINT NOT NULL` | Thứ tự trình bày |
| `document_key` | `VARCHAR(80)` | Ghép các block cùng tài liệu; Part 7 có 1–3 tài liệu |
| `material_type` | `VARCHAR(8) NOT NULL` | TEXT/IMAGE/AUDIO |
| `usage_role` | `VARCHAR(20) NOT NULL DEFAULT 'STIMULUS'` | Quyền hiển thị theo thời điểm |
| `media_id` | `UUID` | File nếu ảnh/audio |
| `body` | `TEXT` | Text/Markdown, chứa blank marker có schema nếu cần |

**Ràng buộc bảng**

- `UNIQUE (question_id, position)`
- `CHECK (material_type IN ('TEXT','IMAGE','AUDIO'))`
- `CHECK (usage_role IN ('STIMULUS','TRANSCRIPT','EXPLANATION'))`
- `CHECK (position > 0)`
- `CHECK ((material_type='TEXT' AND body IS NOT NULL AND media_id IS NULL) OR (material_type IN ('IMAGE','AUDIO') AND media_id IS NOT NULL AND body IS NULL))`
- `CHECK (char_length(body) <= 50000)`
- FK `(question_id)` → `questions(id)`; DELETE RESTRICT.
- FK `(media_id)` → `media_assets(id)`; DELETE RESTRICT.

## practice_sets

Bộ luyện hiện hành; FIXED có set_items, DYNAMIC có selection_config.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Cùng UUID với content_items, không tạo định danh thứ hai |
| `content_type` | `VARCHAR(30) GENERATED ALWAYS AS ('PRACTICE_SET'::VARCHAR) STORED` | Loại cố định, không nhận từ client |
| `published_revision_id` | `UUID NOT NULL` | Bản nguồn của projection hiện hành |
| `category` | `VARCHAR(16) NOT NULL` | Một category mỗi bộ/lượt |
| `title` | `VARCHAR(200) NOT NULL` | Tên bộ |
| `selection_mode` | `VARCHAR(8) NOT NULL` | FIXED/DYNAMIC |
| `selection_config` | `JSONB NOT NULL DEFAULT '{}'` | Filter có schema, không bỏ qua kiểm tra IDs |
| `deck_id` | `UUID` | Nguồn Vocabulary nếu có |
| `topic_id` | `UUID` | Chủ điểm đơn nếu có |
| `part_number` | `SMALLINT` | Part TOEIC |
| `time_limit_seconds` | `INTEGER` | NULL là không đếm ngược |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC'))`
- `CHECK (selection_mode IN ('FIXED','DYNAMIC'))`
- `CHECK (jsonb_typeof(selection_config) = 'object' AND octet_length(selection_config::text) <= 1048576)`
- `CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0)`
- `CHECK ((category='TOEIC' AND part_number BETWEEN 1 AND 7 AND part_number IS NOT NULL) OR (category<>'TOEIC' AND part_number IS NULL))`
- `CHECK (deck_id IS NULL OR category='VOCABULARY')`
- `CHECK (topic_id IS NULL OR category='GRAMMAR')`
- FK `(id, content_type)` → `content_items(id, content_type)`; DELETE RESTRICT.
- FK `(id, published_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT; deferred đến commit.
- FK `(deck_id)` → `decks(id)`; DELETE RESTRICT.
- FK `(topic_id)` → `grammar_topics(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_sets_filters ON practice_sets(category, part_number);`

## set_items

Một vị trí chọn câu độc lập XOR cả nhóm.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Set member ID |
| `set_id` | `UUID NOT NULL` | Bộ |
| `position` | `INTEGER NOT NULL` | Thứ tự đơn vị |
| `question_id` | `UUID` | Chỉ câu độc lập |
| `group_id` | `UUID` | Hoặc cả nhóm |

**Ràng buộc bảng**

- `UNIQUE (set_id, position)`
- `CHECK (position > 0)`
- `CHECK (num_nonnulls(question_id, group_id)=1)`
- FK `(set_id)` → `practice_sets(id)`; DELETE RESTRICT.
- FK `(question_id)` → `questions(id)`; DELETE RESTRICT.
- FK `(group_id)` → `question_groups(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_set_question ON set_items(set_id, question_id) WHERE question_id IS NOT NULL;`
- `CREATE UNIQUE INDEX uq_set_group ON set_items(set_id, group_id) WHERE group_id IS NOT NULL;`

## question_words

Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `question_id` | `UUID NOT NULL` | Câu hỏi |
| `word_sense_id` | `UUID NOT NULL` | Kiến thức liên quan |

**Ràng buộc bảng**

- `PRIMARY KEY (question_id, word_sense_id)`
- FK `(question_id)` → `questions(id)`; DELETE RESTRICT.
- FK `(word_sense_id)` → `word_senses(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_question_words_target ON question_words(word_sense_id);`

## question_topics

Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `question_id` | `UUID NOT NULL` | Câu hỏi |
| `topic_id` | `UUID NOT NULL` | Kiến thức liên quan |

**Ràng buộc bảng**

- `PRIMARY KEY (question_id, topic_id)`
- FK `(question_id)` → `questions(id)`; DELETE RESTRICT.
- FK `(topic_id)` → `grammar_topics(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_question_topics_target ON question_topics(topic_id);`

## attempts

Nguồn và kết quả lượt làm; tạo idempotent, snapshot được seal trước khi giao.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Attempt ID |
| `user_id` | `UUID NOT NULL` | Chủ bài, chỉ chủ được làm/xem theo quyền |
| `category` | `VARCHAR(16) NOT NULL` | Một category |
| `source_type` | `VARCHAR(24) NOT NULL` | Nguồn tạo bài |
| `source_set_id` | `UUID` | Bộ luyện nếu có |
| `source_deck_id` | `UUID` | Deck nếu có |
| `source_topic_id` | `UUID` | Topic đơn nếu có |
| `source_revision_id` | `UUID` | Bản nguồn chính nếu có |
| `source_config` | `JSONB NOT NULL` | Filter/kiểu/số lượng/revision có schema |
| `request_id` | `UUID NOT NULL` | Idempotency-Key khi bắt đầu |
| `request_hash` | `VARCHAR(64) NOT NULL` | Fingerprint payload gốc |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS'` | Trạng thái bài |
| `total_count` | `SMALLINT NOT NULL` | Số câu con, không phải số nhóm |
| `correct_count` | `SMALLINT` | Số đúng sau nộp |
| `answered_count` | `SMALLINT` | Số câu có chọn đáp án |
| `score` | `SMALLINT` | Một điểm/câu con đúng |
| `snapshot_sealed_at` | `TIMESTAMPTZ` | Không được sửa đề sau seal |
| `started_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc bắt đầu |
| `last_active_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Phục vụ bỏ dở |
| `deadline_at` | `TIMESTAMPTZ` | NULL là không giới hạn giờ |
| `submitted_at` | `TIMESTAMPTZ` | Mốc backend ghi kết quả |
| `effective_finished_at` | `TIMESTAMPTZ` | Deadline nếu tự nộp muộn, không cộng độ trễ |
| `submit_reason` | `VARCHAR(16)` | MANUAL/TIME_LIMIT |
| `abandoned_at` | `TIMESTAMPTZ` | Chủ động bỏ hoặc hết hạn hoạt động |
| `duration_seconds` | `INTEGER` | Thời gian bài, không phải phút học hoạt động |
| `timezone` | `VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh'` | Múi giờ ghi nhận ngày |
| `local_day` | `DATE` | Ngày kết quả theo múi giờ lúc nộp |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Optimistic lock của trạng thái |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `UNIQUE (user_id, id)`
- `UNIQUE (user_id, request_id)`
- `UNIQUE (id, category)`
- `CHECK (category IN ('VOCABULARY','GRAMMAR','TOEIC'))`
- `CHECK (source_type IN ('PRACTICE_SET','DECK','GRAMMAR_TOPIC','MIXED_GRAMMAR','MISTAKES'))`
- `CHECK (status IN ('IN_PROGRESS','SUBMITTED','ABANDONED'))`
- `CHECK (submit_reason IN ('MANUAL','TIME_LIMIT'))`
- `CHECK (jsonb_typeof(source_config) = 'object' AND octet_length(source_config::text) <= 1048576)`
- `CHECK (request_hash ~ '^[0-9a-f]{64}$')`
- `CHECK (total_count BETWEEN 1 AND 100 AND version >= 0)`
- `CHECK (correct_count IS NULL OR correct_count BETWEEN 0 AND total_count)`
- `CHECK (answered_count IS NULL OR answered_count BETWEEN 0 AND total_count)`
- `CHECK (score IS NULL OR score = correct_count)`
- `CHECK (duration_seconds IS NULL OR duration_seconds >= 0)`
- `CHECK (deadline_at IS NULL OR deadline_at > started_at)`
- `CHECK ((source_type='PRACTICE_SET' AND source_set_id IS NOT NULL AND source_deck_id IS NULL AND source_topic_id IS NULL) OR (source_type='DECK' AND source_deck_id IS NOT NULL AND category='VOCABULARY' AND source_set_id IS NULL AND source_topic_id IS NULL) OR (source_type='GRAMMAR_TOPIC' AND source_topic_id IS NOT NULL AND category='GRAMMAR' AND source_set_id IS NULL AND source_deck_id IS NULL) OR (source_type='MIXED_GRAMMAR' AND category='GRAMMAR' AND num_nonnulls(source_set_id,source_deck_id,source_topic_id)=0) OR (source_type='MISTAKES' AND num_nonnulls(source_set_id,source_deck_id,source_topic_id)=0))`
- `CHECK ((status='SUBMITTED' AND submitted_at IS NOT NULL AND effective_finished_at IS NOT NULL AND submit_reason IS NOT NULL AND correct_count IS NOT NULL AND answered_count IS NOT NULL AND score IS NOT NULL AND duration_seconds IS NOT NULL AND local_day IS NOT NULL AND abandoned_at IS NULL) OR (status='IN_PROGRESS' AND num_nonnulls(submitted_at,effective_finished_at,submit_reason,correct_count,answered_count,score,duration_seconds,abandoned_at,local_day)=0) OR (status='ABANDONED' AND abandoned_at IS NOT NULL AND num_nonnulls(submitted_at,effective_finished_at,submit_reason,correct_count,answered_count,score,duration_seconds,local_day)=0))`
- `CHECK (effective_finished_at IS NULL OR (effective_finished_at >= started_at AND effective_finished_at <= submitted_at))`
- `CHECK (submit_reason IS DISTINCT FROM 'TIME_LIMIT' OR (deadline_at IS NOT NULL AND effective_finished_at=deadline_at))`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(source_set_id)` → `practice_sets(id)`; DELETE RESTRICT.
- FK `(source_deck_id)` → `decks(id)`; DELETE RESTRICT.
- FK `(source_topic_id)` → `grammar_topics(id)`; DELETE RESTRICT.
- FK `(source_revision_id)` → `content_revisions(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_attempts_user_history ON attempts(user_id, started_at DESC);`
- `CREATE INDEX ix_attempts_deadline ON attempts(deadline_at) WHERE status='IN_PROGRESS';`
- `CREATE INDEX ix_attempts_user_open ON attempts(user_id, last_active_at) WHERE status='IN_PROGRESS';`

## attempt_source_topics

Nguồn nhiều topic có FK, không chỉ giấu ID trong JSON.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `attempt_id` | `UUID NOT NULL` | Lượt làm |
| `topic_id` | `UUID NOT NULL` | Chủ điểm |
| `source_revision_id` | `UUID NOT NULL` | Bản topic ghim |

**Ràng buộc bảng**

- `PRIMARY KEY (attempt_id, topic_id)`
- FK `(attempt_id)` → `attempts(id)`; DELETE RESTRICT.
- FK `(topic_id)` → `grammar_topics(id)`; DELETE RESTRICT.
- FK `(topic_id, source_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT.

## attempt_group_snapshots

Ngữ cảnh nhóm chung lưu một lần trong mỗi lượt làm.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Group snapshot ID |
| `attempt_id` | `UUID NOT NULL` | Lượt sở hữu |
| `group_id` | `UUID NOT NULL` | Nhóm nguồn |
| `source_revision_id` | `UUID NOT NULL` | Bản nhóm đã ghim |
| `ordinal` | `SMALLINT NOT NULL` | Thứ tự đơn vị trong bài |
| `schema_version` | `SMALLINT NOT NULL DEFAULT 1` | Schema render nhóm |
| `category` | `VARCHAR(16) NOT NULL` | Category của bộ câu trong nhóm |
| `part_number` | `SMALLINT` | Part đã ghim |
| `expected_question_count` | `SMALLINT NOT NULL` | Đủ nguyên nhóm khi seal |
| `public_snapshot` | `JSONB NOT NULL` | Stimulus công khai, không transcript/key |
| `private_snapshot` | `JSONB NOT NULL DEFAULT '{}'` | Transcript/lời giải riêng |

**Ràng buộc bảng**

- `UNIQUE (attempt_id, id)`
- `UNIQUE (attempt_id, group_id)`
- `UNIQUE (attempt_id, ordinal)`
- `CHECK (ordinal > 0 AND schema_version > 0 AND expected_question_count BETWEEN 1 AND 100)`
- `CHECK ((category='TOEIC' AND part_number IN (3,4,6,7) AND part_number IS NOT NULL) OR (category IN ('VOCABULARY','GRAMMAR') AND part_number IS NULL))`
- `CHECK (category<>'TOEIC' OR (part_number NOT IN (3,4) OR expected_question_count=3))`
- `CHECK (category<>'TOEIC' OR part_number<>6 OR expected_question_count=4)`
- `CHECK (jsonb_typeof(public_snapshot) = 'object' AND octet_length(public_snapshot::text) <= 1048576)`
- `CHECK (jsonb_typeof(private_snapshot) = 'object' AND octet_length(private_snapshot::text) <= 1048576)`
- FK `(attempt_id)` → `attempts(id)`; DELETE RESTRICT.
- FK `(group_id)` → `question_groups(id)`; DELETE RESTRICT.
- FK `(attempt_id, category)` → `attempts(id, category)`; DELETE RESTRICT.
- FK `(group_id, category)` → `question_groups(id, category)`; DELETE RESTRICT.
- FK `(group_id, source_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT.

## attempt_question_snapshots

Đề giao bất biến, đáp án chuẩn/lời giải tách khỏi dữ liệu trước nộp.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Snapshot ID dùng để lưu đáp án |
| `attempt_id` | `UUID NOT NULL` | Lượt sở hữu |
| `question_id` | `UUID NOT NULL` | Câu nguồn ổn định |
| `source_revision_id` | `UUID NOT NULL` | Bản câu đã ghim |
| `group_snapshot_id` | `UUID` | Ngữ cảnh nhóm trong đúng lượt |
| `category` | `VARCHAR(16) NOT NULL` | Discriminator FK: không trộn category trong bài |
| `ordinal` | `SMALLINT NOT NULL` | Thứ tự toàn bài |
| `question_type_code` | `VARCHAR(32) NOT NULL` | Renderer/grader |
| `schema_version` | `SMALLINT NOT NULL DEFAULT 1` | Phiên bản snapshot |
| `grading_policy_version` | `VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE_V1'` | Chính sách chấm đã đóng băng |
| `question_snapshot` | `JSONB NOT NULL` | Payload trước nộp được kiểm soát |
| `answer_key_snapshot` | `JSONB NOT NULL` | Key riêng tư |
| `explanation_snapshot` | `JSONB` | Lời giải riêng tư |

**Ràng buộc bảng**

- `UNIQUE (attempt_id, id)`
- `UNIQUE (id, question_id)`
- `UNIQUE (attempt_id, ordinal)`
- `UNIQUE (attempt_id, question_id)`
- `CHECK (ordinal BETWEEN 1 AND 100 AND schema_version > 0)`
- `CHECK (jsonb_typeof(question_snapshot) = 'object' AND octet_length(question_snapshot::text) <= 1048576)`
- `CHECK (jsonb_typeof(answer_key_snapshot) = 'object' AND octet_length(answer_key_snapshot::text) <= 1048576)`
- `CHECK (explanation_snapshot IS NULL OR (jsonb_typeof(explanation_snapshot) = 'object' AND octet_length(explanation_snapshot::text) <= 1048576))`
- FK `(attempt_id)` → `attempts(id)`; DELETE RESTRICT.
- FK `(question_id)` → `questions(id)`; DELETE RESTRICT.
- FK `(attempt_id, category)` → `attempts(id, category)`; DELETE RESTRICT.
- FK `(question_id, category)` → `questions(id, category)`; DELETE RESTRICT.
- FK `(question_id, source_revision_id)` → `content_revisions(content_item_id, id)`; DELETE RESTRICT.
- FK `(attempt_id, group_snapshot_id)` → `attempt_group_snapshots(attempt_id, id)`; DELETE RESTRICT.
- FK `(question_type_code)` → `question_types(code)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_snapshot_question ON attempt_question_snapshots(question_id, attempt_id);`

## attempt_snapshot_options

Option thuộc đúng snapshot, không FK tới lựa chọn hiện hành có thể sửa.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `snapshot_id` | `UUID NOT NULL` | Câu snapshot |
| `option_id` | `UUID NOT NULL` | ID option trong bản đã giao |
| `position` | `SMALLINT NOT NULL` | Nhãn/vị trí không đổi |
| `public_body` | `TEXT` | NULL khi phương án chỉ nằm trong audio TOEIC |

**Ràng buộc bảng**

- `PRIMARY KEY (snapshot_id, option_id)`
- `UNIQUE (snapshot_id, position)`
- `CHECK (position BETWEEN 1 AND 6)`
- FK `(snapshot_id)` → `attempt_question_snapshots(id)`; DELETE RESTRICT.

## attempt_answers

Một đáp án hiện tại mỗi câu; version cả khi bỏ chọn.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Answer ID |
| `attempt_id` | `UUID NOT NULL` | Lượt sở hữu |
| `snapshot_id` | `UUID NOT NULL UNIQUE` | Câu snapshot |
| `selected_option_id` | `UUID` | NULL nghĩa là bỏ trống |
| `response_payload` | `JSONB` | Dành cho schema type mở rộng, SINGLE_CHOICE dùng selected_option_id |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Optimistic version khi autosave |
| `answered_at` | `TIMESTAMPTZ` | NULL nếu đang bỏ trống |
| `is_correct` | `BOOLEAN` | Chỉ server ghi khi chấm |
| `score` | `SMALLINT` | 0/1 theo chính sách ban đầu |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (version >= 0)`
- `CHECK ((selected_option_id IS NULL) = (answered_at IS NULL))`
- `CHECK (score IS NULL OR score IN (0,1))`
- `CHECK ((is_correct IS NULL) = (score IS NULL))`
- `CHECK (is_correct IS NULL OR score = CASE WHEN is_correct THEN 1 ELSE 0 END)`
- `CHECK (num_nonnulls(selected_option_id,response_payload) <= 1)`
- `CHECK (response_payload IS NULL OR (jsonb_typeof(response_payload) = 'object' AND octet_length(response_payload::text) <= 1048576))`
- FK `(attempt_id, snapshot_id)` → `attempt_question_snapshots(attempt_id, id)`; DELETE RESTRICT.
- FK `(snapshot_id, selected_option_id)` → `attempt_snapshot_options(snapshot_id, option_id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_answers_attempt ON attempt_answers(attempt_id);`

## attempt_media_refs

File trong đề/lời giải snapshot; bảo toàn độc lập projection hiện hành.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `attempt_id` | `UUID NOT NULL` | Lượt làm |
| `media_id` | `UUID NOT NULL` | File phải giữ |
| `usage_role` | `VARCHAR(20) NOT NULL DEFAULT 'STIMULUS'` | Quyền đọc trước/sau nộp |

**Ràng buộc bảng**

- `PRIMARY KEY (attempt_id, media_id, usage_role)`
- `CHECK (usage_role IN ('STIMULUS','EXPLANATION','TRANSCRIPT'))`
- FK `(attempt_id)` → `attempts(id)`; DELETE RESTRICT.
- FK `(media_id)` → `media_assets(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_attempt_media_asset ON attempt_media_refs(media_id);`

## mistake_records

Lỗi theo user/câu; lịch sử sự kiện gốc ở các kết quả bất biến.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Mistake ID |
| `user_id` | `UUID NOT NULL` | Chủ lỗi |
| `question_id` | `UUID NOT NULL` | Câu ổn định |
| `source_attempt_id` | `UUID NOT NULL` | Bài sai gần nhất |
| `source_snapshot_id` | `UUID NOT NULL` | Snapshot sai tương ứng |
| `state` | `VARCHAR(16) NOT NULL DEFAULT 'OPEN'` | OPEN/IMPROVING/RESOLVED |
| `wrong_count` | `INTEGER NOT NULL` | Tổng lần sai/bỏ trống ở bài đã nộp |
| `consecutive_correct` | `INTEGER NOT NULL DEFAULT 0` | Chuỗi đúng sau lỗi gần nhất |
| `last_wrong_at` | `TIMESTAMPTZ NOT NULL` | Theo thời gian kết thúc hiệu lực |
| `last_evaluated_at` | `TIMESTAMPTZ NOT NULL` | Kết quả mới nhất theo thời gian hiệu lực |
| `resolved_at` | `TIMESTAMPTZ` | Khi đạt ít nhất hai lần đúng liên tiếp |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc projection cập nhật |

**Ràng buộc bảng**

- `UNIQUE (user_id, question_id)`
- `UNIQUE (user_id, id)`
- `CHECK (state IN ('OPEN','IMPROVING','RESOLVED'))`
- `CHECK (wrong_count > 0 AND consecutive_correct >= 0)`
- `CHECK ((state='OPEN' AND consecutive_correct=0 AND resolved_at IS NULL) OR (state='IMPROVING' AND consecutive_correct=1 AND resolved_at IS NULL) OR (state='RESOLVED' AND consecutive_correct>=2 AND resolved_at IS NOT NULL))`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(question_id)` → `questions(id)`; DELETE RESTRICT.
- FK `(user_id, source_attempt_id)` → `attempts(user_id, id)`; DELETE RESTRICT.
- FK `(source_attempt_id, source_snapshot_id)` → `attempt_question_snapshots(attempt_id, id)`; DELETE RESTRICT.
- FK `(source_snapshot_id, question_id)` → `attempt_question_snapshots(id, question_id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_mistakes_user_state ON mistake_records(user_id, state, last_wrong_at DESC);`

## attempt_source_mistakes

Lỗi chọn để luyện lại phải thuộc đúng chủ lượt làm.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `attempt_id` | `UUID NOT NULL` | Lượt luyện lại |
| `user_id` | `UUID NOT NULL` | Discriminator bảo đảm ownership bằng FK |
| `mistake_id` | `UUID NOT NULL` | Lỗi đã chọn |

**Ràng buộc bảng**

- `PRIMARY KEY (attempt_id, mistake_id)`
- FK `(user_id, attempt_id)` → `attempts(user_id, id)`; DELETE RESTRICT.
- FK `(user_id, mistake_id)` → `mistake_records(user_id, id)`; DELETE RESTRICT.

## learning_goals

Một mục tiêu hiện tại, không diễn giải thành trình độ được dự đoán.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `user_id` | `UUID PRIMARY KEY` | Người học có LEARNER |
| `target_score` | `SMALLINT NOT NULL` | Điểm đích TOEIC |
| `daily_minutes` | `SMALLINT NOT NULL` | Phút/ngày |
| `target_date` | `DATE` | Ngày đích tùy chọn, API kiểm tra theo timezone |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (target_score BETWEEN 10 AND 990 AND target_score % 5=0)`
- `CHECK (daily_minutes BETWEEN 5 AND 240)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.

## study_sessions

Đoạn thời gian hoạt động ước tính, không cộng trùng nhiều tab/thiết bị.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Đoạn hoạt động |
| `user_id` | `UUID NOT NULL` | Chủ đoạn |
| `source_type` | `VARCHAR(16) NOT NULL` | FLASHCARD/LESSON/PRACTICE |
| `word_sense_id` | `UUID` | Ngữ cảnh thẻ nếu có |
| `lesson_id` | `UUID` | Ngữ cảnh bài học nếu có |
| `attempt_id` | `UUID` | Ngữ cảnh luyện nếu có |
| `timezone` | `VARCHAR(60) NOT NULL` | Timezone ở thời điểm ghi nhận |
| `local_day` | `DATE NOT NULL` | Chia đoạn khi qua ngày/đổi timezone |
| `credit_token` | `UUID NOT NULL` | Chủ nhịp ghi nhận hiện tại, không thay đăng nhập |
| `started_at` | `TIMESTAMPTZ NOT NULL` | Mốc bắt đầu |
| `last_credited_at` | `TIMESTAMPTZ NOT NULL` | Server cập nhật, không tin client clock |
| `closed_at` | `TIMESTAMPTZ` | Đóng khi ngừng/đổi ngữ cảnh |
| `active_seconds` | `NUMERIC(12,3) NOT NULL DEFAULT 0` | Mỗi heartbeat cộng tối đa 30 giây |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Chống cộng lại |

**Ràng buộc bảng**

- `CHECK (source_type IN ('FLASHCARD','LESSON','PRACTICE'))`
- `CHECK ((source_type='FLASHCARD' AND word_sense_id IS NOT NULL AND num_nonnulls(lesson_id,attempt_id)=0) OR (source_type='LESSON' AND lesson_id IS NOT NULL AND num_nonnulls(word_sense_id,attempt_id)=0) OR (source_type='PRACTICE' AND attempt_id IS NOT NULL AND num_nonnulls(word_sense_id,lesson_id)=0))`
- `CHECK (last_credited_at >= started_at AND (closed_at IS NULL OR closed_at >= last_credited_at))`
- `CHECK (active_seconds >= 0 AND active_seconds <= EXTRACT(EPOCH FROM (last_credited_at-started_at)) AND version >= 0)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(word_sense_id)` → `word_senses(id)`; DELETE RESTRICT.
- FK `(lesson_id)` → `grammar_lessons(id)`; DELETE RESTRICT.
- FK `(user_id, attempt_id)` → `attempts(user_id, id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE UNIQUE INDEX uq_study_one_open ON study_sessions(user_id) WHERE closed_at IS NULL;`
- `CREATE INDEX ix_study_day ON study_sessions(user_id, local_day);`

## notification_preferences

Nhắc học opt-in theo giờ; email bảo mật không phụ thuộc email nhắc học.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `user_id` | `UUID PRIMARY KEY` | Người nhận |
| `in_app_enabled` | `BOOLEAN NOT NULL DEFAULT TRUE` | Kênh in-app |
| `email_enabled` | `BOOLEAN NOT NULL DEFAULT FALSE` | Email nhắc học, không phải OTP |
| `daily_reminder_time` | `TIME` | NULL là chưa đặt lịch |
| `timezone` | `VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh'` | IANA timezone |
| `quiet_hours_start` | `TIME` | NULL cùng quiet_hours_end nếu tắt |
| `quiet_hours_end` | `TIME` | Cho phép qua nửa đêm |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc sửa |

**Ràng buộc bảng**

- `CHECK ((quiet_hours_start IS NULL)=(quiet_hours_end IS NULL))`
- `CHECK (quiet_hours_start IS NULL OR quiet_hours_start<>quiet_hours_end)`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.

## reminder_rules

Danh mục quy tắc nhắc, không có chiến dịch quảng bá/browser push.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Rule ID |
| `code` | `VARCHAR(40) NOT NULL UNIQUE` | Mã do backend hỗ trợ |
| `enabled` | `BOOLEAN NOT NULL DEFAULT TRUE` | Cờ vận hành |
| `minimum_gap_minutes` | `INTEGER NOT NULL DEFAULT 1440` | Khoảng tối thiểu giữa các nhắc cùng loại |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc tạo |

**Ràng buộc bảng**

- `CHECK (minimum_gap_minutes >= 0)`

## notifications

Thông báo/dedupe; bản ghi INTERNAL dùng cho mail bảo mật, không vào feed learner.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Notification ID |
| `user_id` | `UUID NOT NULL` | Người nhận |
| `reminder_rule_id` | `UUID` | Quy tắc nhắc nếu có |
| `type` | `VARCHAR(24) NOT NULL` | Loại sự kiện |
| `visibility` | `VARCHAR(12) NOT NULL DEFAULT 'USER'` | USER hoặc INTERNAL |
| `title` | `VARCHAR(180) NOT NULL` | Không chứa secret |
| `body` | `TEXT NOT NULL` | Nội dung công khai, không chứa OTP/token |
| `target_url` | `VARCHAR(500)` | Chỉ đường dẫn nội bộ |
| `dedupe_key` | `VARCHAR(200) NOT NULL` | User/sự kiện/ngày/ngữ cảnh |
| `read_at` | `TIMESTAMPTZ` | Mốc đọc |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc tạo |

**Ràng buộc bảng**

- `UNIQUE (user_id, dedupe_key)`
- `CHECK (type IN ('REVIEW_DUE','DAILY_GOAL','CONTENT_NEW','SYSTEM','SECURITY'))`
- `CHECK (visibility IN ('USER','INTERNAL'))`
- `CHECK (target_url IS NULL OR (target_url LIKE '/%' AND target_url NOT LIKE '//%'))`
- FK `(user_id)` → `users(id)`; DELETE RESTRICT.
- FK `(reminder_rule_id)` → `reminder_rules(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_notifications_feed ON notifications(user_id, created_at DESC) WHERE visibility='USER';`

## notification_deliveries

Queue gửi có claim/lease; payload OTP chỉ lưu mã hóa, không rõ trong event/log.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Delivery ID |
| `notification_id` | `UUID NOT NULL` | Thông báo sở hữu |
| `channel` | `VARCHAR(8) NOT NULL` | IN_APP/EMAIL |
| `recipient_address` | `VARCHAR(254)` | Địa chỉ email đã chốt cho lần gửi |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'PENDING'` | Trạng thái gửi |
| `attempt_count` | `SMALLINT NOT NULL DEFAULT 0` | Tối đa 1 lần đầu + 3 retry |
| `next_retry_at` | `TIMESTAMPTZ` | Lần được nhận xử lý tiếp |
| `claim_token` | `UUID` | Claim hiện tại |
| `lease_until` | `TIMESTAMPTZ` | Lease 60 giây có gia hạn |
| `provider_message_id` | `VARCHAR(180)` | Receipt nếu nhà cung cấp có |
| `last_failure_code` | `VARCHAR(64)` | Lỗi đã làm sạch |
| `sent_at` | `TIMESTAMPTZ` | Mốc gửi thành công |
| `finished_at` | `TIMESTAMPTZ` | Mốc kết thúc, dùng retention |
| `otp_challenge_id` | `UUID` | Mail OTP chỉ gửi khi challenge còn hợp lệ |
| `encrypted_payload` | `BYTEA` | Ciphertext cho dữ liệu gửi nhạy cảm, không chứa khóa giải mã |
| `encryption_key_id` | `VARCHAR(64)` | ID khóa ở môi trường/kho secret |
| `sensitive_expires_at` | `TIMESTAMPTZ` | Không gửi OTP đã hết hạn |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc tạo |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc cập nhật |

**Ràng buộc bảng**

- `UNIQUE (notification_id, channel)`
- `CHECK (channel IN ('IN_APP','EMAIL'))`
- `CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED'))`
- `CHECK (attempt_count BETWEEN 0 AND 4)`
- `CHECK (channel<>'EMAIL' OR recipient_address IS NOT NULL)`
- `CHECK ((status='PROCESSING')=(claim_token IS NOT NULL AND lease_until IS NOT NULL))`
- `CHECK ((claim_token IS NULL)=(lease_until IS NULL))`
- `CHECK ((status='SENT')=(sent_at IS NOT NULL))`
- `CHECK ((status IN ('SENT','FAILED'))=(finished_at IS NOT NULL))`
- `CHECK (status NOT IN ('SENT','FAILED') OR next_retry_at IS NULL)`
- `CHECK ((encrypted_payload IS NULL)=(encryption_key_id IS NULL))`
- `CHECK (channel='EMAIL' OR (encrypted_payload IS NULL AND otp_challenge_id IS NULL))`
- FK `(notification_id)` → `notifications(id)`; DELETE RESTRICT.
- FK `(otp_challenge_id)` → `otp_challenges(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_delivery_pending ON notification_deliveries(next_retry_at, created_at) WHERE status='PENDING';`
- `CREATE INDEX ix_delivery_lease ON notification_deliveries(lease_until) WHERE status='PROCESSING';`

## import_jobs

Ngữ cảnh upload/import riêng, DryRun trước khi tạo Draft.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Job ID |
| `created_by` | `UUID NOT NULL` | CONTENT_ADMIN thực hiện |
| `request_id` | `UUID NOT NULL` | Retry cùng yêu cầu tạo job |
| `request_hash` | `VARCHAR(64) NOT NULL` | Payload fingerprint |
| `import_type` | `VARCHAR(24) NOT NULL` | Loại importer được hỗ trợ |
| `import_namespace` | `VARCHAR(120) NOT NULL` | Namespace khóa ngoài |
| `original_filename` | `VARCHAR(255) NOT NULL` | Tên hiển thị |
| `input_bucket` | `VARCHAR(100) NOT NULL` | Kho riêng tư |
| `input_object_key` | `VARCHAR(500) NOT NULL` | Key input riêng cho job |
| `declared_size_bytes` | `BIGINT NOT NULL` | Không vượt 5 MiB |
| `input_sha256` | `VARCHAR(64)` | Hash file thực tế sau xác minh |
| `upload_expires_at` | `TIMESTAMPTZ NOT NULL` | Hạn nhận file/complete |
| `status` | `VARCHAR(24) NOT NULL DEFAULT 'UPLOADING'` | Không coi upload CSV là IMAGE/AUDIO |
| `total_rows` | `INTEGER NOT NULL DEFAULT 0` | Đơn vị dòng/item, không phải số entity con |
| `success_rows` | `INTEGER NOT NULL DEFAULT 0` | Đã tạo Draft và ghi ledger |
| `failed_rows` | `INTEGER NOT NULL DEFAULT 0` | Dòng lỗi |
| `skipped_rows` | `INTEGER NOT NULL DEFAULT 0` | Trùng/được bỏ qua |
| `confirmed_at` | `TIMESTAMPTZ` | Người dùng xác nhận sau DryRun |
| `claim_token` | `UUID` | Chủ xử lý |
| `lease_until` | `TIMESTAMPTZ` | Hạn claim, phục hồi sau crash |
| `finished_at` | `TIMESTAMPTZ` | Kết thúc job |
| `failure_code` | `VARCHAR(64)` | Mã lỗi job |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `UNIQUE (created_by, request_id)`
- `UNIQUE (input_bucket, input_object_key)`
- `CHECK (import_type IN ('VOCABULARY_CSV','QUESTIONS_JSON','GRAMMAR_JSON'))`
- `CHECK (status IN ('UPLOADING','DRY_RUN','READY','PROCESSING','COMPLETED','PARTIAL_SUCCESS','FAILED','EXPIRED'))`
- `CHECK (request_hash ~ '^[0-9a-f]{64}$')`
- `CHECK (input_sha256 IS NULL OR input_sha256 ~ '^[0-9a-f]{64}$')`
- `CHECK (declared_size_bytes BETWEEN 1 AND 5242880)`
- `CHECK (total_rows BETWEEN 0 AND 5000 AND success_rows>=0 AND failed_rows>=0 AND skipped_rows>=0 AND success_rows+failed_rows+skipped_rows<=total_rows)`
- `CHECK ((claim_token IS NULL)=(lease_until IS NULL))`
- `CHECK ((status IN ('COMPLETED','PARTIAL_SUCCESS','FAILED','EXPIRED'))=(finished_at IS NOT NULL))`
- `CHECK (status NOT IN ('PROCESSING','COMPLETED','PARTIAL_SUCCESS') OR confirmed_at IS NOT NULL)`
- `CHECK (upload_expires_at > created_at)`
- FK `(created_by)` → `users(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_import_jobs_user ON import_jobs(created_by, created_at DESC);`

## import_job_rows

Ledger từng dòng thay bảng import_job_errors đơn thuần; retry không tạo entity trùng.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `job_id` | `UUID NOT NULL` | Job |
| `row_number` | `INTEGER NOT NULL` | Dòng/item trong file |
| `input_hash` | `VARCHAR(64) NOT NULL` | Fingerprint dòng |
| `external_key` | `VARCHAR(200)` | Khóa ngoài nếu file cung cấp |
| `payload` | `JSONB NOT NULL` | Dữ liệu đã parse có schema |
| `status` | `VARCHAR(12) NOT NULL` | VALID/INVALID trước xác nhận, kết quả sau xử lý |
| `result_content_item_id` | `UUID` | Entity gốc được tạo thành công |
| `result_snapshot` | `JSONB` | IDs kết quả con của dòng nhóm nếu có |
| `errors` | `JSONB NOT NULL DEFAULT '[]'` | Lỗi theo trường, không stack trace/secret |
| `processed_at` | `TIMESTAMPTZ` | Mốc xử lý xong |

**Ràng buộc bảng**

- `PRIMARY KEY (job_id, row_number)`
- `CHECK (row_number BETWEEN 1 AND 5000)`
- `CHECK (status IN ('VALID','INVALID','PENDING','SUCCESS','FAILED','SKIPPED'))`
- `CHECK (input_hash ~ '^[0-9a-f]{64}$')`
- `CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 1048576)`
- `CHECK (result_snapshot IS NULL OR (jsonb_typeof(result_snapshot) = 'object' AND octet_length(result_snapshot::text) <= 1048576))`
- `CHECK (jsonb_typeof(errors)='array')`
- `CHECK (status<>'SUCCESS' OR (result_content_item_id IS NOT NULL AND processed_at IS NOT NULL))`
- FK `(job_id)` → `import_jobs(id)`; DELETE RESTRICT.
- FK `(result_content_item_id)` → `content_items(id)`; DELETE RESTRICT.

## storage_pools

Ngân sách CONTENT/TEMP toàn hệ thống và AVATAR từng user; hạ trần không xóa dữ liệu.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Pool ID |
| `scope` | `VARCHAR(12) NOT NULL` | CONTENT/TEMP/AVATAR |
| `owner_user_id` | `UUID` | Chỉ AVATAR có owner |
| `quota_bytes` | `BIGINT NOT NULL` | Trần cấu hình |
| `reserved_bytes` | `BIGINT NOT NULL DEFAULT 0` | Chỗ đã giữ, do trigger ledger duy trì |
| `used_bytes` | `BIGINT NOT NULL DEFAULT 0` | Byte đã commit, gồm file chờ dọn |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Chống cập nhật đè nhau |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc cập nhật |

**Ràng buộc bảng**

- `UNIQUE NULLS NOT DISTINCT (scope, owner_user_id)`
- `CHECK (scope IN ('CONTENT','TEMP','AVATAR'))`
- `CHECK ((scope='AVATAR')=(owner_user_id IS NOT NULL))`
- `CHECK (quota_bytes > 0 AND reserved_bytes >= 0 AND used_bytes >= 0 AND version >= 0)`
- FK `(owner_user_id)` → `users(id)`; DELETE RESTRICT.

## storage_allocations

Ledger giữ chỗ/file, chỉ giải phóng sau đối chiếu, không reset quota bằng archive.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Allocation ID |
| `pool_id` | `UUID NOT NULL` | Ngân sách chịu byte này |
| `allocation_key` | `VARCHAR(200) NOT NULL UNIQUE` | Khóa idempotent: mục đích/request/claim |
| `media_id` | `UUID` | Asset file cuối nếu có |
| `upload_request_id` | `UUID` | Nguồn upload media nếu có |
| `import_job_id` | `UUID` | Hoặc nguồn input import |
| `bytes` | `BIGINT NOT NULL` | Byte được giữ/commit |
| `state` | `VARCHAR(12) NOT NULL DEFAULT 'RESERVED'` | RESERVED/COMMITTED/RELEASED |
| `bucket` | `VARCHAR(100)` | Đối tượng đã được gắn nếu có |
| `object_key` | `VARCHAR(500)` | Key không phải URL |
| `committed_at` | `TIMESTAMPTZ` | File được xác nhận tồn tại |
| `released_at` | `TIMESTAMPTZ` | Đã xác nhận không còn tiêu thụ byte |
| `version` | `BIGINT NOT NULL DEFAULT 0` | CAS của ledger |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm tạo, UTC |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Thời điểm cập nhật |

**Ràng buộc bảng**

- `CHECK (state IN ('RESERVED','COMMITTED','RELEASED'))`
- `CHECK (bytes > 0 AND version >= 0)`
- `CHECK ((bucket IS NULL)=(object_key IS NULL))`
- `CHECK (state<>'COMMITTED' OR (committed_at IS NOT NULL AND bucket IS NOT NULL))`
- `CHECK ((state='RELEASED')=(released_at IS NOT NULL))`
- `CHECK (state='RELEASED' OR num_nonnulls(media_id,upload_request_id,import_job_id)>0)`
- FK `(pool_id)` → `storage_pools(id)`; DELETE RESTRICT.
- FK `(media_id)` → `media_assets(id)`; DELETE SET NULL.
- FK `(upload_request_id)` → `media_upload_requests(id)`; DELETE SET NULL.
- FK `(import_job_id)` → `import_jobs(id)`; DELETE SET NULL.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_allocations_pool ON storage_allocations(pool_id, state);`
- `CREATE INDEX ix_allocations_media ON storage_allocations(media_id);`

## system_settings

Allowlist typed; không dùng bảng này để chứa secret.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `key` | `VARCHAR(80) PRIMARY KEY` | Khóa được backend hỗ trợ |
| `value` | `JSONB NOT NULL` | Giá trị có kiểu |
| `value_type` | `VARCHAR(12) NOT NULL` | INTEGER/BOOLEAN/STRING |
| `unit` | `VARCHAR(20)` | Đơn vị |
| `description` | `TEXT` | Ý nghĩa/tác động |
| `updated_by` | `UUID` | Quản trị thay, NULL cho seed |
| `version` | `BIGINT NOT NULL DEFAULT 0` | Chống ghi đè |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc đổi |

**Ràng buộc bảng**

- `CHECK (value_type IN ('INTEGER','BOOLEAN','STRING'))`
- `CHECK (key IN ('auth.bcrypt_cost','auth.max_sessions','auth.session_seconds','auth.access_seconds','auth.otp_seconds','auth.otp_resend_seconds','auth.otp_send_hourly_email','auth.otp_send_hourly_ip','auth.otp_verify_15min_ip','auth.login_15min_ip','media.upload_seconds','media.verify_seconds','media.lease_seconds','media.verify_parallelism','media.avatar_hourly','media.content_hourly','media.avatar_open','media.content_open','storage.avatar_bytes','storage.content_bytes','storage.temp_bytes','practice.max_questions','practice.max_open','practice.abandon_days','stats.weak_min_sample','stats.weak_window_days','stats.weak_below_percent','import.max_bytes','import.max_rows','notification.max_retries','notification.lease_seconds','notification.learning_email_default','notification.default_timezone','retention.notification_days','retention.job_days','retention.auth_days','retention.audit_days'))`
- `CHECK (version >= 0)`
- `CHECK ((value_type='INTEGER' AND jsonb_typeof(value)='number' AND value::text ~ '^[0-9]+$') OR (value_type='BOOLEAN' AND jsonb_typeof(value)='boolean') OR (value_type='STRING' AND jsonb_typeof(value)='string'))`
- `CHECK (key<>'auth.bcrypt_cost' OR (value_type='INTEGER' AND (value::text)::integer BETWEEN 10 AND 16))`
- FK `(updated_by)` → `users(id)`; DELETE RESTRICT.

## audit_logs

Audit append-only cho tác vụ quản trị; không sao chép secret/URL có chữ ký.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Audit ID |
| `actor_type` | `VARCHAR(8) NOT NULL` | USER/SYSTEM |
| `actor_user_id` | `UUID` | Người thực hiện nếu USER |
| `action` | `VARCHAR(80) NOT NULL` | Mã thao tác backend kiểm soát |
| `entity_type` | `VARCHAR(50) NOT NULL` | Loại đối tượng, không phải FK đa hình |
| `entity_id` | `UUID` | Định danh đích |
| `metadata` | `JSONB` | Before/after đã lọc |
| `reason` | `TEXT` | Lý do phù hợp thao tác |
| `request_id` | `VARCHAR(100)` | Trace ID, không phải access token |
| `ip_address` | `INET` | IP đáng tin nếu có |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` | Mốc ghi |

**Ràng buộc bảng**

- `CHECK (actor_type IN ('USER','SYSTEM'))`
- `CHECK ((actor_type='USER')=(actor_user_id IS NOT NULL))`
- `CHECK (metadata IS NULL OR (jsonb_typeof(metadata) = 'object' AND octet_length(metadata::text) <= 1048576))`
- FK `(actor_user_id)` → `users(id)`; DELETE RESTRICT.

**Index phục vụ truy vấn**

- `CREATE INDEX ix_audit_actor ON audit_logs(actor_user_id, created_at DESC);`
- `CREATE INDEX ix_audit_entity ON audit_logs(entity_type, entity_id, created_at DESC);`

## event_publication

Schema native Modulith 2.1, giữ đúng V1; không tạo registry khác.

| Cột | Kiểu/ràng buộc cột | Ý nghĩa |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | Publication ID |
| `publication_date` | `TIMESTAMP(6) WITH TIME ZONE NOT NULL` | Mốc xuất bản event |
| `listener_id` | `VARCHAR(512) NOT NULL` | Listener |
| `serialized_event` | `TEXT NOT NULL` | Chỉ payload an toàn/ID, không OTP rõ |
| `event_type` | `VARCHAR(512) NOT NULL` | Event class |
| `completion_date` | `TIMESTAMP(6) WITH TIME ZONE` | Mốc hoàn tất |
| `last_resubmission_date` | `TIMESTAMP(6) WITH TIME ZONE` | Mốc retry |
| `completion_attempts` | `INTEGER NOT NULL DEFAULT 0` | Bộ đếm native |
| `status` | `VARCHAR(255)` | Trạng thái native |

**Ràng buộc bảng**

- Không có CHECK/unique bổ sung ngoài cột.

**Index phục vụ truy vấn**

- `CREATE INDEX idx_event_publication_completion_date ON event_publication(completion_date);`
- `CREATE INDEX idx_event_publication_status_publication_date ON event_publication(status, publication_date);`

## Đếm và nguồn chân lý

Bảng nối, snapshot options, source topics/mistakes và ledger dung lượng là chi tiết kỹ thuật để thực hiện nghiệp vụ đã duyệt; không phải module mới.
Không dùng SQL reference như migration nâng cấp. Kiểu/cột ở đây phải được đồng bộ cùng entity/DTO khi bắt đầu bước triển khai.
