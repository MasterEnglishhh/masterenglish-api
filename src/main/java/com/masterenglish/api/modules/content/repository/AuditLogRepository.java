package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @org.springframework.data.jpa.repository.Query(
            value = """
            SELECT a FROM AuditLog a
            WHERE (:entityType IS NULL OR a.entityType = :entityType)
              AND (:search IS NULL OR LOWER(a.action) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(COALESCE(a.reason, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            """,
            countQuery = """
            SELECT count(a) FROM AuditLog a
            WHERE (:entityType IS NULL OR a.entityType = :entityType)
              AND (:search IS NULL OR LOWER(a.action) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(COALESCE(a.reason, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    org.springframework.data.domain.Page<AuditLog> findAuditLogs(
            @org.springframework.data.repository.query.Param("search") String search,
            @org.springframework.data.repository.query.Param("entityType") String entityType,
            org.springframework.data.domain.Pageable pageable);
}
