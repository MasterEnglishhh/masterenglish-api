package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentItem;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ContentItem i WHERE i.id=:id")
    Optional<ContentItem> findByIdForUpdate(@Param("id") UUID id);

    Optional<ContentItem> findByImportNamespaceAndExternalKey(
            String importNamespace, String externalKey);

    long countByArchivedFalse();

    /**
     * Từ đã xuất bản đang chứa nghĩa `senseId`, kèm phiên bản xuất bản hiện hành để ghim vào bộ
     * thẻ.
     */
    interface PublishedSenseOwner {
        UUID getWordId();

        UUID getRevisionId();
    }

    @Query(
            value =
                    """
                    SELECT i.id AS wordId, i.published_revision_id AS revisionId
                    FROM content_items i
                    JOIN content_revisions r ON r.id = i.published_revision_id
                    WHERE i.content_type = 'WORD'
                      AND NOT i.is_archived
                      AND i.access_revoked_at IS NULL
                      AND COALESCE(r.snapshot -> 'data', r.snapshot) -> 'senses'
                          @> CAST(:probe AS jsonb)
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<PublishedSenseOwner> findPublishedWordBySense(@Param("probe") String probe);

    String ADMIN_CONTENT_FILTER =
            """
            FROM content_items i
            LEFT JOIN LATERAL (
                SELECT r.status, r.snapshot
                FROM content_revisions r
                WHERE r.content_item_id = i.id
                ORDER BY r.version_no DESC
                LIMIT 1
            ) latest ON TRUE
            WHERE (CAST(:contentType AS text) IS NULL OR i.content_type = :contentType)
              AND (CAST(:status AS text) IS NULL OR :status =
                   CASE WHEN i.is_archived THEN 'ARCHIVED' ELSE COALESCE(latest.status, 'DRAFT') END)
              AND (CAST(:domain AS text) IS NULL
                   OR (CAST(:domain AS text) = 'GRAMMAR' AND i.content_type IN ('GRAMMAR_TOPIC', 'GRAMMAR_LESSON')))
              AND (CAST(:search AS text) IS NULL
                   OR strpos(lower(COALESCE(i.external_key, i.content_type || '-' || left(CAST(i.id AS text), 8))),
                             lower(:search)) > 0
                   OR strpos(lower(COALESCE(
                       COALESCE(latest.snapshot -> 'data', latest.snapshot) ->>
                           (CASE i.content_type WHEN 'WORD' THEN 'lemma' WHEN 'DECK' THEN 'title'
                                                WHEN 'GRAMMAR_TOPIC' THEN 'name' WHEN 'GRAMMAR_LESSON' THEN 'title' END),
                       i.content_type)), lower(:search)) > 0)
            """;

    @Query(
            value = "SELECT i.* " + ADMIN_CONTENT_FILTER + " ORDER BY i.created_at DESC, i.id DESC",
            countQuery = "SELECT count(*) " + ADMIN_CONTENT_FILTER,
            nativeQuery = true)
    Page<ContentItem> findContentItems(
            @Param("contentType") String contentType,
            @Param("status") String status,
            @Param("search") String search,
            @Param("domain") String domain,
            Pageable pageable);
}
