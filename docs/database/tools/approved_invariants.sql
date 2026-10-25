-- Cross-table invariants. Application authorization, schema validation and storage
-- IO still belong to the service contracts; these functions do not replace them.

CREATE FUNCTION me_email_key(p_email TEXT) RETURNS TEXT LANGUAGE SQL IMMUTABLE
AS $$ SELECT lower(btrim(p_email)) $$;

CREATE FUNCTION me_public_json_safe(p_data JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE v_key TEXT; v_value JSONB;
BEGIN
  IF p_data IS NULL THEN RETURN TRUE; END IF;
  IF jsonb_typeof(p_data)='object' THEN
    FOR v_key,v_value IN SELECT key,value FROM jsonb_each(p_data) LOOP
      IF v_key = ANY(ARRAY['correctOptionId','correct_option_id','isCorrect','is_correct',
          'answerKey','answer_key','explanation','transcript','permission_note','password_hash',
          'otp_hmac','grant_hash','encrypted_payload']) OR NOT me_public_json_safe(v_value)
      THEN RETURN FALSE; END IF;
    END LOOP;
  ELSIF jsonb_typeof(p_data)='array' THEN
    FOR v_value IN SELECT value FROM jsonb_array_elements(p_data) LOOP
      IF NOT me_public_json_safe(v_value) THEN RETURN FALSE; END IF;
    END LOOP;
  END IF;
  RETURN TRUE;
END $$;

ALTER TABLE attempt_question_snapshots ADD CONSTRAINT ck_snapshot_public_safe
  CHECK (me_public_json_safe(question_snapshot));
ALTER TABLE attempt_group_snapshots ADD CONSTRAINT ck_group_snapshot_public_safe
  CHECK (me_public_json_safe(public_snapshot));

CREATE FUNCTION me_has_role(p_user UUID,p_code TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE
AS $$ SELECT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id
  JOIN roles r ON r.id=ur.role_id WHERE u.id=p_user AND u.status='ACTIVE'
  AND u.email_verified_at IS NOT NULL AND r.code=p_code) $$;

CREATE FUNCTION me_revoke_sessions(p_user UUID,p_reason TEXT) RETURNS VOID
LANGUAGE plpgsql AS $$ BEGIN
  UPDATE auth_sessions SET revoked_at=CURRENT_TIMESTAMP,revoke_reason=p_reason
    WHERE user_id=p_user AND revoked_at IS NULL;
END $$;

CREATE FUNCTION me_session_revoke_tokens() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
    UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,NEW.revoked_at)
      WHERE session_id=NEW.id AND revoked_at IS NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER session_revoke_tokens AFTER UPDATE ON auth_sessions
  FOR EACH ROW EXECUTE FUNCTION me_session_revoke_tokens();

CREATE FUNCTION me_guard_user() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_media media_assets; v_last_admin BOOLEAN;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF NEW.id<>OLD.id OR NEW.email IS DISTINCT FROM OLD.email THEN
      RAISE EXCEPTION 'user id/email are immutable' USING ERRCODE='23514'; END IF;
    IF OLD.status='ACTIVE' AND NEW.status<>'ACTIVE' AND EXISTS(
      SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
      WHERE ur.user_id=OLD.id AND r.code='SYSTEM_ADMIN') THEN
      PERFORM pg_advisory_xact_lock(721001);
      SELECT NOT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id
        JOIN roles r ON r.id=ur.role_id WHERE u.id<>OLD.id AND u.status='ACTIVE'
        AND u.email_verified_at IS NOT NULL AND r.code='SYSTEM_ADMIN') INTO v_last_admin;
      IF v_last_admin THEN RAISE EXCEPTION 'last active system administrator'
        USING ERRCODE='23514'; END IF;
    END IF;
    IF NEW.password_hash IS DISTINCT FROM OLD.password_hash OR NEW.status IS DISTINCT FROM OLD.status THEN
      NEW.auth_version:=OLD.auth_version+1;
      IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
        NEW.password_changed_at:=CURRENT_TIMESTAMP; END IF;
    END IF;
  END IF;
  IF NEW.avatar_media_id IS NOT NULL THEN
    SELECT * INTO v_media FROM media_assets WHERE id=NEW.avatar_media_id FOR UPDATE;
    IF NOT FOUND OR v_media.uploaded_by<>NEW.id OR v_media.purpose<>'AVATAR'
      OR v_media.media_type<>'IMAGE' OR v_media.status<>'READY'
      OR v_media.deletion_started_at IS NOT NULL THEN
      RAISE EXCEPTION 'avatar must be an owned ready image' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_user BEFORE INSERT OR UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION me_guard_user();

CREATE FUNCTION me_user_security_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_reason TEXT;
BEGIN
  IF NEW.auth_version<>OLD.auth_version THEN
    v_reason:=CASE WHEN NEW.status='LOCKED' THEN 'ACCOUNT_LOCKED'
      WHEN NEW.status='DISABLED' THEN 'ACCOUNT_DISABLED'
      WHEN NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN 'PASSWORD_CHANGE'
      ELSE 'SECURITY' END;
    PERFORM me_revoke_sessions(NEW.id,v_reason);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER user_security_changed AFTER UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION me_user_security_changed();

CREATE FUNCTION me_guard_role_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_code TEXT;
BEGIN
  IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'replace role grant rather than update'
    USING ERRCODE='23514'; END IF;
  IF TG_OP='DELETE' THEN
    SELECT code INTO v_code FROM roles WHERE id=OLD.role_id;
    IF v_code='SYSTEM_ADMIN' THEN
      PERFORM pg_advisory_xact_lock(721001);
      IF EXISTS(SELECT 1 FROM users WHERE id=OLD.user_id AND status='ACTIVE') AND NOT EXISTS(
        SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
        WHERE u.id<>OLD.user_id AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
        AND r.code='SYSTEM_ADMIN') THEN
        RAISE EXCEPTION 'cannot remove last active system administrator' USING ERRCODE='23514';
      END IF;
    END IF;
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_role_change BEFORE UPDATE OR DELETE ON user_roles
  FOR EACH ROW EXECUTE FUNCTION me_guard_role_change();

CREATE FUNCTION me_role_change_security() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user UUID;
BEGIN
  v_user:=CASE WHEN TG_OP='DELETE' THEN OLD.user_id ELSE NEW.user_id END;
  PERFORM 1 FROM users WHERE id=v_user FOR UPDATE;
  PERFORM me_revoke_sessions(v_user,'ROLE_CHANGE');
  UPDATE users SET auth_version=auth_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=v_user;
  RETURN NULL;
END $$;
CREATE TRIGGER role_change_security AFTER INSERT OR DELETE ON user_roles
  FOR EACH ROW EXECUTE FUNCTION me_role_change_security();

CREATE FUNCTION me_minimum_role() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user UUID;
BEGIN
  IF TG_TABLE_NAME='users' THEN v_user:=NEW.id;
  ELSE v_user:=CASE WHEN TG_OP='DELETE' THEN OLD.user_id ELSE NEW.user_id END; END IF;
  PERFORM 1 FROM users WHERE id=v_user FOR UPDATE;
  IF FOUND AND NOT EXISTS(SELECT 1 FROM user_roles WHERE user_id=v_user) THEN
    RAISE EXCEPTION 'user must have at least one role at commit' USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER user_minimum_role AFTER INSERT ON users DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION me_minimum_role();
