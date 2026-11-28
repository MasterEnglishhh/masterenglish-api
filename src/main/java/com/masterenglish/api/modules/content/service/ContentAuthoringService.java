package com.masterenglish.api.modules.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.modules.content.dto.response.ContentItemResponse;
import com.masterenglish.api.modules.content.dto.response.ContentRevisionResponse;
import com.masterenglish.api.modules.content.dto.response.ContentSourceResponse;
import com.masterenglish.api.modules.content.dto.response.ValidationResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentAuthoringService {

    ContentSourceResponse createSource(
            UUID id,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            UUID createdBy);

    ContentSourceResponse updateSource(
            UUID id,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            String status,
            UUID actorId);

    ContentItemResponse createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId);

    ContentItemResponse updateDraft(
            UUID itemId,
            String contentType,
            UUID actorId,
            String snapshotJson,
            String changeSummary,
            UUID sourceId);

    ContentItemResponse createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            List<UUID> sourceIds);

    ContentRevisionResponse updateDraft(
            UUID revisionId,
            long expectedVersion,
            JsonNode data,
            List<UUID> sourceIds,
            String changeSummary,
            UUID actorId);

    ContentRevisionResponse createRevisionFromPublished(
            UUID itemId,
            UUID baseRevisionId,
            Long expectedItemVersion,
            String changeSummary,
            UUID actorId);

    ValidationResult validateRevision(UUID revisionId);

    ContentRevisionResponse submitForReview(UUID revisionId, UUID actorUserId);

    ContentRevisionResponse approve(UUID revisionId, UUID reviewerId, String reviewNote);

    ContentRevisionResponse publish(UUID revisionId, UUID publisherId);

    ContentRevisionResponse reject(UUID revisionId, UUID reviewerId, String reviewNote);

    void archiveItem(UUID itemId, String reason, UUID actorId);

    void unpublishItem(UUID itemId, String reason, UUID actorId);

    boolean hasSource(UUID sourceId);

    Optional<UUID> findPublishedRevisionId(String importNamespace, String externalKey);

    UUID createAndPublish(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId);
}
