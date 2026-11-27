package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.admin.dto.request.ArchiveItemRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateDeckDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateGrammarDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreatePracticeSetDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionGroupDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateRevisionFromPublishedRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateWordDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.ReviewDecisionRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRevisionDraftRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminContentDetailDto;
import com.masterenglish.api.modules.admin.dto.response.AdminContentItemDto;
import com.masterenglish.api.modules.admin.dto.response.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.response.AdminRevisionDto;
import com.masterenglish.api.modules.admin.dto.response.AdminRevisionHistoryDto;
import com.masterenglish.api.modules.admin.dto.response.AdminValidationResponse;
import com.masterenglish.api.modules.admin.dto.response.ContentSourceDto;
import com.masterenglish.api.modules.admin.service.AdminContentService;
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
import org.springframework.web.bind.annotation.PutMapping;
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
        this.contentService =
                Objects.requireNonNull(contentService, "contentService must not be null");
    }

    @GetMapping("/items")
    public ResponseEntity<PageResponse<AdminContentItemDto>> getItems(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return ResponseEntity.ok(
                contentService.findContentItems(domain, search, contentType, status, pageable));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<AdminContentDetailDto> getItemDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.getContentItemDetail(id));
    }

    @GetMapping("/items/{id}/revisions")
    public ResponseEntity<PageResponse<AdminRevisionHistoryDto>> getRevisionHistory(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return ResponseEntity.ok(contentService.getRevisionHistory(id, pageable));
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

    @PostMapping("/grammar/topics")
    public ResponseEntity<AdminContentItemDto> createGrammarTopic(
            @Valid @RequestBody CreateGrammarDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createGrammarTopicDraft(request, currentUser.getId()));
    }

    @PostMapping("/grammar/lessons")
    public ResponseEntity<AdminContentItemDto> createGrammarLesson(
            @Valid @RequestBody CreateGrammarDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createGrammarLessonDraft(request, currentUser.getId()));
    }

    @PostMapping("/questions")
    public ResponseEntity<AdminContentItemDto> createQuestion(
            @Valid @RequestBody CreateQuestionDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createQuestionDraft(request, currentUser.getId()));
    }

    @PostMapping("/question-groups")
    public ResponseEntity<AdminContentItemDto> createQuestionGroup(
            @Valid @RequestBody CreateQuestionGroupDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createQuestionGroupDraft(request, currentUser.getId()));
    }

    @PostMapping("/practice-sets")
    public ResponseEntity<AdminContentItemDto> createPracticeSet(
            @Valid @RequestBody CreatePracticeSetDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createPracticeSetDraft(request, currentUser.getId()));
    }

    @PutMapping("/revisions/{id}")
    public ResponseEntity<AdminRevisionDto> updateDraftRevision(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRevisionDraftRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(
                contentService.updateDraftRevision(id, request, currentUser.getId()));
    }

    @PostMapping("/items/{id}/revisions")
    public ResponseEntity<AdminRevisionDto> createRevisionFromPublished(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CreateRevisionFromPublishedRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.createRevisionFromPublished(id, request, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/validate")
    public ResponseEntity<AdminValidationResponse> validateRevision(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.validateRevision(id));
    }

    @PostMapping("/revisions/{id}/submit")
    public ResponseEntity<AdminRevisionDto> submitRevision(
            @PathVariable UUID id, @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(contentService.submitRevision(id, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/approve")
    public ResponseEntity<AdminRevisionDto> approveRevision(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewDecisionRequest body,
            @CurrentUser AuthenticatedUser currentUser) {
        String note = body != null ? body.reviewNote() : null;
        return ResponseEntity.ok(contentService.approveRevision(id, note, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/reject")
    public ResponseEntity<AdminRevisionDto> rejectRevision(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewDecisionRequest body,
            @CurrentUser AuthenticatedUser currentUser) {
        String note = body != null ? body.reviewNote() : null;
        return ResponseEntity.ok(contentService.rejectRevision(id, note, currentUser.getId()));
    }

    @PostMapping("/revisions/{id}/publish")
    public ResponseEntity<AdminRevisionDto> publishRevision(
            @PathVariable UUID id, @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(contentService.publishRevision(id, currentUser.getId()));
    }

    @PostMapping("/items/{id}/archive")
    public ResponseEntity<AdminMessageResponse> archiveItem(
            @PathVariable UUID id,
            @RequestBody(required = false) ArchiveItemRequest body,
            @CurrentUser AuthenticatedUser currentUser) {
        String reason = body != null ? body.reason() : null;
        return ResponseEntity.ok(
                contentService.archiveContentItem(id, reason, currentUser.getId()));
    }

    @PostMapping("/items/{id}/unpublish")
    public ResponseEntity<AdminMessageResponse> unpublishItem(
            @PathVariable UUID id,
            @RequestBody(required = false) ArchiveItemRequest body,
            @CurrentUser AuthenticatedUser currentUser) {
        String reason = body != null ? body.reason() : null;
        return ResponseEntity.ok(
                contentService.unpublishContentItem(id, reason, currentUser.getId()));
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

    @PutMapping("/sources/{id}")
    public ResponseEntity<ContentSourceDto> updateSource(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContentSourceRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(contentService.updateSource(id, request, currentUser.getId()));
    }
}
