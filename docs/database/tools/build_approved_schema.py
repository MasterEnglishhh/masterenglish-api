"""Render the reviewed PostgreSQL reference schema and its data dictionary.

This builds design artifacts only. It never connects to a database.
"""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
TABLES = []
HASH = "^[0-9a-f]{64}$"
BCRYPT = r"^(\{bcrypt\})?\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$"
TIMES = """
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Thời điểm tạo, UTC
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Thời điểm cập nhật
"""


def fk(columns, target, target_columns="id", delete="RESTRICT", deferred=False):
    return (columns, target, target_columns, delete, deferred)


def table(name, module, description, columns, checks=(), foreign=(), indexes=()):
    parsed = []
    for raw in columns.strip().splitlines():
        raw = raw.strip()
        if not raw:
            continue
        declaration, _, explanation = raw.partition(" -- ")
        colname, datatype = declaration.split(None, 1)
        parsed.append((colname, datatype, explanation))
    TABLES.append(dict(name=name, module=module, description=description,
                       columns=parsed, checks=list(checks), foreign=list(foreign),
                       indexes=list(indexes)))


def enum(column, values):
    return f"CHECK ({column} IN ({','.join(repr(v) for v in values.split())}))"


def obj(column, nullable=False):
    test = f"jsonb_typeof({column}) = 'object' AND octet_length({column}::text) <= 1048576"
    return f"CHECK ({column} IS NULL OR ({test}))" if nullable else f"CHECK ({test})"


def projection(kind):
    return f"""
id UUID PRIMARY KEY -- Cùng UUID với content_items, không tạo định danh thứ hai
content_type VARCHAR(30) GENERATED ALWAYS AS ('{kind}'::VARCHAR) STORED -- Loại cố định, không nhận từ client
published_revision_id UUID NOT NULL -- Bản nguồn của projection hiện hành
"""


def projection_fks():
    return [fk("id, content_type", "content_items", "id, content_type"),
            fk("id, published_revision_id", "content_revisions", "content_item_id, id", deferred=True)]


table("roles", "Identity", "Ba role độc lập, danh mục do migration quản lý.", """
id SMALLINT PRIMARY KEY -- Mã số nhỏ, cố định
code VARCHAR(30) NOT NULL UNIQUE -- Mã quyền dùng trong API/JWT
name VARCHAR(100) NOT NULL -- Nhãn hiển thị
""", [enum("code", "LEARNER CONTENT_ADMIN SYSTEM_ADMIN")])

table("users", "Identity", "Tài khoản, profile và trạng thái bảo mật; đăng ký chờ xác minh được thiếu credential chính thức.", f"""
id UUID PRIMARY KEY -- Định danh tài khoản
email VARCHAR(254) NOT NULL -- Email cố định, khóa so sánh lower(email)
first_name VARCHAR(30) -- Phần tên, có thể gồm tên đệm
last_name VARCHAR(30) -- Phần họ
password_hash VARCHAR(255) -- BCrypt, không phải mật khẩu rõ
phone VARCHAR(30) -- Tùy chọn, dạng chuỗi, không unique
avatar_media_id UUID -- Ảnh READY/AVATAR của chính user
status VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION' -- Vòng đời tài khoản
email_verified_at TIMESTAMPTZ -- Thời điểm xác minh
locked_until TIMESTAMPTZ -- Hạn khóa tạm
password_changed_at TIMESTAMPTZ -- Thời điểm gán/đổi credential
auth_version INTEGER NOT NULL DEFAULT 0 -- Thu hồi quyền xác thực cũ
version BIGINT NOT NULL DEFAULT 0 -- Optimistic lock
{TIMES}
""", [enum("status", "PENDING_VERIFICATION ACTIVE LOCKED DISABLED"),
       "CHECK (email = btrim(email) AND email <> '')",
       "CHECK (first_name IS NULL OR (first_name = btrim(first_name) AND first_name <> ''))",
       "CHECK (last_name IS NULL OR (last_name = btrim(last_name) AND last_name <> ''))",
       "CHECK (phone IS NULL OR (phone = btrim(phone) AND phone <> ''))",
       f"CHECK (password_hash IS NULL OR password_hash ~ '{BCRYPT}')",
       "CHECK (email_verified_at IS NULL OR (first_name IS NOT NULL AND last_name IS NOT NULL AND password_hash IS NOT NULL))",
       "CHECK (status <> 'ACTIVE' OR email_verified_at IS NOT NULL)",
       "CHECK (status <> 'PENDING_VERIFICATION' OR email_verified_at IS NULL)",
       "CHECK ((status = 'LOCKED') = (locked_until IS NOT NULL))",
       "CHECK (auth_version >= 0 AND version >= 0)",
       "CHECK (updated_at >= created_at)"],
      [fk("avatar_media_id", "media_assets")],
      ["CREATE UNIQUE INDEX uq_users_email ON users(lower(email));",
       "CREATE INDEX ix_users_status_created ON users(status, created_at DESC);"])

table("user_roles", "Identity", "Role hiện tại; lịch sử thay đổi ở audit_logs.", """
user_id UUID NOT NULL -- Tài khoản được gán
role_id SMALLINT NOT NULL -- Role
assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc gán
assigned_by UUID -- Người gán; NULL cho signup/bootstrap
""", ["PRIMARY KEY (user_id, role_id)"],
      [fk("user_id", "users"), fk("role_id", "roles"), fk("assigned_by", "users")],
      ["CREATE INDEX ix_user_roles_role ON user_roles(role_id, user_id);"])

table("auth_sessions", "Identity", "Phiên độc lập, hạn cố định tối đa bảy ngày; không lưu device_label.", f"""
id UUID PRIMARY KEY -- Định danh phiên/JWT sid
user_id UUID NOT NULL -- Chủ phiên
user_agent TEXT -- Metadata trình duyệt, không phải danh tính thiết bị
ip_address INET -- IP qua chuỗi proxy tin cậy
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Lần đăng nhập
last_active_at TIMESTAMPTZ -- Không dùng làm phút học
expires_at TIMESTAMPTZ NOT NULL -- Không được kéo dài bởi refresh
revoked_at TIMESTAMPTZ -- Mốc thu hồi
revoke_reason VARCHAR(32) -- Lý do thu hồi
""", ["UNIQUE (user_id, id)",
       "CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days')",
       "CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))",
       "CHECK (last_active_at IS NULL OR last_active_at >= created_at)",
       enum("revoke_reason", "LOGOUT PASSWORD_CHANGE PASSWORD_RESET ROLE_CHANGE ACCOUNT_LOCKED ACCOUNT_DISABLED SECURITY SESSION_LIMIT EXPIRED")],
      [fk("user_id", "users")],
      ["CREATE INDEX ix_sessions_user_live ON auth_sessions(user_id, created_at) WHERE revoked_at IS NULL;",
       "CREATE INDEX ix_sessions_expiry ON auth_sessions(expires_at);"])

table("refresh_tokens", "Identity", "Hash token ngẫu nhiên và chuỗi rotation trong một phiên.", f"""
id UUID PRIMARY KEY -- Token ID nội bộ
session_id UUID NOT NULL -- Phiên sở hữu
token_hash VARCHAR(64) NOT NULL UNIQUE -- SHA-256 của opaque token
issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Thời điểm cấp
expires_at TIMESTAMPTZ NOT NULL -- Không vượt hạn phiên
revoked_at TIMESTAMPTZ -- Mốc thu hồi hoặc rotate
replaced_by_id UUID UNIQUE -- Token kế tiếp trong cùng phiên
last_used_at TIMESTAMPTZ -- Lần refresh thành công
""", ["UNIQUE (session_id, id)", f"CHECK (token_hash ~ '{HASH}')",
       "CHECK (expires_at > issued_at)",
       "CHECK (replaced_by_id IS NULL OR (replaced_by_id <> id AND revoked_at IS NOT NULL))"],
      [fk("session_id", "auth_sessions"),
       fk("session_id, replaced_by_id", "refresh_tokens", "session_id, id", deferred=True)],
      ["CREATE UNIQUE INDEX uq_refresh_one_unrevoked ON refresh_tokens(session_id) WHERE revoked_at IS NULL;",
       "CREATE INDEX ix_refresh_expiry ON refresh_tokens(expires_at);"])

table("otp_challenges", "Identity", "OTP dùng một lần; bộ đếm liên yêu cầu ở auth_rate_limits.", f"""
id UUID PRIMARY KEY -- Challenge ID
user_id UUID NOT NULL -- Chủ challenge
purpose VARCHAR(24) NOT NULL -- Không dùng chéo mục đích
otp_hmac VARCHAR(64) -- HMAC, được xóa khi kết thúc
expires_at TIMESTAMPTZ NOT NULL -- Hạn mã hiện tại, 5 phút
last_sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc gửi gần nhất
attempt_count SMALLINT NOT NULL DEFAULT 0 -- Lỗi trên challenge cụ thể
blocked_until TIMESTAMPTZ -- Không thay bộ đếm chung
consumed_at TIMESTAMPTZ -- OTP đã dùng
invalidated_at TIMESTAMPTZ -- OTP bị thay/hủy
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc tạo
""", ["UNIQUE (user_id, purpose, id)",
       enum("purpose", "EMAIL_VERIFICATION PASSWORD_CHANGE PASSWORD_RESET"),
       f"CHECK (otp_hmac IS NULL OR otp_hmac ~ '{HASH}')",
       "CHECK (attempt_count BETWEEN 0 AND 5)",
       "CHECK (expires_at > created_at AND expires_at <= last_sent_at + INTERVAL '5 minutes')",
       "CHECK (NOT (consumed_at IS NOT NULL AND invalidated_at IS NOT NULL))"],
      [fk("user_id", "users")],
      ["CREATE UNIQUE INDEX uq_otp_open ON otp_challenges(user_id, purpose) WHERE consumed_at IS NULL AND invalidated_at IS NULL;",
       "CREATE INDEX ix_otp_expiry ON otp_challenges(expires_at);"])

for request_name, purpose in [("registration_requests", "EMAIL_VERIFICATION"),
                              ("password_change_requests", "PASSWORD_CHANGE")]:
    registration = request_name == "registration_requests"
    extras = """
pending_first_name VARCHAR(30) NOT NULL -- Phần tên của đúng lượt đăng ký
pending_last_name VARCHAR(30) NOT NULL -- Phần họ của đúng lượt đăng ký
""" if registration else """
session_id UUID NOT NULL -- Chỉ phiên khởi tạo được hoàn tất
auth_version_at_request INTEGER NOT NULL -- Phiên bản xác thực lúc kiểm tra mật khẩu cũ
"""
    table(request_name, "Identity", "Ngữ cảnh chờ OTP, không lưu mật khẩu rõ.", f"""
id UUID PRIMARY KEY -- Định danh yêu cầu
user_id UUID NOT NULL -- Chủ yêu cầu
otp_challenge_id UUID NOT NULL UNIQUE -- Challenge đúng ngữ cảnh
otp_purpose VARCHAR(24) GENERATED ALWAYS AS ('{purpose}'::VARCHAR) STORED -- Discriminator FK do DB sinh
{extras}
pending_password_hash VARCHAR(255) -- BCrypt chờ xác nhận, được dọn khi kết thúc
expires_at TIMESTAMPTZ NOT NULL -- Hạn chu kỳ 7 ngày hoặc yêu cầu đổi 5 phút
completed_at TIMESTAMPTZ -- Hoàn tất
invalidated_at TIMESTAMPTZ -- Bị hủy/thay thế
{TIMES}
""", [f"CHECK (pending_password_hash IS NULL OR pending_password_hash ~ '{BCRYPT}')",
       "CHECK (completed_at IS NOT NULL OR invalidated_at IS NOT NULL OR pending_password_hash IS NOT NULL)",
       "CHECK (NOT (completed_at IS NOT NULL AND invalidated_at IS NOT NULL))",
       "CHECK (expires_at > created_at)"] +
       (["CHECK (pending_first_name = btrim(pending_first_name) AND pending_first_name <> '')",
         "CHECK (pending_last_name = btrim(pending_last_name) AND pending_last_name <> '')",
         "CHECK (expires_at <= created_at + INTERVAL '7 days')"] if registration else
        ["CHECK (auth_version_at_request >= 0)", "CHECK (expires_at <= created_at + INTERVAL '5 minutes')"]),
       [fk("user_id", "users"), fk("user_id, otp_purpose, otp_challenge_id", "otp_challenges", "user_id, purpose, id")] +
       ([] if registration else [fk("user_id, session_id", "auth_sessions", "user_id, id")]),
       [f"CREATE UNIQUE INDEX uq_{request_name}_open ON {request_name}(user_id) WHERE completed_at IS NULL AND invalidated_at IS NULL;",
        f"CREATE INDEX ix_{request_name}_expiry ON {request_name}(expires_at);"])