CREATE CONSTRAINT TRIGGER grant_minimum_role AFTER INSERT OR DELETE ON user_roles DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION me_minimum_role();

CREATE FUNCTION me_guard_session() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user users; v_limit INTEGER; v_count INTEGER; v_old UUID;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF (NEW.user_id,NEW.created_at,NEW.expires_at) IS DISTINCT FROM (OLD.user_id,OLD.created_at,OLD.expires_at)
      OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
      RAISE EXCEPTION 'session identity/absolute expiry/revocation is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO v_user FROM users WHERE id=NEW.user_id FOR UPDATE;
  IF NOT FOUND OR v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL THEN
    RAISE EXCEPTION 'only verified active users can log in' USING ERRCODE='23514'; END IF;
  v_limit:=COALESCE((SELECT (value::text)::INTEGER FROM system_settings WHERE key='auth.max_sessions'),5);
  LOOP
    SELECT count(*) INTO v_count FROM auth_sessions WHERE user_id=NEW.user_id
      AND revoked_at IS NULL AND expires_at>NEW.created_at;
    EXIT WHEN v_count<v_limit;
    SELECT id INTO v_old FROM auth_sessions WHERE user_id=NEW.user_id AND revoked_at IS NULL
      AND expires_at>NEW.created_at ORDER BY created_at,id LIMIT 1 FOR UPDATE;
    UPDATE auth_sessions SET revoked_at=NEW.created_at,revoke_reason='SESSION_LIMIT' WHERE id=v_old;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_session BEFORE INSERT OR UPDATE ON auth_sessions
  FOR EACH ROW EXECUTE FUNCTION me_guard_session();

CREATE FUNCTION me_guard_refresh() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_session auth_sessions;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.session_id,NEW.token_hash,NEW.issued_at,NEW.expires_at)
      IS DISTINCT FROM (OLD.id,OLD.session_id,OLD.token_hash,OLD.issued_at,OLD.expires_at)
      OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
      RAISE EXCEPTION 'token identity/lifetime/revocation is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO v_session FROM auth_sessions WHERE id=NEW.session_id;
  IF NOT FOUND OR NEW.issued_at<v_session.created_at OR NEW.expires_at>v_session.expires_at
    OR v_session.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'token must fit its live session' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_refresh BEFORE INSERT OR UPDATE ON refresh_tokens
  FOR EACH ROW EXECUTE FUNCTION me_guard_refresh();

