package com.masterenglish.api.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AuthSession session;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id", unique = true)
    private UUID replacedById;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public RefreshToken(AuthSession session, String tokenHash, Instant expiresAt) {
        this(session, tokenHash, expiresAt, Instant.now());
    }

    public RefreshToken(
            AuthSession session, String tokenHash, Instant expiresAt, Instant issuedAt) {
        this.id = UUID.randomUUID();
        this.session = Objects.requireNonNull(session);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.issuedAt = issuedAt != null ? issuedAt : Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (issuedAt == null) {
            issuedAt = Instant.now();
        }
    }

    public void revoke(UUID replacedById) {
        revoke(replacedById, Instant.now());
    }

    public void revoke(Instant revokedAt) {
        revoke(null, revokedAt);
    }

    public void revoke(UUID replacedById, Instant revokedAt) {
        this.revokedAt = revokedAt != null ? revokedAt : Instant.now();
        this.replacedById = replacedById;
    }

    public void markUsed(Instant usedAt) {
        this.lastUsedAt = usedAt;
    }
}
