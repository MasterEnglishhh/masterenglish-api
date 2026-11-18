package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents.AdminSource;
import com.masterenglish.api.common.events.ContentEvents.AuditEntry;
import com.masterenglish.api.common.events.ContentEvents.DeckDraft;
import com.masterenglish.api.common.events.ContentEvents.ItemView;
import com.masterenglish.api.common.events.ContentEvents.RevisionView;
import com.masterenglish.api.common.events.ContentEvents.SourceView;
import com.masterenglish.api.common.events.ContentEvents.WordDraft;
import com.masterenglish.api.modules.content.entity.AuditLog;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.service.ContentAdminService;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
        this.authoringService =
                Objects.requireNonNull(authoringService, "authoringService must not be null");
        this.itemRepository =
                Objects.requireNonNull(itemRepository, "itemRepository must not be null");
        this.revisionRepository =
                Objects.requireNonNull(revisionRepository, "revisionRepository must not be null");
        this.sourceRepository =
                Objects.requireNonNull(sourceRepository, "sourceRepository must not be null");
        this.auditLogRepository =
                Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
        this.mediaAssetRepository =
                Objects.requireNonNull(
                        mediaAssetRepository, "mediaAssetRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ItemView> findContentItems(
            String search, String contentType, String status, Pageable pageable) {
        String searchFilter = search == null || search.isBlank() ? null : search.strip();
        Page<ContentItem> itemsPage =
                itemRepository.findContentItems(
                        normalizeFilter(contentType),
                        normalizeFilter(status),
                        searchFilter,
                        pageable);
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
            String level = "B1";

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
                    }
                } catch (Exception ignored) {
                }
            }

            dtos.add(
                    new ItemView(
                            item.getId(),
                            title,
                            code,
                            "VOCABULARY",
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
        snapshot.put("members", List.of());

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

    private RevisionView toRevisionDto(
            com.masterenglish.api.modules.content.dto.response.ContentRevisionResponse revision) {
        return new RevisionView(
                revision.getId(),
                revision.getContentItemId(),
                revision.getStatus(),
                revision.getVersionNo(),
                revision.getReviewNote(),
                revision.getPublishedAt(),
                revision.getUpdatedAt());
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
}