CREATE FUNCTION me_consume_otp(p_id UUID,p_user UUID,p_purpose TEXT,p_expected_hmac TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_user users; v_id UUID;
BEGIN
  SELECT * INTO v_user FROM users WHERE id=p_user FOR UPDATE;
  IF NOT FOUND OR v_user.status IN ('LOCKED','DISABLED') OR
    (p_purpose<>'EMAIL_VERIFICATION' AND (v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL))
    THEN RETURN FALSE; END IF;
  UPDATE otp_challenges SET consumed_at=CURRENT_TIMESTAMP,otp_hmac=NULL
    WHERE id=p_id AND user_id=p_user AND purpose=p_purpose AND otp_hmac=p_expected_hmac
    AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at>CURRENT_TIMESTAMP
    AND (blocked_until IS NULL OR blocked_until<=CURRENT_TIMESTAMP) AND attempt_count<5 RETURNING id INTO v_id;
  RETURN v_id IS NOT NULL;
END $$;

CREATE FUNCTION me_rotate_refresh(p_session UUID,p_old_hash TEXT,p_new_id UUID,p_new_hash TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_session auth_sessions; v_user users; v_old refresh_tokens;
BEGIN
  SELECT * INTO v_session FROM auth_sessions WHERE id=p_session;
  IF NOT FOUND THEN RETURN FALSE; END IF;
  SELECT * INTO v_user FROM users WHERE id=v_session.user_id FOR UPDATE;
  SELECT * INTO v_session FROM auth_sessions WHERE id=p_session FOR UPDATE;
  IF v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL OR
    v_session.revoked_at IS NOT NULL OR v_session.expires_at<=CURRENT_TIMESTAMP THEN RETURN FALSE; END IF;
  SELECT * INTO v_old FROM refresh_tokens WHERE session_id=p_session AND token_hash=p_old_hash FOR UPDATE;
  IF NOT FOUND OR v_old.revoked_at IS NOT NULL OR v_old.expires_at<=CURRENT_TIMESTAMP THEN RETURN FALSE; END IF;
  UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP,last_used_at=CURRENT_TIMESTAMP,replaced_by_id=p_new_id
    WHERE id=v_old.id;
  INSERT INTO refresh_tokens(id,session_id,token_hash,issued_at,expires_at)
    VALUES(p_new_id,p_session,p_new_hash,CURRENT_TIMESTAMP,v_session.expires_at);
  RETURN TRUE;
END $$;

CREATE FUNCTION me_guard_change_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_session auth_sessions; v_user users;
BEGIN
  IF NEW.completed_at IS NOT NULL OR NEW.invalidated_at IS NOT NULL THEN RETURN NEW; END IF;
  SELECT * INTO v_session FROM auth_sessions WHERE id=NEW.session_id;
  SELECT * INTO v_user FROM users WHERE id=NEW.user_id;
  IF v_session.id IS NULL OR v_session.user_id<>NEW.user_id OR NEW.expires_at>v_session.expires_at
    OR v_session.revoked_at IS NOT NULL OR v_user.status<>'ACTIVE'
    OR NEW.auth_version_at_request<>v_user.auth_version THEN
    RAISE EXCEPTION 'password change request must fit its authenticated context' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_change_request BEFORE INSERT OR UPDATE ON password_change_requests
  FOR EACH ROW EXECUTE FUNCTION me_guard_change_request();

CREATE FUNCTION me_guard_reset_grant() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_user users; v_otp otp_challenges;
BEGIN
  IF TG_OP='UPDATE' THEN
    IF (NEW.user_id,NEW.otp_challenge_id,NEW.auth_version_at_issue,NEW.issued_at,NEW.expires_at)
      IS DISTINCT FROM (OLD.user_id,OLD.otp_challenge_id,OLD.auth_version_at_issue,OLD.issued_at,OLD.expires_at) THEN
      RAISE EXCEPTION 'grant context is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO v_user FROM users WHERE id=NEW.user_id;
  SELECT * INTO v_otp FROM otp_challenges WHERE id=NEW.otp_challenge_id;
  IF v_user.status<>'ACTIVE' OR v_user.email_verified_at IS NULL OR
    NEW.auth_version_at_issue<>v_user.auth_version OR v_otp.consumed_at IS NULL
    OR v_otp.invalidated_at IS NOT NULL OR v_otp.purpose<>'PASSWORD_RESET'
    OR v_otp.user_id<>NEW.user_id THEN
    RAISE EXCEPTION 'reset grant requires consumed reset OTP and current active user' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reset_grant BEFORE INSERT OR UPDATE ON password_reset_grants
  FOR EACH ROW EXECUTE FUNCTION me_guard_reset_grant();

CREATE FUNCTION me_guard_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.status<>'DRAFT' THEN RAISE EXCEPTION 'revision starts as Draft' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='DELETE' THEN
    IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'published revision retained' USING ERRCODE='55000'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status='PUBLISHED' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'published revision is immutable' USING ERRCODE='55000'; END IF;
  IF (NEW.id,NEW.content_item_id,NEW.version_no) IS DISTINCT FROM (OLD.id,OLD.content_item_id,OLD.version_no) THEN
    RAISE EXCEPTION 'revision identity is immutable' USING ERRCODE='23514'; END IF;
  IF NEW.status<>OLD.status AND NOT (
    (OLD.status='DRAFT' AND NEW.status='IN_REVIEW') OR
    (OLD.status='IN_REVIEW' AND NEW.status IN ('APPROVED','REJECTED','DRAFT')) OR
    (OLD.status='REJECTED' AND NEW.status='DRAFT') OR
    (OLD.status='APPROVED' AND NEW.status IN ('PUBLISHED','DRAFT'))) THEN
    RAISE EXCEPTION 'invalid editorial transition' USING ERRCODE='23514'; END IF;
  IF NEW.snapshot IS DISTINCT FROM OLD.snapshot AND NEW.status<>'DRAFT' THEN
    RAISE EXCEPTION 'edit payload only in Draft' USING ERRCODE='23514'; END IF;
  IF NEW.status='PUBLISHED' THEN
    IF NOT (NEW.snapshot ? 'data') OR jsonb_typeof(NEW.snapshot->'data')<>'object'
      OR NOT (NEW.snapshot ? 'schema_version') OR (NEW.snapshot->>'schema_version')::INTEGER<>NEW.schema_version THEN
      RAISE EXCEPTION 'published snapshot requires typed envelope' USING ERRCODE='23514'; END IF;
    IF NOT EXISTS(SELECT 1 FROM content_source_links WHERE revision_id=NEW.id) OR EXISTS(
      SELECT 1 FROM content_source_links l JOIN content_sources s ON s.id=l.source_id
      WHERE l.revision_id=NEW.id AND (l.source_snapshot IS NULL OR s.status<>'ACTIVE'
      OR s.license_code IS NULL OR btrim(s.license_code)='')) THEN
      RAISE EXCEPTION 'publication requires active declared sources and source snapshots' USING ERRCODE='23514'; END IF;
  END IF;
  NEW.version:=OLD.version+1; NEW.updated_at:=CURRENT_TIMESTAMP;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_revision BEFORE INSERT OR UPDATE OR DELETE ON content_revisions
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision();

CREATE FUNCTION me_check_content_pointer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_item content_items; v_revision content_revisions; v_projection TEXT; v_matches BOOLEAN;
BEGIN
  IF TG_TABLE_NAME='content_items' THEN SELECT * INTO v_item FROM content_items WHERE id=NEW.id;
  ELSE SELECT * INTO v_item FROM content_items WHERE id=NEW.content_item_id; END IF;
  IF v_item.id IS NULL OR v_item.published_revision_id IS NULL THEN RETURN NULL; END IF;
  SELECT * INTO v_revision FROM content_revisions WHERE id=v_item.published_revision_id;
  IF v_revision.content_item_id<>v_item.id OR v_revision.status<>'PUBLISHED' THEN
    RAISE EXCEPTION 'published pointer must target own Published revision' USING ERRCODE='23514'; END IF;
  v_projection:=CASE v_item.content_type WHEN 'WORD' THEN 'words' WHEN 'DECK' THEN 'decks'
    WHEN 'GRAMMAR_TOPIC' THEN 'grammar_topics' WHEN 'GRAMMAR_LESSON' THEN 'grammar_lessons'
    WHEN 'QUESTION' THEN 'questions' WHEN 'QUESTION_GROUP' THEN 'question_groups'
    WHEN 'PRACTICE_SET' THEN 'practice_sets' END;
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I WHERE id=$1 AND published_revision_id=$2)',v_projection)
    INTO v_matches USING v_item.id,v_item.published_revision_id;
  IF NOT v_matches THEN RAISE EXCEPTION 'published projection must match pointer'
    USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER content_pointer_valid AFTER INSERT OR UPDATE ON content_items
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_content_pointer();
CREATE CONSTRAINT TRIGGER published_projection_valid AFTER INSERT OR UPDATE ON content_revisions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_content_pointer();

CREATE FUNCTION me_guard_content_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.id<>OLD.id OR NEW.content_type<>OLD.content_type THEN
    RAISE EXCEPTION 'content identity/type is immutable' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_content_item BEFORE UPDATE ON content_items FOR EACH ROW EXECUTE FUNCTION me_guard_content_item();

CREATE FUNCTION me_guard_revision_link() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_old UUID; v_new UUID; v_child UUID;
BEGIN
  IF TG_OP<>'INSERT' THEN v_old:=(to_jsonb(OLD)->>TG_ARGV[0])::UUID; END IF;
  IF TG_OP<>'DELETE' THEN v_new:=(to_jsonb(NEW)->>TG_ARGV[0])::UUID; END IF;
  IF EXISTS(SELECT 1 FROM content_revisions WHERE id IN (v_old,v_new) AND status='PUBLISHED') THEN
    RAISE EXCEPTION 'published source/media/dependency links are immutable' USING ERRCODE='55000'; END IF;
  IF TG_OP<>'DELETE' AND TG_TABLE_NAME='revision_dependencies' THEN
    v_child:=NEW.child_revision_id;
    IF NOT EXISTS(SELECT 1 FROM content_revisions WHERE id=v_child AND status='PUBLISHED') THEN
      RAISE EXCEPTION 'formal dependency must pin Published revision' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP<>'DELETE' AND TG_TABLE_NAME='content_source_links' THEN
    IF NOT EXISTS(SELECT 1 FROM content_sources WHERE id=NEW.source_id AND status='ACTIVE') THEN
      RAISE EXCEPTION 'new link requires active source' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER guard_source_link BEFORE INSERT OR UPDATE OR DELETE ON content_source_links
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision_link('revision_id');
CREATE TRIGGER guard_revision_media BEFORE INSERT OR UPDATE OR DELETE ON revision_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision_link('revision_id');
CREATE TRIGGER guard_dependency BEFORE INSERT OR UPDATE OR DELETE ON revision_dependencies
  FOR EACH ROW EXECUTE FUNCTION me_guard_revision_link('parent_revision_id');

CREATE FUNCTION me_media_has_refs(p_media UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM users WHERE avatar_media_id=p_media)
 OR EXISTS(SELECT 1 FROM revision_media_refs WHERE media_id=p_media)
 OR EXISTS(SELECT 1 FROM attempt_media_refs WHERE media_id=p_media)
 OR EXISTS(SELECT 1 FROM words WHERE audio_media_id=p_media)
 OR EXISTS(SELECT 1 FROM word_senses WHERE audio_media_id=p_media)
 OR EXISTS(SELECT 1 FROM group_materials WHERE media_id=p_media)
 OR EXISTS(SELECT 1 FROM question_materials WHERE media_id=p_media)
$$;

CREATE FUNCTION me_guard_media() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF TG_OP='DELETE' THEN
    IF me_media_has_refs(OLD.id) OR OLD.deletion_started_at IS NULL OR
      OLD.status NOT IN ('ARCHIVED','FAILED') OR
      (OLD.bucket IS NOT NULL AND OLD.object_deleted_at IS NULL) OR
      (OLD.status='ARCHIVED' AND OLD.archived_at>CURRENT_TIMESTAMP-INTERVAL '30 days') THEN
      RAISE EXCEPTION 'media is not eligible for physical metadata deletion' USING ERRCODE='23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status IN ('READY','ARCHIVED') AND
    (NEW.id,NEW.media_type,NEW.purpose,NEW.bucket,NEW.object_key,NEW.mime_type,NEW.file_size_bytes,
     NEW.duration_ms,NEW.width,NEW.height,NEW.checksum_sha256,NEW.uploaded_by)
    IS DISTINCT FROM
    (OLD.id,OLD.media_type,OLD.purpose,OLD.bucket,OLD.object_key,OLD.mime_type,OLD.file_size_bytes,
     OLD.duration_ms,OLD.width,OLD.height,OLD.checksum_sha256,OLD.uploaded_by) THEN
    RAISE EXCEPTION 'confirmed media bytes and identity are immutable' USING ERRCODE='55000'; END IF;
  IF NEW.status<>OLD.status AND NOT ((OLD.status='PENDING' AND NEW.status IN ('READY','FAILED')) OR
      (OLD.status='READY' AND NEW.status='ARCHIVED')) THEN
    RAISE EXCEPTION 'invalid media lifecycle transition' USING ERRCODE='23514'; END IF;
  IF NEW.status='ARCHIVED' AND OLD.status<>'ARCHIVED' AND EXISTS(SELECT 1 FROM users WHERE avatar_media_id=OLD.id) THEN
    RAISE EXCEPTION 'cannot archive current avatar' USING ERRCODE='23514'; END IF;
  IF NEW.deletion_started_at IS NOT NULL AND OLD.deletion_started_at IS NULL AND me_media_has_refs(OLD.id) THEN
    RAISE EXCEPTION 'cannot claim referenced media for deletion' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_media BEFORE UPDATE OR DELETE ON media_assets FOR EACH ROW EXECUTE FUNCTION me_guard_media();

CREATE FUNCTION me_guard_media_ref() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_id UUID; v_media media_assets;
BEGIN
  v_id:=(to_jsonb(NEW)->>TG_ARGV[0])::UUID;
  IF v_id IS NULL THEN RETURN NEW; END IF;
  SELECT * INTO v_media FROM media_assets WHERE id=v_id FOR UPDATE;
  IF NOT FOUND OR v_media.deletion_started_at IS NOT NULL OR
      (v_media.status<>'READY' AND NOT (TG_TABLE_NAME='attempt_media_refs' AND v_media.status='ARCHIVED')) THEN
    RAISE EXCEPTION 'new media reference must target ready unclaimed file' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='attempt_media_refs' THEN
    -- Archived media remains valid only through the exact Published source graph.
    IF NOT EXISTS(
      WITH RECURSIVE roots(id) AS (
        SELECT source_revision_id FROM attempts WHERE id=NEW.attempt_id
        UNION SELECT source_revision_id FROM attempt_question_snapshots WHERE attempt_id=NEW.attempt_id
        UNION SELECT source_revision_id FROM attempt_group_snapshots WHERE attempt_id=NEW.attempt_id
        UNION SELECT source_revision_id FROM attempt_source_topics WHERE attempt_id=NEW.attempt_id
      ), deps(id) AS (
        SELECT id FROM roots UNION SELECT d.child_revision_id FROM revision_dependencies d JOIN deps p ON p.id=d.parent_revision_id
      ) SELECT 1 FROM deps JOIN revision_media_refs r ON r.revision_id=deps.id
        JOIN content_revisions cr ON cr.id=r.revision_id WHERE r.media_id=v_id AND cr.status='PUBLISHED'
    ) THEN RAISE EXCEPTION 'attempt media must derive from pinned Published revisions' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_TABLE_NAME IN ('group_materials','question_materials') THEN
    IF NEW.material_type<>v_media.media_type THEN
      RAISE EXCEPTION 'material and file types differ' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_TABLE_NAME NOT IN ('users') AND v_media.purpose<>'CONTENT' THEN
    RAISE EXCEPTION 'avatar cannot be attached as learning content' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_new_revision_media BEFORE INSERT OR UPDATE ON revision_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_new_attempt_media BEFORE INSERT OR UPDATE ON attempt_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_group_material_media BEFORE INSERT OR UPDATE ON group_materials
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_question_material_media BEFORE INSERT OR UPDATE ON question_materials
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('media_id');
CREATE TRIGGER guard_word_audio BEFORE INSERT OR UPDATE ON words
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('audio_media_id');
CREATE TRIGGER guard_sense_audio BEFORE INSERT OR UPDATE ON word_senses
  FOR EACH ROW EXECUTE FUNCTION me_guard_media_ref('audio_media_id');

CREATE FUNCTION me_check_question() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_id UUID; v_q questions; v_count INTEGER; v_correct INTEGER; v_expected INTEGER;
BEGIN
  IF TG_TABLE_NAME='questions' THEN v_id:=NEW.id;
  ELSIF TG_OP='DELETE' THEN v_id:=OLD.question_id; ELSE v_id:=NEW.question_id; END IF;
  SELECT * INTO v_q FROM questions WHERE id=v_id;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM content_items WHERE id=v_id AND is_archived) THEN RETURN NULL; END IF;
  SELECT count(*),count(*) FILTER(WHERE is_correct) INTO v_count,v_correct FROM question_options WHERE question_id=v_id;
  v_expected:=CASE WHEN v_q.category='TOEIC' THEN CASE WHEN v_q.part_number=2 THEN 3 ELSE 4 END ELSE NULL END;
  IF v_correct<>1 OR (v_expected IS NOT NULL AND v_count<>v_expected) OR
    (v_expected IS NULL AND (v_count<2 OR v_count>6)) THEN
    RAISE EXCEPTION 'published question requires valid choice count and exactly one key' USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER question_options_complete AFTER INSERT OR UPDATE ON questions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_question();
