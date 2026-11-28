package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageException;
import com.masterenglish.api.modules.content.dto.response.StageMediaResult;
import com.masterenglish.api.modules.content.service.MediaStagingService;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MediaStagingServiceImpl implements MediaStagingService {

    private static final int BUFFER_SIZE = 8192;

    private final MediaStagingTxService txService;
    private final MediaStorage mediaStorage;

    @Autowired
    public MediaStagingServiceImpl(MediaStagingTxService txService, MediaStorage mediaStorage) {
        this.txService = txService;
        this.mediaStorage = mediaStorage;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StageMediaResult stageMediaUpload(
            UUID actorId, UUID uploadRequestId, InputStream inputStream, String declaredMimeType) {
        if (inputStream == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Input byte stream is required.");
        }

        // Phase 1: Claim request in short transaction (Tx 1)
        MediaStagingTxService.ClaimedStagingContext context =
                txService.claimForStaging(actorId, uploadRequestId, declaredMimeType);

        // Phase 2: Stage to local temp file and upload to storage OUTSIDE any DB transaction
        Path tempFile = null;
        boolean writtenToStorage = false;
        long totalBytesRead = 0;
        String sha256Hex;

        try {
            tempFile = Files.createTempFile("me-media-stage-", ".tmp");
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 algorithm not available.", e);
            }

            long maxBytesToRead = context.declaredSizeBytes() + 1;
            int zeroReadCount = 0;

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (totalBytesRead < maxBytesToRead) {
                    int toRead = (int) Math.min(buffer.length, maxBytesToRead - totalBytesRead);
                    int read = inputStream.read(buffer, 0, toRead);
                    if (read == -1) {
                        break;
                    }
                    if (read == 0) {
                        zeroReadCount++;
                        if (zeroReadCount > 10) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR,
                                    "Stalled input stream returned 0 bytes repeatedly.");
                        }
                        continue;
                    }
                    zeroReadCount = 0;
                    totalBytesRead += read;
                    if (totalBytesRead > context.declaredSizeBytes()) {
                        throw new BusinessException(
                                ErrorCode.PAYLOAD_TOO_LARGE,
                                "Uploaded bytes exceed declared size limit ("
                                        + context.declaredSizeBytes()
                                        + " bytes).");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            if (totalBytesRead == 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Uploaded file must not be empty.");
            }

            if (totalBytesRead != context.declaredSizeBytes()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Actual byte count ("
                                + totalBytesRead
                                + ") does not match declared size ("
                                + context.declaredSizeBytes()
                                + ").");
            }

            sha256Hex = HexFormat.of().formatHex(digest.digest());

            // Recheck role, owner, token, state, and expiry before calling storage
            txService.recheckBeforeStoragePut(actorId, uploadRequestId, context.putClaimToken());

            // Write to private storage outside DB transaction
            mediaStorage.put(
                    context.tempObjectKey(), tempFile, context.declaredMimeType(), sha256Hex);
            writtenToStorage = true;

        } catch (BusinessException ex) {
            // Input stream validation failed before writing to storage:
            // Release claim so that the user may retry within the unexpired window
            if (!writtenToStorage) {
                txService.releaseClaim(uploadRequestId, context.putClaimToken());
            }
            throw ex;
        } catch (StorageException ex) {
            // Storage write failed or has indeterminate result:
            // Keep claim and allocation for reconciliation; do NOT release or retry overwrite
            log.error(
                    "Storage put failed for uploadRequestId={}, keeping claim for reconciliation",
                    uploadRequestId);
            throw ex;
        } catch (IOException ex) {
            if (!writtenToStorage) {
                txService.releaseClaim(uploadRequestId, context.putClaimToken());
            }
            log.error("Failed to stage upload stream: requestId={}", uploadRequestId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to stage upload stream.");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn(
                            "Failed to delete local staged temp file: requestId={}",
                            uploadRequestId);
                }
            }
        }

        // Phase 3: Record receipt in short transaction (Tx 2)
        return txService.recordStagedReceipt(
                actorId, uploadRequestId, context.putClaimToken(), totalBytesRead, sha256Hex);
    }
}
