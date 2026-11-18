package com.masterenglish.api.modules.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @Column(nullable = false, length = 64)
    private String key;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public FeatureFlag(
            String key,
            String name,
            String description,
            boolean enabled,
            String category,
            UUID updatedBy) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.category = category;
        this.updatedBy = updatedBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateState(boolean enabled, UUID actorId) {
        this.enabled = enabled;
        this.updatedBy = actorId;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
