package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.content.dto.request.ReserveMediaUploadRequest;
import com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult;
import com.masterenglish.api.modules.content.entity.MediaAsset;
import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import com.masterenglish.api.modules.content.entity.StorageAllocation;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.content.repository.StorageAllocationRepository;
import com.masterenglish.api.modules.content.repository.StoragePoolRepository;
import com.masterenglish.api.modules.content.service.MediaReservationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MediaReservationServiceImpl implements MediaReservationService {

    public static final UUID CONTENT_POOL_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    public static final UUID TEMP_POOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    public static final long MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024L; // 5,242,880 B
    public static final long MAX_AUDIO_SIZE_BYTES = 20 * 1024 * 1024L; // 20,971,520 B

    public static final int MAX_OPEN_REQUESTS_PER_ACTOR = 3;
    public static final int MAX_HOURLY_REQUESTS_PER_ACTOR = 100;
    public static final Duration TTL_DURATION = Duration.ofMinutes(10);

    private static final Set<String> ALLOWED_IMAGE_MIMES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_AUDIO_MIMES = Set.of("audio/mpeg", "audio/mp4");

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaUploadGrantRepository uploadGrantRepository;
    private final StoragePoolRepository storagePoolRepository;
    private final StorageAllocationRepository storageAllocationRepository;
    private final ContentSourceRepository sourceRepository;
    private final IdentityEvents identityEvents;
    private final Clock clock;
    private final String tempBucketConfig;

    @Autowired
    public MediaReservationServiceImpl(
            MediaAssetRepository mediaAssetRepository,
            MediaUploadGrantRepository uploadGrantRepository,
            StoragePoolRepository storagePoolRepository,
            StorageAllocationRepository storageAllocationRepository,
            ContentSourceRepository sourceRepository,
            IdentityEvents identityEvents,
            @Autowired(required = false) Clock clock,
            @Value("${app.storage.r2.bucket:masterenglish-temp}") String tempBucketConfig) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.uploadGrantRepository = uploadGrantRepository;
        this.storagePoolRepository = storagePoolRepository;
        this.storageAllocationRepository = storageAllocationRepository;
        this.sourceRepository = sourceRepository;
        this.identityEvents = identityEvents;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.tempBucketConfig = tempBucketConfig;
    }

    @Override
    @Transactional
    public ReserveMediaUploadResult reserveContentUpload(
            UUID actorId, ReserveMediaUploadRequest request) {
        if (actorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required.");
        }

        // 1. Recheck active CONTENT_ADMIN role and serialize requests by locking actor row
        identityEvents.verifyContentAdmin(actorId);

        // 2. Validate request parameters
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Request body is required.");
        }
        validateFileName(request.fileName());
        String mimeType = validateMimeType(request.mimeType());
        String mediaType = determineMediaType(mimeType);
        long maxSizeBytes = getMaxSizeBytes(mediaType);
        long declaredSizeBytes = validateDeclaredSize(request.declaredSizeBytes(), maxSizeBytes);
        validateSource(request.sourceId());

        Instant now = clock.instant();

        // 3. Enforce actor limits: open requests <= 3, hourly requests <= 100
        long openRequests = uploadGrantRepository.countOpenRequestsForActor(actorId, now);
        if (openRequests >= MAX_OPEN_REQUESTS_PER_ACTOR) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Maximum open upload requests (" + MAX_OPEN_REQUESTS_PER_ACTOR + ") reached.");
        }

        Instant oneHourAgo = now.minus(Duration.ofHours(1));
        long hourlyRequests = uploadGrantRepository.countRequestsSinceForActor(actorId, oneHourAgo);
        if (hourlyRequests >= MAX_HOURLY_REQUESTS_PER_ACTOR) {
            throw new BusinessException(
                    ErrorCode.RATE_LIMITED,
                    "Hourly upload request limit ("
                            + MAX_HOURLY_REQUESTS_PER_ACTOR
                            + ") exceeded.");
        }

        // 4. Lock pools in ascending UUID order (CONTENT then TEMP) and verify quota capacity under
        // lock
        List<StoragePoolRepository.PoolQuotaRow> lockedPools =
                storagePoolRepository.lockAndFetchPoolsForUpdate(
                        List.of(CONTENT_POOL_ID, TEMP_POOL_ID));
        if (lockedPools.size() < 2) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Storage pools not available.");
        }

        for (StoragePoolRepository.PoolQuotaRow pool : lockedPools) {
            long quota = pool.getQuotaBytes() != null ? pool.getQuotaBytes() : 0L;
            long reserved = pool.getReservedBytes() != null ? pool.getReservedBytes() : 0L;
            long used = pool.getUsedBytes() != null ? pool.getUsedBytes() : 0L;
            long available = quota - reserved - used;
            if (declaredSizeBytes > available) {
                throw new BusinessException(ErrorCode.CONFLICT, "Storage pool quota exceeded.");
            }
        }

        // 5. Generate server-side identifiers and paths (temp key strictly server-generated without
        // client filename)
        UUID mediaId = UUID.randomUUID();
        UUID uploadRequestId = UUID.randomUUID();
        String tempBucket =
                (tempBucketConfig != null && !tempBucketConfig.isBlank())
                        ? tempBucketConfig.strip()
                        : "masterenglish-temp";
        String tempObjectKey = "temp/" + uploadRequestId;
        Instant expiresAt = now.plus(TTL_DURATION);

        // 6. Insert MediaAsset with status PENDING (filename used for metadata only)
        MediaAsset mediaAsset =
                new MediaAsset(
                        mediaId,
                        mediaType,
                        "CONTENT",
                        request.fileName().strip(),
                        mimeType,
                        request.sourceId(),
                        actorId,
                        "PENDING");
        mediaAssetRepository.saveAndFlush(mediaAsset);

        // 7. Insert MediaUploadGrant with status PENDING and TTL 10m
        MediaUploadGrant uploadGrant =
                new MediaUploadGrant(
                        uploadRequestId,
                        mediaId,
                        tempBucket,
                        tempObjectKey,
                        declaredSizeBytes,
                        mimeType,
                        declaredSizeBytes, // granted bytes ceiling == declared size
                        "PENDING",
                        now,
                        expiresAt);
        uploadGrantRepository.saveAndFlush(uploadGrant);

        // 8. Insert 2 storage_allocations with state RESERVED and deterministic allocation keys
        StorageAllocation contentAllocation =
                new StorageAllocation(
                        UUID.randomUUID(),
                        CONTENT_POOL_ID,
                        "req:" + uploadRequestId + ":content",
                        mediaId,
                        uploadRequestId,
                        declaredSizeBytes,
                        "RESERVED");
        StorageAllocation tempAllocation =
                new StorageAllocation(
                        UUID.randomUUID(),
                        TEMP_POOL_ID,
                        "req:" + uploadRequestId + ":temp",
                        mediaId,
                        uploadRequestId,
                        declaredSizeBytes,
                        "RESERVED");

        try {
            storageAllocationRepository.saveAndFlush(contentAllocation);
            storageAllocationRepository.saveAndFlush(tempAllocation);
        } catch (Exception ex) {
            if (isQuotaExceeded(ex)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Storage pool quota exceeded.");
            }
            throw ex;
        }

        log.info(
                "Media upload reserved successfully: requestId={}, mediaId={}, declaredBytes={}, expiresAt={}",
                uploadRequestId,
                mediaId,
                declaredSizeBytes,
                expiresAt);

        return new ReserveMediaUploadResult(
                uploadRequestId,
                mediaId,
                tempBucket,
                tempObjectKey,
                declaredSizeBytes,
                declaredSizeBytes,
                expiresAt,
                "PENDING");
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "File name must not be blank.");
        }
        String stripped = fileName.strip();
        if (stripped.length() > 255) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "File name must not exceed 255 characters.");
        }
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c < 32 || c == 127) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "File name contains invalid control characters.");
            }
        }
        if (stripped.contains("/") || stripped.contains("\\") || stripped.contains("..")) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "File name must not contain path traversal characters.");
        }
    }

    private String validateMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "MIME type must not be blank.");
        }
        String normalized = mimeType.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_MIMES.contains(normalized)
                && !ALLOWED_AUDIO_MIMES.contains(normalized)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "MIME type not allowed. Allowed: JPEG, PNG, WebP for image; MP3, M4A for audio.");
        }
        return normalized;
    }

    private String determineMediaType(String mimeType) {
        if (ALLOWED_IMAGE_MIMES.contains(mimeType)) {
            return "IMAGE";
        }
        if (ALLOWED_AUDIO_MIMES.contains(mimeType)) {
            return "AUDIO";
        }
        throw new BusinessException(
                ErrorCode.VALIDATION_ERROR, "Unsupported media type for MIME: " + mimeType);
    }

    private long getMaxSizeBytes(String mediaType) {
        return "IMAGE".equals(mediaType) ? MAX_IMAGE_SIZE_BYTES : MAX_AUDIO_SIZE_BYTES;
    }

    private long validateDeclaredSize(Long declaredSizeBytes, long maxSizeBytes) {
        if (declaredSizeBytes == null || declaredSizeBytes <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Declared size must be positive.");
        }
        if (declaredSizeBytes > maxSizeBytes) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE,
                    "Declared size ("
                            + declaredSizeBytes
                            + ") exceeds maximum allowed limit ("
                            + maxSizeBytes
                            + " bytes).");
        }
        return declaredSizeBytes;
    }

    private void validateSource(UUID sourceId) {
        if (sourceId != null) {
            var sourceOpt = sourceRepository.findById(sourceId);
            if (sourceOpt.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Content source not found: " + sourceId);
            }
            if (!sourceOpt.get().availableForPublication()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Content source is not available for publication.");
            }
        }
    }

    private boolean isQuotaExceeded(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof ConstraintViolationException cve) {
                if ("storage_pool_quota_exceeded".equals(cve.getConstraintName())) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }
}
