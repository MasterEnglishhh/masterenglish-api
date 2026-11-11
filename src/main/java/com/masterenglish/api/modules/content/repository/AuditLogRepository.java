package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @org.springframework.data.jpa.repository.Query(
            value = """
            SELECT a FROM AuditLog a
            WHERE (:entityType IS NULL OR a.entityType = :entityType)
              AND (:searchPattern IS NULL OR LOWER(a.action) LIKE :searchPattern
                   OR LOWER(COALESCE(a.reason, '')) LIKE :searchPattern)
            """,
            countQuery = """
            SELECT count(a) FROM AuditLog a
            WHERE (:entityType IS NULL OR a.entityType = :entityType)
              AND (:searchPattern IS NULL OR LOWER(a.action) LIKE :searchPattern
                   OR LOWER(COALESCE(a.reason, '')) LIKE :searchPattern)
            """)
    org.springframework.data.domain.Page<AuditLog> findAuditLogs(
            @org.springframework.data.repository.query.Param("searchPattern") String searchPattern,
            @org.springframework.data.repository.query.Param("entityType") String entityType,
            org.springframework.data.domain.Pageable pageable);
}
