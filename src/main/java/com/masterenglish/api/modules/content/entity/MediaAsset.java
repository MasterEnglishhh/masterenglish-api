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
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "media_type", nullable = false, length = 16)
    private String mediaType;

    @Column(nullable = false, length = 16)
    private String purpose;

    @Column(length = 100)
    private String bucket;

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "duration_ms")
    private Integer durationMs;

    private Integer width;
    private Integer height;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
