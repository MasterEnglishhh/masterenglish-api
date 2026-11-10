package com.masterenglish.api.modules.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "content_items")
public class ContentItem {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "published_revision_id")
    private UUID publishedRevisionId;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "access_revoked_at")
    private Instant accessRevokedAt;

    @Column(name = "access_revoke_reason", columnDefinition = "text")
    private String accessRevokeReason;

    @Column(name = "import_namespace", length = 120)
    private String importNamespace;

    @Column(name = "external_key", length = 200)
    private String externalKey;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ContentItem(
            UUID id,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey) {
        this.id = id != null ? id : UUID.randomUUID();
        this.contentType = contentType;
        this.createdBy = createdBy;
        this.importNamespace = importNamespace;
        this.externalKey = externalKey;
        this.archived = false;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void setPublishedRevisionId(UUID publishedRevisionId) {
        this.publishedRevisionId = publishedRevisionId;
    }

    public void archive(String reason) {
        this.archived = true;
        this.archivedAt = Instant.now();
        this.accessRevokedAt = Instant.now();
        this.accessRevokeReason = reason;
    }

    public void unarchive() {
        this.archived = false;
        this.archivedAt = null;
        this.accessRevokedAt = null;
        this.accessRevokeReason = null;
    }

    @PrePersist
    void onPersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
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
