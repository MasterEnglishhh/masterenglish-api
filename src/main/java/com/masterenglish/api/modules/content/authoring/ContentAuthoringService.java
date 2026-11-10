package com.masterenglish.api.modules.content.authoring;

import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import java.util.UUID;

public interface ContentAuthoringService {

    ContentSource createSource(
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

    ContentItem createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId);

    ContentRevision submitForReview(UUID revisionId, UUID actorUserId);

    ContentRevision approve(UUID revisionId, UUID reviewerId, String reviewNote);

    ContentRevision reject(UUID revisionId, UUID reviewerId, String reviewNote);

    ContentRevision publish(UUID revisionId, UUID publisherId);

    void archiveItem(UUID itemId, String reason, UUID actorId);

    boolean hasSource(UUID sourceId);

    java.util.Optional<UUID> findPublishedRevisionId(String importNamespace, String externalKey);

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
