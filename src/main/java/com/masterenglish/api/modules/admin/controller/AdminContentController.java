package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.admin.dto.AdminMessageResponse;
import com.masterenglish.api.modules.admin.service.AdminContentService;
import com.masterenglish.api.modules.content.admin.AdminContentItemDto;
import com.masterenglish.api.modules.content.admin.AdminRevisionDto;
import com.masterenglish.api.modules.content.admin.ContentSourceDto;
import com.masterenglish.api.modules.content.admin.CreateContentSourceRequest;
import com.masterenglish.api.modules.content.admin.CreateDeckDraftRequest;
import com.masterenglish.api.modules.content.admin.CreateWordDraftRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/content")
@PreAuthorize("hasRole('CONTENT_ADMIN') or hasRole('SYSTEM_ADMIN')")
public class AdminContentController {

    private final AdminContentService contentService;

    public AdminContentController(AdminContentService contentService) {
        this.contentService = Objects.requireNonNull(contentService, "contentService must not be null");
    }

    @GetMapping("/items")
    public ResponseEntity<PageResponse<AdminContentItemDto>> getItems(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return ResponseEntity.ok(contentService.findContentItems(search, contentType, status, pageable));
    }

    @PostMapping("/words")
    public ResponseEntity<AdminContentItemDto> createWord(
            @Valid @RequestBody CreateWordDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createWordDraft(request, currentUser.getId()));
    }

    @PostMapping("/decks")
    public ResponseEntity<AdminContentItemDto> createDeck(
            @Valid @RequestBody CreateDeckDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createDeckDraft(request, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/submit")
    public ResponseEntity<AdminRevisionDto> submitRevision(
            @PathVariable UUID id,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(contentService.submitRevision(id, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/approve")
    public ResponseEntity<AdminRevisionDto> approveRevision(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewDecisionBody body,
            @CurrentUser AuthenticatedUser currentUser) {
        String note = body != null ? body.reviewNote() : null;
        return ResponseEntity.ok(contentService.approveRevision(id, note, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/reject")
    public ResponseEntity<AdminRevisionDto> rejectRevision(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewDecisionBody body,
            @CurrentUser AuthenticatedUser currentUser) {
        String note = body != null ? body.reviewNote() : null;
        return ResponseEntity.ok(contentService.rejectRevision(id, note, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/publish")
    public ResponseEntity<AdminRevisionDto> publishRevision(
            @PathVariable UUID id,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(contentService.publishRevision(id, currentUser.getId()));
    }

    @PostMapping("/items/{id}/archive")
    public ResponseEntity<AdminMessageResponse> archiveItem(
            @PathVariable UUID id,
            @RequestBody(required = false) ArchiveItemBody body,
            @CurrentUser AuthenticatedUser currentUser) {
        String reason = body != null ? body.reason() : null;
        return ResponseEntity.ok(contentService.archiveContentItem(id, reason, currentUser.getId()));
    }

    @GetMapping("/sources")
    public ResponseEntity<PageResponse<ContentSourceDto>> getSources(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return ResponseEntity.ok(contentService.findSources(search, pageable));
    }

    @PostMapping("/sources")
    public ResponseEntity<ContentSourceDto> createSource(
            @Valid @RequestBody CreateContentSourceRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createSource(request, currentUser.getId()));
    }

    public record ReviewDecisionBody(String reviewNote) {}
    public record ArchiveItemBody(String reason) {}
}
