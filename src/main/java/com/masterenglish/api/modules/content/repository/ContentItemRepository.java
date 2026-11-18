package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentItem;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
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

    @org.springframework.data.jpa.repository.Query(
            value =
                    """
            SELECT i FROM ContentItem i
            WHERE (:contentType IS NULL OR i.contentType = :contentType)
              AND (:archived IS NULL OR i.archived = :archived)
            ORDER BY i.createdAt DESC
            """,
            countQuery =
                    """
            SELECT count(i) FROM ContentItem i
            WHERE (:contentType IS NULL OR i.contentType = :contentType)
              AND (:archived IS NULL OR i.archived = :archived)
            """)
    org.springframework.data.domain.Page<ContentItem> findContentItems(
            @org.springframework.data.repository.query.Param("contentType") String contentType,
            @org.springframework.data.repository.query.Param("archived") Boolean archived,
            org.springframework.data.domain.Pageable pageable);
}
