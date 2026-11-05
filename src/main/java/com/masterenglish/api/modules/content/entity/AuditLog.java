package com.masterenglish.api.modules.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_type", nullable = false, length = 8)
    private String actorType;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @ColumnTransformer(read = "host(ip_address)", write = "?::inet")
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AuditLog(
            String actorType,
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            String metadata,
            String reason) {
        this.id = UUID.randomUUID();
        this.actorType = actorType != null ? actorType : "USER";
        this.actorUserId = actorUserId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.metadata = metadata;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void onPersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
