package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.content.dto.response.ContentItemResponse;
import com.masterenglish.api.modules.content.dto.response.ContentRevisionResponse;
import com.masterenglish.api.modules.content.dto.response.ContentSourceResponse;
import com.masterenglish.api.modules.content.entity.AuditLog;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.ContentSourceLink;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ContentAuthoringServiceImpl implements ContentAuthoringService {

    private final ContentSourceRepository sourceRepository;
    private final ContentItemRepository itemRepository;
    private final ContentRevisionRepository revisionRepository;
    private final ContentSourceLinkRepository sourceLinkRepository;
    private final AuditLogRepository auditLogRepository;
    private final IdentityEvents identity;
    private final ContentSnapshots snapshots;
    private final ContentPublication publication;

    public ContentAuthoringServiceImpl(
            ContentSourceRepository sourceRepository,
            ContentItemRepository itemRepository,
            ContentRevisionRepository revisionRepository,
            ContentSourceLinkRepository sourceLinkRepository,
            AuditLogRepository auditLogRepository,
            IdentityEvents identity,
            ContentSnapshots snapshots,
            ContentPublication publication) {
        this.sourceRepository = sourceRepository;
        this.itemRepository = itemRepository;
        this.revisionRepository = revisionRepository;
        this.sourceLinkRepository = sourceLinkRepository;
        this.auditLogRepository = auditLogRepository;
        this.identity = identity;
        this.snapshots = snapshots;
        this.publication = publication;
    }

    @Override
    @Transactional
    public ContentSourceResponse createSource(
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
        identity.verifyContentAuthor(createdBy);
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
        return response(sourceRepository.save(source));
    }

    @Override
    @Transactional
    public ContentItemResponse createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {
        identity.verifyContentAuthor(createdBy);
        return response(
                createDraft(
                                itemId,
                                contentType,
                                createdBy,
                                importNamespace,
                                externalKey,
                                snapshotJson,
                                changeSummary,
                                sourceId)
                        .item());
    }

    private Draft createDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {
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
        if (!item.getContentType().equals(contentType) || !item.allowsRevisionChanges()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Content item cannot be edited.");
        }
        if (revisionRepository.existsByContentItemIdAndStatusNot(id, "PUBLISHED")) {
            throw new BusinessException(ErrorCode.CONFLICT, "An open revision already exists.");
        }
        int nextVersion = revisionRepository.findMaxVersionNoByContentItemId(item.getId()) + 1;
        ContentRevision draft =
                revisionRepository.save(
                        new ContentRevision(
                                UUID.randomUUID(),
                                item.getId(),
                                nextVersion,
                                ContentSnapshots.SCHEMA_VERSION,
                                snapshots.envelope(contentType, snapshotJson),
                                "DRAFT",
                                changeSummary,
                                createdBy));

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

        return new Draft(item, draft);
    }

    @Override
    @Transactional
    public ContentRevisionResponse submitForReview(UUID revisionId, UUID actorUserId) {
        identity.verifyContentAuthor(actorUserId);
        ContentRevision revision = revisionForUpdate(revisionId);
        submitRevision(revision, actorUserId);
        return response(revision);
    }

    private void submitRevision(ContentRevision revision, UUID actorUserId) {
        revision.submitForReview();
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        actorUserId,
                        "CONTENT_REVIEW_REQUESTED",
                        "CONTENT_REVISION",
                        revision.getId(),
                        "{}",
                        "Submitted for review"));
    }

    @Override
    @Transactional
    public ContentRevisionResponse approve(UUID revisionId, UUID reviewerId, String reviewNote) {
        identity.verifyContentAuthor(reviewerId);
        ContentRevision revision = revisionForUpdate(revisionId);
        approveRevision(revision, reviewerId, reviewNote);
        return response(revision);
    }

    private void approveRevision(ContentRevision revision, UUID reviewerId, String reviewNote) {
        revision.approve(reviewerId, reviewNote != null ? reviewNote : "Approved");
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        reviewerId,
                        "CONTENT_APPROVED",
                        "CONTENT_REVISION",
                        revision.getId(),
                        "{}",
                        reviewNote));
    }

    @Override
    @Transactional
    public ContentRevisionResponse reject(UUID revisionId, UUID reviewerId, String reviewNote) {
        identity.verifyContentAuthor(reviewerId);
        ContentRevision revision =
                revisionRepository
                        .findByIdForUpdate(revisionId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Revision not found: " + revisionId));
        revision.reject(reviewerId, reviewNote);
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        reviewerId,
                        "CONTENT_REJECTED",
                        "CONTENT_REVISION",
                        revisionId,
                        "{}",
                        reviewNote));
        return response(revisionRepository.save(revision));
    }

    @Override
    @Transactional
    public void archiveItem(UUID itemId, String reason, UUID actorId) {
        identity.verifyContentAuthor(actorId);
        ContentItem item =
                itemRepository
                        .findByIdForUpdate(itemId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: " + itemId));
        item.archive(reason);
        itemRepository.save(item);
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        actorId,
                        "CONTENT_ARCHIVED",
                        item.getContentType(),
                        itemId,
                        "{}",
                        reason));
    }

    @Override
    @Transactional
    public ContentRevisionResponse publish(UUID revisionId, UUID publisherId) {
        identity.verifyContentAuthor(publisherId);
        ContentRevision revision =
                revisionRepository
                        .findById(revisionId)
                        .orElseThrow(() -> revisionNotFound(revisionId));

        ContentItem item =
                itemRepository
                        .findByIdForUpdate(revision.getContentItemId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: "
                                                        + revision.getContentItemId()));

        publishRevision(item, revision, publisherId);
        return response(revision);
    }

    private void publishRevision(ContentItem item, ContentRevision revision, UUID publisherId) {
        log.debug("Publishing content revision {}", revision.getId());
        if (!item.allowsRevisionChanges()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Content item cannot be edited.");
        }

        // The caller locks the item before the revision is refreshed and locked.
        revisionRepository.flush(); // Preserve approval performed earlier in this transaction.
        revisionRepository.refreshForUpdate(revision);
        if ("PUBLISHED".equals(revision.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Revision is already published.");
        }
        if (!"APPROVED".equals(revision.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Only an explicitly approved revision can be published.");
        }
        publication.prepare(item, revision);
        // Write source snapshots and dependencies before their parent becomes immutable.
        revisionRepository.flush();
        revision.publish(publisherId);
        item.setPublishedRevisionId(revision.getId());
        publication.revisionPublished(item, revision);

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
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSource(UUID sourceId) {
        return sourceRepository.existsById(sourceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findPublishedRevisionId(String importNamespace, String externalKey) {
        return itemRepository
                .findByImportNamespaceAndExternalKey(importNamespace, externalKey)
                .map(ContentItem::getPublishedRevisionId);
    }

    @Override
    @Transactional
    public UUID createAndPublish(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {
        identity.verifyContentAdmin(createdBy);
        Draft draft =
                createDraft(
                        itemId,
                        contentType,
                        createdBy,
                        importNamespace,
                        externalKey,
                        snapshotJson,
                        changeSummary,
                        sourceId);
        submitRevision(draft.revision(), createdBy);
        approveRevision(draft.revision(), createdBy, "Explicit author approval");
        publishRevision(draft.item(), draft.revision(), createdBy);
        return draft.revision().getId();
    }

    private ContentRevision revisionForUpdate(UUID revisionId) {
        return revisionRepository
                .findByIdForUpdate(revisionId)
                .orElseThrow(() -> revisionNotFound(revisionId));
    }

    private static BusinessException revisionNotFound(UUID revisionId) {
        return new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "Revision not found: " + revisionId);
    }

    private record Draft(ContentItem item, ContentRevision revision) {}

    private static ContentItemResponse response(ContentItem item) {
        return new ContentItemResponse(
                item.getId(),
                item.getContentType(),
                item.getPublishedRevisionId(),
                item.getVersion(),
                item.getCreatedAt());
    }

    private static ContentRevisionResponse response(ContentRevision revision) {
        return new ContentRevisionResponse(
                revision.getId(),
                revision.getContentItemId(),
                revision.getVersionNo(),
                revision.getStatus(),
                revision.getSnapshot(),
                revision.getReviewedBy(),
                revision.getReviewedAt(),
                revision.getPublishedAt(),
                revision.getReviewNote(),
                revision.getUpdatedAt());
    }

    private static ContentSourceResponse response(ContentSource source) {
        return new ContentSourceResponse(
                source.getId(),
                source.getTitle(),
                source.getSourceType(),
                source.getSourceUrl(),
                source.getAuthor(),
                source.getLicenseCode(),
                source.getLicenseUrl(),
                source.getAttribution(),
                source.getPermissionNote(),
                source.getStatus(),
                source.getCreatedAt());
    }
}
