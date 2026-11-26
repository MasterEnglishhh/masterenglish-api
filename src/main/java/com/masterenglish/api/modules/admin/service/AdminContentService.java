package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.admin.dto.request.CreateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateDeckDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateWordDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.SaveGrammarLessonDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.SaveGrammarTopicDraftRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminContentItemDto;
import com.masterenglish.api.modules.admin.dto.response.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.response.AdminRevisionDto;
import com.masterenglish.api.modules.admin.dto.response.ContentSourceDto;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface AdminContentService {

    PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, String domain, Pageable pageable);

    AdminContentItemDto createWordDraft(CreateWordDraftRequest request, UUID actorId);

    AdminContentItemDto createDeckDraft(CreateDeckDraftRequest request, UUID actorId);

    /** {@code itemId} null tạo bản nháp mới; có giá trị thì sửa bản nháp của mục đó. */
    AdminContentItemDto saveGrammarTopicDraft(
            UUID itemId, SaveGrammarTopicDraftRequest request, UUID actorId);

    AdminContentItemDto saveGrammarLessonDraft(
            UUID itemId, SaveGrammarLessonDraftRequest request, UUID actorId);

    AdminRevisionDto submitRevision(UUID revisionId, UUID actorId);

    AdminRevisionDto approveRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto rejectRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto publishRevision(UUID revisionId, UUID actorId);

    AdminMessageResponse archiveContentItem(UUID itemId, String reason, UUID actorId);

    PageResponse<ContentSourceDto> findSources(String search, Pageable pageable);

    ContentSourceDto createSource(CreateContentSourceRequest request, UUID actorId);
}
