package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/import/jobs")
@PreAuthorize("hasRole('CONTENT_ADMIN')")
public class AdminImportController {

    private final ContentEvents contentEvents;

    public AdminImportController(ContentEvents contentEvents) {
        this.contentEvents =
                Objects.requireNonNull(contentEvents, "contentEvents must not be null");
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ContentEvents.ImportJobView> createImportJob(
            @RequestParam("file") MultipartFile file,
            @RequestParam("module") String module,
            @RequestParam("requestId") UUID requestId,
            @RequestParam(value = "namespace", defaultValue = "default") String namespace,
            @CurrentUser AuthenticatedUser currentUser) {

        if (!"VOCABULARY".equalsIgnoreCase(module) && !"QUESTION_BANK".equalsIgnoreCase(module)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported import module: " + module);
        }

        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "File must not be empty.");
        }

        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "File size exceeds 5 MiB limit.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "Failed to read uploaded file: " + e.getMessage());
        }

        ContentEvents.CreateImportJobResult result =
                contentEvents.createImportJob(
                        currentUser.getId(),
                        requestId,
                        module,
                        namespace,
                        file.getOriginalFilename(),
                        bytes);

        if (result.isReplay()) {
            return ResponseEntity.ok(result.job());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.job());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContentEvents.ImportJobView> getImportJob(
            @PathVariable UUID id, @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(contentEvents.getImportJob(currentUser.getId(), id));
    }

    @GetMapping("/{id}/rows")
    public ResponseEntity<PageResponse<ContentEvents.ImportJobRowView>> getImportJobRows(
            @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @CurrentUser AuthenticatedUser currentUser) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return ResponseEntity.ok(
                contentEvents.getImportJobRows(currentUser.getId(), id, status, pageable));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ContentEvents.ConfirmImportJobResult> confirmImportJob(
            @PathVariable UUID id, @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(contentEvents.confirmImportJob(currentUser.getId(), id));
    }
}
