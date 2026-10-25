-- Synthetic data for the labelled, network-isolated review database ONLY.
-- The bcrypt-shaped fixture is deliberately not a usable login credential.
CREATE SCHEMA review_checks;

CREATE FUNCTION review_checks.expect_error(p_sql TEXT,p_state TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
DECLARE v_state TEXT;
BEGIN
  BEGIN
    EXECUTE p_sql;
  EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS v_state=RETURNED_SQLSTATE;
    IF v_state=p_state THEN RETURN TRUE; END IF;
    RAISE EXCEPTION 'expected SQLSTATE %, got % for %',p_state,v_state,p_sql;
  END;
  RAISE EXCEPTION 'statement unexpectedly accepted: %',p_sql;
END $$;

CREATE FUNCTION review_checks.user_row(p_email TEXT,p_roles SMALLINT[] DEFAULT ARRAY[1]::SMALLINT[])
RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE v_id UUID:=gen_random_uuid(); v_role SMALLINT;
BEGIN
  INSERT INTO users(id,email,first_name,last_name,password_hash,status,email_verified_at,password_changed_at)
    VALUES(v_id,p_email,'Test','User','$2b$12$'||repeat('.',53),'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
  FOREACH v_role IN ARRAY p_roles LOOP INSERT INTO user_roles(user_id,role_id) VALUES(v_id,v_role); END LOOP;
  RETURN v_id;
END $$;

CREATE FUNCTION review_checks.prepare_item(p_kind TEXT,p_teacher UUID,p_source UUID)
RETURNS UUID[] LANGUAGE plpgsql AS $$
DECLARE v_item UUID:=gen_random_uuid();v_revision UUID:=gen_random_uuid();
BEGIN
  INSERT INTO content_items(id,content_type,created_by) VALUES(v_item,p_kind,p_teacher);
  INSERT INTO content_revisions(id,content_item_id,version_no,snapshot,created_by)
    VALUES(v_revision,v_item,1,jsonb_build_object('schema_version',1,'content_type',p_kind,'data','{}'::JSONB),p_teacher);
  INSERT INTO content_source_links(revision_id,source_id,source_snapshot)
    VALUES(v_revision,p_source,'{"schema_version":1,"title":"Synthetic self-authored fixture","license_code":"TEST_DECLARATION"}');
  RETURN ARRAY[v_item,v_revision];
END $$;

CREATE FUNCTION review_checks.publish(p_item UUID,p_teacher UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE v_revision UUID;
BEGIN
  SELECT id INTO v_revision FROM content_revisions WHERE content_item_id=p_item AND status='DRAFT';
  UPDATE content_revisions SET status='IN_REVIEW' WHERE id=v_revision;
  UPDATE content_revisions SET status='APPROVED',reviewed_by=p_teacher,reviewed_at=CURRENT_TIMESTAMP WHERE id=v_revision;
  UPDATE content_revisions SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP WHERE id=v_revision;
  UPDATE content_items SET published_revision_id=v_revision WHERE id=p_item;
END $$;

CREATE FUNCTION review_checks.topic(p_teacher UUID,p_source UUID,p_slug TEXT) RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE v_ids UUID[];
BEGIN
  v_ids:=review_checks.prepare_item('GRAMMAR_TOPIC',p_teacher,p_source);
  INSERT INTO grammar_topics(id,published_revision_id,slug,name) VALUES(v_ids[1],v_ids[2],p_slug,p_slug);
  PERFORM review_checks.publish(v_ids[1],p_teacher);
  RETURN jsonb_build_object('id',v_ids[1],'revision',v_ids[2]);
END $$;

CREATE FUNCTION review_checks.question(p_teacher UUID,p_source UUID,p_category TEXT DEFAULT 'GRAMMAR',
    p_part SMALLINT DEFAULT NULL,p_group UUID DEFAULT NULL,p_position SMALLINT DEFAULT NULL,p_blank TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE v_ids UUID[];v_option UUID;v_correct UUID;v_wrong UUID;v_i INTEGER;v_count INTEGER;
BEGIN
  v_ids:=review_checks.prepare_item('QUESTION',p_teacher,p_source);
  INSERT INTO questions(id,published_revision_id,category,part_number,prompt,explanation,group_id,group_position,blank_key)
    VALUES(v_ids[1],v_ids[2],p_category,p_part,'Choose one','A synthetic explanation',p_group,p_position,p_blank);
  v_count:=CASE WHEN p_category='TOEIC' AND p_part=2 THEN 3 ELSE 4 END;
  FOR v_i IN 1..v_count LOOP
    v_option:=gen_random_uuid();
    INSERT INTO question_options(id,question_id,position,body,is_correct)
      VALUES(v_option,v_ids[1],v_i,'Choice '||v_i,v_i=1);
    IF v_i=1 THEN v_correct:=v_option; ELSIF v_i=2 THEN v_wrong:=v_option; END IF;
  END LOOP;
  PERFORM review_checks.publish(v_ids[1],p_teacher);
  RETURN jsonb_build_object('id',v_ids[1],'revision',v_ids[2],'correct',v_correct,'wrong',v_wrong);
END $$;

CREATE FUNCTION review_checks.attempt(p_user UUID,p_question JSONB,p_topic JSONB,
    p_request UUID DEFAULT gen_random_uuid(),p_hash TEXT DEFAULT repeat('a',64),
    p_started TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP-INTERVAL '5 minutes',p_deadline TIMESTAMPTZ DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE v_id UUID:=gen_random_uuid();v_snapshot UUID:=gen_random_uuid();v_existing UUID;v_option RECORD;
BEGIN
  PERFORM 1 FROM users WHERE id=p_user FOR UPDATE;
  SELECT id INTO v_existing FROM attempts WHERE user_id=p_user AND request_id=p_request;
  IF v_existing IS NOT NULL THEN
    IF NOT EXISTS(SELECT 1 FROM attempts WHERE id=v_existing AND request_hash=p_hash) THEN
      RAISE EXCEPTION 'idempotency payload mismatch' USING ERRCODE='23514'; END IF;
    RETURN jsonb_build_object('id',v_existing,'snapshot',(SELECT id FROM attempt_question_snapshots WHERE attempt_id=v_existing LIMIT 1));
  END IF;
  INSERT INTO attempts(id,user_id,category,source_type,source_topic_id,source_revision_id,source_config,
    request_id,request_hash,total_count,started_at,deadline_at)
    VALUES(v_id,p_user,'GRAMMAR','GRAMMAR_TOPIC',(p_topic->>'id')::UUID,(p_topic->>'revision')::UUID,
      '{"schema_version":1}',p_request,p_hash,1,p_started,p_deadline);
  INSERT INTO attempt_question_snapshots(id,attempt_id,question_id,source_revision_id,category,ordinal,
    question_type_code,question_snapshot,answer_key_snapshot,explanation_snapshot)
    VALUES(v_snapshot,v_id,(p_question->>'id')::UUID,(p_question->>'revision')::UUID,'GRAMMAR',1,'SINGLE_CHOICE',
      '{"schema_version":1,"prompt":"Choose one"}',jsonb_build_object('correctOptionId',p_question->>'correct'),
      '{"text":"Synthetic explanation"}');
  FOR v_option IN SELECT * FROM question_options WHERE question_id=(p_question->>'id')::UUID LOOP
    INSERT INTO attempt_snapshot_options(snapshot_id,option_id,position,public_body)
      VALUES(v_snapshot,v_option.id,v_option.position,v_option.body);
  END LOOP;
  INSERT INTO attempt_answers(id,attempt_id,snapshot_id) VALUES(gen_random_uuid(),v_id,v_snapshot);
  INSERT INTO attempt_media_refs(attempt_id,media_id,usage_role)
    SELECT v_id,media_id,usage_role FROM revision_media_refs WHERE revision_id=(p_question->>'revision')::UUID;
  PERFORM me_seal_attempt(v_id,p_user);
  RETURN jsonb_build_object('id',v_id,'snapshot',v_snapshot);
END $$;

BEGIN;
DO $$
DECLARE a UUID;b UUID;t UUID;s UUID;src UUID;topic JSONB;q1 JSONB;q2 JSONB;
  aa JSONB;ab JSONB;m UUID;session_id UUID;otp UUID;
BEGIN
  a:=review_checks.user_row('review-learner-a@example.invalid');
  b:=review_checks.user_row('review-learner-b@example.invalid');
  t:=review_checks.user_row('review-teacher@example.invalid',ARRAY[1,2]::SMALLINT[]);
  s:=review_checks.user_row('review-admin@example.invalid',ARRAY[3]::SMALLINT[]);
  src:=gen_random_uuid();
  INSERT INTO content_sources(id,title,source_type,author,license_code,created_by)
    VALUES(src,'Synthetic fixture','SELF_AUTHORED','Review test fixture','TEST_DECLARATION',t);
  topic:=review_checks.topic(t,src,'fixture-topic');
  q1:=review_checks.question(t,src);q2:=review_checks.question(t,src);
  INSERT INTO question_topics(question_id,topic_id)
    VALUES((q1->>'id')::UUID,(topic->>'id')::UUID),((q2->>'id')::UUID,(topic->>'id')::UUID);
  aa:=review_checks.attempt(a,q1,topic);ab:=review_checks.attempt(a,q2,topic);
  PERFORM me_save_answer((ab->>'id')::UUID,a,(ab->>'snapshot')::UUID,(q2->>'correct')::UUID,0);
  m:=gen_random_uuid();
  INSERT INTO media_assets(id,media_type,purpose,original_filename,uploaded_by)
    VALUES(m,'IMAGE','AVATAR','fixture.png',b);
  session_id:=gen_random_uuid();
  INSERT INTO auth_sessions(id,user_id,expires_at) VALUES(session_id,a,CURRENT_TIMESTAMP+INTERVAL '7 days');
  INSERT INTO refresh_tokens(id,session_id,token_hash,expires_at)
    VALUES(gen_random_uuid(),session_id,repeat('1',64),CURRENT_TIMESTAMP+INTERVAL '7 days');
  otp:=gen_random_uuid();
  INSERT INTO otp_challenges(id,user_id,purpose,otp_hmac,expires_at)
    VALUES(otp,a,'PASSWORD_RESET',repeat('2',64),CURRENT_TIMESTAMP+INTERVAL '5 minutes');
  CREATE TABLE review_checks.fixture(data JSONB NOT NULL);
  INSERT INTO review_checks.fixture VALUES(jsonb_build_object('a',a,'b',b,'teacher',t,'admin',s,'source',src,
    'topic',topic,'q1',q1,'q2',q2,'attempt1',aa,'attempt2',ab,'media',m,'session',session_id,'otp',otp));
END $$;
COMMIT;