CREATE CONSTRAINT TRIGGER options_complete AFTER INSERT OR UPDATE OR DELETE ON question_options
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_question();

CREATE FUNCTION me_guard_question_context() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF (NEW.group_id,NEW.category,NEW.part_number,NEW.question_type_code)
    IS DISTINCT FROM (OLD.group_id,OLD.category,OLD.part_number,OLD.question_type_code) THEN
    RAISE EXCEPTION 'new semantic context requires new question id'
    USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_question_context BEFORE UPDATE ON questions FOR EACH ROW EXECUTE FUNCTION me_guard_question_context();

CREATE FUNCTION me_check_group() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_id UUID; v_group question_groups; v_count INTEGER; v_documents INTEGER;
BEGIN
  IF TG_TABLE_NAME='question_groups' THEN v_id:=NEW.id;
  ELSIF TG_TABLE_NAME='group_materials' THEN v_id:=CASE WHEN TG_OP='DELETE' THEN OLD.group_id ELSE NEW.group_id END;
  ELSE v_id:=CASE WHEN TG_OP='DELETE' THEN OLD.group_id ELSE NEW.group_id END; END IF;
  IF v_id IS NULL THEN RETURN NULL; END IF;
  SELECT * INTO v_group FROM question_groups WHERE id=v_id;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM content_items WHERE id=v_id AND is_archived) THEN RETURN NULL; END IF;
  SELECT count(*) INTO v_count FROM questions WHERE group_id=v_id AND in_current_group;
  IF v_count=0 OR (v_group.category='TOEIC' AND ((v_group.part_number IN (3,4) AND v_count<>3) OR
      (v_group.part_number=6 AND v_count<>4))) THEN
    RAISE EXCEPTION 'group question count does not fit its context' USING ERRCODE='23514'; END IF;
  IF v_group.category='TOEIC' AND v_group.part_number IN (3,4) AND NOT EXISTS(
    SELECT 1 FROM group_materials WHERE group_id=v_id AND material_type='AUDIO' AND usage_role='STIMULUS') THEN
    RAISE EXCEPTION 'listening group needs common audio' USING ERRCODE='23514'; END IF;
  IF v_group.category='TOEIC' AND v_group.part_number=6 AND NOT EXISTS(
    SELECT 1 FROM group_materials WHERE group_id=v_id AND material_type='TEXT' AND usage_role='STIMULUS') THEN
    RAISE EXCEPTION 'Part 6 needs common text' USING ERRCODE='23514'; END IF;
  IF v_group.category='TOEIC' AND v_group.part_number=7 THEN
    SELECT count(DISTINCT document_key) INTO v_documents FROM group_materials
      WHERE group_id=v_id AND usage_role='STIMULUS';
    IF v_documents NOT BETWEEN 1 AND 3 OR EXISTS(SELECT 1 FROM group_materials WHERE group_id=v_id
        AND usage_role='STIMULUS' AND document_key IS NULL) THEN
      RAISE EXCEPTION 'Part 7 requires one to three identified documents' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER group_complete AFTER INSERT OR UPDATE ON question_groups
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_group();
CREATE CONSTRAINT TRIGGER group_children_complete AFTER INSERT OR UPDATE OR DELETE ON questions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_group();
CREATE CONSTRAINT TRIGGER group_material_complete AFTER INSERT OR UPDATE OR DELETE ON group_materials
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_group();

