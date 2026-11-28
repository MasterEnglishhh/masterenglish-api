package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.admin.dto.request.ReserveMediaUploadHttpRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/media")
@PreAuthorize("hasRole('CONTENT_ADMIN')")
public class AdminMediaController {

    private final ContentEvents contentEvents;

    public AdminMediaController(ContentEvents contentEvents) {
        this.contentEvents =
                Objects.requireNonNull(contentEvents, "contentEvents must not be null");
    }

    @PostMapping("/upload-requests")
    public ResponseEntity<ContentEvents.UploadRequestView> reserveUpload(
            @Valid @RequestBody ReserveMediaUploadHttpRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        if (request.resolvedSizeBytes() == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Declared size in bytes is required.");
        }
        ContentEvents.UploadRequestView view =
                contentEvents.reserveMediaUpload(
                        currentUser.getId(),
                        new ContentEvents.ReserveUploadRequest(
                                request.fileName(),
                                request.resolvedSizeBytes(),
                                request.mimeType(),
                                request.sourceId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PutMapping("/upload-requests/{id}/bytes")
    public ResponseEntity<ContentEvents.StageResult> stageBytes(
            @PathVariable UUID id,
            HttpServletRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        try {
            ContentEvents.StageResult result =
                    contentEvents.stageMediaUpload(
                            currentUser.getId(),
                            id,
                            request.getInputStream(),
                            request.getContentType());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Failed to read request body stream: " + e.getMessage());
        }
    }

    @PostMapping("/upload-requests/{id}/complete")
    public ResponseEntity<ContentEvents.CompleteResult> completeUpload(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "true") boolean sync,
            @CurrentUser AuthenticatedUser currentUser) {
        ContentEvents.CompleteResult result =
                contentEvents.completeMediaUpload(currentUser.getId(), id, sync);
        // Never expose internal claim token across HTTP boundaries
        ContentEvents.CompleteResult sanitized =
                new ContentEvents.CompleteResult(
                        result.uploadRequestId(),
                        result.mediaId(),
                        result.status(),
                        result.verificationDeadlineAt(),
                        null,
                        result.leaseUntil(),
                        result.leaseAcquired(),
                        result.stagedSizeBytes(),
                        result.stagedSha256());
        if ("VERIFYING".equals(sanitized.status())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(sanitized);
        }
        return ResponseEntity.ok(sanitized);
    }

    @GetMapping("/upload-requests/{id}")
    public ResponseEntity<ContentEvents.UploadStatusView> getUploadStatus(
            @PathVariable UUID id, @CurrentUser AuthenticatedUser currentUser) {
        ContentEvents.UploadStatusView status =
                contentEvents.getMediaUploadStatus(currentUser.getId(), id);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/assets/{id}")
    public ResponseEntity<ContentEvents.MediaAssetView> getAsset(
            @PathVariable UUID id, @CurrentUser AuthenticatedUser currentUser) {
        ContentEvents.MediaAssetView asset = contentEvents.getMediaAsset(currentUser.getId(), id);
        return ResponseEntity.ok(asset);
    }

    @PostMapping("/assets/{id}/archive")
    public ResponseEntity<ContentEvents.MediaAssetView> archiveAsset(
            @PathVariable UUID id, @CurrentUser AuthenticatedUser currentUser) {
        ContentEvents.MediaAssetView asset =
                contentEvents.archiveMediaAsset(currentUser.getId(), id);
        return ResponseEntity.ok(asset);
    }
}
