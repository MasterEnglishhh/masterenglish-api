package com.masterenglish.api.modules.admin.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.AdminSource;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.DraftEdit;
import com.masterenglish.api.common.events.ContentEvents.GrammarDraft;
import com.masterenglish.api.common.events.ContentEvents.ItemDetailView;
import com.masterenglish.api.common.events.ContentEvents.ItemView;
import com.masterenglish.api.common.events.ContentEvents.RevisionClone;
import com.masterenglish.api.common.events.ContentEvents.RevisionHistoryView;
import com.masterenglish.api.common.events.ContentEvents.RevisionView;
import com.masterenglish.api.common.events.ContentEvents.SourceView;
import com.masterenglish.api.common.events.ContentEvents.UpdateSource;
import com.masterenglish.api.common.events.ContentEvents.ValidationView;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import com.masterenglish.api.modules.admin.dto.request.CreateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateDeckDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateGrammarDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreatePracticeSetDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionGroupDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateRevisionFromPublishedRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateWordDraftRequest;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
@PreAuthorize("hasRole('CONTENT_ADMIN') or hasRole('SYSTEM_ADMIN')")
public class AdminContentServiceImpl implements AdminContentService {

    private final ContentEvents content;

    public AdminContentServiceImpl(ContentEvents content) {
        this.content = Objects.requireNonNull(content, "content must not be null");
    }