CREATE FUNCTION me_guard_set_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF NEW.question_id IS NOT NULL AND EXISTS(SELECT 1 FROM questions WHERE id=NEW.question_id AND group_id IS NOT NULL) THEN
    RAISE EXCEPTION 'select a whole group rather than a grouped child' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_set_item BEFORE INSERT OR UPDATE ON set_items FOR EACH ROW EXECUTE FUNCTION me_guard_set_item();

CREATE FUNCTION me_storage_account() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_reserved_old BIGINT:=0; v_used_old BIGINT:=0; v_reserved_new BIGINT:=0; v_used_new BIGINT:=0;
  v_pool storage_pools; v_increase BIGINT;
BEGIN
  IF TG_OP='DELETE' THEN
    IF OLD.state<>'RELEASED' THEN RAISE EXCEPTION 'release allocation after storage reconciliation before deletion'
      USING ERRCODE='23514'; END IF;
    RETURN OLD;
  END IF;
  IF TG_OP='UPDATE' THEN
    IF NEW.pool_id<>OLD.pool_id OR NEW.bytes<>OLD.bytes OR NEW.allocation_key<>OLD.allocation_key THEN
      RAISE EXCEPTION 'allocation identity/byte reservation is immutable' USING ERRCODE='23514'; END IF;
    IF OLD.state='RELEASED' AND NEW IS DISTINCT FROM OLD AND
      (NEW.state,NEW.pool_id,NEW.bytes,NEW.bucket,NEW.object_key) IS DISTINCT FROM
      (OLD.state,OLD.pool_id,OLD.bytes,OLD.bucket,OLD.object_key) THEN
      RAISE EXCEPTION 'released allocation cannot consume again' USING ERRCODE='23514'; END IF;
    IF NEW.state<>OLD.state AND NOT ((OLD.state='RESERVED' AND NEW.state IN ('COMMITTED','RELEASED')) OR
        (OLD.state='COMMITTED' AND NEW.state='RELEASED')) THEN
      RAISE EXCEPTION 'invalid allocation transition' USING ERRCODE='23514'; END IF;
    v_reserved_old:=CASE WHEN OLD.state='RESERVED' THEN OLD.bytes ELSE 0 END;
    v_used_old:=CASE WHEN OLD.state='COMMITTED' THEN OLD.bytes ELSE 0 END;
  END IF;
  v_reserved_new:=CASE WHEN NEW.state='RESERVED' THEN NEW.bytes ELSE 0 END;
  v_used_new:=CASE WHEN NEW.state='COMMITTED' THEN NEW.bytes ELSE 0 END;
  SELECT * INTO v_pool FROM storage_pools WHERE id=NEW.pool_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'storage pool not found' USING ERRCODE='23503'; END IF;
  v_increase:=(v_reserved_new+v_used_new)-(v_reserved_old+v_used_old);
  IF v_increase>0 AND v_pool.reserved_bytes+v_pool.used_bytes+v_increase>v_pool.quota_bytes THEN
    RAISE EXCEPTION 'storage quota exceeded' USING ERRCODE='23514'; END IF;
  UPDATE storage_pools SET reserved_bytes=reserved_bytes-v_reserved_old+v_reserved_new,
    used_bytes=used_bytes-v_used_old+v_used_new,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=NEW.pool_id;
  RETURN NEW;
END $$;
CREATE TRIGGER storage_account BEFORE INSERT OR UPDATE OR DELETE ON storage_allocations
  FOR EACH ROW EXECUTE FUNCTION me_storage_account();

CREATE FUNCTION me_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  IF TG_TABLE_NAME='audit_logs' THEN
    IF TG_OP='DELETE' AND OLD.created_at<=CURRENT_TIMESTAMP-INTERVAL '365 days' THEN RETURN OLD; END IF;
  END IF;
  RAISE EXCEPTION '% is append only (subject to documented retention)',TG_TABLE_NAME USING ERRCODE='55000';
END $$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION me_append_only();
CREATE TRIGGER review_append_only BEFORE UPDATE OR DELETE ON word_reviews FOR EACH ROW EXECUTE FUNCTION me_append_only();

