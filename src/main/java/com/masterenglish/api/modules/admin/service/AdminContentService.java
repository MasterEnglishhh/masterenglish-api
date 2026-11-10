package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.admin.dto.AdminMessageResponse;
import com.masterenglish.api.modules.content.admin.AdminContentItemDto;
import com.masterenglish.api.modules.content.admin.AdminRevisionDto;
import com.masterenglish.api.modules.content.admin.ContentSourceDto;
import com.masterenglish.api.modules.content.admin.CreateContentSourceRequest;
import com.masterenglish.api.modules.content.admin.CreateDeckDraftRequest;
import com.masterenglish.api.modules.content.admin.CreateWordDraftRequest;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface AdminContentService {

    PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, Pageable pageable);

    AdminContentItemDto createWordDraft(CreateWordDraftRequest request, UUID actorId);

    AdminContentItemDto createDeckDraft(CreateDeckDraftRequest request, UUID actorId);

    AdminRevisionDto submitRevision(UUID revisionId, UUID actorId);

    AdminRevisionDto approveRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto rejectRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto publishRevision(UUID revisionId, UUID actorId);

    AdminMessageResponse archiveContentItem(UUID itemId, String reason, UUID actorId);

    PageResponse<ContentSourceDto> findSources(String search, Pageable pageable);

    ContentSourceDto createSource(CreateContentSourceRequest request, UUID actorId);
}