table("password_reset_grants", "Identity", "Quyền reset giới hạn, không phải role hay phiên đăng nhập.", f"""
id UUID PRIMARY KEY -- ID grant
user_id UUID NOT NULL -- Chủ grant
otp_challenge_id UUID NOT NULL UNIQUE -- Một OTP chỉ cấp một grant
otp_purpose VARCHAR(24) GENERATED ALWAYS AS ('PASSWORD_RESET'::VARCHAR) STORED -- Đúng purpose của OTP
grant_hash VARCHAR(64) UNIQUE -- Hash opaque grant, được dọn khi hết hiệu lực
auth_version_at_issue INTEGER NOT NULL -- Chống dùng grant cũ sau thay bảo mật
issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc cấp
expires_at TIMESTAMPTZ NOT NULL -- Tối đa 5 phút
used_at TIMESTAMPTZ -- Mốc sử dụng
invalidated_at TIMESTAMPTZ -- Mốc hủy/thay
""", [f"CHECK (grant_hash IS NULL OR grant_hash ~ '{HASH}')",
       "CHECK (used_at IS NOT NULL OR invalidated_at IS NOT NULL OR grant_hash IS NOT NULL)",
       "CHECK (expires_at > issued_at AND expires_at <= issued_at + INTERVAL '5 minutes')",
       "CHECK (auth_version_at_issue >= 0)",
       "CHECK (NOT (used_at IS NOT NULL AND invalidated_at IS NOT NULL))"],
      [fk("user_id", "users"), fk("user_id, otp_purpose, otp_challenge_id", "otp_challenges", "user_id, purpose, id")],
      ["CREATE UNIQUE INDEX uq_reset_grant_open ON password_reset_grants(user_id) WHERE used_at IS NULL AND invalidated_at IS NULL;"])

table("auth_rate_limits", "Identity", "Bộ giới hạn độc lập user/challenge ID, có cả email chưa đăng ký.", f"""
id UUID PRIMARY KEY -- Bucket ID
scope VARCHAR(64) NOT NULL -- Quy tắc và mục đích, tách email/IP
subject_hash VARCHAR(64) NOT NULL -- HMAC định danh chuẩn hóa
blocked_until TIMESTAMPTZ -- Hạn chờ, không kéo dài vì request bị chặn
reset_at TIMESTAMPTZ -- Mốc đặt lại lỗi, không xóa quota gửi
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc cập nhật
""", ["UNIQUE (scope, subject_hash)", f"CHECK (subject_hash ~ '{HASH}')",
       "CHECK (scope IN ('OTP_SEND_EMAIL','LOGIN_FAILURE_EMAIL','OTP_VERIFY_EMAIL_VERIFICATION','OTP_VERIFY_PASSWORD_CHANGE','OTP_VERIFY_PASSWORD_RESET','OTP_SEND_IP','OTP_VERIFY_IP','LOGIN_IP'))"])

table("auth_rate_limit_events", "Identity", "Sự kiện đếm trong cửa sổ thời gian trượt, không phải audit đầy đủ.", """
id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY -- ID sự kiện
rate_limit_id UUID NOT NULL -- Bucket
occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc được tính
""", foreign=[fk("rate_limit_id", "auth_rate_limits")],
      indexes=["CREATE INDEX ix_rate_events_window ON auth_rate_limit_events(rate_limit_id, occurred_at DESC);"])

table("content_sources", "Content", "Xuất xứ và khai báo quyền sử dụng; ACTIVE không phải kết luận pháp lý.", f"""
id UUID PRIMARY KEY -- Source ID
title VARCHAR(255) NOT NULL -- Tên nguồn
source_type VARCHAR(20) NOT NULL -- Tự soạn hoặc bên ngoài
source_url TEXT -- URL nguồn gốc, không phải signed URL
author VARCHAR(255) -- Tác giả/đơn vị, không tự đồng nhất người nhập
license_code VARCHAR(100) -- Khai báo giấy phép, có thể thiếu ở Draft
license_url TEXT -- URL thông tin giấy phép
attribution TEXT -- Nội dung ghi nguồn
permission_note TEXT -- Thông tin khai báo nội bộ, không tự trả learner
status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' -- Chọn mới hay archive
created_by UUID NOT NULL -- Người khai báo
{TIMES}
""", [enum("source_type", "SELF_AUTHORED EXTERNAL"), enum("status", "ACTIVE ARCHIVED"),
       "CHECK (btrim(title) <> '')"], [fk("created_by", "users")])

table("media_assets", "Media", "File lâu dài; object bất biến, xóa chỉ khi hết mọi tham chiếu.", f"""
id UUID PRIMARY KEY -- Media ID ổn định
media_type VARCHAR(16) NOT NULL -- IMAGE/AUDIO hiện tại
purpose VARCHAR(16) NOT NULL -- CONTENT/AVATAR
bucket VARCHAR(100) -- Kho file cuối
object_key VARCHAR(500) -- Key cuối, không phải URL
original_filename VARCHAR(255) NOT NULL -- Tên để hiển thị
mime_type VARCHAR(100) -- MIME thực tế sau xác minh
file_size_bytes BIGINT -- Byte thực tế
duration_ms INTEGER -- Thời lượng audio
width INTEGER -- Chiều rộng ảnh
height INTEGER -- Chiều cao ảnh
checksum_sha256 VARCHAR(64) -- Hash file thực tế
source_id UUID -- Nguồn chính của file, avatar tùy chọn
uploaded_by UUID NOT NULL -- Chủ lượt upload
status VARCHAR(16) NOT NULL DEFAULT 'PENDING' -- Vòng đời file
failure_code VARCHAR(64) -- Lỗi có kiểm soát
archived_at TIMESTAMPTZ -- Mốc archive ổn định
deletion_started_at TIMESTAMPTZ -- Claim ngăn cấp/gắn mới khi đang dọn
object_deleted_at TIMESTAMPTZ -- Backend xác nhận đối tượng cuối đã xóa, khác việc archive
{TIMES}
""", [enum("media_type", "IMAGE AUDIO"), enum("purpose", "CONTENT AVATAR"),
       enum("status", "PENDING READY FAILED ARCHIVED"), "UNIQUE (bucket, object_key)",
       "CHECK (purpose <> 'AVATAR' OR media_type = 'IMAGE')",
       "CHECK (file_size_bytes IS NULL OR file_size_bytes > 0)",
       f"CHECK (checksum_sha256 IS NULL OR checksum_sha256 ~ '{HASH}')",
       "CHECK (status NOT IN ('READY','ARCHIVED') OR (bucket IS NOT NULL AND object_key IS NOT NULL AND mime_type IS NOT NULL AND file_size_bytes IS NOT NULL AND checksum_sha256 IS NOT NULL))",
       "CHECK (status NOT IN ('READY','ARCHIVED') OR (media_type = 'IMAGE' AND width > 0 AND height > 0 AND width IS NOT NULL AND height IS NOT NULL AND duration_ms IS NULL) OR (media_type = 'AUDIO' AND duration_ms > 0 AND duration_ms IS NOT NULL AND width IS NULL AND height IS NULL))",
       "CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))",
       "CHECK (deletion_started_at IS NULL OR status IN ('ARCHIVED','FAILED'))",
       "CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)",
       "CHECK (file_size_bytes IS NULL OR file_size_bytes <= CASE WHEN purpose='AVATAR' THEN 2097152 WHEN media_type='IMAGE' THEN 5242880 ELSE 20971520 END)",
       "CHECK (mime_type IS NULL OR (media_type='IMAGE' AND mime_type IN ('image/jpeg','image/png','image/webp')) OR (media_type='AUDIO' AND mime_type IN ('audio/mpeg','audio/mp4')))",
       "CHECK (original_filename <> '' AND original_filename !~ '[[:cntrl:]]')"],
      [fk("source_id", "content_sources"), fk("uploaded_by", "users")],
      ["CREATE INDEX ix_media_owner_purpose ON media_assets(uploaded_by, purpose, status);",
       "CREATE INDEX ix_media_cleanup ON media_assets(status, archived_at);"])

table("media_upload_requests", "Media", "Ngữ cảnh upload tạm; finished_at không đổi khi dọn file.", f"""
id UUID PRIMARY KEY -- Upload request ID
media_id UUID NOT NULL UNIQUE -- Một media cho một yêu cầu
temp_bucket VARCHAR(100) NOT NULL -- Kho tạm
temp_object_key VARCHAR(500) NOT NULL -- Key tạm duy nhất
declared_size_bytes BIGINT NOT NULL -- Byte khai báo, phải khớp file thật
declared_mime_type VARCHAR(100) NOT NULL -- MIME khai báo
max_size_bytes BIGINT NOT NULL -- Trần được backend cấp
status VARCHAR(16) NOT NULL DEFAULT 'PENDING' -- Trạng thái yêu cầu
expires_at TIMESTAMPTZ NOT NULL -- Hạn nhận complete
verification_started_at TIMESTAMPTZ -- Mốc nhận complete hợp lệ
verification_deadline_at TIMESTAMPTZ -- Hạn toàn quá trình, không kéo dài retry
claim_token UUID -- Lượt xử lý đang giữ quyền
lease_until TIMESTAMPTZ -- Hạn quyền xử lý
completed_at TIMESTAMPTZ -- Mốc thành công
finished_at TIMESTAMPTZ -- Mốc kết thúc mọi trạng thái, cho retention ổn định
failure_code VARCHAR(64) -- Mã lỗi
{TIMES}
""", [enum("status", "PENDING VERIFYING COMPLETED FAILED EXPIRED"),
       "UNIQUE (temp_bucket, temp_object_key)",
       "CHECK (declared_size_bytes > 0 AND declared_size_bytes <= max_size_bytes AND max_size_bytes <= 20971520)",
       "CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '10 minutes')",
       "CHECK ((verification_started_at IS NULL) = (verification_deadline_at IS NULL))",
       "CHECK (verification_started_at IS NULL OR (verification_started_at <= expires_at AND verification_deadline_at > verification_started_at AND verification_deadline_at <= verification_started_at + INTERVAL '5 minutes'))",
       "CHECK ((claim_token IS NULL) = (lease_until IS NULL))",
       "CHECK (lease_until IS NULL OR (status='VERIFYING' AND lease_until <= verification_deadline_at))",
       "CHECK ((status='COMPLETED') = (completed_at IS NOT NULL))",
       "CHECK ((status IN ('COMPLETED','FAILED','EXPIRED')) = (finished_at IS NOT NULL))",
       "CHECK (status NOT IN ('FAILED','EXPIRED') OR failure_code IS NOT NULL)",
       "CHECK (status <> 'VERIFYING' OR verification_started_at IS NOT NULL)"],
      [fk("media_id", "media_assets")],
      ["CREATE INDEX ix_uploads_open ON media_upload_requests(status, expires_at);",
       "CREATE INDEX ix_uploads_claim ON media_upload_requests(lease_until) WHERE status='VERIFYING';"])

table("content_items", "Content", "Định danh ổn định và pointer bản công bố; domain projection dùng cùng UUID.", f"""
id UUID PRIMARY KEY -- Định danh dùng xuyên phiên bản
content_type VARCHAR(30) NOT NULL -- Loại nội dung, bất biến
created_by UUID NOT NULL -- Người khởi tạo
published_revision_id UUID -- Bản hiện hành, NULL khi chưa công bố
is_archived BOOLEAN NOT NULL DEFAULT FALSE -- Ngừng cấp mới
archived_at TIMESTAMPTZ -- Mốc archive
access_revoked_at TIMESTAMPTZ -- Thu hồi truy cập riêng với archive
access_revoke_reason TEXT -- Lý do thu hồi
import_namespace VARCHAR(120) -- Namespace khóa ngoài nếu nhập có key
external_key VARCHAR(200) -- Nhận diện xuyên job, không tự suy theo lemma
version BIGINT NOT NULL DEFAULT 0 -- Optimistic lock của item
{TIMES}
""", ["UNIQUE (id, content_type)",
       enum("content_type", "WORD DECK GRAMMAR_TOPIC GRAMMAR_LESSON QUESTION QUESTION_GROUP PRACTICE_SET"),
       "CHECK ((is_archived) = (archived_at IS NOT NULL))",
       "CHECK ((access_revoked_at IS NULL) = (access_revoke_reason IS NULL))",
       "CHECK ((import_namespace IS NULL) = (external_key IS NULL))", "CHECK (version >= 0)"],
      [fk("created_by", "users"),
       fk("id, published_revision_id", "content_revisions", "content_item_id, id", deferred=True)],
      ["CREATE UNIQUE INDEX uq_content_external_key ON content_items(content_type, import_namespace, external_key) WHERE external_key IS NOT NULL;",
       "CREATE INDEX ix_content_catalog ON content_items(content_type, is_archived);"])