CREATE FUNCTION me_guard_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_count INTEGER; v_old UUID;
BEGIN
  IF TG_OP='INSERT' THEN
    PERFORM 1 FROM users WHERE id=NEW.user_id FOR UPDATE;
    IF NOT me_has_role(NEW.user_id,'LEARNER') THEN
      RAISE EXCEPTION 'attempt requires active learner' USING ERRCODE='23514'; END IF;
    FOR v_old IN SELECT id FROM attempts WHERE user_id=NEW.user_id AND status='IN_PROGRESS'
      AND deadline_at<=CURRENT_TIMESTAMP ORDER BY id LOOP
      PERFORM me_submit_attempt(v_old,NEW.user_id,'TIME_LIMIT');
    END LOOP;
    UPDATE attempts SET status='ABANDONED',abandoned_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
      WHERE user_id=NEW.user_id AND status='IN_PROGRESS' AND deadline_at IS NULL
      AND last_active_at<=CURRENT_TIMESTAMP-INTERVAL '7 days';
    SELECT count(*) INTO v_count FROM attempts WHERE user_id=NEW.user_id AND status='IN_PROGRESS';
    IF v_count>=COALESCE((SELECT (value::text)::INTEGER FROM system_settings WHERE key='practice.max_open'),5) THEN
      RAISE EXCEPTION 'open attempt limit reached' USING ERRCODE='23514'; END IF;
    IF NEW.status<>'IN_PROGRESS' OR NEW.snapshot_sealed_at IS NOT NULL THEN
      RAISE EXCEPTION 'attempt starts unsealed and in progress' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='DELETE' THEN
    IF OLD.status<>'ABANDONED' OR OLD.abandoned_at>CURRENT_TIMESTAMP-INTERVAL '30 days' THEN
      RAISE EXCEPTION 'attempt retained by history policy' USING ERRCODE='55000'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status='SUBMITTED' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'submitted result is immutable' USING ERRCODE='55000'; END IF;
  IF OLD.status='ABANDONED' AND NEW.status<>'ABANDONED' THEN
    RAISE EXCEPTION 'abandoned attempt cannot resume' USING ERRCODE='23514'; END IF;
  IF OLD.snapshot_sealed_at IS NOT NULL AND
    (NEW.user_id,NEW.category,NEW.source_type,NEW.source_set_id,NEW.source_deck_id,NEW.source_topic_id,
     NEW.source_revision_id,NEW.source_config,NEW.request_id,NEW.request_hash,NEW.total_count,
     NEW.snapshot_sealed_at,NEW.started_at,NEW.deadline_at)
    IS DISTINCT FROM
    (OLD.user_id,OLD.category,OLD.source_type,OLD.source_set_id,OLD.source_deck_id,OLD.source_topic_id,
     OLD.source_revision_id,OLD.source_config,OLD.request_id,OLD.request_hash,OLD.total_count,
     OLD.snapshot_sealed_at,OLD.started_at,OLD.deadline_at) THEN
    RAISE EXCEPTION 'sealed attempt source/deadline/identity is immutable' USING ERRCODE='55000'; END IF;
  NEW.version:=OLD.version+1; NEW.updated_at:=CURRENT_TIMESTAMP;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_attempt BEFORE INSERT OR UPDATE OR DELETE ON attempts
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt();

CREATE FUNCTION me_guard_attempt_child() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_attempt UUID; v_parent attempts; v_old_attempt UUID; v_snapshot UUID;
BEGIN
  IF TG_TABLE_NAME='attempt_snapshot_options' THEN
    v_snapshot:=CASE WHEN TG_OP='DELETE' THEN OLD.snapshot_id ELSE NEW.snapshot_id END;
    SELECT attempt_id INTO v_attempt FROM attempt_question_snapshots WHERE id=v_snapshot;
  ELSE
    v_attempt:=CASE WHEN TG_OP='DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END;
    IF TG_OP='UPDATE' AND NEW.attempt_id<>OLD.attempt_id THEN
      RAISE EXCEPTION 'cannot move attempt child' USING ERRCODE='23514'; END IF;
  END IF;
  SELECT * INTO v_parent FROM attempts WHERE id=v_attempt FOR UPDATE;
  IF v_parent.id IS NULL THEN RAISE EXCEPTION 'attempt not found' USING ERRCODE='23503'; END IF;
  IF v_parent.status='ABANDONED' AND TG_OP='DELETE' AND v_parent.abandoned_at<=CURRENT_TIMESTAMP-INTERVAL '30 days' THEN RETURN OLD; END IF;
  IF v_parent.snapshot_sealed_at IS NOT NULL THEN
    RAISE EXCEPTION 'attempt snapshot and source refs are sealed' USING ERRCODE='55000'; END IF;
  IF TG_OP<>'DELETE' AND TG_TABLE_NAME IN ('attempt_question_snapshots','attempt_group_snapshots') THEN
    IF NOT me_revision_available(NEW.source_revision_id) THEN
      RAISE EXCEPTION 'attempt source must be available Published content' USING ERRCODE='23514'; END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER guard_group_snapshot BEFORE INSERT OR UPDATE OR DELETE ON attempt_group_snapshots
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_question_snapshot BEFORE INSERT OR UPDATE OR DELETE ON attempt_question_snapshots
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_snapshot_option BEFORE INSERT OR UPDATE OR DELETE ON attempt_snapshot_options
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_attempt_media BEFORE INSERT OR UPDATE OR DELETE ON attempt_media_refs
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_attempt_topics BEFORE INSERT OR UPDATE OR DELETE ON attempt_source_topics
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();
CREATE TRIGGER guard_attempt_mistakes BEFORE INSERT OR UPDATE OR DELETE ON attempt_source_mistakes
  FOR EACH ROW EXECUTE FUNCTION me_guard_attempt_child();

CREATE FUNCTION me_revision_available(p_revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  WITH RECURSIVE reachable(id) AS (
    SELECT id FROM content_revisions WHERE id=p_revision
    UNION SELECT d.child_revision_id FROM revision_dependencies d JOIN reachable r ON d.parent_revision_id=r.id
  )
  SELECT EXISTS(SELECT 1 FROM reachable) AND NOT EXISTS(
    SELECT 1 FROM reachable r JOIN content_revisions v ON v.id=r.id JOIN content_items i ON i.id=v.content_item_id
    WHERE v.status<>'PUBLISHED' OR i.is_archived OR i.access_revoked_at IS NOT NULL)
$$;

CREATE FUNCTION me_guard_answer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_attempt UUID; v_parent attempts;
BEGIN
  v_attempt:=CASE WHEN TG_OP='DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END;
  SELECT * INTO v_parent FROM attempts WHERE id=v_attempt FOR UPDATE;
  IF v_parent.id IS NULL THEN RAISE EXCEPTION 'attempt not found' USING ERRCODE='23503'; END IF;
  IF TG_OP='DELETE' AND v_parent.status='ABANDONED' AND v_parent.abandoned_at<=CURRENT_TIMESTAMP-INTERVAL '30 days' THEN RETURN OLD; END IF;
  IF v_parent.status<>'IN_PROGRESS' THEN
    RAISE EXCEPTION 'answers cannot change after submit/abandon' USING ERRCODE='55000'; END IF;
  IF TG_OP='INSERT' AND v_parent.snapshot_sealed_at IS NOT NULL THEN
    RAISE EXCEPTION 'pre-create empty answer slots before sealing' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.attempt_id,NEW.snapshot_id) IS DISTINCT FROM (OLD.id,OLD.attempt_id,OLD.snapshot_id) THEN
      RAISE EXCEPTION 'answer identity is immutable' USING ERRCODE='23514'; END IF;
    NEW.version:=OLD.version+1; NEW.updated_at:=CURRENT_TIMESTAMP;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER guard_answer BEFORE INSERT OR UPDATE OR DELETE ON attempt_answers
  FOR EACH ROW EXECUTE FUNCTION me_guard_answer();

