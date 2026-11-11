package com.masterenglish.api.modules.identity.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RoleAuditStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public RoleAuditStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void recordChange(
            UUID actor,
            UUID target,
            Set<String> before,
            Set<String> after,
            String requestId,
            String ip,
            Instant now) {
        String metadata =
                json.writeValueAsString(
                        Map.of(
                                "before", new TreeSet<>(before),
                                "after", new TreeSet<>(after)));
        jdbc.update(
                """
                INSERT INTO audit_logs(id,actor_type,actor_user_id,action,entity_type,
                    entity_id,metadata,request_id,ip_address,created_at)
                VALUES (?,'USER',?,'ROLE_CHANGE','USER',?,CAST(? AS jsonb),?,CAST(? AS inet),?)
                """,
                UUID.randomUUID(),
                actor,
                target,
                metadata,
                requestId,
                ip,
                Timestamp.from(now));
    }
}