table("content_revisions", "Content", "Draft có schema; Published bất biến; một nhánh đang biên soạn mỗi item.", f"""
id UUID PRIMARY KEY -- Revision ID
content_item_id UUID NOT NULL -- Nội dung sở hữu
version_no INTEGER NOT NULL -- Số thứ tự bản, khác optimistic version
schema_version SMALLINT NOT NULL DEFAULT 1 -- Schema payload theo content_type
snapshot JSONB NOT NULL DEFAULT '{{}}' -- Nguồn chân lý của bản, Draft có thể thiếu
status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' -- Vòng đời biên soạn
change_summary TEXT -- Tóm tắt thay đổi
created_by UUID NOT NULL -- Người tạo bản
reviewed_by UUID -- Người duyệt/từ chối gần nhất
review_note TEXT -- Lý do khi từ chối
reviewed_at TIMESTAMPTZ -- Mốc quyết định duyệt
published_at TIMESTAMPTZ -- Mốc công bố, không phải pointer hiện hành
version BIGINT NOT NULL DEFAULT 0 -- Chống ghi đè Draft
{TIMES}
""", ["UNIQUE (content_item_id, id)", "UNIQUE (content_item_id, version_no)",
       enum("status", "DRAFT IN_REVIEW APPROVED REJECTED PUBLISHED"), obj("snapshot"),
       "CHECK (version_no > 0 AND schema_version > 0 AND version >= 0)",
       "CHECK (status NOT IN ('APPROVED','REJECTED','PUBLISHED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))",
       "CHECK (status <> 'REJECTED' OR (review_note IS NOT NULL AND btrim(review_note) <> ''))",
       "CHECK ((status='PUBLISHED') = (published_at IS NOT NULL))"],
      [fk("content_item_id", "content_items"), fk("created_by", "users"), fk("reviewed_by", "users")],
      ["CREATE UNIQUE INDEX uq_revision_one_open ON content_revisions(content_item_id) WHERE status <> 'PUBLISHED';",
       "CREATE INDEX ix_revisions_item_version ON content_revisions(content_item_id, version_no DESC);"])

table("content_source_links", "Content", "Nguồn theo từng revision; bản chụp nguồn lúc công bố.", f"""
revision_id UUID NOT NULL -- Bản nội dung
source_id UUID NOT NULL -- Nguồn
usage_note TEXT -- Đoạn/ảnh/audio dùng nguồn này
source_snapshot JSONB -- Bản chụp metadata; có trường nội bộ
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc gắn
""", ["PRIMARY KEY (revision_id, source_id)", obj("source_snapshot", True)],
      [fk("revision_id", "content_revisions"), fk("source_id", "content_sources")],
      ["CREATE INDEX ix_source_links_source ON content_source_links(source_id);"])

table("revision_dependencies", "Content", "Ghim revision con; formal edge chỉ tới bản đã công bố.", """
parent_revision_id UUID NOT NULL -- Bản cha
relation_key VARCHAR(200) NOT NULL -- Khóa thành viên ổn định trong schema, ví dụ sense:UUID
child_revision_id UUID NOT NULL -- Bản con được ghim
""", ["PRIMARY KEY (parent_revision_id, relation_key)",
       "CHECK (parent_revision_id <> child_revision_id)"],
      [fk("parent_revision_id", "content_revisions"), fk("child_revision_id", "content_revisions")],
      ["CREATE INDEX ix_revision_dependencies_child ON revision_dependencies(child_revision_id);"])

table("revision_media_refs", "Content", "FK tra cứu tất cả file trong Draft/Published, kể cả ở JSON.", """
revision_id UUID NOT NULL -- Revision dùng file
media_id UUID NOT NULL -- File cần bảo toàn
usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS' -- Phân biệt học liệu và lời giải/transcript
""", ["PRIMARY KEY (revision_id, media_id, usage_role)", enum("usage_role", "STIMULUS EXPLANATION TRANSCRIPT")],
      [fk("revision_id", "content_revisions"), fk("media_id", "media_assets")],
      ["CREATE INDEX ix_revision_media_asset ON revision_media_refs(media_id);"])

table("words", "Vocabulary", "Projection từ hiện hành; nghĩa là đơn vị theo dõi học.", projection("WORD") + f"""
lemma VARCHAR(150) NOT NULL -- Từ/biểu thức, không unique tuyệt đối
language_code VARCHAR(10) NOT NULL DEFAULT 'en' -- Ngôn ngữ
ipa VARCHAR(150) -- Phiên âm
audio_media_id UUID -- Audio chung
{TIMES}
""", ["CHECK (btrim(lemma) <> '')"], projection_fks()+[fk("audio_media_id", "media_assets")],
      ["CREATE INDEX ix_words_search ON words(lower(lemma) varchar_pattern_ops);"])

table("word_senses", "Vocabulary", "Nghĩa ổn định; đổi hẳn nghĩa tạo ID mới, không chuyển SRS.", f"""
id UUID PRIMARY KEY -- Định danh nghĩa
word_id UUID NOT NULL -- Từ sở hữu
word_revision_id UUID NOT NULL -- Bản từ sinh projection hiện tại của nghĩa
sense_no SMALLINT NOT NULL -- Thứ tự nghĩa
part_of_speech VARCHAR(24) NOT NULL -- Từ loại
cefr_level VARCHAR(2) NOT NULL -- Cấp độ của nghĩa
meaning_vi TEXT NOT NULL -- Nghĩa Việt
definition_en TEXT -- Định nghĩa Anh
audio_media_id UUID -- Ghi đè audio chung nếu có
is_archived BOOLEAN NOT NULL DEFAULT FALSE -- Giữ progress cũ khi bỏ nghĩa
{TIMES}
""", ["UNIQUE (word_id, id)",
       enum("part_of_speech", "NOUN VERB ADJECTIVE ADVERB PRONOUN PREPOSITION CONJUNCTION DETERMINER INTERJECTION NUMERAL PHRASE"),
       enum("cefr_level", "A1 A2 B1 B2 C1 C2"), "CHECK (sense_no > 0 AND btrim(meaning_vi) <> '')"],
      [fk("word_id", "words"), fk("word_id, word_revision_id", "content_revisions", "content_item_id, id"), fk("audio_media_id", "media_assets")],
      ["CREATE INDEX ix_senses_filters ON word_senses(cefr_level, part_of_speech) WHERE is_archived=FALSE;",
       "CREATE UNIQUE INDEX uq_senses_active_position ON word_senses(word_id, sense_no) WHERE is_archived=FALSE;"])

table("word_sense_examples", "Vocabulary", "Nhiều ví dụ của một nghĩa, cặp Anh/Việt có thứ tự.", """
id UUID PRIMARY KEY -- Example ID
word_sense_id UUID NOT NULL -- Nghĩa
position SMALLINT NOT NULL -- Thứ tự ví dụ
example_en TEXT NOT NULL -- Câu tiếng Anh
example_vi TEXT -- Bản dịch tùy chọn
""", ["UNIQUE (word_sense_id, position)", "CHECK (position > 0 AND btrim(example_en) <> '')"], [fk("word_sense_id", "word_senses")])

table("decks", "Vocabulary", "Deck công bố, thành viên theo nghĩa và revision đã ghim.", projection("DECK") + f"""
title VARCHAR(200) NOT NULL -- Tên deck
description TEXT -- Mô tả
level VARCHAR(2) -- Mức gợi ý, không thay CEFR ở nghĩa
{TIMES}
""", [enum("level", "A1 A2 B1 B2 C1 C2"), "CHECK (btrim(title) <> '')"], projection_fks())

table("deck_words", "Vocabulary", "Thành viên projection của deck; runtime dùng bản ghim khi giao bài.", """
deck_id UUID NOT NULL -- Deck
word_sense_id UUID NOT NULL -- Nghĩa
position INTEGER NOT NULL -- Thứ tự
""", ["PRIMARY KEY (deck_id, word_sense_id)", "UNIQUE (deck_id, position)", "CHECK (position > 0)"],
      [fk("deck_id", "decks"), fk("word_sense_id", "word_senses")],
      ["CREATE INDEX ix_deck_words_sense ON deck_words(word_sense_id);"])

table("user_saved_words", "Learning", "Bỏ lưu chỉ xóa liên kết này, không xóa SRS.", """
user_id UUID NOT NULL -- Người lưu
word_sense_id UUID NOT NULL -- Nghĩa được lưu
saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc lưu
""", ["PRIMARY KEY (user_id, word_sense_id)"], [fk("user_id", "users"), fk("word_sense_id", "word_senses")])

table("user_word_progress", "Learning", "Trạng thái SRS hiện tại, khóa theo user và nghĩa.", f"""
user_id UUID NOT NULL -- Người học
word_sense_id UUID NOT NULL -- Nghĩa
repetitions INTEGER NOT NULL DEFAULT 0 -- Chuỗi nhớ đạt
interval_days INTEGER NOT NULL DEFAULT 0 -- Khoảng ngày hiện tại; học lại ngắn có thể bằng 0
ease_factor NUMERIC(6,3) NOT NULL DEFAULT 2.5 -- Hệ số, không dưới 1.3
due_at TIMESTAMPTZ NOT NULL -- Hạn ôn do server tính
last_reviewed_at TIMESTAMPTZ -- Lần review gần nhất
policy_version VARCHAR(32) NOT NULL DEFAULT 'SM2_3BTN_V1' -- Thuật toán đã dùng
version BIGINT NOT NULL DEFAULT 0 -- Chống ghi đè
{TIMES}
""", ["PRIMARY KEY (user_id, word_sense_id)",
       "CHECK (repetitions >= 0 AND interval_days >= 0 AND ease_factor >= 1.3 AND version >= 0)"],
      [fk("user_id", "users"), fk("word_sense_id", "word_senses")],
      ["CREATE INDEX ix_progress_due ON user_word_progress(user_id, due_at);"])

table("word_reviews", "Learning", "Sự kiện review bất biến và idempotent, có chính sách/trạng thái trước-sau.", f"""
id UUID PRIMARY KEY -- Review ID
user_id UUID NOT NULL -- Người học
word_sense_id UUID NOT NULL -- Nghĩa
source_word_id UUID NOT NULL -- Từ chứa nghĩa đã xem
source_revision_id UUID NOT NULL -- Bản từ đã xem
request_id UUID NOT NULL -- Retry dùng lại cùng key
request_hash VARCHAR(64) NOT NULL -- Phát hiện cùng key nhưng payload khác
rating VARCHAR(8) NOT NULL -- AGAIN/HARD/GOOD sau khi lật
policy_version VARCHAR(32) NOT NULL -- Bản thuật toán
before_state JSONB NOT NULL -- Trạng thái SRS trước review
after_state JSONB NOT NULL -- Trạng thái SRS sau review
previous_due_at TIMESTAMPTZ -- Hạn cũ
next_due_at TIMESTAMPTZ NOT NULL -- Hạn mới
reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Server time
timezone VARCHAR(60) NOT NULL -- Múi giờ tại thời điểm ghi nhận
local_day DATE NOT NULL -- Ngày thống kê ổn định
""", ["UNIQUE (user_id, request_id)", f"CHECK (request_hash ~ '{HASH}')",
       enum("rating", "AGAIN HARD GOOD"), obj("before_state"), obj("after_state")],
      [fk("user_id", "users"), fk("source_word_id, word_sense_id", "word_senses", "word_id, id"),
       fk("source_word_id, source_revision_id", "content_revisions", "content_item_id, id")],
      ["CREATE INDEX ix_reviews_user_day ON word_reviews(user_id, local_day);",
       "CREATE INDEX ix_reviews_sense_time ON word_reviews(user_id, word_sense_id, reviewed_at DESC);"])

