-- Run explicitly as the database operator, with psql -v account_email=... .
-- Adds CONTENT_ADMIN to one verified ACTIVE account; never removes LEARNER.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('app.content_import.account_email', lower(btrim(:'account_email')), true);
DO $$
DECLARE
    target_user users%ROWTYPE;
    content_role_id SMALLINT;
    inserted_count INTEGER;
BEGIN
    SELECT * INTO target_user FROM users
    WHERE email = current_setting('app.content_import.account_email') FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Account not found; register it on the online website first.'; END IF;
    IF target_user.status <> 'ACTIVE' OR target_user.email_verified_at IS NULL THEN
        RAISE EXCEPTION 'Only an ACTIVE, email-verified account can be granted this role.';
    END IF;
    SELECT id INTO STRICT content_role_id FROM roles WHERE code = 'CONTENT_ADMIN';
    INSERT INTO user_roles(user_id, role_id) VALUES (target_user.id, content_role_id)
    ON CONFLICT (user_id, role_id) DO NOTHING;
    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    IF inserted_count = 1 THEN
        UPDATE users SET auth_version = auth_version + 1, version = version + 1,
            updated_at = CURRENT_TIMESTAMP WHERE id = target_user.id;
        UPDATE auth_sessions SET revoked_at = CURRENT_TIMESTAMP, revoke_reason = 'ROLE_CHANGE'
            WHERE user_id = target_user.id AND revoked_at IS NULL;
        INSERT INTO audit_logs(id, actor_type, action, entity_type, entity_id, metadata, reason)
        VALUES (gen_random_uuid(), 'SYSTEM', 'GRANT_CONTENT_ADMIN', 'USER', target_user.id,
            jsonb_build_object('role', 'CONTENT_ADMIN', 'via', 'operator-content-import'),
            'Explicit operator grant for vocabulary content import');
    END IF;
END $$;
SELECT u.id, u.email, r.code FROM users u
JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
WHERE u.email = current_setting('app.content_import.account_email') ORDER BY r.code;
COMMIT;
