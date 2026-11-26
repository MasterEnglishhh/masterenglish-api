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

    boolean existsByContentItemIdAndStatus(UUID contentItemId, String status);

    boolean existsByContentItemIdAndStatusNot(UUID contentItemId, String status);

    long countByStatus(String status);

    interface Asset {
        UUID getWordId();

        String getBucket();

        String getKey();
    }
}
