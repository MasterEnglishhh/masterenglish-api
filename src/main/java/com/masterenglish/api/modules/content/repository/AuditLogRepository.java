package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {}
