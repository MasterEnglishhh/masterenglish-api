package com.masterenglish.api.modules.content.entity;

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
@Table(name = "content_sources")
public class ContentSource {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(length = 255)
    private String author;

    @Column(name = "license_code", length = 100)
    private String licenseCode;

    @Column(name = "license_url", columnDefinition = "text")
    private String licenseUrl;

    @Column(columnDefinition = "text")
    private String attribution;

    @Column(name = "permission_note", columnDefinition = "text")
    private String permissionNote;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ContentSource(
            UUID id,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            String status,
            UUID createdBy) {
        this.id = id != null ? id : UUID.randomUUID();
        this.title = title;
        this.sourceType = sourceType != null ? sourceType : "SELF_AUTHORED";
        this.sourceUrl = sourceUrl;
        this.author = author;
        this.licenseCode = licenseCode;
        this.licenseUrl = licenseUrl;
        this.attribution = attribution;
        this.permissionNote = permissionNote;
        this.status = status != null ? status : "ACTIVE";
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
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
