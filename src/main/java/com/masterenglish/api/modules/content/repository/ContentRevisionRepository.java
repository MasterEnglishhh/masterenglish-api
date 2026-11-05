package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentRevisionRepository extends JpaRepository<ContentRevision, UUID> {
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ContentRevision r WHERE r.id=:id")
    Optional<ContentRevision> findByIdForUpdate(@Param("id") UUID id);

    Optional<ContentRevision> findByContentItemIdAndVersionNo(UUID contentItemId, int versionNo);

    @Query(
            "SELECT coalesce(max(r.versionNo), 0) FROM ContentRevision r WHERE r.contentItemId = :contentItemId")
    int findMaxVersionNoByContentItemId(@Param("contentItemId") UUID contentItemId);

    List<ContentRevision> findByContentItemIdOrderByVersionNoDesc(UUID contentItemId);
}
