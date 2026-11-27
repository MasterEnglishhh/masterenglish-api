package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageDisabledException;
import com.masterenglish.api.modules.content.dto.response.StageMediaResult;
import com.masterenglish.api.modules.content.entity.MediaAsset;
import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MediaStagingTxService {

    private final MediaUploadGrantRepository grantRepository;
    private final MediaAssetRepository assetRepository;
    private final IdentityEvents identityEvents;
    private final MediaStorage mediaStorage;
    private final Clock clock;

    public record ClaimedStagingContext(
            UUID uploadRequestId,
            UUID mediaId,
            String tempBucket,
            String tempObjectKey,
            long declaredSizeBytes,
            long maxSizeBytes,
            String declaredMimeType,
            UUID putClaimToken) {}

    @Autowired
    public MediaStagingTxService(
            MediaUploadGrantRepository grantRepository,
            MediaAssetRepository assetRepository,
            IdentityEvents identityEvents,
            MediaStorage mediaStorage,
            Clock clock) {
        this.grantRepository = grantRepository;
        this.assetRepository = assetRepository;
        this.identityEvents = identityEvents;
        this.mediaStorage = mediaStorage;
        this.clock = Objects.requireNonNull(clock, "Clock is required.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimedStagingContext claimForStaging(
            UUID actorId, UUID uploadRequestId, String providedMimeType) {
        if (actorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required.");
        }
        if (uploadRequestId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Upload request ID is required.");
        }

        // 1. Recheck active CONTENT_ADMIN role and serialize requests by locking actor row
        identityEvents.verifyContentAdmin(actorId);

        // 2. Storage enabled check
        if (!mediaStorage.isAvailable()) {
            throw new StorageDisabledException("Media storage is disabled.");
        }

        // 3. Lock grant row for update
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

        // 5. Verify status and expiry
        Instant now = clock.instant();
        if (!"PENDING".equals(grant.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Upload request status is " + grant.getStatus() + ", expected PENDING.");
        }

        if (!now.isBefore(grant.getExpiresAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Upload request has expired.");
        }

        // 6. Verify MIME type if provided by caller
        if (providedMimeType != null && !providedMimeType.isBlank()) {
            if (!grant.getDeclaredMimeType().equalsIgnoreCase(providedMimeType.strip())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Provided MIME type does not match declared upload grant.");
            }
        }

        // 7. Check for concurrent or existing PUT claim / staged object
        if (grant.getPutClaimToken() != null || grant.getStagedAt() != null) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Upload request is already claimed or has already been staged.");
        }

        // 8. Record PUT claim token
        UUID claimToken = UUID.randomUUID();
        grant.claimPut(claimToken, now);
        grantRepository.saveAndFlush(grant);

        log.debug(
                "Claimed media upload request for staging: requestId={}, claimToken={}",
                uploadRequestId,
                claimToken);

        return new ClaimedStagingContext(
                grant.getId(),
                grant.getMediaId(),
                grant.getTempBucket(),
                grant.getTempObjectKey(),
                grant.getDeclaredSizeBytes(),
                grant.getMaxSizeBytes(),
                grant.getDeclaredMimeType(),
                claimToken);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(UUID uploadRequestId, UUID claimToken) {
        if (uploadRequestId == null || claimToken == null) {
            return;
        }
        var grantOpt = grantRepository.findByIdForUpdate(uploadRequestId);
        if (grantOpt.isPresent()) {
            var grant = grantOpt.get();
            if (Objects.equals(grant.getPutClaimToken(), claimToken)
                    && grant.getStagedAt() == null) {
                grant.releasePutClaim(clock.instant());
                grantRepository.saveAndFlush(grant);
                log.debug(
                        "Released staging claim for retriable request: requestId={}",
                        uploadRequestId);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recheckBeforeStoragePut(UUID actorId, UUID uploadRequestId, UUID claimToken) {
        // 1. Recheck active role and lock actor
        identityEvents.verifyContentAdmin(actorId);

        // 2. Lock grant row
        MediaUploadGrant grant =
                grantRepository
                        .findByIdForUpdate(uploadRequestId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Upload request not found."));

        // 3. Verify media asset ownership
        MediaAsset asset =
                assetRepository
                        .findById(grant.getMediaId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Media asset not found."));

        if (!Objects.equals(asset.getUploadedBy(), actorId)) {
            grant.releasePutClaim(clock.instant());
            grantRepository.saveAndFlush(grant);
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "Owner mismatch before storage put.");
        }

        // 4. Verify token and status
        if (!Objects.equals(grant.getPutClaimToken(), claimToken)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Claim token mismatch before storage put.");
        }

        if (!"PENDING".equals(grant.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Upload request is no longer PENDING.");
        }

        // 5. Check expiry
        Instant now = clock.instant();
        if (!now.isBefore(grant.getExpiresAt())) {
            // Expired before storage write: release claim so request remains retriable
            grant.releasePutClaim(now);
            grantRepository.saveAndFlush(grant);
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Upload request has expired before storage put.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StageMediaResult recordStagedReceipt(
            UUID actorId,
            UUID uploadRequestId,
            UUID claimToken,
            long actualBytes,
            String sha256Hex) {
        // 1. Recheck active role and lock actor
        identityEvents.verifyContentAdmin(actorId);

        // 2. Lock grant row
        MediaUploadGrant grant =
                grantRepository
                        .findByIdForUpdate(uploadRequestId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Upload request not found."));

        // 3. Verify media asset ownership
        MediaAsset asset =
                assetRepository
                        .findById(grant.getMediaId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Media asset not found."));

        if (!Objects.equals(asset.getUploadedBy(), actorId)) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "Owner mismatch when finalizing receipt.");
        }

        // 4. Verify token and status
        if (!Objects.equals(grant.getPutClaimToken(), claimToken)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Claim token mismatch when finalizing receipt.");
        }

        if (!"PENDING".equals(grant.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Upload request is no longer PENDING.");
        }

        // 5. Check expiry: if expired after write, do NOT record receipt, keep claim for
        // reconciliation
        Instant now = clock.instant();
        if (!now.isBefore(grant.getExpiresAt())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Upload request has expired after storage write.");
        }

        // 6. Record receipt
        grant.recordStagedReceipt(claimToken, actualBytes, sha256Hex, now);
        grantRepository.saveAndFlush(grant);

        log.info(
                "Staging receipt recorded: requestId={}, mediaId={}, size={}, sha256={}",
                uploadRequestId,
                grant.getMediaId(),
                actualBytes,
                sha256Hex);

        return new StageMediaResult(
                uploadRequestId, grant.getMediaId(), actualBytes, sha256Hex, grant.getStatus());
    }
}
