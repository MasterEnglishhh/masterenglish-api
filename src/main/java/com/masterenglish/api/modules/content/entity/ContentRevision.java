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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "content_revisions")
public class ContentRevision {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "schema_version", nullable = false)
    private short schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private String snapshot;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "change_summary", columnDefinition = "text")
    private String changeSummary;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ContentRevision(
            UUID id,
            UUID contentItemId,
            int versionNo,
            short schemaVersion,
            String snapshot,
            String status,
            String changeSummary,
            UUID createdBy) {
        this.id = id != null ? id : UUID.randomUUID();
        this.contentItemId = contentItemId;
        this.versionNo = versionNo;
        this.schemaVersion = schemaVersion > 0 ? schemaVersion : 1;
        this.snapshot = snapshot != null ? snapshot : "{}";
        this.status = status != null ? status : "DRAFT";
        this.changeSummary = changeSummary;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void submitForReview() {
        requireStatus("DRAFT");
        this.status = "IN_REVIEW";
    }

    public void approve(UUID reviewerId, String note) {
        requireStatus("IN_REVIEW");
        this.status = "APPROVED";
        this.reviewedBy = reviewerId;
        this.reviewNote = note;
        this.reviewedAt = Instant.now();
    }

    public void publish(UUID publisherId) {
        requireStatus("APPROVED");
        this.status = "PUBLISHED";
        if (this.reviewedBy == null) {
            this.reviewedBy = publisherId;
            this.reviewedAt = Instant.now();
        }
        this.publishedAt = Instant.now();
    }

    private void requireStatus(String expected) {
        if (!expected.equals(status))
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.CONFLICT,
                    "Invalid content workflow transition from " + status);
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
