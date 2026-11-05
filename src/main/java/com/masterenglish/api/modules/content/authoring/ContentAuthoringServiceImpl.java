package com.masterenglish.api.modules.content.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.entity.AuditLog;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.ContentSourceLink;
import com.masterenglish.api.modules.content.entity.RevisionDependency;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ContentAuthoringServiceImpl implements ContentAuthoringService {

    private final ContentSourceRepository sourceRepository;
    private final ContentItemRepository itemRepository;
    private final ContentRevisionRepository revisionRepository;
    private final ContentSourceLinkRepository sourceLinkRepository;
    private final RevisionDependencyRepository dependencyRepository;
    private final AuditLogRepository auditLogRepository;
    private final List<ContentPublishHook> publishHooks;
    private final ObjectMapper objectMapper;
    private final com.masterenglish.api.modules.identity.user.UserLockService userLock;
    private final VocabularySnapshotValidator validator;
    private final jakarta.persistence.EntityManager entityManager;

    public ContentAuthoringServiceImpl(
            ContentSourceRepository sourceRepository,
            ContentItemRepository itemRepository,
            ContentRevisionRepository revisionRepository,
            ContentSourceLinkRepository sourceLinkRepository,
            RevisionDependencyRepository dependencyRepository,
            AuditLogRepository auditLogRepository,
            List<ContentPublishHook> publishHooks,
            ObjectMapper objectMapper,
            com.masterenglish.api.modules.identity.user.UserLockService userLock,
            VocabularySnapshotValidator validator,
            jakarta.persistence.EntityManager entityManager) {
        this.sourceRepository = sourceRepository;
        this.itemRepository = itemRepository;
        this.revisionRepository = revisionRepository;
        this.sourceLinkRepository = sourceLinkRepository;
        this.dependencyRepository = dependencyRepository;
        this.auditLogRepository = auditLogRepository;
        this.publishHooks = publishHooks;
        this.objectMapper = objectMapper;
        this.userLock = userLock;
        this.validator = validator;
        this.entityManager = entityManager;
    }

    @Override
    public ContentSource createSource(
            UUID id,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            UUID createdBy) {
        userLock.verifyContentAdmin(createdBy);
        ContentSource source =
                new ContentSource(
                        id,
                        title,
                        sourceType,
                        sourceUrl,
                        author,
                        licenseCode,
                        licenseUrl,
                        attribution,
                        permissionNote,
                        "ACTIVE",
                        createdBy);
        return sourceRepository.save(source);
    }

    @Override
    public ContentItem createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {
        userLock.verifyContentAdmin(createdBy);
        UUID id = itemId != null ? itemId : UUID.randomUUID();
        ContentItem item =
                itemRepository
                        .findByIdForUpdate(id)
                        .orElseGet(
                                () ->
                                        itemRepository.save(
                                                new ContentItem(
                                                        id,
                                                        contentType,
                                                        createdBy,
                                                        importNamespace,
                                                        externalKey)));
        if (!item.getContentType().equals(contentType)
                || item.isArchived()
                || item.getAccessRevokedAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Content item cannot be edited.");
        }
        if (revisionRepository.findByContentItemIdOrderByVersionNoDesc(id).stream()
                .anyMatch(r -> !r.getStatus().equals("PUBLISHED"))) {
            throw new BusinessException(ErrorCode.CONFLICT, "An open revision already exists.");
        }
        snapshotJson = envelope(contentType, snapshotJson);

        int nextVersion = revisionRepository.findMaxVersionNoByContentItemId(item.getId()) + 1;
        ContentRevision draft =
                new ContentRevision(
                        UUID.randomUUID(),
                        item.getId(),
                        nextVersion,
                        (short) 1,
                        snapshotJson,
                        "DRAFT",
                        changeSummary,
                        createdBy);
        draft = revisionRepository.save(draft);

        if (sourceId != null) {
            ContentSourceLink link =
                    new ContentSourceLink(
                            draft.getId(), sourceId, "Initial draft source attribution", null);
            sourceLinkRepository.save(link);
        }

        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        createdBy,
                        "CONTENT_DRAFT_CREATED",
                        contentType,
                        item.getId(),
                        String.format(
                                "{\"revisionId\":\"%s\",\"versionNo\":%d}",
                                draft.getId(), nextVersion),
                        "Created draft content item"));

        return item;
    }

    @Override
    public ContentRevision submitForReview(UUID revisionId, UUID actorUserId) {
        userLock.verifyContentAdmin(actorUserId);
        ContentRevision revision =
                revisionRepository
                        .findByIdForUpdate(revisionId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Revision not found: " + revisionId));
        revision.submitForReview();
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        actorUserId,
                        "CONTENT_REVIEW_REQUESTED",
                        "CONTENT_REVISION",
                        revisionId,
                        "{}",
                        "Submitted for review"));
        return revisionRepository.save(revision);
    }

    @Override
    public ContentRevision approve(UUID revisionId, UUID reviewerId, String reviewNote) {
        userLock.verifyContentAdmin(reviewerId);
        ContentRevision revision =
                revisionRepository
                        .findByIdForUpdate(revisionId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Revision not found: " + revisionId));
        revision.approve(reviewerId, reviewNote != null ? reviewNote : "Approved");
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        reviewerId,
                        "CONTENT_APPROVED",
                        "CONTENT_REVISION",
                        revisionId,
                        "{}",
                        reviewNote));
        return revisionRepository.save(revision);
    }

    @Override
    public ContentRevision publish(UUID revisionId, UUID publisherId) {
        userLock.verifyContentAdmin(publisherId);
        ContentRevision revision =
                revisionRepository
                        .findById(revisionId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Revision not found: " + revisionId));

        ContentItem item =
                itemRepository
                        .findByIdForUpdate(revision.getContentItemId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: "
                                                        + revision.getContentItemId()));

        // All publication/review locks acquire item before revision.
        entityManager.flush(); // Preserve explicit approval performed earlier in this transaction.
        entityManager.refresh(revision, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if ("PUBLISHED".equals(revision.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Revision is already published.");
        }
        if (!"APPROVED".equals(revision.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Only an explicitly approved revision can be published.");
        }
        JsonNode data = snapshotData(revision.getSnapshot());
        validator.validate(item.getId(), item.getContentType(), data);
        captureSources(revision.getId());

        // Validate and record dependencies if DECK
        if ("DECK".equals(item.getContentType())) {
            recordDeckDependencies(revision);
        }

        // Call publish hooks to populate projection tables
        for (ContentPublishHook hook : publishHooks) {
            if (hook.supports(item.getContentType())) {
                hook.onPublish(
                        item.getId(),
                        revision.getId(),
                        item.getContentType(),
                        revision.getSnapshot());
            }
        }

        entityManager.flush(); // Freeze source snapshots/dependencies before making their parent
        // immutable.
        revision.publish(publisherId);
        revisionRepository.save(revision);

        item.setPublishedRevisionId(revision.getId());
        itemRepository.save(item);

        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        publisherId,
                        "CONTENT_PUBLISHED",
                        item.getContentType(),
                        item.getId(),
                        String.format(
                                "{\"revisionId\":\"%s\",\"versionNo\":%d}",
                                revision.getId(), revision.getVersionNo()),
                        "Published content revision"));

        return revision;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSource(UUID sourceId) {
        return sourceRepository.existsById(sourceId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> findPublishedRevisionId(
            String importNamespace, String externalKey) {
        return itemRepository
                .findByImportNamespaceAndExternalKey(importNamespace, externalKey)
                .map(ContentItem::getPublishedRevisionId);
    }

    @Override
    public UUID createAndPublish(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {
        ContentItem item =
                createItemWithDraft(
                        itemId,
                        contentType,
                        createdBy,
                        importNamespace,
                        externalKey,
                        snapshotJson,
                        changeSummary,
                        sourceId);
        List<ContentRevision> revs =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        ContentRevision draft = revs.get(0);
        submitForReview(draft.getId(), createdBy);
        approve(draft.getId(), createdBy, "Explicit author approval");
        ContentRevision published = publish(draft.getId(), createdBy);
        return published.getId();
    }

    private void recordDeckDependencies(ContentRevision revision) {
        try {
            JsonNode root = snapshotData(revision.getSnapshot());
            JsonNode members = root.get("members");
            if (members != null && members.isArray()) {
                for (JsonNode member : members) {
                    JsonNode pinnedRev = member.get("pinned_word_revision_id");
                    if (pinnedRev == null || pinnedRev.isNull()) {
                        pinnedRev = member.get("pinnedWordRevisionId");
                    }
                    if (pinnedRev != null && !pinnedRev.asText().isBlank()) {
                        UUID childRevId = UUID.fromString(pinnedRev.asText());
                        String relationKey = "deck_member:" + member.path("word_sense_id").asText();
                        dependencyRepository.save(
                                new RevisionDependency(revision.getId(), relationKey, childRevId));
                    }
                }
            }
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Invalid DECK snapshot JSON: " + e.getMessage());
        }
    }

    private JsonNode snapshotData(String snapshot) {
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            return root.has("data") ? root.path("data") : root;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid snapshot JSON.");
        }
    }

    private String envelope(String type, String snapshot) {
        try {
            JsonNode root = objectMapper.readTree(snapshot == null ? "{}" : snapshot);
            if (!root.isObject()
                    || (root.has("schema_version") && root.path("schema_version").asInt() != 1)
                    || (root.has("content_type")
                            && !root.path("content_type").asText().equals(type))) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Snapshot envelope does not match the item.");
            }
            var result = objectMapper.createObjectNode();
            result.put("schema_version", 1);
            result.put("content_type", type);
            var data = root.has("data") ? root.path("data") : root.deepCopy();
            if (data.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) data).remove("schema_version");
                ((com.fasterxml.jackson.databind.node.ObjectNode) data).remove("content_type");
            }
            result.set("data", data);
            return result.toString();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid snapshot JSON.");
        }
    }

    private void captureSources(UUID revisionId) {
        var links = sourceLinkRepository.findByIdRevisionId(revisionId);
        if (links.isEmpty())
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Published content needs a declared source.");
        for (var link : links) {
            var source =
                    sourceRepository
                            .findByIdForShare(link.getId().getSourceId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.VALIDATION_ERROR,
                                                    "Source unavailable."));
            if (!source.getStatus().equals("ACTIVE")
                    || ((source.getLicenseCode() == null || source.getLicenseCode().isBlank())
                            && (source.getPermissionNote() == null
                                    || source.getPermissionNote().isBlank()))) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Active source and declared permission required.");
            }
            link.captureSourceSnapshot(objectMapper.valueToTree(source).toString());
        }
    }
}
