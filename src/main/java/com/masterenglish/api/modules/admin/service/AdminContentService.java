package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.admin.dto.request.CreateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateDeckDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateGrammarDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreatePracticeSetDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionGroupDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateRevisionFromPublishedRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateWordDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.SaveGrammarLessonDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.SaveGrammarTopicDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRevisionDraftRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminContentDetailDto;
import com.masterenglish.api.modules.admin.dto.response.AdminContentItemDto;
import com.masterenglish.api.modules.admin.dto.response.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.response.AdminRevisionDto;
import com.masterenglish.api.modules.admin.dto.response.AdminRevisionHistoryDto;
import com.masterenglish.api.modules.admin.dto.response.AdminValidationResponse;
import com.masterenglish.api.modules.admin.dto.response.ContentSourceDto;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface AdminContentService {

    PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, Pageable pageable);

    PageResponse<AdminContentItemDto> findContentItems(
            String domain, String search, String contentType, String status, Pageable pageable);

    AdminContentDetailDto getContentItemDetail(UUID itemId);

    PageResponse<AdminRevisionHistoryDto> getRevisionHistory(UUID itemId, Pageable pageable);

    AdminContentItemDto createWordDraft(CreateWordDraftRequest request, UUID actorId);

    AdminContentItemDto createDeckDraft(CreateDeckDraftRequest request, UUID actorId);

    /** {@code itemId} null tạo bản nháp mới; có giá trị thì sửa bản nháp của mục đó. */
    AdminContentItemDto saveGrammarTopicDraft(
            UUID itemId, SaveGrammarTopicDraftRequest request, UUID actorId);

    AdminContentItemDto saveGrammarLessonDraft(
            UUID itemId, SaveGrammarLessonDraftRequest request, UUID actorId);

    AdminContentItemDto createGrammarTopicDraft(CreateGrammarDraftRequest request, UUID actorId);

    AdminContentItemDto createGrammarLessonDraft(CreateGrammarDraftRequest request, UUID actorId);

    AdminContentItemDto createQuestionDraft(CreateQuestionDraftRequest request, UUID actorId);

    AdminContentItemDto createQuestionGroupDraft(
            CreateQuestionGroupDraftRequest request, UUID actorId);

    AdminContentItemDto createPracticeSetDraft(CreatePracticeSetDraftRequest request, UUID actorId);

    AdminRevisionDto updateDraftRevision(
            UUID revisionId, UpdateRevisionDraftRequest request, UUID actorId);

    AdminRevisionDto createRevisionFromPublished(
            UUID itemId, CreateRevisionFromPublishedRequest request, UUID actorId);

    AdminValidationResponse validateRevision(UUID revisionId);

    AdminRevisionDto submitRevision(UUID revisionId, UUID actorId);

    AdminRevisionDto approveRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto rejectRevision(UUID revisionId, String reviewNote, UUID actorId);

    AdminRevisionDto publishRevision(UUID revisionId, UUID actorId);

    AdminMessageResponse archiveContentItem(UUID itemId, String reason, UUID actorId);

    AdminMessageResponse unpublishContentItem(UUID itemId, String reason, UUID actorId);

    PageResponse<ContentSourceDto> findSources(String search, Pageable pageable);

    ContentSourceDto createSource(CreateContentSourceRequest request, UUID actorId);

    ContentSourceDto updateSource(UUID sourceId, UpdateContentSourceRequest request, UUID actorId);
}
