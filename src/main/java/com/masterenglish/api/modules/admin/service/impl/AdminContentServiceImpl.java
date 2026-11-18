package com.masterenglish.api.modules.admin.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.AdminSource;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.ItemView;
import com.masterenglish.api.common.events.ContentEvents.RevisionView;
import com.masterenglish.api.common.events.ContentEvents.SourceView;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import com.masterenglish.api.modules.admin.dto.request.CreateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateDeckDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateWordDraftRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminContentItemDto;
import com.masterenglish.api.modules.admin.dto.response.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.response.AdminRevisionDto;
import com.masterenglish.api.modules.admin.dto.response.ContentSourceDto;
import com.masterenglish.api.modules.admin.service.AdminContentService;
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
        return content.findContentItems(search, contentType, status, pageable)
                .map(AdminContentServiceImpl::toItemView);
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

    private static AdminRevisionDto toRevisionView(RevisionView view) {
        return new AdminRevisionDto(
                view.revisionId(),
                view.contentItemId(),
                view.status(),
                view.versionNo(),
                view.reviewNote(),
                view.publishedAt(),
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
