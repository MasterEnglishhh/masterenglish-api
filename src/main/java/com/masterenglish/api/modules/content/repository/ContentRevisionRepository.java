package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.repository.fragment.ContentRevisionLocking;
import com.masterenglish.api.modules.content.repository.fragment.PronunciationOperations;
import com.masterenglish.api.modules.content.repository.fragment.PublishedContentOperations;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContentRevisionRepository
        extends JpaRepository<ContentRevision, UUID>,
                ContentRevisionLocking,
                PublishedContentOperations,
                PronunciationOperations {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ContentRevision r WHERE r.id=:id")
    Optional<ContentRevision> findByIdForUpdate(@Param("id") UUID id);

    Optional<ContentRevision> findByContentItemIdAndVersionNo(UUID contentItemId, int versionNo);

    @Query(
            "SELECT coalesce(max(r.versionNo), 0) FROM ContentRevision r WHERE r.contentItemId = :contentItemId")
    int findMaxVersionNoByContentItemId(@Param("contentItemId") UUID contentItemId);

    List<ContentRevision> findByContentItemIdOrderByVersionNoDesc(UUID contentItemId);

    org.springframework.data.domain.Page<ContentRevision> findByContentItemId(
            UUID contentItemId, org.springframework.data.domain.Pageable pageable);

    boolean existsByContentItemIdAndStatusNot(UUID contentItemId, String status);

    @Query(
            value =
                    """
                    SELECT r.content_item_id
                    FROM content_revisions r
                    WHERE COALESCE(r.snapshot -> 'data', r.snapshot) -> 'senses'
                          @> CAST(:probe AS jsonb)
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<UUID> findOwnerItemIdBySenseProbe(@Param("probe") String probe);

    @Query(
            value =
                    """
                    SELECT r.content_item_id
                    FROM content_revisions r
                    WHERE COALESCE(r.snapshot -> 'data', r.snapshot) -> 'senses'
                          @> CAST(:probe AS jsonb)
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<UUID> findOwnerItemIdByExampleProbe(@Param("probe") String probe);

    @Query(
            value =
                    """
                    SELECT r.snapshot
                    FROM content_revisions r
                    WHERE r.content_item_id = :itemId
                      AND COALESCE(r.snapshot -> 'data', r.snapshot) -> 'senses'
                          @> CAST(:probe AS jsonb)
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<String> findSnapshotContainingExample(
            @Param("itemId") UUID itemId, @Param("probe") String probe);

    long countByStatus(String status);

    @Query(
            value = "SELECT count(*) > 0 FROM revision_media_refs WHERE revision_id = :revisionId",
            nativeQuery = true)
    boolean hasRevisionMediaRefs(@Param("revisionId") UUID revisionId);

    interface Asset {
        UUID getWordId();

        String getBucket();

        String getKey();
    }
}
