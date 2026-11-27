package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageDisabledException;
import com.masterenglish.api.modules.content.dto.response.CompleteMediaResult;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.content.service.MediaDecoder;
import com.masterenglish.api.modules.content.service.MediaVerificationService;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MediaVerificationServiceImpl implements MediaVerificationService {

    private static final int BUFFER_SIZE = 8192;

    private final MediaUploadGrantRepository grantRepository;
    private final MediaVerificationTxService txService;
    private final MediaStorage mediaStorage;
    private final MediaDecoder mediaDecoder;

    @Autowired
    public MediaVerificationServiceImpl(
            MediaUploadGrantRepository grantRepository,
            MediaVerificationTxService txService,
            MediaStorage mediaStorage,
            MediaDecoder mediaDecoder) {
        this.grantRepository = grantRepository;
        this.txService = txService;
        this.mediaStorage = mediaStorage;
        this.mediaDecoder = mediaDecoder;
    }

    @Override
    public CompleteMediaResult completeUpload(UUID actorId, UUID uploadRequestId) {
        return txService.claimVerificationLease(actorId, uploadRequestId);
    }

    @Override
    public CompleteMediaResult verifyAndFinalize(UUID uploadRequestId, UUID claimToken) {
        if (!mediaStorage.isAvailable()) {
            throw new StorageDisabledException("Storage is unavailable.");
        }

        UUID workerToken = UUID.randomUUID();
        // ponytail: Keep crash-held slots occupied until worker termination is confirmed.
        if (!txService.tryAcquirePhysicalSlot(workerToken)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Verification capacity reached. Maximum concurrent physical workers in progress.");
        }

        Path scratchFile = null;
        try {
            MediaUploadGrant grant =
                    grantRepository
                            .findById(uploadRequestId)
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.RESOURCE_NOT_FOUND,
                                                    "Upload request not found."));

            if (!"VERIFYING".equals(grant.getStatus())
                    || !Objects.equals(grant.getClaimToken(), claimToken)) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Verification claim token mismatch or request no longer VERIFYING.");
            }

            if (grant.getLeaseUntil() != null && Instant.now().isAfter(grant.getLeaseUntil())) {
                throw new BusinessException(ErrorCode.CONFLICT, "Verification lease expired.");
            }

            // Stream from temp storage to local scratch file with byte cap & sha256 verification
            try {
                scratchFile = Files.createTempFile("me-media-verify-", ".tmp");
            } catch (IOException e) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "Failed to create local scratch file: " + e.getMessage());
            }

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 algorithm not available.", e);
            }

            long maxBytes = grant.getDeclaredSizeBytes() + 1;
            long totalRead = 0;

            try (InputStream in = mediaStorage.openStream(grant.getTempObjectKey());
                    OutputStream out =
                            new BufferedOutputStream(Files.newOutputStream(scratchFile))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (totalRead < maxBytes) {
                    int toRead = (int) Math.min(buffer.length, maxBytes - totalRead);
                    int r = in.read(buffer, 0, toRead);
                    if (r == -1) {
                        break;
                    }
                    if (r > 0) {
                        totalRead += r;
                        digest.update(buffer, 0, r);
                        out.write(buffer, 0, r);
                    }
                }
                out.flush();
            } catch (IOException e) {
                log.error("Failed to read staged object from storage: {}", e.getMessage());
                txService.commitFailure(uploadRequestId, claimToken, "STORAGE_READ_FAILED");
                throw new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "Failed to read staged object from storage.");
            }

            String computedSha256 = HexFormat.of().formatHex(digest.digest());

            if (totalRead != grant.getDeclaredSizeBytes()
                    || !computedSha256.equalsIgnoreCase(grant.getStagedSha256())) {
                log.warn(
                        "Staged object checksum or size mismatch: expectedSize={}, read={}, expectedSha={}, computedSha={}",
                        grant.getDeclaredSizeBytes(),
                        totalRead,
                        grant.getStagedSha256(),
                        computedSha256);
                txService.commitFailure(uploadRequestId, claimToken, "CORRUPT_STAGED_PAYLOAD");
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Staged media payload is corrupt or truncated.");
            }

            // Decode and inspect format/container/codec/duration
            DecodedMediaInfo decodedInfo;
            try {
                decodedInfo = mediaDecoder.decode(scratchFile, grant.getDeclaredMimeType());
            } catch (Exception e) {
                log.warn(
                        "Media decoding failed for uploadRequestId={}: {}",
                        uploadRequestId,
                        e.getMessage());
                txService.commitFailure(uploadRequestId, claimToken, "DECODE_FAILED");
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Media decoding rejected: " + e.getMessage());
            }

            // Re-check lease before writing to final storage destination
            if (grant.getLeaseUntil() != null && Instant.now().isAfter(grant.getLeaseUntil())) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Verification lease expired before final storage write.");
            }

            String ext = extensionForMime(grant.getDeclaredMimeType());
            String destKey = "content/" + grant.getMediaId() + ext;
            String destBucket = mediaStorage.bucket();

            txService.recordFinalWrite(uploadRequestId, claimToken, destBucket, destKey);
            mediaStorage.putIfAbsent(
                    destKey, scratchFile, grant.getDeclaredMimeType(), computedSha256);
            String storedHash = sha256(mediaStorage.openStream(destKey), totalRead);
            if (!computedSha256.equalsIgnoreCase(storedHash)) {
                throw new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "Stored media could not be verified against uploaded bytes.");
            }

            // Keep staged bytes for retries and post-terminal retention.
            return txService.commitSuccess(
                    uploadRequestId, claimToken, decodedInfo, destBucket, destKey);
        } finally {
            if (scratchFile != null) {
                try {
                    Files.deleteIfExists(scratchFile);
                } catch (IOException ignored) {
                }
            }
            txService.releasePhysicalSlot(workerToken);
        }
    }

    @Override
    public CompleteMediaResult completeAndVerify(UUID actorId, UUID uploadRequestId) {
        CompleteMediaResult claimed = completeUpload(actorId, uploadRequestId);
        if (claimed.leaseAcquired() && claimed.claimToken() != null) {
            return verifyAndFinalize(uploadRequestId, claimed.claimToken());
        }
        return claimed;
    }

    private String sha256(InputStream input, long expectedSize) {
        try (InputStream in = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = expectedSize;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (read == -1) {
                    return "";
                }
                digest.update(buffer, 0, read);
                remaining -= read;
            }
            return in.read() == -1 ? HexFormat.of().formatHex(digest.digest()) : "";
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "Stored media could not be read for verification.");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available.", e);
        }
    }

    private String extensionForMime(String mime) {
        if ("image/jpeg".equalsIgnoreCase(mime)) return ".jpg";
        if ("image/png".equalsIgnoreCase(mime)) return ".png";
        if ("image/webp".equalsIgnoreCase(mime)) return ".webp";
        if ("audio/mpeg".equalsIgnoreCase(mime)) return ".mp3";
        if ("audio/mp4".equalsIgnoreCase(mime)) return ".m4a";
        return "";
    }
}