table("grammar_topics", "Grammar", "Chủ điểm, không thêm cây curriculum nhiều cấp ngoài phạm vi.", projection("GRAMMAR_TOPIC") + f"""
slug VARCHAR(120) NOT NULL UNIQUE -- Đường dẫn ổn định
name VARCHAR(200) NOT NULL -- Tên chủ điểm
description TEXT -- Mô tả
difficulty VARCHAR(16) -- Mức gợi ý
sort_order INTEGER NOT NULL DEFAULT 0 -- Thứ tự
{TIMES}
""", [enum("difficulty", "BASIC INTERMEDIATE ADVANCED"), "CHECK (sort_order >= 0 AND btrim(name) <> '')"], projection_fks())

table("grammar_lessons", "Grammar", "Bài học khối có schema, thuộc một topic.", projection("GRAMMAR_LESSON") + f"""
topic_id UUID NOT NULL -- Chủ điểm
title VARCHAR(200) NOT NULL -- Tiêu đề
body_schema_version SMALLINT NOT NULL DEFAULT 1 -- Schema khối
body JSONB NOT NULL -- Các block được kiểm soát
sort_order INTEGER NOT NULL DEFAULT 0 -- Thứ tự trong chủ điểm
estimated_minutes SMALLINT -- Thời gian gợi ý, không phải phút học thực tế
{TIMES}
""", [obj("body"), "CHECK (char_length(body::text) <= 100000)",
       "CHECK (sort_order >= 0 AND body_schema_version > 0)",
       "CHECK (estimated_minutes IS NULL OR estimated_minutes > 0)"],
      projection_fks()+[fk("topic_id", "grammar_topics")],
      ["CREATE INDEX ix_lessons_topic_order ON grammar_lessons(topic_id, sort_order);"])

table("lesson_progress", "Learning", "Hoàn thành bằng thao tác người học; giữ bản đã hoàn thành khi có bản mới.", f"""
user_id UUID NOT NULL -- Người học
lesson_id UUID NOT NULL -- Bài học
state VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' -- Không có row là NOT_STARTED
last_completed_revision_id UUID -- Bản đã hoàn thành
started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mở học
completed_at TIMESTAMPTZ -- Lần hoàn thành
version BIGINT NOT NULL DEFAULT 0 -- Chống ghi đè
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc cập nhật
""", ["PRIMARY KEY (user_id, lesson_id)", enum("state", "IN_PROGRESS COMPLETED"),
       "CHECK ((state='COMPLETED') = (completed_at IS NOT NULL))",
       "CHECK (state <> 'COMPLETED' OR last_completed_revision_id IS NOT NULL)", "CHECK (version >= 0)"],
      [fk("user_id", "users"), fk("lesson_id", "grammar_lessons"),
       fk("lesson_id, last_completed_revision_id", "content_revisions", "content_item_id, id")])

table("question_types", "Practice", "Catalogue cách trả lời; chỉ bật code có renderer/grader đã triển khai.", """
code VARCHAR(32) PRIMARY KEY -- SINGLE_CHOICE ban đầu
name VARCHAR(100) NOT NULL -- Nhãn
schema_version SMALLINT NOT NULL DEFAULT 1 -- Phiên bản response
enabled BOOLEAN NOT NULL DEFAULT FALSE -- Không tự hỗ trợ type bằng thêm row
""", ["CHECK (schema_version > 0)"])

table("question_groups", "Practice", "Ngữ cảnh cha; group type không thay question response type.", projection("QUESTION_GROUP") + f"""
category VARCHAR(16) NOT NULL -- Mảng kiến thức
part_number SMALLINT -- Part nếu TOEIC
title VARCHAR(200) NOT NULL -- Tên nhóm
group_type VARCHAR(24) NOT NULL -- Loại ngữ cảnh
{TIMES}
""", ["UNIQUE (id, category)", "UNIQUE (id, category, part_number)",
       enum("category", "VOCABULARY GRAMMAR TOEIC"),
       enum("group_type", "GENERAL CONVERSATION TALK TEXT_COMPLETION PASSAGE"),
       "CHECK ((category='TOEIC' AND part_number IS NOT NULL AND ((part_number=3 AND group_type='CONVERSATION') OR (part_number=4 AND group_type='TALK') OR (part_number=6 AND group_type='TEXT_COMPLETION') OR (part_number=7 AND group_type='PASSAGE'))) OR (category<>'TOEIC' AND part_number IS NULL AND group_type='GENERAL'))"], projection_fks())

table("questions", "Practice", "Câu chấm được; mỗi câu tối đa một nhóm; không dùng group_questions N:N.", projection("QUESTION") + f"""
category VARCHAR(16) NOT NULL -- Vocabulary/Grammar/TOEIC
question_type_code VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE' -- Cơ chế trả lời
practice_mode VARCHAR(32) NOT NULL DEFAULT 'STANDARD' -- Kiểu luyện, không phải grader
part_number SMALLINT -- TOEIC Part
prompt TEXT -- Có thể trống cho câu nghe
explanation TEXT NOT NULL -- Lời giải, chỉ cấp sau nộp
difficulty VARCHAR(8) NOT NULL DEFAULT 'MEDIUM' -- Độ khó
group_id UUID -- Context cha nếu có
group_position SMALLINT -- Thứ tự câu con
in_current_group BOOLEAN NOT NULL DEFAULT TRUE -- Membership projection hiện hành; giữ context cũ khi thay câu
blank_key VARCHAR(80) -- Neo vị trí trống Part 6
{TIMES}
""", ["UNIQUE (id, group_id)", "UNIQUE (id, category)",
       enum("category", "VOCABULARY GRAMMAR TOEIC"), enum("difficulty", "EASY MEDIUM HARD"),
       enum("practice_mode", "STANDARD TRANSLATE_EN_VI TRANSLATE_VI_EN LISTEN_AND_CHOOSE FILL_OPTION"),
       "CHECK (char_length(prompt) <= 10000 AND char_length(explanation) <= 20000)",
       "CHECK ((category='TOEIC' AND part_number BETWEEN 1 AND 7 AND part_number IS NOT NULL) OR (category<>'TOEIC' AND part_number IS NULL))",
       "CHECK ((group_id IS NULL AND group_position IS NULL AND blank_key IS NULL) OR (group_id IS NOT NULL AND group_position > 0 AND group_position IS NOT NULL))",
       "CHECK (category<>'TOEIC' OR (part_number IN (3,4,6,7) AND group_id IS NOT NULL) OR (part_number IN (1,2,5) AND group_id IS NULL))",
       "CHECK (blank_key IS NULL OR (category='TOEIC' AND part_number=6))",
       "CHECK (category<>'TOEIC' OR part_number<>6 OR blank_key IS NOT NULL)"],
      projection_fks()+[fk("question_type_code", "question_types", "code"), fk("group_id", "question_groups"),
                       fk("group_id, category", "question_groups", "id, category"),
                       fk("group_id, category, part_number", "question_groups", "id, category, part_number")],
      ["CREATE INDEX ix_questions_filters ON questions(category, part_number, question_type_code, difficulty);",
       "CREATE UNIQUE INDEX uq_group_current_position ON questions(group_id, group_position) WHERE in_current_group=TRUE;",
       "CREATE UNIQUE INDEX uq_group_current_blank ON questions(group_id, blank_key) WHERE in_current_group=TRUE;"])

table("question_options", "Practice", "Lựa chọn của projection; đúng một đáp án khi commit.", """
id UUID PRIMARY KEY -- ID lựa chọn ổn định trong revision/snapshot
question_id UUID NOT NULL -- Câu sở hữu
position SMALLINT NOT NULL -- Vị trí/nhãn hiển thị
body TEXT -- Có thể trống với phương án chỉ nằm trong audio TOEIC
is_correct BOOLEAN NOT NULL DEFAULT FALSE -- Chỉ backend/biên soạn, không DTO trước nộp
""", ["UNIQUE (question_id, id)", "UNIQUE (question_id, position)", "CHECK (position BETWEEN 1 AND 6)"],
      [fk("question_id", "questions")],
      ["CREATE UNIQUE INDEX uq_options_one_correct ON question_options(question_id) WHERE is_correct=TRUE;"])

for material_name, owner, parent in [("group_materials", "group_id", "question_groups"),
                                     ("question_materials", "question_id", "questions")]:
    table(material_name, "Practice", "Học liệu có thứ tự; transcript/lời giải tách vai trò với stimulus.", f"""
id UUID PRIMARY KEY -- Material ID/anchor ổn định
{owner} UUID NOT NULL -- Nội dung sở hữu
position SMALLINT NOT NULL -- Thứ tự trình bày
document_key VARCHAR(80) -- Ghép các block cùng tài liệu; Part 7 có 1–3 tài liệu
material_type VARCHAR(8) NOT NULL -- TEXT/IMAGE/AUDIO
usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS' -- Quyền hiển thị theo thời điểm
media_id UUID -- File nếu ảnh/audio
body TEXT -- Text/Markdown, chứa blank marker có schema nếu cần
""", [f"UNIQUE ({owner}, position)", enum("material_type", "TEXT IMAGE AUDIO"),
       enum("usage_role", "STIMULUS TRANSCRIPT EXPLANATION"), "CHECK (position > 0)",
       "CHECK ((material_type='TEXT' AND body IS NOT NULL AND media_id IS NULL) OR (material_type IN ('IMAGE','AUDIO') AND media_id IS NOT NULL AND body IS NULL))",
       "CHECK (char_length(body) <= 50000)"], [fk(owner, parent), fk("media_id", "media_assets")])

table("practice_sets", "Practice", "Bộ luyện hiện hành; FIXED có set_items, DYNAMIC có selection_config.", projection("PRACTICE_SET") + f"""
category VARCHAR(16) NOT NULL -- Một category mỗi bộ/lượt
title VARCHAR(200) NOT NULL -- Tên bộ
selection_mode VARCHAR(8) NOT NULL -- FIXED/DYNAMIC
selection_config JSONB NOT NULL DEFAULT '{{}}' -- Filter có schema, không bỏ qua kiểm tra IDs
deck_id UUID -- Nguồn Vocabulary nếu có
topic_id UUID -- Chủ điểm đơn nếu có
part_number SMALLINT -- Part TOEIC
time_limit_seconds INTEGER -- NULL là không đếm ngược
{TIMES}
""", [enum("category", "VOCABULARY GRAMMAR TOEIC"), enum("selection_mode", "FIXED DYNAMIC"), obj("selection_config"),
       "CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0)",
       "CHECK ((category='TOEIC' AND part_number BETWEEN 1 AND 7 AND part_number IS NOT NULL) OR (category<>'TOEIC' AND part_number IS NULL))",
       "CHECK (deck_id IS NULL OR category='VOCABULARY')", "CHECK (topic_id IS NULL OR category='GRAMMAR')"],
      projection_fks()+[fk("deck_id", "decks"), fk("topic_id", "grammar_topics")],
      ["CREATE INDEX ix_sets_filters ON practice_sets(category, part_number);"])

table("set_items", "Practice", "Một vị trí chọn câu độc lập XOR cả nhóm.", """
id UUID PRIMARY KEY -- Set member ID
set_id UUID NOT NULL -- Bộ
position INTEGER NOT NULL -- Thứ tự đơn vị
question_id UUID -- Chỉ câu độc lập
group_id UUID -- Hoặc cả nhóm
""", ["UNIQUE (set_id, position)", "CHECK (position > 0)", "CHECK (num_nonnulls(question_id, group_id)=1)"],
      [fk("set_id", "practice_sets"), fk("question_id", "questions"), fk("group_id", "question_groups")],
      ["CREATE UNIQUE INDEX uq_set_question ON set_items(set_id, question_id) WHERE question_id IS NOT NULL;",
       "CREATE UNIQUE INDEX uq_set_group ON set_items(set_id, group_id) WHERE group_id IS NOT NULL;"])

for link, target, target_id in [("question_words", "word_senses", "word_sense_id"),
                                 ("question_topics", "grammar_topics", "topic_id")]:
    table(link, "Practice", "Liên kết kiến thức để chọn bài và tổng hợp theo sense/topic.", f"""
question_id UUID NOT NULL -- Câu hỏi
{target_id} UUID NOT NULL -- Kiến thức liên quan
""", [f"PRIMARY KEY (question_id, {target_id})"], [fk("question_id", "questions"), fk(target_id, target)],
      [f"CREATE INDEX ix_{link}_target ON {link}({target_id});"])

