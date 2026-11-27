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
@Table(name = "import_jobs")
public class ImportJob {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "import_type", nullable = false, length = 24)
    private String importType;

    @Column(name = "import_namespace", nullable = false, length = 120)
    private String importNamespace;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "input_bucket", nullable = false, length = 100)
    private String inputBucket;

    @Column(name = "input_object_key", nullable = false, length = 500)
    private String inputObjectKey;

    @Column(name = "declared_size_bytes", nullable = false)
    private Long declaredSizeBytes;

    @Column(name = "input_sha256", length = 64)
    private String inputSha256;

    @Column(name = "upload_expires_at", nullable = false)
    private Instant uploadExpiresAt;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "success_rows", nullable = false)
    private int successRows;

    @Column(name = "failed_rows", nullable = false)
    private int failedRows;

    @Column(name = "skipped_rows", nullable = false)
    private int skippedRows;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ImportJob(
            UUID id,
            UUID createdBy,
            UUID requestId,
            String requestHash,
            String importType,
            String importNamespace,
            String originalFilename,
            String inputBucket,
            String inputObjectKey,
            Long declaredSizeBytes,
            String inputSha256,
            Instant uploadExpiresAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.createdBy = createdBy;
        this.requestId = requestId;
        this.requestHash = requestHash;
        this.importType = importType;
        this.importNamespace = importNamespace;
        this.originalFilename = originalFilename;
        this.inputBucket = inputBucket;
        this.inputObjectKey = inputObjectKey;
        this.declaredSizeBytes = declaredSizeBytes;
        this.inputSha256 = inputSha256;
        this.uploadExpiresAt = uploadExpiresAt;
        this.status = "UPLOADING";
        this.totalRows = 0;
        this.successRows = 0;
        this.failedRows = 0;
        this.skippedRows = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markReady(int totalRows, int failedRows, Instant at) {
        this.status = "READY";
        this.totalRows = totalRows;
        this.failedRows = failedRows;
        this.updatedAt = at;
    }

    public void markFailed(String failureCode, Instant at) {
        this.status = "FAILED";
        this.failureCode = failureCode;
        this.finishedAt = at;
        this.updatedAt = at;
    }

    public void markExpired(Instant at) {
        this.status = "EXPIRED";
        this.failureCode = "UPLOAD_EXPIRED";
        this.claimToken = null;
        this.leaseUntil = null;
        this.finishedAt = at;
        this.updatedAt = at;
    }

    public void startProcessing(Instant confirmedAt, UUID claimToken, Instant leaseUntil) {
        this.status = "PROCESSING";
        this.confirmedAt = confirmedAt;
        this.claimToken = claimToken;
        this.leaseUntil = leaseUntil;
        this.updatedAt = confirmedAt;
    }

    public void finishProcessing(int success, int failed, int skipped, Instant at) {
        this.successRows = success;
        this.failedRows = failed;
        this.skippedRows = skipped;
        if (failed == 0) {
            this.status = "COMPLETED";
        } else if (success > 0) {
            this.status = "PARTIAL_SUCCESS";
        } else {
            this.status = "FAILED";
        }
        this.claimToken = null;
        this.leaseUntil = null;
        this.finishedAt = at;
        this.updatedAt = at;
    }
}