    @Override
    public PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, Pageable pageable) {
        return findContentItems(null, search, contentType, status, pageable);
    }

    @Override
    public PageResponse<AdminContentItemDto> findContentItems(
            String domain, String search, String contentType, String status, Pageable pageable) {
        return content.findContentItems(domain, search, contentType, status, pageable)
                .map(AdminContentServiceImpl::toItemView);
    }

    @Override
    public AdminContentDetailDto getContentItemDetail(UUID itemId) {
        return toDetailDto(content.getContentItemDetail(itemId));
    }

    @Override
    public PageResponse<AdminRevisionHistoryDto> getRevisionHistory(
            UUID itemId, Pageable pageable) {
        return content.getRevisionHistory(itemId, pageable)
                .map(AdminContentServiceImpl::toRevisionHistoryDto);
    }

    @Override
    public AdminContentItemDto createWordDraft(CreateWordDraftRequest request, UUID actorId) {
        return toItemView(
                content.createWordDraft(
                        new WordDraft(
                                request.lemma(),
                                request.partOfSpeech(),
                                request.cefrLevel(),
                                request.meaningVi(),
                                request.definitionEn(),
                                request.ipa(),
                                request.examplesEn(),
                                request.examplesVi(),
                                request.sourceId(),
                                request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminContentItemDto createDeckDraft(CreateDeckDraftRequest request, UUID actorId) {
        return toItemView(
                content.createDeckDraft(
                        new DeckDraft(
                                request.title(),
                                request.description(),
                                request.level(),
                                request.wordSenseIds(),
                                request.sourceId(),
                                request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminContentItemDto createGrammarTopicDraft(
            CreateGrammarDraftRequest request, UUID actorId) {
        List<UUID> sourceIds = request.sourceIds() != null ? request.sourceIds() : List.of();
        return toItemView(
                content.createGrammarTopicDraft(
                        new GrammarDraft(request.data(), sourceIds, request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminContentItemDto createGrammarLessonDraft(
            CreateGrammarDraftRequest request, UUID actorId) {
        List<UUID> sourceIds = request.sourceIds() != null ? request.sourceIds() : List.of();
        return toItemView(
                content.createGrammarLessonDraft(
                        new GrammarDraft(request.data(), sourceIds, request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminContentItemDto createQuestionDraft(
            CreateQuestionDraftRequest request, UUID actorId) {
        List<UUID> sourceIds = request.sourceIds() != null ? request.sourceIds() : List.of();
        return toItemView(
                content.createQuestionDraft(
                        new ContentEvents.QuestionDraft(
                                request.data(), sourceIds, request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminContentItemDto createQuestionGroupDraft(
            CreateQuestionGroupDraftRequest request, UUID actorId) {
        List<UUID> sourceIds = request.sourceIds() != null ? request.sourceIds() : List.of();
        return toItemView(
                content.createQuestionGroupDraft(
                        new ContentEvents.QuestionGroupDraft(
                                request.data(), sourceIds, request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminContentItemDto createPracticeSetDraft(
            CreatePracticeSetDraftRequest request, UUID actorId) {
        List<UUID> sourceIds = request.sourceIds() != null ? request.sourceIds() : List.of();
        return toItemView(
                content.createPracticeSetDraft(
                        new ContentEvents.PracticeSetDraft(
                                request.data(), sourceIds, request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminRevisionDto updateDraftRevision(
            UUID revisionId, UpdateRevisionDraftRequest request, UUID actorId) {
        return toRevisionView(
                content.updateDraftRevision(
                        new DraftEdit(
                                revisionId,
                                request.expectedVersion(),
                                request.data(),
                                request.sourceIds(),
                                request.changeSummary()),
                        actorId));
    }

    @Override
    public AdminRevisionDto createRevisionFromPublished(
            UUID itemId, CreateRevisionFromPublishedRequest request, UUID actorId) {
        UUID baseRevisionId = request != null ? request.baseRevisionId() : null;
        Long expectedItemVersion = request != null ? request.expectedItemVersion() : null;
        String changeSummary = request != null ? request.changeSummary() : null;
        return toRevisionView(
                content.createRevisionFromPublished(
                        new RevisionClone(
                                itemId, baseRevisionId, expectedItemVersion, changeSummary),
                        actorId));
    }

    @Override
    public AdminValidationResponse validateRevision(UUID revisionId) {
        ValidationView view = content.validateRevision(revisionId);
        return new AdminValidationResponse(view.valid(), view.errors());
    }

    @Override
    public AdminRevisionDto submitRevision(UUID revisionId, UUID actorId) {
        return toRevisionView(content.submitRevision(revisionId, actorId));
    }

    @Override
    public AdminRevisionDto approveRevision(UUID revisionId, String reviewNote, UUID actorId) {
        return toRevisionView(content.approveRevision(revisionId, reviewNote, actorId));
    }

    @Override
    public AdminRevisionDto rejectRevision(UUID revisionId, String reviewNote, UUID actorId) {
        return toRevisionView(content.rejectRevision(revisionId, reviewNote, actorId));
    }

    @Override
    public AdminRevisionDto publishRevision(UUID revisionId, UUID actorId) {
        return toRevisionView(content.publishRevision(revisionId, actorId));
    }

    @Override
    public AdminMessageResponse archiveContentItem(UUID itemId, String reason, UUID actorId) {
        content.archiveContentItem(itemId, reason, actorId);
        return new AdminMessageResponse("Nội dung đã được chuyển sang trạng thái lưu trữ.");
    }

    @Override
    public AdminMessageResponse unpublishContentItem(UUID itemId, String reason, UUID actorId) {
        content.unpublishContentItem(itemId, reason, actorId);
        return new AdminMessageResponse("Nội dung đã được ngừng xuất bản.");
    }

    @Override
    public PageResponse<ContentSourceDto> findSources(String search, Pageable pageable) {
        return content.findSources(search, pageable).map(AdminContentServiceImpl::toSourceView);
    }

    @Override
    public ContentSourceDto createSource(CreateContentSourceRequest request, UUID actorId) {
        return toSourceView(
                content.createSource(
                        new AdminSource(
                                request.title(),
                                request.sourceType(),
                                request.sourceUrl(),
                                request.author(),
                                request.licenseCode(),
                                request.licenseUrl(),
                                request.attribution(),
                                request.permissionNote()),
                        actorId));
    }

    @Override
    public ContentSourceDto updateSource(
            UUID sourceId, UpdateContentSourceRequest request, UUID actorId) {
        return toSourceView(
                content.updateSource(
                        new UpdateSource(
                                sourceId,
                                request.title(),
                                request.sourceType(),
                                request.sourceUrl(),
                                request.author(),
                                request.licenseCode(),
                                request.licenseUrl(),
                                request.attribution(),
                                request.permissionNote(),
                                request.status()),
                        actorId));
    }

    private static AdminContentItemDto toItemView(ItemView view) {
        return new AdminContentItemDto(
                view.id(),
                view.title(),
                view.code(),
                view.module(),
                view.type(),
                view.status(),
                view.versionNo(),
                view.summary(),
                view.author(),
                view.reviewer(),
                view.updatedAt(),
                view.level(),
                view.latestRevisionId());
    }

    private static AdminContentDetailDto toDetailDto(ItemDetailView view) {
        return new AdminContentDetailDto(
                view.id(),
                view.revisionId(),
                view.contentType(),
                view.status(),
                view.versionNo(),
                view.version(),
                view.itemVersion(),
                view.data(),
                view.sourceIds(),
                view.changeSummary(),
                view.createdBy(),
                view.reviewedBy(),
                view.reviewedAt(),
                view.reviewNote(),
                view.publishedAt(),
                view.createdAt(),
                view.updatedAt());
    }

    private static AdminRevisionDto toRevisionView(RevisionView view) {
        return new AdminRevisionDto(
                view.revisionId(),
                view.contentItemId(),
                view.status(),
                view.versionNo(),
                view.reviewNote(),
                view.publishedAt(),
                view.updatedAt(),
                view.version());
    }

    private static AdminRevisionHistoryDto toRevisionHistoryDto(RevisionHistoryView view) {
        return new AdminRevisionHistoryDto(
                view.revisionId(),
                view.contentItemId(),
                view.versionNo(),
                view.status(),
                view.version(),
                view.changeSummary(),
                view.sourceIds(),
                view.createdBy(),
                view.reviewedBy(),
                view.reviewedAt(),
                view.reviewNote(),
                view.publishedAt(),
                view.createdAt(),
                view.updatedAt());
    }

    private static ContentSourceDto toSourceView(SourceView view) {
        return new ContentSourceDto(
                view.id(),
                view.title(),
                view.sourceType(),
                view.sourceUrl(),
                view.author(),
                view.licenseCode(),
                view.status(),
                view.createdAt());
    }
}