CREATE FUNCTION me_validate_attempt(p_id UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts; v_count INTEGER; v_min INTEGER; v_max INTEGER;
BEGIN
  SELECT * INTO v_attempt FROM attempts WHERE id=p_id;
  IF NOT FOUND OR v_attempt.status='ABANDONED' THEN RETURN; END IF;
  IF v_attempt.snapshot_sealed_at IS NULL THEN
    RAISE EXCEPTION 'attempt must be fully sealed before commit' USING ERRCODE='23514'; END IF;
  SELECT count(*),min(ordinal),max(ordinal) INTO v_count,v_min,v_max FROM attempt_question_snapshots WHERE attempt_id=p_id;
  IF v_count<>v_attempt.total_count OR v_min<>1 OR v_max<>v_count OR
     (SELECT count(*) FROM attempt_answers WHERE attempt_id=p_id)<>v_count THEN
    RAISE EXCEPTION 'attempt requires complete ordered snapshots and answer slots' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM attempt_question_snapshots s WHERE s.attempt_id=p_id AND
    (s.question_type_code<>'SINGLE_CHOICE' OR NOT (s.answer_key_snapshot ? 'correctOptionId') OR NOT EXISTS(
      SELECT 1 FROM attempt_snapshot_options o WHERE o.snapshot_id=s.id
      AND o.option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID))) THEN
    RAISE EXCEPTION 'snapshot key must belong to its own supported choice snapshot' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM attempt_question_snapshots s JOIN questions q ON q.id=s.question_id
    WHERE s.attempt_id=p_id AND ((q.group_id IS NULL AND s.group_snapshot_id IS NOT NULL) OR
      (q.group_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM attempt_group_snapshots g
       WHERE g.id=s.group_snapshot_id AND g.attempt_id=p_id AND g.group_id=q.group_id)))) THEN
    RAISE EXCEPTION 'question must carry its own group context' USING ERRCODE='23514'; END IF;
  IF EXISTS(SELECT 1 FROM attempt_group_snapshots g LEFT JOIN attempt_question_snapshots s ON s.group_snapshot_id=g.id
    WHERE g.attempt_id=p_id GROUP BY g.id HAVING count(s.id)<>g.expected_question_count
    OR max(s.ordinal)-min(s.ordinal)+1<>count(s.id)) THEN
    RAISE EXCEPTION 'whole group must be contiguous and complete' USING ERRCODE='23514'; END IF;
  IF v_attempt.source_type='MIXED_GRAMMAR' AND (SELECT count(*) FROM attempt_source_topics WHERE attempt_id=p_id)<2 THEN
    RAISE EXCEPTION 'mixed Grammar needs multiple source topics' USING ERRCODE='23514'; END IF;
  IF v_attempt.source_type='MISTAKES' AND NOT EXISTS(SELECT 1 FROM attempt_source_mistakes WHERE attempt_id=p_id) THEN
    RAISE EXCEPTION 'mistakes source needs owned mistake refs' USING ERRCODE='23514'; END IF;
  IF v_attempt.status='SUBMITTED' THEN
    IF EXISTS(SELECT 1 FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id
      WHERE a.attempt_id=p_id AND (a.is_correct IS DISTINCT FROM
        COALESCE(a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID,FALSE)
        OR a.score IS DISTINCT FROM CASE WHEN a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID THEN 1 ELSE 0 END))
      OR v_attempt.correct_count<>(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_id AND is_correct)
      OR v_attempt.answered_count<>(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_id AND selected_option_id IS NOT NULL)
      THEN RAISE EXCEPTION 'result must match server snapshot grading' USING ERRCODE='23514'; END IF;
  END IF;
END $$;

CREATE FUNCTION me_attempt_complete_at_commit() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
  PERFORM me_validate_attempt(NEW.id); RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER attempt_complete_at_commit AFTER INSERT OR UPDATE ON attempts
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_attempt_complete_at_commit();

