package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.content.dto.response.CompleteMediaResult;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.entity.MediaAsset;
import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.content.repository.StorageAllocationRepository;
import com.masterenglish.api.modules.content.repository.StoragePoolRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MediaVerificationTxService {

    public static final Duration LEASE_DURATION = Duration.ofSeconds(60);
    public static final Duration VERIFICATION_DEADLINE = Duration.ofMinutes(5);
    public static final int MAX_CONCURRENT_VERIFICATIONS = 2;
    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED", "EXPIRED");

    private final MediaUploadGrantRepository grantRepository;
    private final MediaAssetRepository assetRepository;
    private final StoragePoolRepository poolRepository;
    private final StorageAllocationRepository allocationRepository;
    private final IdentityEvents identityEvents;

    @Autowired
    public MediaVerificationTxService(
            MediaUploadGrantRepository grantRepository,
            MediaAssetRepository assetRepository,
            StoragePoolRepository poolRepository,
            StorageAllocationRepository allocationRepository,
            IdentityEvents identityEvents) {
        this.grantRepository = grantRepository;
        this.assetRepository = assetRepository;
        this.poolRepository = poolRepository;
        this.allocationRepository = allocationRepository;
        this.identityEvents = identityEvents;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquirePhysicalSlot(UUID workerToken) {
        return grantRepository.acquirePhysicalSlot(workerToken) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releasePhysicalSlot(UUID workerToken) {
        grantRepository.releasePhysicalSlot(workerToken);
    }

    @Transactional
    public CompleteMediaResult claimVerificationLease(UUID actorId, UUID uploadRequestId) {
        if (actorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required.");
        }
        if (uploadRequestId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Upload request ID is required.");
        }

        // 1. Recheck active CONTENT_ADMIN role and serialize requests by locking actor row
        identityEvents.verifyContentAdmin(actorId);

        // 2. Lock TEMP singleton pool row to serialize verification count and claim checking across
        // backend
        poolRepository
                .findByIdForUpdate(MediaReservationServiceImpl.TEMP_POOL_ID)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INTERNAL_ERROR,
                                        "TEMP storage pool not available."));

        // 3. Lock grant row
        MediaUploadGrant grant =
                grantRepository
                        .findByIdForUpdate(uploadRequestId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Upload request not found."));

        // 4. Verify media asset ownership and purpose (CONTENT)
        MediaAsset asset =
                assetRepository
                        .findById(grant.getMediaId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Media asset not found."));

        if (!Objects.equals(asset.getUploadedBy(), actorId)) {
            // Cross-owner returns 404 to avoid leaking existence
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Upload request not found.");
        }

        if (!"CONTENT".equals(asset.getPurpose())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Upload purpose must be CONTENT.");
        }

        // 5. Query DB transaction-start time (txTime) and actual wall time after locks (wallTime)
        MediaUploadGrantRepository.DbTimestamps dbTimes = grantRepository.getDbTimestamps();
        Instant txTime = dbTimes.getTxTime().truncatedTo(ChronoUnit.MICROS);
        Instant wallTime = dbTimes.getWallTime().truncatedTo(ChronoUnit.MICROS);

        // 6. Handle terminal states (COMPLETED, FAILED, EXPIRED): return existing representation,
        // no mutation
        if (TERMINAL_STATUSES.contains(grant.getStatus())) {
            return new CompleteMediaResult(
                    grant.getId(),
                    grant.getMediaId(),
                    grant.getStatus(),
                    grant.getVerificationDeadlineAt(),
                    null,
                    null,
                    false,
                    grant.getStagedSizeBytes(),
                    grant.getStagedSha256());
        }

        // 7. Handle VERIFYING state (retry / lease takeover)
        if ("VERIFYING".equals(grant.getStatus())) {
            // Check deadline against wall clock
            if (!wallTime.isBefore(grant.getVerificationDeadlineAt())) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, "Verification deadline has expired.");
            }

            // If lease is still active, lease cannot be taken over; return current state without
            // new claim token
            if (grant.getLeaseUntil() != null && wallTime.isBefore(grant.getLeaseUntil())) {
                return new CompleteMediaResult(
                        grant.getId(),
                        grant.getMediaId(),
                        grant.getStatus(),
                        grant.getVerificationDeadlineAt(),
                        null,
                        grant.getLeaseUntil(),
                        false,
                        grant.getStagedSizeBytes(),
                        grant.getStagedSha256());
            }

            // Lease expired within deadline: re-claim lease under lock
            checkVerificationCapacity(grant.getId(), wallTime);

            Instant leaseUntil =
                    computeLeaseUntil(txTime, wallTime, grant.getVerificationDeadlineAt());
            UUID claimToken = UUID.randomUUID();

            grant.renewVerificationLease(claimToken, wallTime, leaseUntil);
            grantRepository.saveAndFlush(grant);

            return new CompleteMediaResult(
                    grant.getId(),
                    grant.getMediaId(),
                    grant.getStatus(),
                    grant.getVerificationDeadlineAt(),
                    claimToken,
                    leaseUntil,
                    true,
                    grant.getStagedSizeBytes(),
                    grant.getStagedSha256());
        }

        // 8. Handle PENDING state (first complete request)
        if ("PENDING".equals(grant.getStatus())) {
            // Verify request has not expired against wall clock
            if (!wallTime.isBefore(grant.getExpiresAt())) {
                throw new BusinessException(ErrorCode.CONFLICT, "Upload request has expired.");
            }

            // Verify staged receipt: exact declared bytes, SHA-256, staged timestamp, put claim
            // token
            if (grant.getStagedAt() == null
                    || grant.getStagedSizeBytes() == null
                    || grant.getStagedSha256() == null
                    || grant.getPutClaimToken() == null) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Upload request has not been staged or has missing staging receipt.");
            }

            if (!Objects.equals(grant.getStagedSizeBytes(), grant.getDeclaredSizeBytes())) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Staged byte count ("
                                + grant.getStagedSizeBytes()
                                + ") does not match declared size ("
                                + grant.getDeclaredSizeBytes()
                                + ").");
            }

            // Check global capacity (max 2 concurrent verification claims) against wall clock
            checkVerificationCapacity(grant.getId(), wallTime);

            Instant deadline = wallTime.plus(VERIFICATION_DEADLINE);
            Instant leaseUntil = computeLeaseUntil(txTime, wallTime, deadline);
            UUID claimToken = UUID.randomUUID();

            grant.startVerification(claimToken, wallTime, deadline, leaseUntil);
            grantRepository.saveAndFlush(grant);

            return new CompleteMediaResult(
                    grant.getId(),
                    grant.getMediaId(),
                    grant.getStatus(),
                    deadline,
                    claimToken,
                    leaseUntil,
                    true,
                    grant.getStagedSizeBytes(),
                    grant.getStagedSha256());
        }

        throw new BusinessException(
                ErrorCode.CONFLICT, "Unexpected upload request status: " + grant.getStatus());
    }

    @Transactional
    public CompleteMediaResult commitSuccess(
            UUID uploadRequestId,
            UUID claimToken,
            DecodedMediaInfo decodedInfo,
            String destBucket,
            String destKey) {
        // 1. Lock pools in ascending UUID order (CONTENT then TEMP) matching reservation order
        poolRepository
                .findByIdForUpdate(MediaReservationServiceImpl.CONTENT_POOL_ID)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INTERNAL_ERROR,
                                        "CONTENT storage pool not available."));

        poolRepository
                .findByIdForUpdate(MediaReservationServiceImpl.TEMP_POOL_ID)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INTERNAL_ERROR,
                                        "TEMP storage pool not available."));

        // 2. Lock grant and asset rows
        MediaUploadGrant grant =
                grantRepository
                        .findByIdForUpdate(uploadRequestId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Upload request not found."));

        MediaAsset asset =
                assetRepository
                        .findByIdForUpdate(grant.getMediaId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Media asset not found."));

        // 3. Re-verify active CONTENT_ADMIN role of the uploader under lock
        identityEvents.verifyContentAdmin(asset.getUploadedBy());

        // 4. Query DB timestamps strictly AFTER acquiring all locks
        MediaUploadGrantRepository.DbTimestamps dbTimes = grantRepository.getDbTimestamps();
        Instant wallTime = dbTimes.getWallTime().truncatedTo(ChronoUnit.MICROS);

        // Fencing checks
        if (!"VERIFYING".equals(grant.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Upload request is no longer in VERIFYING state: " + grant.getStatus());
        }
        if (!Objects.equals(grant.getClaimToken(), claimToken)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Verification claim token has been taken over or invalidated.");
        }
        if (grant.getLeaseUntil() != null && !wallTime.isBefore(grant.getLeaseUntil())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Verification lease has expired before commit.");
        }

        Integer width = "AUDIO".equals(asset.getMediaType()) ? null : decodedInfo.width();
        Integer height = "AUDIO".equals(asset.getMediaType()) ? null : decodedInfo.height();
        Integer durationMs =
                "IMAGE".equals(asset.getMediaType())
                        ? null
                        : (decodedInfo.durationSeconds() != null
                                ? (int) Math.round(decodedInfo.durationSeconds() * 1000)
                                : null);

        asset.markReady(
                destBucket,
                destKey,
                grant.getDeclaredSizeBytes(),
                grant.getStagedSha256(),
                width,
                height,
                durationMs);
        assetRepository.saveAndFlush(asset);

        grant.markCompleted(wallTime);
        grantRepository.saveAndFlush(grant);

        // Transition CONTENT storage allocation from RESERVED to COMMITTED
        allocationRepository
                .findByAllocationKey("req:" + uploadRequestId + ":content")
                .ifPresent(
                        alloc -> {
                            alloc.commit(destBucket, destKey, wallTime);
                            allocationRepository.saveAndFlush(alloc);
                        });

        log.info(
                "Media verification completed successfully for uploadRequestId={}",
                uploadRequestId);

        return new CompleteMediaResult(
                grant.getId(),
                grant.getMediaId(),
                grant.getStatus(),
                grant.getVerificationDeadlineAt(),
                null,
                null,
                false,
                grant.getStagedSizeBytes(),
                grant.getStagedSha256());
    }

    @Transactional
    public void recordFinalWrite(
            UUID uploadRequestId, UUID claimToken, String bucket, String objectKey) {
        poolRepository.findByIdForUpdate(MediaReservationServiceImpl.CONTENT_POOL_ID);
        MediaUploadGrant grant = grantRepository.findByIdForUpdate(uploadRequestId).orElseThrow();
        Instant now = grantRepository.getDbTimestamps().getWallTime();
        if (!"VERIFYING".equals(grant.getStatus())
                || !Objects.equals(grant.getClaimToken(), claimToken)
                || !now.isBefore(grant.getLeaseUntil())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Verification claim is no longer active.");
        }

        var allocation =
                allocationRepository
                        .findByAllocationKey("req:" + uploadRequestId + ":content")
                        .filter(alloc -> "RESERVED".equals(alloc.getState()))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CONFLICT,
                                                "Final storage is not reserved."));
        if (allocation.getObjectKey() != null
                && (!Objects.equals(allocation.getBucket(), bucket)
                        || !Objects.equals(allocation.getObjectKey(), objectKey))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Final storage target has changed.");
        }
        allocation.recordWriteTarget(bucket, objectKey, now);
    }

    @Transactional
    public void commitFailure(UUID uploadRequestId, UUID claimToken, String failureCode) {
        poolRepository.findByIdForUpdate(MediaReservationServiceImpl.CONTENT_POOL_ID);
        poolRepository.findByIdForUpdate(MediaReservationServiceImpl.TEMP_POOL_ID);

        grantRepository
                .findByIdForUpdate(uploadRequestId)
                .ifPresent(
                        grant -> {
                            MediaUploadGrantRepository.DbTimestamps dbTimes =
                                    grantRepository.getDbTimestamps();
                            Instant wallTime = dbTimes.getWallTime().truncatedTo(ChronoUnit.MICROS);

                            if (!"VERIFYING".equals(grant.getStatus())) {
                                return;
                            }
                            if (!Objects.equals(grant.getClaimToken(), claimToken)
                                    || !wallTime.isBefore(grant.getLeaseUntil())) {
                                return;
                            }

                            var allocation =
                                    allocationRepository.findByAllocationKey(
                                            "req:" + uploadRequestId + ":content");
                            if (allocation
                                    .filter(alloc -> alloc.getObjectKey() != null)
                                    .isPresent()) {
                                // A prior PUT can still exist; leave the grant retryable.
                                return;
                            }

                            assetRepository
                                    .findByIdForUpdate(grant.getMediaId())
                                    .ifPresent(
                                            asset -> {
                                                asset.markFailed(failureCode);
                                                assetRepository.saveAndFlush(asset);
                                            });

                            grant.markFailed(failureCode, wallTime);
                            grantRepository.saveAndFlush(grant);

                            allocation.ifPresent(alloc -> alloc.release(wallTime));

                            // Keep TEMP quota until backend can confirm the physical object is
                            // gone.

                            log.warn(
                                    "Media verification failed for uploadRequestId={}, code={}",
                                    uploadRequestId,
                                    failureCode);
                        });
    }

    private Instant computeLeaseUntil(Instant txTime, Instant wallTime, Instant deadline) {
        Instant maxTriggerAllowed = txTime.plus(LEASE_DURATION);
        Instant desiredLease = wallTime.plus(LEASE_DURATION);
        Instant candidate =
                desiredLease.isBefore(maxTriggerAllowed) ? desiredLease : maxTriggerAllowed;
        Instant capped = candidate.isBefore(deadline) ? candidate : deadline;

        if (!capped.isAfter(wallTime)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Verification lease window lost due to lock wait. Please retry.");
        }
        return capped;
    }

    private void checkVerificationCapacity(UUID currentGrantId, Instant now) {
        long active = grantRepository.countActiveVerifications(currentGrantId, now);
        if (active >= MAX_CONCURRENT_VERIFICATIONS) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Verification capacity reached. Maximum concurrent verifications in progress.");
        }
    }
}
