"""Integration checks, ONLY on the labelled network-isolated review container.

No production connection string is accepted. --reset affects approved_review only.
Run the artifact generator first. Python stdlib + Docker CLI are sufficient.
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = 'me-schema-20261025-01a16da4'
LABEL = '01a16da4-503d-7143-bd11-8cac51f6110d'
DATABASE = 'approved_review'
RESULTS = []


def docker(*args, input=None):
    return subprocess.run(['docker', *args], input=input, text=True, encoding='utf-8',
                          capture_output=True, timeout=50)


def sql(statement, *, fail=False):
    result = docker('exec', '-i', CONTAINER, 'psql', '-X', '-Atq', '-U', 'postgres',
                    '-d', DATABASE, '-v', 'ON_ERROR_STOP=1', input=statement)
    if result.returncode and not fail:
        raise AssertionError(result.stderr.strip())
    return result


def check(name, action):
    try:
        action()
        RESULTS.append(dict(name=name, status='PASS'))
        print('PASS', name, flush=True)
    except Exception as exc:
        RESULTS.append(dict(name=name, status='FAIL', detail=str(exc)))
        print('FAIL', name, str(exc)[:350], flush=True)


def truth(statement):
    result = sql('BEGIN;\n' + statement + '\nSET CONSTRAINTS ALL IMMEDIATE; ROLLBACK;')
    assert result.stdout.strip().splitlines()[-1:] == ['t'], result.stdout


def expect(statement, state='23514'):
    result = sql('\\set VERBOSITY verbose\nBEGIN;\n' + statement + '\nSET CONSTRAINTS ALL IMMEDIATE; ROLLBACK;', fail=True)
    assert result.returncode and state in result.stderr, result.stderr or result.stdout


def equal(query, value):
    assert sql(query).stdout.strip() == value


def race(statements, expected):
    gate = threading.Barrier(len(statements))
    def run(statement):
        gate.wait(timeout=10)
        return sql(statement, fail=True)
    with ThreadPoolExecutor(max_workers=len(statements)) as executor:
        results = list(executor.map(run, statements))
    actual = sorted((r.stdout.strip() if r.returncode == 0 else 'ERROR:23514' if '23514' in r.stderr else r.stderr.strip()) for r in results)
    assert actual == sorted(expected), actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reset', action='store_true')
    args = parser.parse_args()
    inspected = docker('inspect', CONTAINER)
    assert inspected.returncode == 0, inspected.stderr
    info = json.loads(inspected.stdout)[0]
    assert info['Config']['Labels'].get('com.masterenglish.schema-review') == LABEL
    assert info['HostConfig']['NetworkMode'] == 'none'
    assert not info['HostConfig'].get('Binds') and not info['HostConfig'].get('PortBindings')
    if args.reset:
        for command in ('dropdb', 'createdb'):
            result = docker('exec', CONTAINER, command, '-U', 'postgres', DATABASE)
            assert result.returncode == 0, result.stderr
        sql((ROOT / 'proposals/approved-baseline.sql').read_text(encoding='utf-8'))
        sql((ROOT / 'checks/fixtures.sql').read_text(encoding='utf-8'))
    f = json.loads(sql('SELECT data FROM review_checks.fixture;').stdout)
    def uid(value): return "'" + value + "'::UUID"
    a,b,t,admin = [uid(f[key]) for key in ('a','b','teacher','admin')]
    topic = "'" + json.dumps(f['topic']) + "'::JSONB"
    q1 = "'" + json.dumps(f['q1']) + "'::JSONB"
    q2 = "'" + json.dumps(f['q2']) + "'::JSONB"
    at1,at2 = [uid(f[k]['id']) for k in ('attempt1','attempt2')]
    sn1,sn2 = [uid(f[k]['snapshot']) for k in ('attempt1','attempt2')]
    correct,wrong = uid(f['q1']['correct']),uid(f['q1']['wrong'])
    qid,rev = uid(f['q1']['id']),uid(f['q1']['revision'])
    src,media,session,otp = [uid(f[k]) for k in ('source','media','session','otp')]
    make = f'review_checks.attempt({b},{q1},{topic})'
    check('schema: 62 tables, 4 reporting views', lambda: equal("SELECT (SELECT count(*) FROM pg_tables WHERE schemaname='public')=62 AND (SELECT count(*) FROM pg_views WHERE schemaname='public')=4;", 't'))
    check('identity: case-insensitive email uniqueness', lambda: expect("SELECT review_checks.user_row('REVIEW-LEARNER-A@example.invalid');", '23505'))
    check('identity: immutable email', lambda: expect(f"UPDATE users SET email='changed@example.invalid' WHERE id={a};"))
    check('identity: first name max 30 characters', lambda: expect(f"UPDATE users SET first_name=repeat('x',31) WHERE id={a};", '22001'))
    check('identity: reject plaintext password', lambda: expect(f"UPDATE users SET password_hash='plaintext' WHERE id={a};"))
    check('identity: every user requires a role at commit', lambda: expect("INSERT INTO users(id,email) VALUES(gen_random_uuid(),'no-role@example.invalid');"))
    check('identity: independent multiple roles accepted', lambda: truth(f"SELECT count(*)=2 FROM user_roles WHERE user_id={t};"))
    check('identity: protect last active system admin', lambda: expect(f"UPDATE users SET status='DISABLED' WHERE id={admin};"))
    check('identity: security change revokes sessions and increments version', lambda: truth(f"UPDATE users SET status='LOCKED',locked_until=CURRENT_TIMESTAMP+INTERVAL '15 minutes' WHERE id={a}; SELECT u.auth_version>0 AND s.revoked_at IS NOT NULL AND r.revoked_at IS NOT NULL FROM users u JOIN auth_sessions s ON s.user_id=u.id JOIN refresh_tokens r ON r.session_id=s.id WHERE s.id={session};"))
    check('identity: session deadline cannot slide', lambda: expect(f"UPDATE auth_sessions SET expires_at=expires_at+INTERVAL '1 second' WHERE id={session};"))
    check('identity: sixth login replaces oldest session', lambda: truth(f"DO $$ BEGIN FOR i IN 1..6 LOOP INSERT INTO auth_sessions(id,user_id,expires_at) VALUES(gen_random_uuid(),{b},CURRENT_TIMESTAMP+INTERVAL '7 days'); END LOOP; END $$; SELECT count(*)=5 FROM auth_sessions WHERE user_id={b} AND revoked_at IS NULL;"))
    check('identity: OTP cannot cross purpose', lambda: truth(f"SELECT NOT me_consume_otp({otp},{a},'PASSWORD_CHANGE',repeat('2',64));"))
    check('identity: OTP cannot cross owner', lambda: truth(f"SELECT NOT me_consume_otp({otp},{b},'PASSWORD_RESET',repeat('2',64));"))
    check('identity: locked user cannot reset by OTP', lambda: truth(f"UPDATE users SET status='LOCKED',locked_until=CURRENT_TIMESTAMP+INTERVAL '15 minutes' WHERE id={a}; SELECT NOT me_consume_otp({otp},{a},'PASSWORD_RESET',repeat('2',64));"))
    check('content: Published revision is immutable', lambda: expect(f"UPDATE content_revisions SET change_summary='changed' WHERE id={rev};", '55000'))
    check('content: Published source snapshot is retained', lambda: expect(f"DELETE FROM content_source_links WHERE revision_id={rev};", '55000'))
    check('content: pointer cannot refer to another item', lambda: expect(f"UPDATE content_items SET published_revision_id={uid(f['q2']['revision'])} WHERE id={qid};", '23503'))
    check('content: archive stops selection and retains history', lambda: truth(f"UPDATE content_items SET is_archived=TRUE,archived_at=CURRENT_TIMESTAMP WHERE id={qid}; SELECT NOT me_revision_available({rev}) AND EXISTS(SELECT 1 FROM attempt_question_snapshots WHERE question_id={qid});"))
    check('content: only one open draft branch', lambda: expect(f"INSERT INTO content_revisions(id,content_item_id,version_no,created_by) VALUES(gen_random_uuid(),{qid},2,{t}),(gen_random_uuid(),{qid},3,{t});", '23505'))
    check('questions: exactly one correct choice', lambda: expect(f"UPDATE question_options SET is_correct=FALSE WHERE question_id={qid};"))
    check('questions: two correct choices rejected', lambda: expect(f"UPDATE question_options SET is_correct=TRUE WHERE id={wrong};", '23505'))
    check('questions: semantic context cannot change in place', lambda: expect(f"UPDATE questions SET category='VOCABULARY' WHERE id={qid};"))
    check('questions: TOEIC Part 2 accepts exactly three options', lambda: truth(f"DO $$ DECLARE x JSONB; BEGIN x:=review_checks.question({t},{src},'TOEIC',2::SMALLINT); IF (SELECT count(*) FROM question_options WHERE question_id=(x->>'id')::UUID)<>3 THEN RAISE EXCEPTION 'choice count'; END IF; END $$; SELECT TRUE;"))
    check('questions: TOEIC Part 2 rejects a fourth option', lambda: expect(f"DO $$ DECLARE x JSONB; BEGIN x:=review_checks.question({t},{src},'TOEIC',2::SMALLINT); INSERT INTO question_options(id,question_id,position,body) VALUES(gen_random_uuid(),(x->>'id')::UUID,4,'extra'); END $$;"))
    check('groups: incomplete TOEIC Part 3 group rejected at commit', lambda: expect(f"DO $$ DECLARE ids UUID[]; BEGIN ids:=review_checks.prepare_item('QUESTION_GROUP',{t},{src}); INSERT INTO question_groups(id,published_revision_id,title,category,part_number,group_type) VALUES(ids[1],ids[2],'Incomplete','TOEIC',3,'CONVERSATION'); END $$;"))
    check('attempts: owner authorization', lambda: expect(f"SELECT me_save_answer({at1},{b},{sn1},{correct},0);", '42501'))
    check('attempts: choice must belong to own snapshot', lambda: expect(f"SELECT me_save_answer({at1},{a},{sn1},{uid(f['q2']['correct'])},0);", '23503'))
    check('attempts: sealed snapshot is immutable', lambda: expect(f"UPDATE attempt_question_snapshots SET question_snapshot='{{}}' WHERE id={sn1};", '55000'))
    check('attempts: snapshot options retained after projection edit', lambda: truth(f"UPDATE question_options SET body='new current text' WHERE question_id={qid}; SELECT bool_and(public_body<>'new current text') FROM attempt_snapshot_options WHERE snapshot_id={sn1};"))
    check('attempts: clear answer increments version', lambda: truth(f"SELECT me_save_answer({at1},{a},{sn1},{correct},0); SELECT me_save_answer({at1},{a},{sn1},NULL,1); SELECT selected_option_id IS NULL AND version=2 FROM attempt_answers WHERE snapshot_id={sn1};"))
    request = "'10000000-0000-0000-0000-000000000001'::UUID"
    start = f'review_checks.attempt({b},{q1},{topic},{request})'
    check('attempts: same start request returns same ID', lambda: truth(f"SELECT ({start})->>'id'=({start})->>'id';"))
    check('attempts: request payload mismatch is rejected', lambda: expect(f"SELECT {start}; SELECT review_checks.attempt({b},{q1},{topic},{request},repeat('b',64));"))
    check('attempts: sixth open attempt rejected', lambda: expect(f"SELECT {make} FROM generate_series(1,6);"))
    check('attempts: blank submitted answer counts as wrong', lambda: truth(f"SELECT me_submit_attempt({at1},{a}); SELECT status='SUBMITTED' AND correct_count=0 AND answered_count=0 FROM attempts WHERE id={at1};"))
    check('attempts: correct answer scores one', lambda: truth(f"SELECT me_submit_attempt({at2},{a}); SELECT correct_count=1 AND score=1 FROM attempts WHERE id={at2};"))
    check('attempts: answer cannot change after submit', lambda: truth(f"SELECT me_submit_attempt({at1},{a}); SELECT NOT me_save_answer({at1},{a},{sn1},{correct},0);"))
    check('attempts: result remains immutable', lambda: expect(f"SELECT me_submit_attempt({at1},{a}); UPDATE attempts SET correct_count=1 WHERE id={at1};", '55000'))
    check('attempts: deadline auto-grades before accepting late answer', lambda: truth(f"DO $$ DECLARE x JSONB; BEGIN x:=review_checks.attempt({b},{q1},{topic},gen_random_uuid(),repeat('a',64),CURRENT_TIMESTAMP-INTERVAL '5 minutes',CURRENT_TIMESTAMP-INTERVAL '1 minute'); IF me_save_answer((x->>'id')::UUID,{b},(x->>'snapshot')::UUID,{correct},0) THEN RAISE EXCEPTION 'late answer accepted'; END IF; IF NOT EXISTS(SELECT 1 FROM attempts WHERE id=(x->>'id')::UUID AND status='SUBMITTED' AND submit_reason='TIME_LIMIT' AND correct_count=0 AND duration_seconds=240) THEN RAISE EXCEPTION 'wrong timed result'; END IF; END $$; SELECT TRUE;"))
    check('mistakes: blank result creates OPEN record', lambda: truth(f"SELECT me_submit_attempt({at1},{a}); SELECT state='OPEN' AND wrong_count=1 AND consecutive_correct=0 FROM mistake_records WHERE user_id={a} AND question_id={qid};"))
    check('media: reject unverified avatar in profile', lambda: expect(f"UPDATE users SET avatar_media_id={media} WHERE id={b};"))
    check('media: verified metadata requires positive dimensions', lambda: expect(f"UPDATE media_assets SET status='READY',bucket='fixture',object_key='a',mime_type='image/png',file_size_bytes=10,checksum_sha256=repeat('a',64) WHERE id={media};"))
    ready = f"UPDATE media_assets SET status='READY',bucket='fixture',object_key='a',mime_type='image/png',file_size_bytes=10,checksum_sha256=repeat('a',64),width=1,height=1 WHERE id={media};"
    check('media: cannot use another user avatar', lambda: expect(ready+f" UPDATE users SET avatar_media_id={media} WHERE id={a};"))
    check('media: confirmed object cannot change bytes', lambda: expect(ready+f" UPDATE media_assets SET object_key='overwrite' WHERE id={media};", '55000'))
    check('media: cannot archive current avatar', lambda: expect(ready+f" UPDATE users SET avatar_media_id={media} WHERE id={b}; UPDATE media_assets SET status='ARCHIVED',archived_at=CURRENT_TIMESTAMP WHERE id={media};"))
    check('media: avatar size hard cap 2 MiB', lambda: expect(f"UPDATE media_assets SET file_size_bytes=2097153 WHERE id={media};"))
    check('storage: ledger keeps reserved then committed bytes', lambda: truth(f"INSERT INTO storage_allocations(id,pool_id,allocation_key,media_id,bytes) VALUES(gen_random_uuid(),'20000000-0000-0000-0000-000000000001','fixture-ledger',{media},60); UPDATE storage_allocations SET state='COMMITTED',bucket='fixture',object_key='a',committed_at=CURRENT_TIMESTAMP WHERE allocation_key='fixture-ledger'; SELECT used_bytes=60 AND reserved_bytes=0 FROM storage_pools WHERE scope='CONTENT';"))
    check('storage: allocation cannot be deleted to bypass quota', lambda: expect(f"INSERT INTO storage_allocations(id,pool_id,allocation_key,media_id,bytes) VALUES(gen_random_uuid(),'20000000-0000-0000-0000-000000000001','fixture-ledger',{media},60); DELETE FROM storage_allocations WHERE allocation_key='fixture-ledger';"))
    check('goals: one target, score step five and daily limits', lambda: expect(f"INSERT INTO learning_goals(user_id,target_score,daily_minutes) VALUES({a},731,30);"))
    study = f"INSERT INTO study_sessions(id,user_id,source_type,attempt_id,timezone,local_day,credit_token,started_at,last_credited_at) VALUES(gen_random_uuid(),{a},'PRACTICE',{at1},'Asia/Ho_Chi_Minh',CURRENT_DATE,gen_random_uuid(),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);"
    check('study: one active timeline across tabs', lambda: expect(study+study,'23505'))
    check('study: cannot credit more time than elapsed', lambda: expect(study+f" UPDATE study_sessions SET active_seconds=1 WHERE user_id={a};"))
    check('study: context cannot refer to another learner attempt', lambda: expect(study.replace(f",{a},'PRACTICE'",f",{b},'PRACTICE'"),'23503'))
    check('notifications: internal target cannot be external URL', lambda: expect(f"INSERT INTO notifications(id,user_id,type,title,body,target_url,dedupe_key) VALUES(gen_random_uuid(),{a},'SECURITY','Test','Test','//external.invalid','test');"))
    notification = f"INSERT INTO notifications(id,user_id,type,title,body,dedupe_key) VALUES('10000000-0000-0000-0000-000000000011',{a},'SYSTEM','Test','Test','fixture-notification');"
    check('notifications: event dedupe is scoped to user and key', lambda: expect(notification+notification,'23505'))
    check('notifications: one delivery per event and channel', lambda: expect(notification+" INSERT INTO notification_deliveries(id,notification_id,channel) VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000011','IN_APP'),(gen_random_uuid(),'10000000-0000-0000-0000-000000000011','IN_APP');",'23505'))
    check('notifications: encrypted payload must name external key', lambda: expect(notification+" INSERT INTO notification_deliveries(id,notification_id,channel,recipient_address,encrypted_payload) VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000011','EMAIL','fake@example.invalid',decode('00','hex'));"))
    job = f"INSERT INTO import_jobs(id,created_by,request_id,request_hash,import_type,import_namespace,original_filename,input_bucket,input_object_key,declared_size_bytes,upload_expires_at) VALUES('10000000-0000-0000-0000-000000000012',{t},gen_random_uuid(),repeat('a',64),'QUESTIONS_JSON','fixture','q.json','private','import/fixture',1,CURRENT_TIMESTAMP+INTERVAL '10 minutes');"
    check('import: file cap 5 MiB', lambda: expect(job+" UPDATE import_jobs SET declared_size_bytes=5242881 WHERE id='10000000-0000-0000-0000-000000000012';"))
    check('import: row cap 5000 items', lambda: expect(job+" UPDATE import_jobs SET total_rows=5001 WHERE id='10000000-0000-0000-0000-000000000012';"))
    row = "INSERT INTO import_job_rows(job_id,row_number,input_hash,payload,status) VALUES('10000000-0000-0000-0000-000000000012',1,repeat('a',64),'{}','VALID');"
    check('import: unique row ledger prevents duplicate retry', lambda: expect(job+row+row,'23505'))
    check('settings: unknown keys cannot become secret store', lambda: expect("INSERT INTO system_settings(key,value,value_type) VALUES('smtp.password','\"secret\"','STRING');"))
    check('settings: BCrypt cost cannot drop below ten', lambda: expect("UPDATE system_settings SET value='9' WHERE key='auth.bcrypt_cost';"))
    check('audit: append-only record', lambda: expect(f"INSERT INTO audit_logs(id,actor_type,action,entity_type) VALUES('10000000-0000-0000-0000-000000000002','SYSTEM','TEST','TEST'); UPDATE audit_logs SET action='HIDE' WHERE id='10000000-0000-0000-0000-000000000002';", '55000'))
    # Separate real connections commit their work. This tests row/advisory lock protocols.
    check('concurrency: OTP consumed exactly once', lambda: race([f"SELECT me_consume_otp({otp},{a},'PASSWORD_RESET',repeat('2',64));"]*2, ['t','f']))
    check('concurrency: refresh old token rotates once', lambda: race([f"SELECT me_rotate_refresh({session},repeat('1',64),gen_random_uuid(),repeat('{h}',64));" for h in ('3','4')], ['t','f']))
    check('concurrency: two tabs cannot overwrite same answer version', lambda: race([f"SELECT me_save_answer({at1},{a},{sn1},{option},0);" for option in (correct,wrong)], ['t','f']))
    check('concurrency: repeated submit is stable and idempotent', lambda: race([f"SELECT me_submit_attempt({at2},{a});"]*2, ['t','t']))
    pool = '10000000-0000-0000-0000-000000000003'
    sql(f"INSERT INTO storage_pools(id,scope,owner_user_id,quota_bytes) VALUES('{pool}','AVATAR',{b},100);")
    check('concurrency: quota reservation serializes, one of two 60-byte claims fits 100', lambda: race([f"\\set VERBOSITY verbose\nINSERT INTO storage_allocations(id,pool_id,allocation_key,media_id,bytes) VALUES(gen_random_uuid(),'{pool}','race-{i}',{media},60); SELECT TRUE;" for i in (1,2)], ['t','ERROR:23514']))
    check('concurrency: quota counter equals accepted reservations', lambda: equal(f"SELECT reserved_bytes=60 AND used_bytes=0 FROM storage_pools WHERE id='{pool}';", 't'))
    second_admin = uid(sql("SELECT review_checks.user_row('review-admin2@example.invalid',ARRAY[3]::SMALLINT[]);").stdout.strip())
    check('concurrency: cannot disable both final system admins', lambda: race([f"\\set VERBOSITY verbose\nUPDATE users SET status='DISABLED' WHERE id={who}; SELECT TRUE;" for who in (admin,second_admin)], ['t','ERROR:23514']))
    check('concurrency: at least one active system admin remains', lambda: equal("SELECT count(*)=1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.code='SYSTEM_ADMIN' AND u.status='ACTIVE';",'t'))
    passed = sum(r['status']=='PASS' for r in RESULTS)
    report = dict(checked_at=dt.datetime.now(dt.timezone.utc).isoformat(), engine=sql('SELECT version();').stdout.strip(),
                  database='isolated synthetic approved_review; no project DB access',
                  schema_sha256=hashlib.sha256((ROOT/'proposals/approved-baseline.sql').read_bytes()).hexdigest(),
                  passed=passed, failed=len(RESULTS)-passed, checks=RESULTS)
    (ROOT/'SCHEMA_VALIDATION.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    lines=['# Kiểm thử SQL tham chiếu đã duyệt','',f"Ngày UTC: {report['checked_at']}. Kết quả: **{passed}/{len(RESULTS)} đạt**.",'',
           f"Engine: {report['engine']}",'', 'Database cô lập, chỉ dữ liệu giả; container không có mạng, cổng công khai hay volume dự án.',
           'Không chạy SQL lên database đang dùng. Các ca cạnh tranh dùng hai kết nối PostgreSQL độc lập.', '',
           'Bộ kiểm thử chứng minh những ràng buộc/giao dịch được liệt kê dưới đây; chưa kiểm thử API, giao diện, SMTP, S3, kiểm tra nội dung file, SRS hoặc worker thực tế.', '',
           'Các hàm trong `review_checks` chỉ là fixture, không phải API sản phẩm. SHA-256 của SQL: `'+report['schema_sha256']+'`.','',
           '| Kiểm tra | Kết quả |','|---|---|']
    for row in RESULTS:
        lines.append(f"| {row['name']} | {row['status']} |")
        if row.get('detail'): lines.append('\nLỗi: `'+row['detail'].replace('`','').replace('\n',' ')+'`\n')
    (ROOT/'SCHEMA_VALIDATION.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(f'{passed}/{len(RESULTS)} checks passed',flush=True)
    return 0 if passed==len(RESULTS) else 1


if __name__=='__main__':
    sys.exit(main())