CREATE FUNCTION me_seal_attempt(p_id UUID,p_user UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts;
BEGIN
  PERFORM 1 FROM users WHERE id=p_user FOR UPDATE;
  SELECT * INTO v_attempt FROM attempts WHERE id=p_id AND user_id=p_user FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'attempt is not owned' USING ERRCODE='42501'; END IF;
  IF v_attempt.snapshot_sealed_at IS NULL THEN
    UPDATE attempts SET snapshot_sealed_at=CURRENT_TIMESTAMP WHERE id=p_id;
  END IF;
  PERFORM me_validate_attempt(p_id);
END $$;

CREATE FUNCTION me_save_answer(p_attempt UUID,p_user UUID,p_snapshot UUID,p_option UUID,p_expected_version BIGINT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts; v_id UUID;
BEGIN
  PERFORM 1 FROM users WHERE id=p_user FOR UPDATE;
  SELECT * INTO v_attempt FROM attempts WHERE id=p_attempt AND user_id=p_user FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'attempt is not owned' USING ERRCODE='42501'; END IF;
  IF v_attempt.status<>'IN_PROGRESS' THEN RETURN FALSE; END IF;
  IF NOT me_has_role(p_user,'LEARNER') THEN RAISE EXCEPTION 'active learner required' USING ERRCODE='42501'; END IF;
  IF v_attempt.deadline_at<=CURRENT_TIMESTAMP THEN
    PERFORM me_submit_attempt(p_attempt,p_user,'TIME_LIMIT'); RETURN FALSE; END IF;
  UPDATE attempt_answers SET selected_option_id=p_option,response_payload=NULL,
    answered_at=CASE WHEN p_option IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,
    is_correct=NULL,score=NULL WHERE attempt_id=p_attempt AND snapshot_id=p_snapshot
    AND version=p_expected_version RETURNING id INTO v_id;
  IF v_id IS NOT NULL THEN UPDATE attempts SET last_active_at=CURRENT_TIMESTAMP WHERE id=p_attempt; END IF;
  RETURN v_id IS NOT NULL;
END $$;

CREATE FUNCTION me_refresh_mistakes(p_user UUID,p_question UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_wrong INTEGER; v_streak INTEGER; v_last_wrong TIMESTAMPTZ; v_last TIMESTAMPTZ;
  v_attempt UUID; v_snapshot UUID; v_state TEXT;
BEGIN
  SELECT count(*) INTO v_wrong FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id
    JOIN attempts t ON t.id=a.attempt_id WHERE t.user_id=p_user AND t.status='SUBMITTED'
    AND s.question_id=p_question AND a.is_correct=FALSE;
  IF v_wrong=0 THEN RETURN; END IF;
  SELECT t.id,s.id,t.effective_finished_at INTO v_attempt,v_snapshot,v_last_wrong
    FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id JOIN attempts t ON t.id=a.attempt_id
    WHERE t.user_id=p_user AND t.status='SUBMITTED' AND s.question_id=p_question AND a.is_correct=FALSE
    ORDER BY t.effective_finished_at DESC,t.id DESC LIMIT 1;
  SELECT count(*),max(t.effective_finished_at) INTO v_streak,v_last
    FROM attempt_answers a JOIN attempt_question_snapshots s ON s.id=a.snapshot_id JOIN attempts t ON t.id=a.attempt_id
    WHERE t.user_id=p_user AND t.status='SUBMITTED' AND s.question_id=p_question AND a.is_correct=TRUE
    AND (t.effective_finished_at,t.id)>(v_last_wrong,v_attempt);
  v_last:=COALESCE(v_last,v_last_wrong);
  v_state:=CASE WHEN v_streak=0 THEN 'OPEN' WHEN v_streak=1 THEN 'IMPROVING' ELSE 'RESOLVED' END;
  INSERT INTO mistake_records(id,user_id,question_id,source_attempt_id,source_snapshot_id,state,
    wrong_count,consecutive_correct,last_wrong_at,last_evaluated_at,resolved_at)
    VALUES(gen_random_uuid(),p_user,p_question,v_attempt,v_snapshot,v_state,v_wrong,v_streak,v_last_wrong,v_last,
      CASE WHEN v_streak>=2 THEN v_last ELSE NULL END)
    ON CONFLICT(user_id,question_id) DO UPDATE SET source_attempt_id=EXCLUDED.source_attempt_id,
      source_snapshot_id=EXCLUDED.source_snapshot_id,state=EXCLUDED.state,wrong_count=EXCLUDED.wrong_count,
      consecutive_correct=EXCLUDED.consecutive_correct,last_wrong_at=EXCLUDED.last_wrong_at,
      last_evaluated_at=EXCLUDED.last_evaluated_at,resolved_at=EXCLUDED.resolved_at,updated_at=CURRENT_TIMESTAMP;
END $$;

CREATE FUNCTION me_submit_attempt(p_attempt UUID,p_user UUID,p_reason TEXT DEFAULT 'MANUAL')
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE v_attempt attempts; v_finish TIMESTAMPTZ; v_reason TEXT; v_question UUID;
BEGIN
  PERFORM 1 FROM users WHERE id=p_user FOR UPDATE;
  SELECT * INTO v_attempt FROM attempts WHERE id=p_attempt AND user_id=p_user FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'attempt is not owned' USING ERRCODE='42501'; END IF;
  IF v_attempt.status='SUBMITTED' THEN RETURN TRUE; END IF;
  IF v_attempt.status<>'IN_PROGRESS' THEN RETURN FALSE; END IF;
  IF v_attempt.snapshot_sealed_at IS NULL THEN RAISE EXCEPTION 'attempt not sealed' USING ERRCODE='23514'; END IF;
  IF v_attempt.deadline_at<=CURRENT_TIMESTAMP THEN v_finish:=v_attempt.deadline_at;v_reason:='TIME_LIMIT';
  ELSE
    IF p_reason<>'MANUAL' OR NOT me_has_role(p_user,'LEARNER') THEN
      RAISE EXCEPTION 'manual submit requires active learner' USING ERRCODE='42501'; END IF;
    v_finish:=CURRENT_TIMESTAMP;v_reason:='MANUAL';
  END IF;
  UPDATE attempt_answers a SET is_correct=COALESCE(a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID,FALSE),
    score=CASE WHEN a.selected_option_id=(s.answer_key_snapshot->>'correctOptionId')::UUID THEN 1 ELSE 0 END
    FROM attempt_question_snapshots s WHERE a.snapshot_id=s.id AND a.attempt_id=p_attempt;
  UPDATE attempts SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP,effective_finished_at=v_finish,
    submit_reason=v_reason,correct_count=(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_attempt AND is_correct),
    answered_count=(SELECT count(*) FROM attempt_answers WHERE attempt_id=p_attempt AND selected_option_id IS NOT NULL),
    score=(SELECT COALESCE(sum(score),0) FROM attempt_answers WHERE attempt_id=p_attempt),
    duration_seconds=GREATEST(0,floor(EXTRACT(EPOCH FROM(v_finish-started_at)))::INTEGER),
    local_day=(v_finish AT TIME ZONE timezone)::DATE WHERE id=p_attempt;
  FOR v_question IN SELECT question_id FROM attempt_question_snapshots WHERE attempt_id=p_attempt LOOP
    PERFORM me_refresh_mistakes(p_user,v_question);
  END LOOP;
  RETURN TRUE;
END $$;

CREATE FUNCTION me_guard_upload_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_media media_assets; v_previous UUID;
BEGIN
  SELECT * INTO v_media FROM media_assets WHERE id=NEW.media_id;
  IF NEW.declared_size_bytes>(CASE WHEN v_media.purpose='AVATAR' THEN 2097152
     WHEN v_media.media_type='IMAGE' THEN 5242880 ELSE 20971520 END) THEN
    RAISE EXCEPTION 'upload size exceeds type/purpose policy' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.media_id,NEW.temp_bucket,NEW.temp_object_key,NEW.declared_size_bytes,
        NEW.declared_mime_type,NEW.max_size_bytes,NEW.expires_at)
      IS DISTINCT FROM (OLD.id,OLD.media_id,OLD.temp_bucket,OLD.temp_object_key,OLD.declared_size_bytes,
        OLD.declared_mime_type,OLD.max_size_bytes,OLD.expires_at) THEN
      RAISE EXCEPTION 'upload grant context is immutable' USING ERRCODE='23514'; END IF;
    IF OLD.finished_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'terminal upload metadata is immutable' USING ERRCODE='55000'; END IF;
    IF OLD.verification_deadline_at IS NOT NULL AND
      (NEW.verification_started_at,NEW.verification_deadline_at) IS DISTINCT FROM
      (OLD.verification_started_at,OLD.verification_deadline_at) THEN
      RAISE EXCEPTION 'verification retries cannot extend deadline' USING ERRCODE='23514'; END IF;
    IF NEW.status<>OLD.status AND NOT ((OLD.status='PENDING' AND NEW.status IN ('VERIFYING','FAILED','EXPIRED'))
       OR (OLD.status='VERIFYING' AND NEW.status IN ('COMPLETED','FAILED'))) THEN
      RAISE EXCEPTION 'invalid upload transition' USING ERRCODE='23514'; END IF;
    IF NEW.lease_until IS NOT NULL AND NEW.lease_until>CURRENT_TIMESTAMP+INTERVAL '60 seconds' THEN
      RAISE EXCEPTION 'verification lease exceeds 60 seconds' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_upload_request BEFORE INSERT OR UPDATE ON media_upload_requests
  FOR EACH ROW EXECUTE FUNCTION me_guard_upload_request();

CREATE FUNCTION me_check_upload_complete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_request media_upload_requests; v_media media_assets;
BEGIN
  SELECT * INTO v_request FROM media_upload_requests WHERE id=NEW.id;
  SELECT * INTO v_media FROM media_assets WHERE id=v_request.media_id;
  IF v_request.status='COMPLETED' AND (v_media.status NOT IN ('READY','ARCHIVED')
    OR v_media.file_size_bytes<>v_request.declared_size_bytes) THEN
    RAISE EXCEPTION 'completed upload must match confirmed media' USING ERRCODE='23514'; END IF;
  IF v_request.status IN ('FAILED','EXPIRED') AND v_media.status<>'FAILED' THEN
    RAISE EXCEPTION 'failed upload must match failed media' USING ERRCODE='23514'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER upload_result_consistent AFTER INSERT OR UPDATE ON media_upload_requests
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION me_check_upload_complete();

-- Explicit read models: application authorization still scopes queries by user.
CREATE VIEW learning_daily_activity AS
SELECT user_id,local_day,sum(active_seconds) AS active_seconds
FROM study_sessions GROUP BY user_id,local_day;

CREATE VIEW learning_review_summary AS
SELECT user_id,local_day,count(*) AS review_count,count(DISTINCT word_sense_id) AS unique_senses_reviewed
FROM word_reviews GROUP BY user_id,local_day;

CREATE VIEW learning_attempt_summary AS
SELECT user_id,local_day,category,count(*) AS submitted_attempts,
  sum(total_count) AS question_count,sum(correct_count) AS correct_count,
  sum(total_count-answered_count) AS blank_count
FROM attempts WHERE status='SUBMITTED' GROUP BY user_id,local_day,category;

CREATE VIEW media_quota_usage AS
SELECT id,scope,owner_user_id,quota_bytes,reserved_bytes,used_bytes,
  GREATEST(0,quota_bytes-reserved_bytes-used_bytes) AS available_bytes
FROM storage_pools;
