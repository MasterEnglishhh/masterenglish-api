package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.RevisionMediaRef;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RevisionMediaRefRepository
        extends JpaRepository<RevisionMediaRef, RevisionMediaRef.RevisionMediaRefId> {

    List<RevisionMediaRef> findByRevisionId(UUID revisionId);

    boolean existsByMediaId(UUID mediaId);

    @Query(
            """
            SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
            FROM RevisionMediaRef r, ContentRevision rev, ContentItem item
            WHERE r.mediaId = :mediaId
              AND r.revisionId = rev.id
              AND rev.contentItemId = item.id
              AND item.publishedRevisionId = rev.id
              AND item.archived = false
            """)
    boolean existsPublishedReference(@Param("mediaId") UUID mediaId);
}
