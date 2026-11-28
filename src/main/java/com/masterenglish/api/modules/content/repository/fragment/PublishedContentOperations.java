package com.masterenglish.api.modules.content.repository.fragment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PublishedContentOperations {

    String VISIBLE_SNAPSHOT =
            """
            SELECT cast(r.snapshot AS text) FROM content_revisions r
            JOIN content_items i ON i.id = r.content_item_id
            WHERE i.id = :itemId AND r.id = :revisionId AND i.content_type = :type
              AND r.status = 'PUBLISHED' AND NOT i.is_archived AND i.access_revoked_at IS NULL
            """;

    @Transactional
    default Optional<String> snapshot(UUID itemId, UUID revisionId, String type, boolean lock) {
        return lock
                ? findPublishedSnapshotForShare(itemId, revisionId, type)
                : findPublishedSnapshot(itemId, revisionId, type);
    }

    @Query(value = VISIBLE_SNAPSHOT, nativeQuery = true)
    Optional<String> findPublishedSnapshot(
            @Param("itemId") UUID itemId,
            @Param("revisionId") UUID revisionId,
            @Param("type") String type);

    @Query(value = VISIBLE_SNAPSHOT + " FOR SHARE OF i, r", nativeQuery = true)
    Optional<String> findPublishedSnapshotForShare(
            @Param("itemId") UUID itemId,
            @Param("revisionId") UUID revisionId,
            @Param("type") String type);

    String PUBLISHED_REVISION_FOR_SHARE =
            """
            SELECT r.id FROM content_revisions r
            JOIN content_items i ON i.id = r.content_item_id
            WHERE i.id = :itemId AND i.content_type = :type
              AND r.status = 'PUBLISHED' AND i.published_revision_id = r.id
              AND NOT i.is_archived AND i.access_revoked_at IS NULL
            FOR SHARE OF i, r
            """;

    @Query(value = PUBLISHED_REVISION_FOR_SHARE, nativeQuery = true)
    Optional<UUID> findPublishedRevisionForShare(
            @Param("itemId") UUID itemId, @Param("type") String type);
}
