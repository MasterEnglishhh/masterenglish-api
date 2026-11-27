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
@Table(name = "media_upload_requests")
public class MediaUploadGrant {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "media_id", nullable = false, unique = true)
    private UUID mediaId;

    @Column(name = "temp_bucket", nullable = false, length = 100)
    private String tempBucket;

    @Column(name = "temp_object_key", nullable = false, length = 500)
    private String tempObjectKey;

    @Column(name = "declared_size_bytes", nullable = false)
    private Long declaredSizeBytes;

    @Column(name = "declared_mime_type", nullable = false, length = 100)
    private String declaredMimeType;

    @Column(name = "max_size_bytes", nullable = false)
    private Long maxSizeBytes;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verification_started_at")
    private Instant verificationStartedAt;

    @Column(name = "verification_deadline_at")
    private Instant verificationDeadlineAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "put_claim_token")
    private UUID putClaimToken;

    @Column(name = "put_claimed_at")
    private Instant putClaimedAt;

    @Column(name = "staged_size_bytes")
    private Long stagedSizeBytes;

    @Column(name = "staged_sha256", length = 64)
    private String stagedSha256;

    @Column(name = "staged_at")
    private Instant stagedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public MediaUploadGrant(
            UUID id,
            UUID mediaId,
            String tempBucket,
            String tempObjectKey,
            Long declaredSizeBytes,
            String declaredMimeType,
            Long maxSizeBytes,
            String status,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.mediaId = mediaId;
        this.tempBucket = tempBucket;
        this.tempObjectKey = tempObjectKey;
        this.declaredSizeBytes = declaredSizeBytes;
        this.declaredMimeType = declaredMimeType;
        this.maxSizeBytes = maxSizeBytes;
        this.status = status != null ? status : "PENDING";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.expiresAt = expiresAt;
        this.updatedAt = this.createdAt;
    }

    public void claimPut(UUID token, Instant claimedAt) {
        this.putClaimToken = token;
        this.putClaimedAt = claimedAt;
        this.updatedAt = claimedAt;
    }

    public void releasePutClaim(Instant at) {
        this.putClaimToken = null;
        this.putClaimedAt = null;
        this.updatedAt = at;
    }

    public void recordStagedReceipt(UUID token, long sizeBytes, String sha256, Instant at) {
        if (this.putClaimToken == null || !this.putClaimToken.equals(token)) {
            throw new IllegalStateException("Claim token mismatch when recording receipt.");
        }
        this.stagedSizeBytes = sizeBytes;
        this.stagedSha256 = sha256;
        this.stagedAt = at;
        this.updatedAt = at;
    }

    public void startVerification(
            UUID token, Instant startedAt, Instant deadline, Instant leaseUntil) {
        this.status = "VERIFYING";
        this.verificationStartedAt = startedAt;
        this.verificationDeadlineAt = deadline;
        this.claimToken = token;
        this.leaseUntil = leaseUntil;
        this.updatedAt = startedAt;
    }

    public void renewVerificationLease(UUID newToken, Instant now, Instant leaseUntil) {
        this.claimToken = newToken;
        this.leaseUntil = leaseUntil;
        this.updatedAt = now;
    }

    public void markCompleted(Instant completedAt) {
        this.status = "COMPLETED";
        this.completedAt = completedAt;
        this.finishedAt = completedAt;
        this.claimToken = null;
        this.leaseUntil = null;
        this.updatedAt = completedAt;
    }

    public void markFailed(String failureCode, Instant finishedAt) {
        this.status = "FAILED";
        this.failureCode = failureCode;
        this.finishedAt = finishedAt;
        this.claimToken = null;
        this.leaseUntil = null;
        this.updatedAt = finishedAt;
    }

    public void markExpired(Instant finishedAt) {
        this.status = "EXPIRED";
        this.failureCode = "UPLOAD_EXPIRED";
        this.finishedAt = finishedAt;
        this.claimToken = null;
        this.leaseUntil = null;
        this.updatedAt = finishedAt;
    }
}