table("attempts", "Results", "Nguồn và kết quả lượt làm; tạo idempotent, snapshot được seal trước khi giao.", f"""
id UUID PRIMARY KEY -- Attempt ID
user_id UUID NOT NULL -- Chủ bài, chỉ chủ được làm/xem theo quyền
category VARCHAR(16) NOT NULL -- Một category
source_type VARCHAR(24) NOT NULL -- Nguồn tạo bài
source_set_id UUID -- Bộ luyện nếu có
source_deck_id UUID -- Deck nếu có
source_topic_id UUID -- Topic đơn nếu có
source_revision_id UUID -- Bản nguồn chính nếu có
source_config JSONB NOT NULL -- Filter/kiểu/số lượng/revision có schema
request_id UUID NOT NULL -- Idempotency-Key khi bắt đầu
request_hash VARCHAR(64) NOT NULL -- Fingerprint payload gốc
status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' -- Trạng thái bài
total_count SMALLINT NOT NULL -- Số câu con, không phải số nhóm
correct_count SMALLINT -- Số đúng sau nộp
answered_count SMALLINT -- Số câu có chọn đáp án
score SMALLINT -- Một điểm/câu con đúng
snapshot_sealed_at TIMESTAMPTZ -- Không được sửa đề sau seal
started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc bắt đầu
last_active_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Phục vụ bỏ dở
deadline_at TIMESTAMPTZ -- NULL là không giới hạn giờ
submitted_at TIMESTAMPTZ -- Mốc backend ghi kết quả
effective_finished_at TIMESTAMPTZ -- Deadline nếu tự nộp muộn, không cộng độ trễ
submit_reason VARCHAR(16) -- MANUAL/TIME_LIMIT
abandoned_at TIMESTAMPTZ -- Chủ động bỏ hoặc hết hạn hoạt động
duration_seconds INTEGER -- Thời gian bài, không phải phút học hoạt động
timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh' -- Múi giờ ghi nhận ngày
local_day DATE -- Ngày kết quả theo múi giờ lúc nộp
version BIGINT NOT NULL DEFAULT 0 -- Optimistic lock của trạng thái
{TIMES}
""", ["UNIQUE (user_id, id)", "UNIQUE (user_id, request_id)", "UNIQUE (id, category)",
       enum("category", "VOCABULARY GRAMMAR TOEIC"),
       enum("source_type", "PRACTICE_SET DECK GRAMMAR_TOPIC MIXED_GRAMMAR MISTAKES"),
       enum("status", "IN_PROGRESS SUBMITTED ABANDONED"), enum("submit_reason", "MANUAL TIME_LIMIT"),
       obj("source_config"), f"CHECK (request_hash ~ '{HASH}')",
       "CHECK (total_count BETWEEN 1 AND 100 AND version >= 0)",
       "CHECK (correct_count IS NULL OR correct_count BETWEEN 0 AND total_count)",
       "CHECK (answered_count IS NULL OR answered_count BETWEEN 0 AND total_count)",
       "CHECK (score IS NULL OR score = correct_count)",
       "CHECK (duration_seconds IS NULL OR duration_seconds >= 0)",
       "CHECK (deadline_at IS NULL OR deadline_at > started_at)",
       "CHECK ((source_type='PRACTICE_SET' AND source_set_id IS NOT NULL AND source_deck_id IS NULL AND source_topic_id IS NULL) OR (source_type='DECK' AND source_deck_id IS NOT NULL AND category='VOCABULARY' AND source_set_id IS NULL AND source_topic_id IS NULL) OR (source_type='GRAMMAR_TOPIC' AND source_topic_id IS NOT NULL AND category='GRAMMAR' AND source_set_id IS NULL AND source_deck_id IS NULL) OR (source_type='MIXED_GRAMMAR' AND category='GRAMMAR' AND num_nonnulls(source_set_id,source_deck_id,source_topic_id)=0) OR (source_type='MISTAKES' AND num_nonnulls(source_set_id,source_deck_id,source_topic_id)=0))",
       "CHECK ((status='SUBMITTED' AND submitted_at IS NOT NULL AND effective_finished_at IS NOT NULL AND submit_reason IS NOT NULL AND correct_count IS NOT NULL AND answered_count IS NOT NULL AND score IS NOT NULL AND duration_seconds IS NOT NULL AND local_day IS NOT NULL AND abandoned_at IS NULL) OR (status='IN_PROGRESS' AND num_nonnulls(submitted_at,effective_finished_at,submit_reason,correct_count,answered_count,score,duration_seconds,abandoned_at,local_day)=0) OR (status='ABANDONED' AND abandoned_at IS NOT NULL AND num_nonnulls(submitted_at,effective_finished_at,submit_reason,correct_count,answered_count,score,duration_seconds,local_day)=0))",
       "CHECK (effective_finished_at IS NULL OR (effective_finished_at >= started_at AND effective_finished_at <= submitted_at))",
       "CHECK (submit_reason IS DISTINCT FROM 'TIME_LIMIT' OR (deadline_at IS NOT NULL AND effective_finished_at=deadline_at))"],
      [fk("user_id", "users"), fk("source_set_id", "practice_sets"), fk("source_deck_id", "decks"),
       fk("source_topic_id", "grammar_topics"), fk("source_revision_id", "content_revisions")],
      ["CREATE INDEX ix_attempts_user_history ON attempts(user_id, started_at DESC);",
       "CREATE INDEX ix_attempts_deadline ON attempts(deadline_at) WHERE status='IN_PROGRESS';",
       "CREATE INDEX ix_attempts_user_open ON attempts(user_id, last_active_at) WHERE status='IN_PROGRESS';"])

table("attempt_source_topics", "Results", "Nguồn nhiều topic có FK, không chỉ giấu ID trong JSON.", """
attempt_id UUID NOT NULL -- Lượt làm
topic_id UUID NOT NULL -- Chủ điểm
source_revision_id UUID NOT NULL -- Bản topic ghim
""", ["PRIMARY KEY (attempt_id, topic_id)"],
      [fk("attempt_id", "attempts"), fk("topic_id", "grammar_topics"),
       fk("topic_id, source_revision_id", "content_revisions", "content_item_id, id")])

table("attempt_group_snapshots", "Results", "Ngữ cảnh nhóm chung lưu một lần trong mỗi lượt làm.", f"""
id UUID PRIMARY KEY -- Group snapshot ID
attempt_id UUID NOT NULL -- Lượt sở hữu
group_id UUID NOT NULL -- Nhóm nguồn
source_revision_id UUID NOT NULL -- Bản nhóm đã ghim
ordinal SMALLINT NOT NULL -- Thứ tự đơn vị trong bài
schema_version SMALLINT NOT NULL DEFAULT 1 -- Schema render nhóm
category VARCHAR(16) NOT NULL -- Category của bộ câu trong nhóm
part_number SMALLINT -- Part đã ghim
expected_question_count SMALLINT NOT NULL -- Đủ nguyên nhóm khi seal
public_snapshot JSONB NOT NULL -- Stimulus công khai, không transcript/key
private_snapshot JSONB NOT NULL DEFAULT '{{}}' -- Transcript/lời giải riêng
""", ["UNIQUE (attempt_id, id)", "UNIQUE (attempt_id, group_id)", "UNIQUE (attempt_id, ordinal)",
       "CHECK (ordinal > 0 AND schema_version > 0 AND expected_question_count BETWEEN 1 AND 100)",
       "CHECK ((category='TOEIC' AND part_number IN (3,4,6,7) AND part_number IS NOT NULL) OR (category IN ('VOCABULARY','GRAMMAR') AND part_number IS NULL))",
       "CHECK (category<>'TOEIC' OR (part_number NOT IN (3,4) OR expected_question_count=3))",
       "CHECK (category<>'TOEIC' OR part_number<>6 OR expected_question_count=4)",
       obj("public_snapshot"), obj("private_snapshot")],
      [fk("attempt_id", "attempts"), fk("group_id", "question_groups"),
       fk("attempt_id, category", "attempts", "id, category"),
       fk("group_id, category", "question_groups", "id, category"),
       fk("group_id, source_revision_id", "content_revisions", "content_item_id, id")])

table("attempt_question_snapshots", "Results", "Đề giao bất biến, đáp án chuẩn/lời giải tách khỏi dữ liệu trước nộp.", """
id UUID PRIMARY KEY -- Snapshot ID dùng để lưu đáp án
attempt_id UUID NOT NULL -- Lượt sở hữu
question_id UUID NOT NULL -- Câu nguồn ổn định
source_revision_id UUID NOT NULL -- Bản câu đã ghim
group_snapshot_id UUID -- Ngữ cảnh nhóm trong đúng lượt
category VARCHAR(16) NOT NULL -- Discriminator FK: không trộn category trong bài
ordinal SMALLINT NOT NULL -- Thứ tự toàn bài
question_type_code VARCHAR(32) NOT NULL -- Renderer/grader
schema_version SMALLINT NOT NULL DEFAULT 1 -- Phiên bản snapshot
grading_policy_version VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE_V1' -- Chính sách chấm đã đóng băng
question_snapshot JSONB NOT NULL -- Payload trước nộp được kiểm soát
answer_key_snapshot JSONB NOT NULL -- Key riêng tư
explanation_snapshot JSONB -- Lời giải riêng tư
""", ["UNIQUE (attempt_id, id)", "UNIQUE (id, question_id)",
       "UNIQUE (attempt_id, ordinal)", "UNIQUE (attempt_id, question_id)",
       "CHECK (ordinal BETWEEN 1 AND 100 AND schema_version > 0)",
       obj("question_snapshot"), obj("answer_key_snapshot"), obj("explanation_snapshot", True)],
      [fk("attempt_id", "attempts"), fk("question_id", "questions"),
       fk("attempt_id, category", "attempts", "id, category"),
       fk("question_id, category", "questions", "id, category"),
       fk("question_id, source_revision_id", "content_revisions", "content_item_id, id"),
       fk("attempt_id, group_snapshot_id", "attempt_group_snapshots", "attempt_id, id"),
       fk("question_type_code", "question_types", "code")],
      ["CREATE INDEX ix_snapshot_question ON attempt_question_snapshots(question_id, attempt_id);"])

table("attempt_snapshot_options", "Results", "Option thuộc đúng snapshot, không FK tới lựa chọn hiện hành có thể sửa.", """
snapshot_id UUID NOT NULL -- Câu snapshot
option_id UUID NOT NULL -- ID option trong bản đã giao
position SMALLINT NOT NULL -- Nhãn/vị trí không đổi
public_body TEXT -- NULL khi phương án chỉ nằm trong audio TOEIC
""", ["PRIMARY KEY (snapshot_id, option_id)", "UNIQUE (snapshot_id, position)", "CHECK (position BETWEEN 1 AND 6)"],
      [fk("snapshot_id", "attempt_question_snapshots")])

table("attempt_answers", "Results", "Một đáp án hiện tại mỗi câu; version cả khi bỏ chọn.", f"""
id UUID PRIMARY KEY -- Answer ID
attempt_id UUID NOT NULL -- Lượt sở hữu
snapshot_id UUID NOT NULL UNIQUE -- Câu snapshot
selected_option_id UUID -- NULL nghĩa là bỏ trống
response_payload JSONB -- Dành cho schema type mở rộng, SINGLE_CHOICE dùng selected_option_id
version BIGINT NOT NULL DEFAULT 0 -- Optimistic version khi autosave
answered_at TIMESTAMPTZ -- NULL nếu đang bỏ trống
is_correct BOOLEAN -- Chỉ server ghi khi chấm
score SMALLINT -- 0/1 theo chính sách ban đầu
{TIMES}
""", ["CHECK (version >= 0)", "CHECK ((selected_option_id IS NULL) = (answered_at IS NULL))",
       "CHECK (score IS NULL OR score IN (0,1))", "CHECK ((is_correct IS NULL) = (score IS NULL))",
       "CHECK (is_correct IS NULL OR score = CASE WHEN is_correct THEN 1 ELSE 0 END)",
       "CHECK (num_nonnulls(selected_option_id,response_payload) <= 1)", obj("response_payload", True)],
      [fk("attempt_id, snapshot_id", "attempt_question_snapshots", "attempt_id, id"),
       fk("snapshot_id, selected_option_id", "attempt_snapshot_options", "snapshot_id, option_id")],
      ["CREATE INDEX ix_answers_attempt ON attempt_answers(attempt_id);"])

