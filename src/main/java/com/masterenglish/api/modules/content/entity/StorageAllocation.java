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
@Table(name = "storage_allocations")
public class StorageAllocation {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Column(name = "allocation_key", nullable = false, unique = true, length = 200)
    private String allocationKey;

    @Column(name = "media_id")
    private UUID mediaId;

    @Column(name = "upload_request_id")
    private UUID uploadRequestId;

    @Column(name = "import_job_id")
    private UUID importJobId;

    @Column(nullable = false)
    private Long bytes;

    @Column(nullable = false, length = 12)
    private String state;

    @Column(length = 100)
    private String bucket;

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StorageAllocation(
            UUID id,
            UUID poolId,
            String allocationKey,
            UUID mediaId,
            UUID uploadRequestId,
            Long bytes,
            String state) {
        this.id = id != null ? id : UUID.randomUUID();
        this.poolId = poolId;
        this.allocationKey = allocationKey;
        this.mediaId = mediaId;
        this.uploadRequestId = uploadRequestId;
        this.bytes = bytes;
        this.state = state != null ? state : "RESERVED";
        this.version = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public StorageAllocation(
            UUID id,
            UUID poolId,
            String allocationKey,
            UUID mediaId,
            UUID uploadRequestId,
            Long bytes,
            String state,
            String bucket,
            String objectKey,
            Instant committedAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.poolId = poolId;
        this.allocationKey = allocationKey;
        this.mediaId = mediaId;
        this.uploadRequestId = uploadRequestId;
        this.bytes = bytes;
        this.state = state;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.committedAt = committedAt;
        this.version = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void release(Instant at) {
        this.state = "RELEASED";
        this.releasedAt = at;
        this.updatedAt = at;
    }

    public void recordWriteTarget(String bucket, String objectKey, Instant at) {
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.updatedAt = at;
    }

    public void commit(String bucket, String objectKey, Instant committedAt) {
        this.state = "COMMITTED";
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.committedAt = committedAt;
        this.updatedAt = committedAt;
    }

    public static StorageAllocation forImportJob(
            UUID id,
            UUID poolId,
            String allocationKey,
            UUID importJobId,
            Long bytes,
            String state,
            String bucket,
            String objectKey) {
        StorageAllocation alloc = new StorageAllocation();
        alloc.id = id != null ? id : UUID.randomUUID();
        alloc.poolId = poolId;
        alloc.allocationKey = allocationKey;
        alloc.importJobId = importJobId;
        alloc.bytes = bytes;
        alloc.state = state != null ? state : "RESERVED";
        alloc.bucket = bucket;
        alloc.objectKey = objectKey;
        alloc.version = 0L;
        alloc.createdAt = Instant.now();
        alloc.updatedAt = alloc.createdAt;
        return alloc;
    }
}
