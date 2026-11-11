package com.masterenglish.api.modules.content.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.authoring.ContentAuthoringService;
import com.masterenglish.api.modules.content.entity.AuditLog;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContentAdminServiceImpl implements ContentAdminService {

    private final ContentAuthoringService authoringService;
    private final ContentItemRepository itemRepository;
    private final ContentRevisionRepository revisionRepository;
    private final ContentSourceRepository sourceRepository;
    private final AuditLogRepository auditLogRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final ObjectMapper objectMapper;

    public ContentAdminServiceImpl(
            ContentAuthoringService authoringService,
            ContentItemRepository itemRepository,
            ContentRevisionRepository revisionRepository,
            ContentSourceRepository sourceRepository,
            AuditLogRepository auditLogRepository,
            MediaAssetRepository mediaAssetRepository,
            ObjectMapper objectMapper) {
        this.authoringService = Objects.requireNonNull(authoringService, "authoringService must not be null");
        this.itemRepository = Objects.requireNonNull(itemRepository, "itemRepository must not be null");
        this.revisionRepository = Objects.requireNonNull(revisionRepository, "revisionRepository must not be null");
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
        this.mediaAssetRepository = Objects.requireNonNull(mediaAssetRepository, "mediaAssetRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public PageResponse<AdminContentItemDto> findContentItems(
            String search, String contentType, String status, Pageable pageable) {
        String typeFilter = contentType == null || contentType.isBlank() || "ALL".equalsIgnoreCase(contentType)
                ? null
                : contentType.trim().toUpperCase();
        Boolean archivedFilter = "ARCHIVED".equalsIgnoreCase(status) ? Boolean.TRUE : null;

        Page<ContentItem> itemsPage = itemRepository.findContentItems(typeFilter, archivedFilter, pageable);
        List<AdminContentItemDto> dtos = new ArrayList<>();

        for (ContentItem item : itemsPage.getContent()) {
            List<ContentRevision> revisions = revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
            ContentRevision latest = revisions.isEmpty() ? null : revisions.get(0);

            String title = item.getContentType();
            String code = item.getExternalKey() != null
                    ? item.getExternalKey()
                    : item.getContentType() + "-" + item.getId().toString().substring(0, 8);
            String itemStatus = item.isArchived() ? "ARCHIVED" : (latest != null ? latest.getStatus() : "DRAFT");
            int versionNo = latest != null ? latest.getVersionNo() : 1;
            String summary = latest != null && latest.getChangeSummary() != null ? latest.getChangeSummary() : "";
            String level = "B1";

            if (latest != null) {
                try {
                    JsonNode node = objectMapper.readTree(latest.getSnapshot());
                    if (node.has("data")) {
                        node = node.path("data");
                    }
                    if ("WORD".equals(item.getContentType())) {
                        title = node.path("lemma").asText(title);
                        if (node.has("senses") && node.path("senses").isArray() && !node.path("senses").isEmpty()) {
                            JsonNode firstSense = node.path("senses").get(0);
                            level = firstSense.path("cefr_level").asText("B1");
                            summary = firstSense.path("meaning_vi").asText(summary);
                        }
                    } else if ("DECK".equals(item.getContentType())) {
                        title = node.path("title").asText(title);
                        level = node.path("level").asText("B1");
                        summary = node.path("description").asText(summary);
                    }
                } catch (Exception ignored) {
                }
            }

            dtos.add(new AdminContentItemDto(
                    item.getId(),
                    title,
                    code,
                    "VOCABULARY",
                    item.getContentType(),
                    itemStatus,
                    versionNo,
                    summary,
                    "Admin",
                    latest != null && latest.getReviewedBy() != null ? latest.getReviewedBy().toString() : null,
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

    @Override
    @Transactional
    public AdminContentItemDto createWordDraft(CreateWordDraftRequest request, UUID actorId) {
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
                String vi = request.examplesVi() != null && i < request.examplesVi().size() ? request.examplesVi().get(i) : null;
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
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot serialize snapshot: " + e.getMessage());
        }

        ContentItem item = authoringService.createItemWithDraft(
                itemId,
                "WORD",
                actorId,
                "admin",
                request.lemma().trim().toLowerCase(),
                snapshotJson,
                request.changeSummary() != null ? request.changeSummary() : "Tạo mới từ vựng",
                request.sourceId());

        List<ContentRevision> revisions = revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        return new AdminContentItemDto(
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

    @Override
    @Transactional
    public AdminContentItemDto createDeckDraft(CreateDeckDraftRequest request, UUID actorId) {
        UUID itemId = UUID.randomUUID();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("title", request.title().trim());
        if (request.description() != null) {
            snapshot.put("description", request.description().trim());
        }
        if (request.level() != null) {
            snapshot.put("level", request.level().trim().toUpperCase());
        }
        snapshot.put("members", List.of());

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot serialize snapshot: " + e.getMessage());
        }

        ContentItem item = authoringService.createItemWithDraft(
                itemId,
                "DECK",
                actorId,
                "admin",
                "deck-" + itemId.toString().substring(0, 8),
                snapshotJson,
                request.changeSummary() != null ? request.changeSummary() : "Tạo mới bộ Flashcard",
                request.sourceId());

        List<ContentRevision> revisions = revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        UUID revId = revisions.isEmpty() ? null : revisions.get(0).getId();

        return new AdminContentItemDto(
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

    @Override
    @Transactional
    public AdminRevisionDto submitRevision(UUID revisionId, UUID actorId) {
        ContentRevision revision = authoringService.submitForReview(revisionId, actorId);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public AdminRevisionDto approveRevision(UUID revisionId, String reviewNote, UUID actorId) {
        String note = reviewNote != null && !reviewNote.isBlank() ? reviewNote : "Approved";
        ContentRevision revision = authoringService.approve(revisionId, actorId, note);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public AdminRevisionDto rejectRevision(UUID revisionId, String reviewNote, UUID actorId) {
        String note = reviewNote != null && !reviewNote.isBlank() ? reviewNote : "Yêu cầu chỉnh sửa";
        ContentRevision revision = authoringService.reject(revisionId, actorId, note);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public AdminRevisionDto publishRevision(UUID revisionId, UUID actorId) {
        ContentRevision revision = authoringService.publish(revisionId, actorId);
        return toRevisionDto(revision);
    }

    @Override
    @Transactional
    public void archiveContentItem(UUID itemId, String reason, UUID actorId) {
        authoringService.archiveItem(itemId, reason, actorId);
    }

    @Override
    public PageResponse<ContentSourceDto> findSources(String search, Pageable pageable) {
        String cleanSearch = search == null || search.isBlank() ? null : search.trim();
        Page<ContentSource> page = sourceRepository.findSources(cleanSearch, pageable);
        return PageResponse.from(page, ContentSourceDto::from);
    }

    @Override
    @Transactional
    public ContentSourceDto createSource(CreateContentSourceRequest request, UUID actorId) {
        ContentSource source = authoringService.createSource(
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
        return ContentSourceDto.from(source);
    }

    @Override
    public PageResponse<AuditLogEntryDto> findAuditLogs(
            String search, String entityType, Pageable pageable) {
        String cleanSearch = search == null || search.isBlank() ? null : search.trim();
        String cleanEntity = entityType == null || entityType.isBlank() || "ALL".equalsIgnoreCase(entityType)
                ? null
                : entityType.trim();

        String searchPattern = cleanSearch != null ? "%" + cleanSearch.toLowerCase() + "%" : null;
        Page<AuditLog> page = auditLogRepository.findAuditLogs(searchPattern, cleanEntity, pageable);
        return PageResponse.from(page, log -> AuditLogEntryDto.from(log, null));
    }

    @Override
    @Transactional
    public void recordAuditLog(
            UUID actorId, String action, String entityType, UUID entityId, String metadata, String reason) {
        auditLogRepository.save(new AuditLog(
                "USER",
                actorId,
                action,
                entityType,
                entityId,
                metadata,
                reason));
    }

    @Override
    public long getStorageReadyBytes() {
        return mediaAssetRepository.sumReadyBytes();
    }

    @Override
    public long getStorageReadyCount() {
        return mediaAssetRepository.countByStatus("READY");
    }

    @Override
    public List<AuditLogEntryDto> getRecentActivities(int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        Page<AuditLog> page = auditLogRepository.findAll(
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return page.getContent().stream()
                .map(log -> AuditLogEntryDto.from(log, null))
                .toList();
    }

    private AdminRevisionDto toRevisionDto(ContentRevision revision) {
        return new AdminRevisionDto(
                revision.getId(),
                revision.getContentItemId(),
                revision.getStatus(),
                revision.getVersionNo(),
                revision.getReviewNote(),
                revision.getPublishedAt(),
                revision.getUpdatedAt());
    }
}