table("attempt_media_refs", "Results", "File trong đề/lời giải snapshot; bảo toàn độc lập projection hiện hành.", """
attempt_id UUID NOT NULL -- Lượt làm
media_id UUID NOT NULL -- File phải giữ
usage_role VARCHAR(20) NOT NULL DEFAULT 'STIMULUS' -- Quyền đọc trước/sau nộp
""", ["PRIMARY KEY (attempt_id, media_id, usage_role)", enum("usage_role", "STIMULUS EXPLANATION TRANSCRIPT")],
      [fk("attempt_id", "attempts"), fk("media_id", "media_assets")],
      ["CREATE INDEX ix_attempt_media_asset ON attempt_media_refs(media_id);"])

table("mistake_records", "Results", "Lỗi theo user/câu; lịch sử sự kiện gốc ở các kết quả bất biến.", """
id UUID PRIMARY KEY -- Mistake ID
user_id UUID NOT NULL -- Chủ lỗi
question_id UUID NOT NULL -- Câu ổn định
source_attempt_id UUID NOT NULL -- Bài sai gần nhất
source_snapshot_id UUID NOT NULL -- Snapshot sai tương ứng
state VARCHAR(16) NOT NULL DEFAULT 'OPEN' -- OPEN/IMPROVING/RESOLVED
wrong_count INTEGER NOT NULL -- Tổng lần sai/bỏ trống ở bài đã nộp
consecutive_correct INTEGER NOT NULL DEFAULT 0 -- Chuỗi đúng sau lỗi gần nhất
last_wrong_at TIMESTAMPTZ NOT NULL -- Theo thời gian kết thúc hiệu lực
last_evaluated_at TIMESTAMPTZ NOT NULL -- Kết quả mới nhất theo thời gian hiệu lực
resolved_at TIMESTAMPTZ -- Khi đạt ít nhất hai lần đúng liên tiếp
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc projection cập nhật
""", ["UNIQUE (user_id, question_id)", "UNIQUE (user_id, id)", enum("state", "OPEN IMPROVING RESOLVED"),
       "CHECK (wrong_count > 0 AND consecutive_correct >= 0)",
       "CHECK ((state='OPEN' AND consecutive_correct=0 AND resolved_at IS NULL) OR (state='IMPROVING' AND consecutive_correct=1 AND resolved_at IS NULL) OR (state='RESOLVED' AND consecutive_correct>=2 AND resolved_at IS NOT NULL))"],
      [fk("user_id", "users"), fk("question_id", "questions"),
       fk("user_id, source_attempt_id", "attempts", "user_id, id"),
       fk("source_attempt_id, source_snapshot_id", "attempt_question_snapshots", "attempt_id, id"),
       fk("source_snapshot_id, question_id", "attempt_question_snapshots", "id, question_id")],
      ["CREATE INDEX ix_mistakes_user_state ON mistake_records(user_id, state, last_wrong_at DESC);"])

table("attempt_source_mistakes", "Results", "Lỗi chọn để luyện lại phải thuộc đúng chủ lượt làm.", """
attempt_id UUID NOT NULL -- Lượt luyện lại
user_id UUID NOT NULL -- Discriminator bảo đảm ownership bằng FK
mistake_id UUID NOT NULL -- Lỗi đã chọn
""", ["PRIMARY KEY (attempt_id, mistake_id)"],
      [fk("user_id, attempt_id", "attempts", "user_id, id"), fk("user_id, mistake_id", "mistake_records", "user_id, id")])

table("learning_goals", "Learning", "Một mục tiêu hiện tại, không diễn giải thành trình độ được dự đoán.", f"""
user_id UUID PRIMARY KEY -- Người học có LEARNER
target_score SMALLINT NOT NULL -- Điểm đích TOEIC
daily_minutes SMALLINT NOT NULL -- Phút/ngày
target_date DATE -- Ngày đích tùy chọn, API kiểm tra theo timezone
{TIMES}
""", ["CHECK (target_score BETWEEN 10 AND 990 AND target_score % 5=0)",
       "CHECK (daily_minutes BETWEEN 5 AND 240)"], [fk("user_id", "users")])

table("study_sessions", "Learning", "Đoạn thời gian hoạt động ước tính, không cộng trùng nhiều tab/thiết bị.", """
id UUID PRIMARY KEY -- Đoạn hoạt động
user_id UUID NOT NULL -- Chủ đoạn
source_type VARCHAR(16) NOT NULL -- FLASHCARD/LESSON/PRACTICE
word_sense_id UUID -- Ngữ cảnh thẻ nếu có
lesson_id UUID -- Ngữ cảnh bài học nếu có
attempt_id UUID -- Ngữ cảnh luyện nếu có
timezone VARCHAR(60) NOT NULL -- Timezone ở thời điểm ghi nhận
local_day DATE NOT NULL -- Chia đoạn khi qua ngày/đổi timezone
credit_token UUID NOT NULL -- Chủ nhịp ghi nhận hiện tại, không thay đăng nhập
started_at TIMESTAMPTZ NOT NULL -- Mốc bắt đầu
last_credited_at TIMESTAMPTZ NOT NULL -- Server cập nhật, không tin client clock
closed_at TIMESTAMPTZ -- Đóng khi ngừng/đổi ngữ cảnh
active_seconds NUMERIC(12,3) NOT NULL DEFAULT 0 -- Mỗi heartbeat cộng tối đa 30 giây
version BIGINT NOT NULL DEFAULT 0 -- Chống cộng lại
""", [enum("source_type", "FLASHCARD LESSON PRACTICE"),
       "CHECK ((source_type='FLASHCARD' AND word_sense_id IS NOT NULL AND num_nonnulls(lesson_id,attempt_id)=0) OR (source_type='LESSON' AND lesson_id IS NOT NULL AND num_nonnulls(word_sense_id,attempt_id)=0) OR (source_type='PRACTICE' AND attempt_id IS NOT NULL AND num_nonnulls(word_sense_id,lesson_id)=0))",
       "CHECK (last_credited_at >= started_at AND (closed_at IS NULL OR closed_at >= last_credited_at))",
       "CHECK (active_seconds >= 0 AND active_seconds <= EXTRACT(EPOCH FROM (last_credited_at-started_at)) AND version >= 0)"],
      [fk("user_id", "users"), fk("word_sense_id", "word_senses"), fk("lesson_id", "grammar_lessons"),
       fk("user_id, attempt_id", "attempts", "user_id, id")],
      ["CREATE UNIQUE INDEX uq_study_one_open ON study_sessions(user_id) WHERE closed_at IS NULL;",
       "CREATE INDEX ix_study_day ON study_sessions(user_id, local_day);"])

table("notification_preferences", "Notifications", "Nhắc học opt-in theo giờ; email bảo mật không phụ thuộc email nhắc học.", f"""
user_id UUID PRIMARY KEY -- Người nhận
in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE -- Kênh in-app
email_enabled BOOLEAN NOT NULL DEFAULT FALSE -- Email nhắc học, không phải OTP
daily_reminder_time TIME -- NULL là chưa đặt lịch
timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh' -- IANA timezone
quiet_hours_start TIME -- NULL cùng quiet_hours_end nếu tắt
quiet_hours_end TIME -- Cho phép qua nửa đêm
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc sửa
""", ["CHECK ((quiet_hours_start IS NULL)=(quiet_hours_end IS NULL))",
       "CHECK (quiet_hours_start IS NULL OR quiet_hours_start<>quiet_hours_end)"], [fk("user_id", "users")])

table("reminder_rules", "Notifications", "Danh mục quy tắc nhắc, không có chiến dịch quảng bá/browser push.", """
id UUID PRIMARY KEY -- Rule ID
code VARCHAR(40) NOT NULL UNIQUE -- Mã do backend hỗ trợ
enabled BOOLEAN NOT NULL DEFAULT TRUE -- Cờ vận hành
minimum_gap_minutes INTEGER NOT NULL DEFAULT 1440 -- Khoảng tối thiểu giữa các nhắc cùng loại
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc tạo
""", ["CHECK (minimum_gap_minutes >= 0)"])

table("notifications", "Notifications", "Thông báo/dedupe; bản ghi INTERNAL dùng cho mail bảo mật, không vào feed learner.", f"""
id UUID PRIMARY KEY -- Notification ID
user_id UUID NOT NULL -- Người nhận
reminder_rule_id UUID -- Quy tắc nhắc nếu có
type VARCHAR(24) NOT NULL -- Loại sự kiện
visibility VARCHAR(12) NOT NULL DEFAULT 'USER' -- USER hoặc INTERNAL
title VARCHAR(180) NOT NULL -- Không chứa secret
body TEXT NOT NULL -- Nội dung công khai, không chứa OTP/token
target_url VARCHAR(500) -- Chỉ đường dẫn nội bộ
dedupe_key VARCHAR(200) NOT NULL -- User/sự kiện/ngày/ngữ cảnh
read_at TIMESTAMPTZ -- Mốc đọc
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc tạo
""", ["UNIQUE (user_id, dedupe_key)", enum("type", "REVIEW_DUE DAILY_GOAL CONTENT_NEW SYSTEM SECURITY"),
       enum("visibility", "USER INTERNAL"),
       "CHECK (target_url IS NULL OR (target_url LIKE '/%' AND target_url NOT LIKE '//%'))"],
      [fk("user_id", "users"), fk("reminder_rule_id", "reminder_rules")],
      ["CREATE INDEX ix_notifications_feed ON notifications(user_id, created_at DESC) WHERE visibility='USER';"])

table("notification_deliveries", "Notifications", "Queue gửi có claim/lease; payload OTP chỉ lưu mã hóa, không rõ trong event/log.", f"""
id UUID PRIMARY KEY -- Delivery ID
notification_id UUID NOT NULL -- Thông báo sở hữu
channel VARCHAR(8) NOT NULL -- IN_APP/EMAIL
recipient_address VARCHAR(254) -- Địa chỉ email đã chốt cho lần gửi
status VARCHAR(16) NOT NULL DEFAULT 'PENDING' -- Trạng thái gửi
attempt_count SMALLINT NOT NULL DEFAULT 0 -- Tối đa 1 lần đầu + 3 retry
next_retry_at TIMESTAMPTZ -- Lần được nhận xử lý tiếp
claim_token UUID -- Claim hiện tại
lease_until TIMESTAMPTZ -- Lease 60 giây có gia hạn
provider_message_id VARCHAR(180) -- Receipt nếu nhà cung cấp có
last_failure_code VARCHAR(64) -- Lỗi đã làm sạch
sent_at TIMESTAMPTZ -- Mốc gửi thành công
finished_at TIMESTAMPTZ -- Mốc kết thúc, dùng retention
otp_challenge_id UUID -- Mail OTP chỉ gửi khi challenge còn hợp lệ
encrypted_payload BYTEA -- Ciphertext cho dữ liệu gửi nhạy cảm, không chứa khóa giải mã
encryption_key_id VARCHAR(64) -- ID khóa ở môi trường/kho secret
sensitive_expires_at TIMESTAMPTZ -- Không gửi OTP đã hết hạn
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc tạo
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc cập nhật
""", ["UNIQUE (notification_id, channel)", enum("channel", "IN_APP EMAIL"), enum("status", "PENDING PROCESSING SENT FAILED"),
       "CHECK (attempt_count BETWEEN 0 AND 4)",
       "CHECK (channel<>'EMAIL' OR recipient_address IS NOT NULL)",
       "CHECK ((status='PROCESSING')=(claim_token IS NOT NULL AND lease_until IS NOT NULL))",
       "CHECK ((claim_token IS NULL)=(lease_until IS NULL))",
       "CHECK ((status='SENT')=(sent_at IS NOT NULL))",
       "CHECK ((status IN ('SENT','FAILED'))=(finished_at IS NOT NULL))",
       "CHECK (status NOT IN ('SENT','FAILED') OR next_retry_at IS NULL)",
       "CHECK ((encrypted_payload IS NULL)=(encryption_key_id IS NULL))",
       "CHECK (channel='EMAIL' OR (encrypted_payload IS NULL AND otp_challenge_id IS NULL))"],
      [fk("notification_id", "notifications"), fk("otp_challenge_id", "otp_challenges")],
      ["CREATE INDEX ix_delivery_pending ON notification_deliveries(next_retry_at, created_at) WHERE status='PENDING';",
       "CREATE INDEX ix_delivery_lease ON notification_deliveries(lease_until) WHERE status='PROCESSING';"])

