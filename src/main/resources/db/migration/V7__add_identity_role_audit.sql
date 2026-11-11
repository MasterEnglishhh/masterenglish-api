ALTER TABLE user_roles ADD COLUMN assigned_by UUID REFERENCES users(id);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_type VARCHAR(8) NOT NULL,
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID,
    metadata JSONB,
    reason TEXT,
    request_id VARCHAR(100),
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('USER','SYSTEM')),
    CONSTRAINT ck_audit_actor_presence CHECK ((actor_type='USER') = (actor_user_id IS NOT NULL)),
    CONSTRAINT ck_audit_metadata CHECK (metadata IS NULL OR
        (jsonb_typeof(metadata) = 'object' AND octet_length(metadata::text) <= 1048576))
);

CREATE INDEX ix_audit_actor ON audit_logs(actor_user_id, created_at DESC);
CREATE INDEX ix_audit_entity ON audit_logs(entity_type, entity_id, created_at DESC);

-- Initialization is an explicit operation by the database owner.
CREATE FUNCTION bootstrap_system_admin(target_user UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    system_role SMALLINT;
    previous_roles JSONB;
    changed_at TIMESTAMPTZ;
BEGIN
    SELECT id INTO STRICT system_role FROM roles WHERE code = 'SYSTEM_ADMIN' FOR UPDATE;
    IF EXISTS (SELECT 1 FROM user_roles WHERE role_id = system_role) THEN
        RAISE EXCEPTION 'The system administrator role has already been initialized'
            USING ERRCODE = '23514';
    END IF;
    PERFORM 1 FROM users
    WHERE id = target_user AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'The initial system administrator must be active and verified'
            USING ERRCODE = '23514';
    END IF;

    changed_at := clock_timestamp();
    SELECT coalesce(jsonb_agg(r.code ORDER BY r.code), '[]'::jsonb) INTO previous_roles
    FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = target_user;
    INSERT INTO user_roles(user_id, role_id, assigned_at) VALUES (target_user, system_role, changed_at);
    UPDATE users SET auth_version = auth_version + 1, version = version + 1, updated_at = changed_at
    WHERE id = target_user;
    UPDATE otp_challenges SET invalidated_at = changed_at, otp_hmac = NULL
    WHERE user_id = target_user AND purpose = 'PASSWORD_CHANGE' AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE password_change_requests SET invalidated_at = changed_at, pending_password_hash = NULL
    WHERE user_id = target_user AND completed_at IS NULL AND invalidated_at IS NULL;
    UPDATE auth_sessions SET revoked_at = changed_at, revoke_reason = 'ROLE_CHANGE'
    WHERE user_id = target_user AND revoked_at IS NULL;
    UPDATE refresh_tokens SET revoked_at = changed_at
    WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id = target_user) AND revoked_at IS NULL;
    INSERT INTO audit_logs(id, actor_type, action, entity_type, entity_id, metadata, created_at)
    VALUES (gen_random_uuid(), 'SYSTEM', 'ROLE_BOOTSTRAP', 'USER', target_user,
            jsonb_build_object('before', previous_roles, 'after', previous_roles || '"SYSTEM_ADMIN"'::jsonb),
            changed_at);
END;
$$;

REVOKE EXECUTE ON FUNCTION bootstrap_system_admin(UUID) FROM PUBLIC;
