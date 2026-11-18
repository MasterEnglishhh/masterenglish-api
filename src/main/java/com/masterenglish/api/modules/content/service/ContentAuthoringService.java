package com.masterenglish.api.modules.content.service;

import com.masterenglish.api.modules.content.dto.response.ContentItemResponse;
import com.masterenglish.api.modules.content.dto.response.ContentRevisionResponse;
import com.masterenglish.api.modules.content.dto.response.ContentSourceResponse;
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

    ContentItemResponse createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId);

    ContentRevisionResponse submitForReview(UUID revisionId, UUID actorUserId);

    ContentRevisionResponse approve(UUID revisionId, UUID reviewerId, String reviewNote);

    ContentRevisionResponse publish(UUID revisionId, UUID publisherId);

    ContentRevisionResponse reject(UUID revisionId, UUID reviewerId, String reviewNote);

    void archiveItem(UUID itemId, String reason, UUID actorId);

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