table("import_jobs", "Content", "Ngữ cảnh upload/import riêng, DryRun trước khi tạo Draft.", f"""
id UUID PRIMARY KEY -- Job ID
created_by UUID NOT NULL -- CONTENT_ADMIN thực hiện
request_id UUID NOT NULL -- Retry cùng yêu cầu tạo job
request_hash VARCHAR(64) NOT NULL -- Payload fingerprint
import_type VARCHAR(24) NOT NULL -- Loại importer được hỗ trợ
import_namespace VARCHAR(120) NOT NULL -- Namespace khóa ngoài
original_filename VARCHAR(255) NOT NULL -- Tên hiển thị
input_bucket VARCHAR(100) NOT NULL -- Kho riêng tư
input_object_key VARCHAR(500) NOT NULL -- Key input riêng cho job
declared_size_bytes BIGINT NOT NULL -- Không vượt 5 MiB
input_sha256 VARCHAR(64) -- Hash file thực tế sau xác minh
upload_expires_at TIMESTAMPTZ NOT NULL -- Hạn nhận file/complete
status VARCHAR(24) NOT NULL DEFAULT 'UPLOADING' -- Không coi upload CSV là IMAGE/AUDIO
total_rows INTEGER NOT NULL DEFAULT 0 -- Đơn vị dòng/item, không phải số entity con
success_rows INTEGER NOT NULL DEFAULT 0 -- Đã tạo Draft và ghi ledger
failed_rows INTEGER NOT NULL DEFAULT 0 -- Dòng lỗi
skipped_rows INTEGER NOT NULL DEFAULT 0 -- Trùng/được bỏ qua
confirmed_at TIMESTAMPTZ -- Người dùng xác nhận sau DryRun
claim_token UUID -- Chủ xử lý
lease_until TIMESTAMPTZ -- Hạn claim, phục hồi sau crash
finished_at TIMESTAMPTZ -- Kết thúc job
failure_code VARCHAR(64) -- Mã lỗi job
{TIMES}
""", ["UNIQUE (created_by, request_id)", "UNIQUE (input_bucket, input_object_key)",
       enum("import_type", "VOCABULARY_CSV QUESTIONS_JSON GRAMMAR_JSON"),
       enum("status", "UPLOADING DRY_RUN READY PROCESSING COMPLETED PARTIAL_SUCCESS FAILED EXPIRED"),
       f"CHECK (request_hash ~ '{HASH}')", f"CHECK (input_sha256 IS NULL OR input_sha256 ~ '{HASH}')",
       "CHECK (declared_size_bytes BETWEEN 1 AND 5242880)",
       "CHECK (total_rows BETWEEN 0 AND 5000 AND success_rows>=0 AND failed_rows>=0 AND skipped_rows>=0 AND success_rows+failed_rows+skipped_rows<=total_rows)",
       "CHECK ((claim_token IS NULL)=(lease_until IS NULL))",
       "CHECK ((status IN ('COMPLETED','PARTIAL_SUCCESS','FAILED','EXPIRED'))=(finished_at IS NOT NULL))",
       "CHECK (status NOT IN ('PROCESSING','COMPLETED','PARTIAL_SUCCESS') OR confirmed_at IS NOT NULL)",
       "CHECK (upload_expires_at > created_at)"], [fk("created_by", "users")],
      ["CREATE INDEX ix_import_jobs_user ON import_jobs(created_by, created_at DESC);"])

table("import_job_rows", "Content", "Ledger từng dòng thay bảng import_job_errors đơn thuần; retry không tạo entity trùng.", f"""
job_id UUID NOT NULL -- Job
row_number INTEGER NOT NULL -- Dòng/item trong file
input_hash VARCHAR(64) NOT NULL -- Fingerprint dòng
external_key VARCHAR(200) -- Khóa ngoài nếu file cung cấp
payload JSONB NOT NULL -- Dữ liệu đã parse có schema
status VARCHAR(12) NOT NULL -- VALID/INVALID trước xác nhận, kết quả sau xử lý
result_content_item_id UUID -- Entity gốc được tạo thành công
result_snapshot JSONB -- IDs kết quả con của dòng nhóm nếu có
errors JSONB NOT NULL DEFAULT '[]' -- Lỗi theo trường, không stack trace/secret
processed_at TIMESTAMPTZ -- Mốc xử lý xong
""", ["PRIMARY KEY (job_id, row_number)", "CHECK (row_number BETWEEN 1 AND 5000)",
       enum("status", "VALID INVALID PENDING SUCCESS FAILED SKIPPED"),
       f"CHECK (input_hash ~ '{HASH}')", obj("payload"), obj("result_snapshot", True),
       "CHECK (jsonb_typeof(errors)='array')", "CHECK (status<>'SUCCESS' OR (result_content_item_id IS NOT NULL AND processed_at IS NOT NULL))"],
      [fk("job_id", "import_jobs"), fk("result_content_item_id", "content_items")])

table("storage_pools", "Media", "Ngân sách CONTENT/TEMP toàn hệ thống và AVATAR từng user; hạ trần không xóa dữ liệu.", f"""
id UUID PRIMARY KEY -- Pool ID
scope VARCHAR(12) NOT NULL -- CONTENT/TEMP/AVATAR
owner_user_id UUID -- Chỉ AVATAR có owner
quota_bytes BIGINT NOT NULL -- Trần cấu hình
reserved_bytes BIGINT NOT NULL DEFAULT 0 -- Chỗ đã giữ, do trigger ledger duy trì
used_bytes BIGINT NOT NULL DEFAULT 0 -- Byte đã commit, gồm file chờ dọn
version BIGINT NOT NULL DEFAULT 0 -- Chống cập nhật đè nhau
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc cập nhật
""", ["UNIQUE NULLS NOT DISTINCT (scope, owner_user_id)", enum("scope", "CONTENT TEMP AVATAR"),
       "CHECK ((scope='AVATAR')=(owner_user_id IS NOT NULL))",
       "CHECK (quota_bytes > 0 AND reserved_bytes >= 0 AND used_bytes >= 0 AND version >= 0)"],
      [fk("owner_user_id", "users")])

table("storage_allocations", "Media", "Ledger giữ chỗ/file, chỉ giải phóng sau đối chiếu, không reset quota bằng archive.", f"""
id UUID PRIMARY KEY -- Allocation ID
pool_id UUID NOT NULL -- Ngân sách chịu byte này
allocation_key VARCHAR(200) NOT NULL UNIQUE -- Khóa idempotent: mục đích/request/claim
media_id UUID -- Asset file cuối nếu có
upload_request_id UUID -- Nguồn upload media nếu có
import_job_id UUID -- Hoặc nguồn input import
bytes BIGINT NOT NULL -- Byte được giữ/commit
state VARCHAR(12) NOT NULL DEFAULT 'RESERVED' -- RESERVED/COMMITTED/RELEASED
bucket VARCHAR(100) -- Đối tượng đã được gắn nếu có
object_key VARCHAR(500) -- Key không phải URL
committed_at TIMESTAMPTZ -- File được xác nhận tồn tại
released_at TIMESTAMPTZ -- Đã xác nhận không còn tiêu thụ byte
version BIGINT NOT NULL DEFAULT 0 -- CAS của ledger
{TIMES}
""", [enum("state", "RESERVED COMMITTED RELEASED"), "CHECK (bytes > 0 AND version >= 0)",
       "CHECK ((bucket IS NULL)=(object_key IS NULL))",
       "CHECK (state<>'COMMITTED' OR (committed_at IS NOT NULL AND bucket IS NOT NULL))",
       "CHECK ((state='RELEASED')=(released_at IS NOT NULL))",
       "CHECK (state='RELEASED' OR num_nonnulls(media_id,upload_request_id,import_job_id)>0)"],
      [fk("pool_id", "storage_pools"), fk("media_id", "media_assets", delete="SET NULL"),
       fk("upload_request_id", "media_upload_requests", delete="SET NULL"), fk("import_job_id", "import_jobs", delete="SET NULL")],
      ["CREATE INDEX ix_allocations_pool ON storage_allocations(pool_id, state);",
       "CREATE INDEX ix_allocations_media ON storage_allocations(media_id);"])

SETTINGS = {
    "auth.bcrypt_cost": (12, "INTEGER", "rounds"),
    "auth.max_sessions": (5, "INTEGER", "sessions"),
    "auth.session_seconds": (604800, "INTEGER", "seconds"),
    "auth.access_seconds": (900, "INTEGER", "seconds"),
    "auth.otp_seconds": (300, "INTEGER", "seconds"),
    "auth.otp_resend_seconds": (60, "INTEGER", "seconds"),
    "auth.otp_send_hourly_email": (5, "INTEGER", "requests"),
    "auth.otp_send_hourly_ip": (300, "INTEGER", "requests"),
    "auth.otp_verify_15min_ip": (300, "INTEGER", "requests"),
    "auth.login_15min_ip": (300, "INTEGER", "requests"),
    "media.upload_seconds": (600, "INTEGER", "seconds"),
    "media.verify_seconds": (300, "INTEGER", "seconds"),
    "media.lease_seconds": (60, "INTEGER", "seconds"),
    "media.verify_parallelism": (2, "INTEGER", "workers"),
    "media.avatar_hourly": (10, "INTEGER", "requests"),
    "media.content_hourly": (100, "INTEGER", "requests"),
    "media.avatar_open": (2, "INTEGER", "requests"),
    "media.content_open": (3, "INTEGER", "requests"),
    "storage.avatar_bytes": (52428800, "INTEGER", "bytes"),
    "storage.content_bytes": (10737418240, "INTEGER", "bytes"),
    "storage.temp_bytes": (1073741824, "INTEGER", "bytes"),
    "practice.max_questions": (100, "INTEGER", "questions"),
    "practice.max_open": (5, "INTEGER", "attempts"),
    "practice.abandon_days": (7, "INTEGER", "days"),
    "stats.weak_min_sample": (20, "INTEGER", "questions"),
    "stats.weak_window_days": (30, "INTEGER", "days"),
    "stats.weak_below_percent": (70, "INTEGER", "percent"),
    "import.max_bytes": (5242880, "INTEGER", "bytes"),
    "import.max_rows": (5000, "INTEGER", "rows"),
    "notification.max_retries": (3, "INTEGER", "retries"),
    "notification.lease_seconds": (60, "INTEGER", "seconds"),
    "notification.learning_email_default": (False, "BOOLEAN", None),
    "notification.default_timezone": ("Asia/Ho_Chi_Minh", "STRING", None),
    "retention.notification_days": (90, "INTEGER", "days"),
    "retention.job_days": (30, "INTEGER", "days"),
    "retention.auth_days": (30, "INTEGER", "days"),
    "retention.audit_days": (365, "INTEGER", "days"),
}

table("system_settings", "Administration", "Allowlist typed; không dùng bảng này để chứa secret.", """
key VARCHAR(80) PRIMARY KEY -- Khóa được backend hỗ trợ
value JSONB NOT NULL -- Giá trị có kiểu
value_type VARCHAR(12) NOT NULL -- INTEGER/BOOLEAN/STRING
unit VARCHAR(20) -- Đơn vị
description TEXT -- Ý nghĩa/tác động
updated_by UUID -- Quản trị thay, NULL cho seed
version BIGINT NOT NULL DEFAULT 0 -- Chống ghi đè
updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc đổi
""", [enum("value_type", "INTEGER BOOLEAN STRING"), enum("key", " ".join(SETTINGS)),
       "CHECK (version >= 0)",
       "CHECK ((value_type='INTEGER' AND jsonb_typeof(value)='number' AND value::text ~ '^[0-9]+$') OR (value_type='BOOLEAN' AND jsonb_typeof(value)='boolean') OR (value_type='STRING' AND jsonb_typeof(value)='string'))",
       "CHECK (key<>'auth.bcrypt_cost' OR (value_type='INTEGER' AND (value::text)::integer BETWEEN 10 AND 16))"],
      [fk("updated_by", "users")])

