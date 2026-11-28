package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.AdminSource;
import com.masterenglish.api.common.events.ContentEvents.AuditEntry;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.DraftEdit;
import com.masterenglish.api.common.events.ContentEvents.GrammarDraft;
import com.masterenglish.api.common.events.ContentEvents.ItemDetailView;
import com.masterenglish.api.common.events.ContentEvents.ItemView;
import com.masterenglish.api.common.events.ContentEvents.QuestionDraft;
import com.masterenglish.api.common.events.ContentEvents.RevisionClone;
import com.masterenglish.api.common.events.ContentEvents.RevisionHistoryView;
import com.masterenglish.api.common.events.ContentEvents.RevisionView;
import com.masterenglish.api.common.events.ContentEvents.SavedGrammarDraft;
import com.masterenglish.api.common.events.ContentEvents.SourceView;
import com.masterenglish.api.common.events.ContentEvents.UpdateSource;
import com.masterenglish.api.common.events.ContentEvents.ValidationView;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.content.dto.response.ContentRevisionResponse;
import com.masterenglish.api.modules.content.dto.response.ValidationResult;
import com.masterenglish.api.modules.content.entity.AuditLog;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.RevisionMediaRefRepository;
import com.masterenglish.api.modules.content.service.ContentAdminService;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ContentAdminServiceImpl implements ContentAdminService {

    private final ContentAuthoringService authoringService;
    private final ContentItemRepository itemRepository;
    private final ContentRevisionRepository revisionRepository;
    private final ContentSourceRepository sourceRepository;
    private final ContentSourceLinkRepository sourceLinkRepository;
    private final AuditLogRepository auditLogRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final RevisionMediaRefRepository revisionMediaRefRepository;
    private final com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository
            uploadGrantRepository;
    private final com.masterenglish.api.modules.content.service.MediaReservationService
            mediaReservationService;
    private final com.masterenglish.api.modules.content.service.MediaStagingService
            mediaStagingService;
    private final com.masterenglish.api.modules.content.service.MediaVerificationService
            mediaVerificationService;
    private final ContentSnapshots snapshots;
    private final ObjectMapper objectMapper;
    private final IdentityEvents identityEvents;

    public ContentAdminServiceImpl(
            ContentAuthoringService authoringService,
            ContentItemRepository itemRepository,
            ContentRevisionRepository revisionRepository,
            ContentSourceRepository sourceRepository,
            ContentSourceLinkRepository sourceLinkRepository,
            AuditLogRepository auditLogRepository,
            MediaAssetRepository mediaAssetRepository,
            RevisionMediaRefRepository revisionMediaRefRepository,
            com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository
                    uploadGrantRepository,
            com.masterenglish.api.modules.content.service.MediaReservationService
                    mediaReservationService,
            com.masterenglish.api.modules.content.service.MediaStagingService mediaStagingService,
            com.masterenglish.api.modules.content.service.MediaVerificationService
                    mediaVerificationService,
            ContentSnapshots snapshots,
            ObjectMapper objectMapper,
            IdentityEvents identityEvents) {
        this.authoringService =
                Objects.requireNonNull(authoringService, "authoringService must not be null");
        this.itemRepository =
                Objects.requireNonNull(itemRepository, "itemRepository must not be null");
        this.revisionRepository =
                Objects.requireNonNull(revisionRepository, "revisionRepository must not be null");
        this.sourceRepository =
                Objects.requireNonNull(sourceRepository, "sourceRepository must not be null");
        this.sourceLinkRepository =
                Objects.requireNonNull(
                        sourceLinkRepository, "sourceLinkRepository must not be null");
        this.auditLogRepository =
                Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
        this.mediaAssetRepository =
                Objects.requireNonNull(
                        mediaAssetRepository, "mediaAssetRepository must not be null");
        this.revisionMediaRefRepository =
                Objects.requireNonNull(
                        revisionMediaRefRepository, "revisionMediaRefRepository must not be null");
        this.uploadGrantRepository =
                Objects.requireNonNull(
                        uploadGrantRepository, "uploadGrantRepository must not be null");
        this.mediaReservationService =
                Objects.requireNonNull(
                        mediaReservationService, "mediaReservationService must not be null");
        this.mediaStagingService =
                Objects.requireNonNull(mediaStagingService, "mediaStagingService must not be null");
        this.mediaVerificationService =
                Objects.requireNonNull(
                        mediaVerificationService, "mediaVerificationService must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.identityEvents =
                Objects.requireNonNull(identityEvents, "identityEvents must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ItemView> findContentItems(
            String search, String contentType, String status, Pageable pageable) {
        return findContentItems(null, search, contentType, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ItemView> findContentItems(
            String domain, String search, String contentType, String status, Pageable pageable) {
        String normDomain = normalizeDomain(domain);
        String normType = normalizeFilter(contentType);
        String searchFilter = search == null || search.isBlank() ? null : search.strip();

        if ("GRAMMAR".equals(normDomain)) {
            if (normType != null
                    && !"GRAMMAR_TOPIC".equals(normType)
                    && !"GRAMMAR_LESSON".equals(normType)) {
                return new PageResponse<>(
                        List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0, false);
            }
        } else if ("VOCABULARY".equals(normDomain)) {
            if (normType != null && !"WORD".equals(normType) && !"DECK".equals(normType)) {
                return new PageResponse<>(
                        List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0, false);
            }
        }

        Page<ContentItem> itemsPage =
                itemRepository.findContentItems(
                        normDomain, normType, normalizeFilter(status), searchFilter, pageable);
        List<ItemView> dtos = new ArrayList<>();

        for (ContentItem item : itemsPage.getContent()) {
            List<ContentRevision> revisions =
                    revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
            ContentRevision latest = revisions.isEmpty() ? null : revisions.get(0);

            String title = item.getContentType();
            String code =
                    item.getExternalKey() != null
                            ? item.getExternalKey()
                            : item.getContentType() + "-" + item.getId().toString().substring(0, 8);
            String itemStatus =
                    item.isArchived()
                            ? "ARCHIVED"
                            : (latest != null ? latest.getStatus() : "DRAFT");
            int versionNo = latest != null ? latest.getVersionNo() : 1;
            String summary =
                    latest != null && latest.getChangeSummary() != null
                            ? latest.getChangeSummary()
                            : "";
            boolean grammar = item.getContentType().startsWith("GRAMMAR_");
            String level = grammar ? "" : "B1";

            if (latest != null) {
                try {
                    JsonNode node = objectMapper.readTree(latest.getSnapshot());
                    if (node.has("data")) {
                        node = node.path("data");
                    }
                    if ("WORD".equals(item.getContentType())) {
                        title = node.path("lemma").asText(title);
                        if (node.has("senses")
                                && node.path("senses").isArray()
                                && !node.path("senses").isEmpty()) {
                            JsonNode firstSense = node.path("senses").get(0);
                            level = firstSense.path("cefr_level").asText("B1");
                            summary = firstSense.path("meaning_vi").asText(summary);
                        }
                    } else if ("DECK".equals(item.getContentType())) {
                        title = node.path("title").asText(title);
                        level = node.path("level").asText("B1");
                        summary = node.path("description").asText(summary);
                    } else if ("GRAMMAR_TOPIC".equals(item.getContentType())) {
                        title = node.path("name").asText(title);
                        level = node.path("difficulty").asText("BASIC");
                        summary = node.path("description").asText(summary);
                    } else if ("GRAMMAR_LESSON".equals(item.getContentType())) {
                        title = node.path("title").asText(title);
                        level = "BASIC";
                    } else if ("QUESTION".equals(item.getContentType())) {
                        title = node.path("prompt").asText(title);
                        level = node.path("difficulty").asText("MEDIUM");
                        summary = node.path("explanation").asText(summary);
                    }
                } catch (Exception ignored) {
                }
            }

            String module =
                    ("GRAMMAR_TOPIC".equals(item.getContentType())
                                    || "GRAMMAR_LESSON".equals(item.getContentType()))
                            ? "GRAMMAR"
                            : (("QUESTION".equals(item.getContentType())
                                            || "QUESTION_GROUP".equals(item.getContentType())
                                            || "PRACTICE_SET".equals(item.getContentType()))
                                    ? "PRACTICE"
                                    : "VOCABULARY");

            dtos.add(
                    new ItemView(
                            item.getId(),
                            title,
                            code,
                            module,
                            item.getContentType(),
                            itemStatus,
                            versionNo,
                            summary,
                            "Admin",
                            latest != null && latest.getReviewedBy() != null
                                    ? latest.getReviewedBy().toString()
                                    : null,
                            latest != null ? latest.getUpdatedAt() : item.getUpdatedAt(),
                            level,
                            latest != null ? latest.getId() : null));
        }

        return new PageResponse<>(
                dtos,
                itemsPage.getNumber(),
                itemsPage.getSize(),
                itemsPage.getTotalElements(),
                itemsPage.getTotalPages(),
                itemsPage.hasNext());
    }

    private static String normalizeDomain(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return null;
        }
        if (!"GRAMMAR".equals(normalized) && !"VOCABULARY".equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid domain: " + value);
        }
        return normalized;
    }

    @Override
    @Transactional(readOnly = true)
    public ItemDetailView getContentItemDetail(UUID itemId) {
        ContentItem item =
                itemRepository
                        .findById(itemId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: " + itemId));

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        if (revisions.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No revisions found for content item: " + itemId);
        }

        ContentRevision latest = revisions.get(0);
        JsonNode dataNode = snapshots.data(latest.getSnapshot());
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap =
                dataNode != null && dataNode.isObject()
                        ? objectMapper.convertValue(dataNode, Map.class)
                        : Map.of();

        List<UUID> sourceIds =
                sourceLinkRepository.findByIdRevisionId(latest.getId()).stream()
                        .map(link -> link.getId().getSourceId())
                        .toList();

        String status = item.isArchived() ? "ARCHIVED" : latest.getStatus();

        return new ItemDetailView(
                item.getId(),
                latest.getId(),
                item.getContentType(),
                status,
                latest.getVersionNo(),
                latest.getVersion(),
                item.getVersion(),
                dataMap,
                sourceIds,
                latest.getChangeSummary(),
                latest.getCreatedBy(),
                latest.getReviewedBy(),
                latest.getReviewedAt(),
                latest.getReviewNote(),
                latest.getPublishedAt(),
                item.getCreatedAt(),
                latest.getUpdatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RevisionHistoryView> getRevisionHistory(UUID itemId, Pageable pageable) {
        if (!itemRepository.existsById(itemId)) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Content item not found: " + itemId);
        }

        Pageable sortedPageable =
                pageable.getSort().isSorted()
                        ? pageable
                        : PageRequest.of(
                                pageable.getPageNumber(),
                                pageable.getPageSize(),
                                Sort.by(Sort.Direction.DESC, "versionNo", "id"));

        Page<ContentRevision> page = revisionRepository.findByContentItemId(itemId, sortedPageable);

        List<RevisionHistoryView> views = new ArrayList<>();
        for (ContentRevision rev : page.getContent()) {
            List<UUID> sourceIds =
                    sourceLinkRepository.findByIdRevisionId(rev.getId()).stream()
                            .map(link -> link.getId().getSourceId())
                            .toList();
            views.add(
                    new RevisionHistoryView(
                            rev.getId(),
                            rev.getContentItemId(),
                            rev.getVersionNo(),
                            rev.getStatus(),
                            rev.getVersion(),
                            rev.getChangeSummary(),
                            sourceIds,
                            rev.getCreatedBy(),
                            rev.getReviewedBy(),
                            rev.getReviewedAt(),
                            rev.getReviewNote(),
                            rev.getPublishedAt(),
                            rev.getCreatedAt(),
                            rev.getUpdatedAt()));
        }

        return new PageResponse<>(
                views,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    private static String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return "ALL".equals(normalized) ? null : normalized;
    }

    @Override
    @Transactional
    public ItemView createWordDraft(WordDraft request, UUID actorId) {
        UUID itemId = UUID.randomUUID();
        UUID senseId = UUID.randomUUID();

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("lemma", request.lemma().trim());
        snapshot.put("language_code", "en");
        if (request.ipa() != null && !request.ipa().isBlank()) {
            snapshot.put("ipa", request.ipa().trim());
        }

        Map<String, Object> sense = new HashMap<>();
        sense.put("id", senseId.toString());
        sense.put("sense_no", 1);
        sense.put("part_of_speech", request.partOfSpeech().trim().toUpperCase());
        sense.put("cefr_level", request.cefrLevel().trim().toUpperCase());
        sense.put("meaning_vi", request.meaningVi().trim());
        if (request.definitionEn() != null) {
            sense.put("definition_en", request.definitionEn().trim());
        }

        List<Map<String, Object>> examples = new ArrayList<>();
        if (request.examplesEn() != null) {
            for (int i = 0; i < request.examplesEn().size(); i++) {
                String en = request.examplesEn().get(i);
                String vi =
                        request.examplesVi() != null && i < request.examplesVi().size()
                                ? request.examplesVi().get(i)
                                : null;
                if (en != null && !en.isBlank()) {
                    Map<String, Object> ex = new HashMap<>();
                    ex.put("id", UUID.randomUUID().toString());
                    ex.put("position", i + 1);
                    ex.put("example_en", en.trim());
                    if (vi != null) ex.put("example_vi", vi.trim());
                    examples.add(ex);
                }
            }
        }
        sense.put("examples", examples);
        snapshot.put("senses", List.of(sense));

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Cannot serialize snapshot: " + e.getMessage());
        }

        var item =
                authoringService.createItemWithDraft(
                        itemId,
                        "WORD",
                        actorId,
                        "admin",
                        request.lemma().trim().toLowerCase(),
                        snapshotJson,
                        request.changeSummary() != null
                                ? request.changeSummary()
                                : "Tạo mới từ vựng",
                        request.sourceId());

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        return new ItemView(
                item.getId(),
                request.lemma().trim(),
                "WORD-" + request.lemma().trim().toLowerCase(),
                "VOCABULARY",
                "WORD",
                "DRAFT",
                1,
                request.meaningVi().trim(),
                "Admin",
                null,
                item.getCreatedAt(),
                request.cefrLevel().trim().toUpperCase(),
                revId);
    }

    private List<Map<String, Object>> deckMembers(List<UUID> wordSenseIds) {
        List<Map<String, Object>> members = new ArrayList<>();
        if (wordSenseIds == null) {
            return members;
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID senseId : wordSenseIds) {
            if (!seen.add(senseId)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Nghĩa từ bị chọn trùng: " + senseId);
            }
            var owner =
                    itemRepository
                            .findPublishedWordBySense("[{\"id\":\"" + senseId + "\"}]")
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.VALIDATION_ERROR,
                                                    "Nghĩa từ chưa thuộc từ nào đã xuất bản: "
                                                            + senseId));
            Map<String, Object> member = new HashMap<>();
            member.put("word_id", owner.getWordId().toString());
            member.put("word_sense_id", senseId.toString());
            member.put("pinned_word_revision_id", owner.getRevisionId().toString());
            member.put("position", members.size() + 1);
            members.add(member);
        }
        return members;
    }

    @Override
    @Transactional
    public ItemView createDeckDraft(DeckDraft request, UUID actorId) {
        UUID itemId = UUID.randomUUID();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("title", request.title().trim());
        if (request.description() != null) {
            snapshot.put("description", request.description().trim());
        }
        if (request.level() != null) {
            snapshot.put("level", request.level().trim().toUpperCase());
        }
        snapshot.put("members", deckMembers(request.wordSenseIds()));

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Cannot serialize snapshot: " + e.getMessage());
        }

        var item =
                authoringService.createItemWithDraft(
                        itemId,
                        "DECK",
                        actorId,
                        "admin",
                        "deck-" + itemId.toString().substring(0, 8),
                        snapshotJson,
                        request.changeSummary() != null
                                ? request.changeSummary()
                                : "Tạo mới bộ Flashcard",
                        request.sourceId());

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        return new ItemView(
                item.getId(),
                request.title().trim(),
                "DECK-" + itemId.toString().substring(0, 8),
                "VOCABULARY",
                "FLASHCARD_DECK",
                "DRAFT",
                1,
                request.description() != null ? request.description().trim() : "",
                "Admin",
                null,
                item.getCreatedAt(),
                request.level() != null ? request.level().trim().toUpperCase() : "B1",
                revId);
    }

    /** Snapshot đã được bên gọi dựng sẵn; ở đây chỉ lưu bản nháp và trả về dòng danh sách. */
    @Override
    @Transactional
    public ItemView saveGrammarDraft(SavedGrammarDraft request, UUID actorId) {
        String contentType = request.contentType();
        var item =
                request.itemId() == null
                        ? authoringService.createItemWithDraft(
                                UUID.randomUUID(),
                                contentType,
                                actorId,
                                null,
                                null,
                                request.snapshotJson(),
                                request.changeSummary(),
                                request.sourceId())
                        : authoringService.updateDraft(
                                request.itemId(),
                                contentType,
                                actorId,
                                request.snapshotJson(),
                                request.changeSummary(),
                                request.sourceId());
        ContentRevision latest =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId()).get(0);
        JsonNode data = snapshotData(latest.getSnapshot());
        boolean topic = "GRAMMAR_TOPIC".equals(contentType);
        return new ItemView(
                item.getId(),
                data.path(topic ? "name" : "title").asText(contentType),
                contentType + "-" + item.getId().toString().substring(0, 8),
                "GRAMMAR",
                contentType,
                "DRAFT",
                latest.getVersionNo(),
                data.path("description").asText(""),
                "Admin",
                null,
                latest.getUpdatedAt(),
                topic ? data.path("difficulty").asText("") : "",
                latest.getId());
    }

    private JsonNode snapshotData(String snapshot) {
        try {
            return objectMapper.readTree(snapshot).path("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored snapshot is not valid JSON", e);
        }
    }

    @Override
    @Transactional
    public ItemView createGrammarTopicDraft(GrammarDraft request, UUID actorId) {
        return createGrammarDraft("GRAMMAR_TOPIC", request, actorId);
    }

    @Override
    @Transactional
    public ItemView createGrammarLessonDraft(GrammarDraft request, UUID actorId) {
        return createGrammarDraft("GRAMMAR_LESSON", request, actorId);
    }

    @Override
    @Transactional
    public ItemView createQuestionDraft(QuestionDraft request, UUID actorId) {
        if (request == null || request.data() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data is required.");
        }
        JsonNode dataNode = objectMapper.valueToTree(request.data());
        if (!dataNode.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data must be a JSON object.");
        }
        if (dataNode.has("data")) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported snapshot field: data");
        }

        UUID itemId = UUID.randomUUID();
        String snapshotJson = dataNode.toString();

        var item =
                authoringService.createItemWithDraft(
                        itemId,
                        "QUESTION",
                        actorId,
                        null,
                        null,
                        snapshotJson,
                        request.changeSummary() != null
                                ? request.changeSummary()
                                : "Tạo mới QUESTION",
                        request.sourceIds());

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        String title = dataNode.path("prompt").asText("QUESTION");
        String level = dataNode.path("difficulty").asText("MEDIUM");
        String summary =
                dataNode.path("explanation")
                        .asText(request.changeSummary() != null ? request.changeSummary() : "");

        String code = "QUESTION-" + itemId.toString().substring(0, 8);
        return new ItemView(
                item.getId(),
                title,
                code,
                "PRACTICE",
                "QUESTION",
                "DRAFT",
                1,
                summary,
                "Admin",
                null,
                item.getCreatedAt(),
                level,
                revId);
    }

    @Override
    @Transactional
    public ItemView createQuestionGroupDraft(
            ContentEvents.QuestionGroupDraft request, UUID actorId) {
        if (request == null || request.data() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data is required.");
        }
        JsonNode dataNode = objectMapper.valueToTree(request.data());
        if (!dataNode.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data must be a JSON object.");
        }
        if (dataNode.has("data")) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported snapshot field: data");
        }

        UUID itemId = UUID.randomUUID();
        String snapshotJson = dataNode.toString();

        var item =
                authoringService.createItemWithDraft(
                        itemId,
                        "QUESTION_GROUP",
                        actorId,
                        null,
                        null,
                        snapshotJson,
                        request.changeSummary() != null
                                ? request.changeSummary()
                                : "Tạo mới QUESTION_GROUP",
                        request.sourceIds());

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        String title = dataNode.path("title").asText("QUESTION_GROUP");
        String code = "GROUP-" + itemId.toString().substring(0, 8);
        return new ItemView(
                item.getId(),
                title,
                code,
                "PRACTICE",
                "QUESTION_GROUP",
                "DRAFT",
                1,
                request.changeSummary() != null ? request.changeSummary() : "",
                "Admin",
                null,
                item.getCreatedAt(),
                null,
                revId);
    }

    @Override
    @Transactional
    public ItemView createPracticeSetDraft(ContentEvents.PracticeSetDraft request, UUID actorId) {
        if (request == null || request.data() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data is required.");
        }
        JsonNode dataNode = objectMapper.valueToTree(request.data());
        if (!dataNode.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data must be a JSON object.");
        }
        if (dataNode.has("data")) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported snapshot field: data");
        }

        UUID itemId = UUID.randomUUID();
        String snapshotJson = dataNode.toString();

        var item =
                authoringService.createItemWithDraft(
                        itemId,
                        "PRACTICE_SET",
                        actorId,
                        null,
                        null,
                        snapshotJson,
                        request.changeSummary() != null
                                ? request.changeSummary()
                                : "Tạo mới PRACTICE_SET",
                        request.sourceIds());

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        String title = dataNode.path("title").asText("PRACTICE_SET");
        String code = "SET-" + itemId.toString().substring(0, 8);
        return new ItemView(
                item.getId(),
                title,
                code,
                "PRACTICE",
                "PRACTICE_SET",
                "DRAFT",
                1,
                request.changeSummary() != null ? request.changeSummary() : "",
                "Admin",
                null,
                item.getCreatedAt(),
                null,
                revId);
    }

    private ItemView createGrammarDraft(String contentType, GrammarDraft request, UUID actorId) {
        if (request == null || request.data() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data is required.");
        }
        JsonNode dataNode = objectMapper.valueToTree(request.data());
        if (!dataNode.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "data must be a JSON object.");
        }
        if (dataNode.has("data")) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported snapshot field: data");
        }

        UUID itemId = UUID.randomUUID();
        String snapshotJson = dataNode.toString();

        var item =
                authoringService.createItemWithDraft(
                        itemId,
                        contentType,
                        actorId,
                        null,
                        null,
                        snapshotJson,
                        request.changeSummary() != null
                                ? request.changeSummary()
                                : "Tạo mới " + contentType,
                        request.sourceIds());

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        String title = contentType;
        String level = "BASIC";
        String summary = request.changeSummary() != null ? request.changeSummary() : "";
        if ("GRAMMAR_TOPIC".equals(contentType)) {
            title = dataNode.path("name").asText(title);
            level = dataNode.path("difficulty").asText("BASIC");
            summary = dataNode.path("description").asText(summary);
        } else if ("GRAMMAR_LESSON".equals(contentType)) {
            title = dataNode.path("title").asText(title);
        }

        String code = contentType + "-" + itemId.toString().substring(0, 8);
        return new ItemView(
                item.getId(),
                title,
                code,
                "GRAMMAR",
                contentType,
                "DRAFT",
                1,
                summary,
                "Admin",
                null,
                item.getCreatedAt(),
                level,
                revId);
    }

    @Override
    @Transactional
    public RevisionView updateDraftRevision(DraftEdit request, UUID actorId) {
        JsonNode dataNode =
                request.data() != null ? objectMapper.valueToTree(request.data()) : null;
        var revision =
                authoringService.updateDraft(
                        request.revisionId(),
                        request.expectedVersion(),
                        dataNode,
                        request.sourceIds(),
                        request.changeSummary(),
                        actorId);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public RevisionView createRevisionFromPublished(RevisionClone request, UUID actorId) {
        var revision =
                authoringService.createRevisionFromPublished(
                        request.itemId(),
                        request.baseRevisionId(),
                        request.expectedItemVersion(),
                        request.changeSummary(),
                        actorId);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public ValidationView validateRevision(UUID revisionId) {
        ValidationResult result = authoringService.validateRevision(revisionId);
        return new ValidationView(result.valid(), result.errors());
    }

    @Override
    @Transactional
    public RevisionView submitRevision(UUID revisionId, UUID actorId) {
        var revision = authoringService.submitForReview(revisionId, actorId);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public RevisionView approveRevision(UUID revisionId, String reviewNote, UUID actorId) {
        String note = reviewNote != null && !reviewNote.isBlank() ? reviewNote : "Approved";
        var revision = authoringService.approve(revisionId, actorId, note);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public RevisionView rejectRevision(UUID revisionId, String reviewNote, UUID actorId) {
        String note =
                reviewNote != null && !reviewNote.isBlank() ? reviewNote : "Yêu cầu chỉnh sửa";
        var revision = authoringService.reject(revisionId, actorId, note);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public RevisionView publishRevision(UUID revisionId, UUID actorId) {
        var revision = authoringService.publish(revisionId, actorId);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public void archiveContentItem(UUID itemId, String reason, UUID actorId) {
        authoringService.archiveItem(itemId, reason, actorId);
    }

    @Override
    @Transactional
    public void unpublishContentItem(UUID itemId, String reason, UUID actorId) {
        authoringService.unpublishItem(itemId, reason, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SourceView> findSources(String search, Pageable pageable) {
        String cleanSearch = search == null || search.isBlank() ? null : search.trim();
        Page<ContentSource> page = sourceRepository.findSources(cleanSearch, pageable);
        return PageResponse.from(page, ContentAdminServiceImpl::sourceView);
    }

    @Override
    @Transactional
    public SourceView createSource(AdminSource request, UUID actorId) {
        var source =
                authoringService.createSource(
                        UUID.randomUUID(),
                        request.title(),
                        request.sourceType(),
                        request.sourceUrl(),
                        request.author(),
                        request.licenseCode(),
                        request.licenseUrl(),
                        request.attribution(),
                        request.permissionNote(),
                        actorId);
        return new SourceView(
                source.getId(),
                source.getTitle(),
                source.getSourceType(),
                source.getSourceUrl(),
                source.getAuthor(),
                source.getLicenseCode(),
                source.getStatus(),
                source.getCreatedAt());
    }

    @Override
    @Transactional
    public SourceView updateSource(UpdateSource request, UUID actorId) {
        var source =
                authoringService.updateSource(
                        request.sourceId(),
                        request.title(),
                        request.sourceType(),
                        request.sourceUrl(),
                        request.author(),
                        request.licenseCode(),
                        request.licenseUrl(),
                        request.attribution(),
                        request.permissionNote(),
                        request.status(),
                        actorId);
        return new SourceView(
                source.getId(),
                source.getTitle(),
                source.getSourceType(),
                source.getSourceUrl(),
                source.getAuthor(),
                source.getLicenseCode(),
                source.getStatus(),
                source.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> findAuditLogs(
            String search, String entityType, Pageable pageable) {
        String cleanSearch = search == null || search.isBlank() ? null : search.trim();
        String cleanEntity =
                entityType == null || entityType.isBlank() || "ALL".equalsIgnoreCase(entityType)
                        ? null
                        : entityType.trim();

        String searchPattern = cleanSearch != null ? "%" + cleanSearch.toLowerCase() + "%" : null;
        Page<AuditLog> page =
                auditLogRepository.findAuditLogs(searchPattern, cleanEntity, pageable);
        return PageResponse.from(page, log -> auditEntry(log));
    }

    @Override
    @Transactional
    public void recordAuditLog(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            String metadata,
            String reason) {
        auditLogRepository.save(
                new AuditLog("USER", actorId, action, entityType, entityId, metadata, reason));
    }

    @Override
    @Transactional(readOnly = true)
    public long getStorageReadyBytes() {
        return mediaAssetRepository.sumReadyBytes();
    }

    @Override
    @Transactional(readOnly = true)
    public long getStorageReadyCount() {
        return mediaAssetRepository.countByStatus("READY");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> getRecentActivities(int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        Page<AuditLog> page =
                auditLogRepository.findAll(
                        PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return page.getContent().stream().map(log -> auditEntry(log)).toList();
    }

    private RevisionView toRevisionDto(ContentRevisionResponse revision) {
        return new RevisionView(
                revision.getId(),
                revision.getContentItemId(),
                revision.getStatus(),
                revision.getVersionNo(),
                revision.getReviewNote(),
                revision.getPublishedAt(),
                revision.getUpdatedAt(),
                revision.getVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public long countTotalContentItems() {
        return itemRepository.countByArchivedFalse();
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingReviewRevisions() {
        return revisionRepository.countByStatus("IN_REVIEW");
    }

    private static SourceView sourceView(ContentSource source) {
        return new SourceView(
                source.getId(),
                source.getTitle(),
                source.getSourceType(),
                source.getSourceUrl(),
                source.getAuthor(),
                source.getLicenseCode(),
                source.getStatus(),
                source.getCreatedAt());
    }

    private static AuditEntry auditEntry(AuditLog log) {
        String severity = "INFO";
        if (log.getAction().contains("LOCK")
                || log.getAction().contains("DELETE")
                || log.getAction().contains("REVOKE")
                || log.getAction().contains("REJECT")) {
            severity = "WARNING";
        }
        return new AuditEntry(
                log.getId(),
                log.getActorUserId() != null ? log.getActorUserId().toString() : "SYSTEM",
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getReason() != null ? log.getReason() : log.getAction(),
                log.getIpAddress(),
                log.getCreatedAt(),
                severity);
    }

    @Override
    public ContentEvents.UploadRequestView reserveMediaUpload(
            UUID actorId, ContentEvents.ReserveUploadRequest request) {
        com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult res =
                mediaReservationService.reserveContentUpload(
                        actorId,
                        new com.masterenglish.api.modules.content.dto.request
                                .ReserveMediaUploadRequest(
                                request.fileName(),
                                request.mimeType(),
                                request.declaredSizeBytes(),
                                request.sourceId()));
        String uploadUrl =
                "/api/v1/admin/media/upload-requests/" + res.uploadRequestId() + "/bytes";
        return new ContentEvents.UploadRequestView(
                res.uploadRequestId(),
                res.mediaId(),
                uploadUrl,
                res.expiresAt(),
                res.status(),
                res.maxSizeBytes());
    }

    @Override
    public ContentEvents.StageResult stageMediaUpload(
            UUID actorId,
            UUID uploadRequestId,
            java.io.InputStream inputStream,
            String contentType) {
        var res =
                mediaStagingService.stageMediaUpload(
                        actorId, uploadRequestId, inputStream, contentType);
        return new ContentEvents.StageResult(
                res.uploadRequestId(),
                res.mediaId(),
                res.sizeBytes(),
                res.sha256Checksum(),
                res.status());
    }

    @Override
    public ContentEvents.CompleteResult completeMediaUpload(
            UUID actorId, UUID uploadRequestId, boolean sync) {
        if (sync) {
            var res = mediaVerificationService.completeAndVerify(actorId, uploadRequestId);
            return new ContentEvents.CompleteResult(
                    res.uploadRequestId(),
                    res.mediaId(),
                    res.status(),
                    res.verificationDeadlineAt(),
                    res.claimToken(),
                    res.leaseUntil(),
                    res.leaseAcquired(),
                    res.stagedSizeBytes(),
                    res.stagedSha256());
        } else {
            var claimed = mediaVerificationService.completeUpload(actorId, uploadRequestId);
            if (claimed.leaseAcquired() && claimed.claimToken() != null) {
                UUID finalClaimToken = claimed.claimToken();
                Thread.ofVirtual()
                        .name("media-verify-" + uploadRequestId)
                        .start(
                                () -> {
                                    try {
                                        mediaVerificationService.verifyAndFinalize(
                                                uploadRequestId, finalClaimToken);
                                    } catch (Exception e) {
                                        log.warn(
                                                "Async media verification failed for uploadRequestId={}: {}",
                                                uploadRequestId,
                                                e.getClass().getSimpleName());
                                    }
                                });
            }
            return new ContentEvents.CompleteResult(
                    claimed.uploadRequestId(),
                    claimed.mediaId(),
                    claimed.status(),
                    claimed.verificationDeadlineAt(),
                    claimed.claimToken(),
                    claimed.leaseUntil(),
                    claimed.leaseAcquired(),
                    claimed.stagedSizeBytes(),
                    claimed.stagedSha256());
        }
    }

    @Override
    @Transactional
    public ContentEvents.UploadStatusView getMediaUploadStatus(UUID actorId, UUID uploadRequestId) {
        identityEvents.verifyContentAdmin(actorId);
        com.masterenglish.api.modules.content.entity.MediaUploadGrant grant =
                uploadGrantRepository
                        .findById(uploadRequestId)
                        .orElseThrow(
                                () ->
                                        new com.masterenglish.api.common.error.BusinessException(
                                                com.masterenglish.api.common.error.ErrorCode
                                                        .RESOURCE_NOT_FOUND,
                                                "Upload request not found."));
        com.masterenglish.api.modules.content.entity.MediaAsset asset =
                mediaAssetRepository
                        .findById(grant.getMediaId())
                        .orElseThrow(
                                () ->
                                        new com.masterenglish.api.common.error.BusinessException(
                                                com.masterenglish.api.common.error.ErrorCode
                                                        .RESOURCE_NOT_FOUND,
                                                "Media asset not found."));
        if (!Objects.equals(asset.getUploadedBy(), actorId)) {
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.RESOURCE_NOT_FOUND,
                    "Upload request not found.");
        }
        return new ContentEvents.UploadStatusView(
                grant.getId(),
                grant.getMediaId(),
                grant.getStatus(),
                grant.getExpiresAt(),
                grant.getDeclaredSizeBytes(),
                grant.getDeclaredMimeType(),
                grant.getStagedSizeBytes(),
                grant.getStagedSha256(),
                grant.getStagedAt(),
                grant.getFinishedAt(),
                grant.getFailureCode());
    }

    @Override
    @Transactional
    public ContentEvents.MediaAssetView getMediaAsset(UUID actorId, UUID assetId) {
        identityEvents.verifyContentAdmin(actorId);
        com.masterenglish.api.modules.content.entity.MediaAsset asset =
                mediaAssetRepository
                        .findById(assetId)
                        .orElseThrow(
                                () ->
                                        new com.masterenglish.api.common.error.BusinessException(
                                                com.masterenglish.api.common.error.ErrorCode
                                                        .RESOURCE_NOT_FOUND,
                                                "Media asset not found."));
        if (!Objects.equals(asset.getUploadedBy(), actorId)) {
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.RESOURCE_NOT_FOUND,
                    "Media asset not found.");
        }
        return toMediaAssetView(asset);
    }

    @Override
    @Transactional
    public ContentEvents.MediaAssetView archiveMediaAsset(UUID actorId, UUID assetId) {
        identityEvents.verifyContentAdmin(actorId);
        com.masterenglish.api.modules.content.entity.MediaAsset asset =
                mediaAssetRepository
                        .findByIdForUpdate(assetId)
                        .orElseThrow(
                                () ->
                                        new com.masterenglish.api.common.error.BusinessException(
                                                com.masterenglish.api.common.error.ErrorCode
                                                        .RESOURCE_NOT_FOUND,
                                                "Media asset not found."));
        if (!Objects.equals(asset.getUploadedBy(), actorId)) {
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.RESOURCE_NOT_FOUND,
                    "Media asset not found.");
        }
        if (!"READY".equals(asset.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Only READY media assets can be archived.");
        }
        if (revisionMediaRefRepository.existsPublishedReference(assetId)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Media asset is still referenced by published content.");
        }
        asset.archive();
        mediaAssetRepository.saveAndFlush(asset);
        return toMediaAssetView(asset);
    }

    private ContentEvents.MediaAssetView toMediaAssetView(
            com.masterenglish.api.modules.content.entity.MediaAsset asset) {
        return new ContentEvents.MediaAssetView(
                asset.getId(),
                asset.getMediaType(),
                asset.getPurpose(),
                asset.getOriginalFilename(),
                asset.getMimeType(),
                asset.getFileSizeBytes(),
                asset.getDurationMs(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getChecksumSha256(),
                asset.getSourceId(),
                asset.getUploadedBy(),
                asset.getStatus(),
                asset.getFailureCode(),
                asset.getArchivedAt(),
                asset.getCreatedAt(),
                asset.getUpdatedAt());
    }
}
