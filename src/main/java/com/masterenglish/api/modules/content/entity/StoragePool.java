package com.masterenglish.api.modules.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "storage_pools")
public class StoragePool {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 12)
    private String scope;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "quota_bytes", nullable = false)
    private Long quotaBytes;

    @Column(name = "reserved_bytes", nullable = false)
    private Long reservedBytes;

    @Column(name = "used_bytes", nullable = false)
    private Long usedBytes;

    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