table("audit_logs", "Administration", "Audit append-only cho tác vụ quản trị; không sao chép secret/URL có chữ ký.", """
id UUID PRIMARY KEY -- Audit ID
actor_type VARCHAR(8) NOT NULL -- USER/SYSTEM
actor_user_id UUID -- Người thực hiện nếu USER
action VARCHAR(80) NOT NULL -- Mã thao tác backend kiểm soát
entity_type VARCHAR(50) NOT NULL -- Loại đối tượng, không phải FK đa hình
entity_id UUID -- Định danh đích
metadata JSONB -- Before/after đã lọc
reason TEXT -- Lý do phù hợp thao tác
request_id VARCHAR(100) -- Trace ID, không phải access token
ip_address INET -- IP đáng tin nếu có
created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP -- Mốc ghi
""", [enum("actor_type", "USER SYSTEM"), "CHECK ((actor_type='USER')=(actor_user_id IS NOT NULL))", obj("metadata", True)],
      [fk("actor_user_id", "users")],
      ["CREATE INDEX ix_audit_actor ON audit_logs(actor_user_id, created_at DESC);",
       "CREATE INDEX ix_audit_entity ON audit_logs(entity_type, entity_id, created_at DESC);"])

table("event_publication", "Infrastructure", "Schema native Modulith 2.1, giữ đúng V1; không tạo registry khác.", """
id UUID PRIMARY KEY -- Publication ID
publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL -- Mốc xuất bản event
listener_id VARCHAR(512) NOT NULL -- Listener
serialized_event TEXT NOT NULL -- Chỉ payload an toàn/ID, không OTP rõ
event_type VARCHAR(512) NOT NULL -- Event class
completion_date TIMESTAMP(6) WITH TIME ZONE -- Mốc hoàn tất
last_resubmission_date TIMESTAMP(6) WITH TIME ZONE -- Mốc retry
completion_attempts INTEGER NOT NULL DEFAULT 0 -- Bộ đếm native
status VARCHAR(255) -- Trạng thái native
""", indexes=["CREATE INDEX idx_event_publication_completion_date ON event_publication(completion_date);",
             "CREATE INDEX idx_event_publication_status_publication_date ON event_publication(status, publication_date);"])

def ident(name):
    if len(name) <= 60:
        return name
    return name[:48] + "_" + hashlib.sha1(name.encode()).hexdigest()[:10]


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def render():
    names = {t['name']: t for t in TABLES}
    assert len(names) == len(TABLES), "duplicate table"
    for t in TABLES:
        for cols, parent, parent_cols, _, _ in t['foreign']:
            assert parent in names, (t['name'], parent)
            for col in cols.replace(' ', '').split(','):
                assert col in {c[0] for c in t['columns']}, (t['name'], col)
            for col in parent_cols.replace(' ', '').split(','):
                assert col in {c[0] for c in names[parent]['columns']}, (parent, col)
    sql = ["-- APPROVED REFERENCE DESIGN, 2026-10-25. PostgreSQL 17.",
           "-- Fresh isolated database ONLY. Not a Flyway migration. Never run over the current application DB.",
           "-- Includes native V1 event_publication; do not apply V1/V2 on top of this reference schema.",
           "-- Source: D01-D29 and approved B01-B37. Regenerate with tools/build_approved_schema.py.",
           "BEGIN;", "SET LOCAL TIME ZONE 'UTC';"]
    for t in TABLES:
        entries = [f"    {name} {spec}" for name, spec, _ in t['columns']]
        entries += [f"    CONSTRAINT {ident(t['name']+'_c'+str(i+1))} {check}"
                    for i, check in enumerate(t['checks'])]
        sql.append(f"\n-- {t['module']}: {t['description']}\nCREATE TABLE {t['name']} (\n" + ',\n'.join(entries) + '\n);')
    sql.append("\n-- All FK targets now exist; circular publication/avatar relations are explicit.")
    for t in TABLES:
        for cols, parent, parent_cols, delete, deferred in t['foreign']:
            suffix = re.sub(r'\W+', '_', cols)
            name = ident('fk_'+t['name']+'_'+suffix+'_'+parent)
            tail = ' DEFERRABLE INITIALLY DEFERRED' if deferred else ''
            sql.append(f"ALTER TABLE {t['name']} ADD CONSTRAINT {name} FOREIGN KEY ({cols}) "
                       f"REFERENCES {parent} ({parent_cols}) ON DELETE {delete}{tail};")
    for t in TABLES:
        sql.extend(t['indexes'])
        sql.append(f"COMMENT ON TABLE {t['name']} IS {quote(t['description'])};")
        for name, _, description in t['columns']:
            if description:
                sql.append(f"COMMENT ON COLUMN {t['name']}.{name} IS {quote(description)};")
    sql.append((ROOT/'tools'/'approved_invariants.sql').read_text(encoding='utf-8'))
    sql.append("\n-- Safe catalog/config seeds. No users, passwords, API keys or production credentials.")
    sql.append("INSERT INTO roles(id,code,name) VALUES (1,'LEARNER','Learner'),(2,'CONTENT_ADMIN','Content administrator'),(3,'SYSTEM_ADMIN','System administrator');")
    sql.append("INSERT INTO question_types(code,name,schema_version,enabled) VALUES ('SINGLE_CHOICE','Chọn một đáp án',1,TRUE);")
    for key, (value, value_type, unit) in SETTINGS.items():
        value_json = quote(json.dumps(value, ensure_ascii=False)) + '::jsonb'
        sql.append(f"INSERT INTO system_settings(key,value,value_type,unit) VALUES ({quote(key)},{value_json},{quote(value_type)},{quote(unit) if unit else 'NULL'});")
    sql.append("INSERT INTO storage_pools(id,scope,quota_bytes) VALUES ('20000000-0000-0000-0000-000000000001','CONTENT',10737418240),('20000000-0000-0000-0000-000000000002','TEMP',1073741824);")
    sql.append("COMMIT;")
    (ROOT/'proposals').mkdir(exist_ok=True)
    (ROOT/'proposals'/'approved-baseline.sql').write_text('\n'.join(sql)+'\n', encoding='utf-8')

    def md(text):
        return str(text).replace('|', '&#124;').replace('\n', ' ')
    d = ["# Data dictionary — database đã duyệt", "",
         "Ngày 25/10/2026. Sinh từ cùng mô hình với proposals/approved-baseline.sql.",
         f"Có **{len(TABLES)-1} bảng nghiệp vụ/hỗ trợ + 1 bảng native event_publication**; không tính flyway_schema_history.",
         "Đây là thiết kế/reference SQL, chưa đồng bộ entity hoặc áp dụng migration vào DB dự án.",
         "PRIMARY KEY ngầm NOT NULL. DEFAULT không thay cho validation/API. Các điều kiện liên bảng bổ sung ở SCHEMA_CONTRACTS.md.", "",
         "## Danh mục bảng", "", "| Module | Bảng | Mục đích |", "|---|---|---|"]
    for t in TABLES:
        d.append(f"| {t['module']} | [{t['name']}](#{t['name']}) | {md(t['description'])} |")
    for t in TABLES:
        d += ['', f"## {t['name']}", '', t['description'], '',
              '| Cột | Kiểu/ràng buộc cột | Ý nghĩa |', '|---|---|---|']
        for name, spec, description in t['columns']:
            d.append(f"| `{name}` | `{md(spec)}` | {md(description)} |")
        d += ['', '**Ràng buộc bảng**', '']
        d += [f"- `{md(c)}`" for c in t['checks']] or ['- Không có CHECK/unique bổ sung ngoài cột.']
        for cols, parent, target_cols, delete, deferred in t['foreign']:
            d.append(f"- FK `({cols})` → `{parent}({target_cols})`; DELETE {delete}" + ('; deferred đến commit.' if deferred else '.'))
        if t['indexes']:
            d += ['', '**Index phục vụ truy vấn**', ''] + [f"- `{md(i)}`" for i in t['indexes']]
    d += ['', '## Đếm và nguồn chân lý', '',
          'Bảng nối, snapshot options, source topics/mistakes và ledger dung lượng là chi tiết kỹ thuật để thực hiện nghiệp vụ đã duyệt; không phải module mới.',
          'Không dùng SQL reference như migration nâng cấp. Kiểu/cột ở đây phải được đồng bộ cùng entity/DTO khi bắt đầu bước triển khai.']
    (ROOT/'DATA_DICTIONARY.md').write_text('\n'.join(d)+'\n', encoding='utf-8')

    modules = list(dict.fromkeys(t['module'] for t in TABLES))
    erd = ['# ERD — database đã duyệt', '',
           'Ngày 25/10/2026. Sơ đồ chia theo module để dễ đọc; FK/nullable/unique chính xác nằm ở DATA_DICTIONARY.md và SQL.',
           'Các cạnh biểu diễn tham chiếu entity; quan hệ 1:1 được ràng buộc bằng PK/UNIQUE trong dictionary. ID projection dùng cùng UUID với content_items.', '',
           '## Luồng chính', '', '```mermaid', 'flowchart LR',
           '  I[Content item] --> R[Revision bất biến sau công bố]',
           '  R --> D[Revision con được ghim]', '  R --> M[Media và nguồn có FK]',
           '  R --> A[Attempt snapshot]', '  A --> G[Ngữ cảnh nhóm dùng chung]',
           '  G --> Q[Câu con và option snapshot]', '  Q --> S[Autosave có version]',
           '  S --> O[Nộp và chấm atomic]', '  O --> H[Kết quả và lỗi]', '```']
    for module in modules:
        subset = [t for t in TABLES if t['module']==module]
        lines = []
        for t in subset:
            for cols, parent, _, _, _ in t['foreign']:
                label = cols.replace(', ', '_')
                source_cols = set(cols.replace(' ', '').split(','))
                pk = {name for name, spec, _ in t['columns'] if 'PRIMARY KEY' in spec}
                unique_sets = [{name} for name, spec, _ in t['columns'] if 'PRIMARY KEY' in spec or 'UNIQUE' in spec]
                for constraint in t['checks']:
                    match = re.fullmatch(r'(PRIMARY KEY|UNIQUE) \(([^)]+)\)', constraint)
                    if match:
                        fields = set(match[2].replace(' ', '').split(','))
                        unique_sets.append(fields)
                        if match[1] == 'PRIMARY KEY':
                            pk.update(fields)
                required = all(name in pk or 'NOT NULL' in spec or 'GENERATED ALWAYS' in spec
                               for name, spec, _ in t['columns'] if name in source_cols)
                maximum_one = any(fields <= source_cols for fields in unique_sets)
                left = '||' if required else '|o'
                right = 'o|' if maximum_one else 'o{'
                lines.append(f'    {parent.upper()} {left}--{right} {t["name"].upper()} : "{label}"')
            if not t['foreign']:
                lines += [f'    {t["name"].upper()} {{', '        string primary_key', '    }']
        erd += ['', f'## {module}', '', '```mermaid', 'erDiagram'] + list(dict.fromkeys(lines)) + ['```']
    erd += ['', '## Quan hệ đáng chú ý', '',
            '- users N:N roles; min một role và bảo vệ admin cuối có guard riêng.',
            '- questions.group_id là N:1; không có bảng group_questions N:N. Cờ in_current_group chỉ là membership của projection hiện hành, không đổi context gốc.',
            '- content_items.published_revision_id trỏ về revision của chính item; quan hệ vòng được deferred.',
            '- attempt_answers chọn option bằng FK kép (snapshot_id, option_id), không tham chiếu option hiện hành.',
            '- storage_allocations ghi giữ chỗ/commit/release và cập nhật pool có khóa, không tính quota bằng SUM thiếu phối hợp.',
            '- Nguồn attempt nhiều topic/lỗi và media trong JSON có bảng FK để kiểm tra ownership và giữ lịch sử.']
    (ROOT/'ERD.md').write_text('\n'.join(erd)+'\n', encoding='utf-8')
    manifest = dict(tables=len(TABLES), columns=sum(len(t['columns']) for t in TABLES),
                    foreign_keys=sum(len(t['foreign']) for t in TABLES),
                    check_constraints=sum(c.startswith('CHECK') for t in TABLES for c in t['checks']),
                    additional_table_constraints=sum(len(t['checks']) for t in TABLES),
                    explicit_indexes=sum(len(t['indexes']) for t in TABLES),
                    modules={m: sum(t['module']==m for t in TABLES) for m in modules})
    (ROOT/'schema-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(manifest, separators=(',', ':')))


if __name__ == '__main__':
    render()
