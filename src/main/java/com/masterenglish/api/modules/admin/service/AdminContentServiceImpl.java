package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.admin.dto.AdminMessageResponse;
import com.masterenglish.api.modules.content.admin.AdminContentItemDto;
import com.masterenglish.api.modules.content.admin.AdminRevisionDto;
import com.masterenglish.api.modules.content.admin.ContentAdminService;
import com.masterenglish.api.modules.content.admin.ContentSourceDto;
import com.masterenglish.api.modules.content.admin.CreateContentSourceRequest;
import com.masterenglish.api.modules.content.admin.CreateDeckDraftRequest;
import com.masterenglish.api.modules.content.admin.CreateWordDraftRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class AdminContentServiceImpl implements AdminContentService {

    private final ContentAdminService contentAdminService;

    public AdminContentServiceImpl(ContentAdminService contentAdminService) {
        this.contentAdminService = Objects.requireNonNull(contentAdminService, "contentAdminService must not be null");
    }

    @Override
    public PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, Pageable pageable) {
        return contentAdminService.findContentItems(search, contentType, status, pageable);
    }

    @Override
    public AdminContentItemDto createWordDraft(CreateWordDraftRequest request, UUID actorId) {
        return contentAdminService.createWordDraft(request, actorId);
    }

    @Override
    public AdminContentItemDto createDeckDraft(CreateDeckDraftRequest request, UUID actorId) {
        return contentAdminService.createDeckDraft(request, actorId);
    }

    @Override
    public AdminRevisionDto submitRevision(UUID revisionId, UUID actorId) {
        return contentAdminService.submitRevision(revisionId, actorId);
    }

    @Override
    public AdminRevisionDto approveRevision(UUID revisionId, String reviewNote, UUID actorId) {
        return contentAdminService.approveRevision(revisionId, reviewNote, actorId);
    }

    @Override
    public AdminRevisionDto rejectRevision(UUID revisionId, String reviewNote, UUID actorId) {
        return contentAdminService.rejectRevision(revisionId, reviewNote, actorId);
    }

    @Override
    public AdminRevisionDto publishRevision(UUID revisionId, UUID actorId) {
        return contentAdminService.publishRevision(revisionId, actorId);
    }

    @Override
    public AdminMessageResponse archiveContentItem(UUID itemId, String reason, UUID actorId) {
        contentAdminService.archiveContentItem(itemId, reason, actorId);
        return new AdminMessageResponse("Nội dung đã được chuyển sang trạng thái lưu trữ.");
    }

    @Override
    public PageResponse<ContentSourceDto> findSources(String search, Pageable pageable) {
        return contentAdminService.findSources(search, pageable);
    }

    @Override
    public ContentSourceDto createSource(CreateContentSourceRequest request, UUID actorId) {
        return contentAdminService.createSource(request, actorId);
    }
}
