package com.masterenglish.api.modules.admin.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.AdminSource;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.GrammarDraft;
import com.masterenglish.api.common.events.ContentEvents.ItemView;
import com.masterenglish.api.common.events.ContentEvents.RevisionView;
import com.masterenglish.api.common.events.ContentEvents.SourceView;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.modules.admin.dto.request.CreateContentSourceRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateDeckDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateWordDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.SaveGrammarLessonDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.SaveGrammarTopicDraftRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminContentItemDto;
import com.masterenglish.api.modules.admin.dto.response.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.response.AdminRevisionDto;
import com.masterenglish.api.modules.admin.dto.response.ContentSourceDto;
import com.masterenglish.api.modules.admin.service.AdminContentService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
@PreAuthorize("hasRole('CONTENT_ADMIN') or hasRole('SYSTEM_ADMIN')")
public class AdminContentServiceImpl implements AdminContentService {

    private final ContentEvents content;
    private final GrammarEvents grammar;
    private final ObjectMapper objectMapper;

    public AdminContentServiceImpl(
            ContentEvents content, GrammarEvents grammar, ObjectMapper objectMapper) {
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.grammar = Objects.requireNonNull(grammar, "grammar must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, String domain, Pageable pageable) {
        return content.findContentItems(search, contentType, status, domain, pageable)
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

    @Override
    public AdminContentItemDto saveGrammarTopicDraft(
            UUID itemId, SaveGrammarTopicDraftRequest request, UUID actorId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfPresent(snapshot, "slug", request.slug());
        putIfPresent(snapshot, "name", request.name());
        putIfPresent(snapshot, "description", request.description());
        putIfPresent(snapshot, "difficulty", request.difficulty());
        putIfPresent(snapshot, "sort_order", request.sortOrder());
        return saveGrammarDraft(
                itemId,
                "GRAMMAR_TOPIC",
                snapshot,
                request.sourceId(),
                request.changeSummary(),
                actorId);
    }

    @Override
    public AdminContentItemDto saveGrammarLessonDraft(
            UUID itemId, SaveGrammarLessonDraftRequest request, UUID actorId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (request.topicId() != null) {
            snapshot.put("topic_id", request.topicId().toString());
            // Ghim revision đang xuất bản; chưa có thì để trống, validator báo khi xuất bản.
            grammar.findPublishedTopic(request.topicId())
                    .ifPresent(
                            ref ->
                                    snapshot.put(
                                            "pinned_topic_revision_id",
                                            ref.revisionId().toString()));
        }
        putIfPresent(snapshot, "title", request.title());
        putIfPresent(snapshot, "position", request.position());
        putIfPresent(snapshot, "estimated_minutes", request.estimatedMinutes());
        snapshot.put("body_schema_version", 1);
        putIfPresent(snapshot, "body", request.body());
        return saveGrammarDraft(
                itemId,
                "GRAMMAR_LESSON",
                snapshot,
                request.sourceId(),
                request.changeSummary(),
                actorId);
    }

    private AdminContentItemDto saveGrammarDraft(
            UUID itemId,
            String contentType,
            Map<String, Object> snapshot,
            UUID sourceId,
            String changeSummary,
            UUID actorId) {
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot serialize snapshot.");
        }
        return toItemView(
                content.saveGrammarDraft(
                        new GrammarDraft(
                                itemId, contentType, snapshotJson, changeSummary, sourceId),
                        actorId));
    }

    /** Bỏ qua trường vắng để bản nháp chỉ mang những gì người soạn đã nhập. */
    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        target.put(key, value instanceof String text ? text.strip() : value);
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
