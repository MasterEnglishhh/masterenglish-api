package com.masterenglish.api.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "auth_rate_limits",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_auth_rate_limits_scope_subject",
                    columnNames = {"scope", "subject_hash"})
        })
public class AuthRateLimit {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String scope;

    @Column(name = "subject_hash", nullable = false, length = 64)
    private String subjectHash;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "reset_at")
    private Instant resetAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AuthRateLimit(String scope, String subjectHash) {
        this.id = UUID.randomUUID();
        this.scope = Objects.requireNonNull(scope);
        this.subjectHash = Objects.requireNonNull(subjectHash);
        this.updatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isBlocked() {
        return blockedUntil != null && blockedUntil.isAfter(Instant.now());
    }

    public void blockUntil(Instant until) {
        this.blockedUntil = until;
        this.updatedAt = Instant.now();
    }

    public void resetAt(Instant resetAt) {
        this.resetAt = resetAt;
        this.updatedAt = Instant.now();
    }
}
