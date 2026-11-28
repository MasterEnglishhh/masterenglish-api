package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.MediaAsset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MediaAsset m WHERE m.id = :id")
    java.util.Optional<MediaAsset> findByIdForUpdate(
            @org.springframework.data.repository.query.Param("id") UUID id);

    @Query("SELECT coalesce(sum(m.fileSizeBytes), 0) FROM MediaAsset m WHERE m.status = 'READY'")
    long sumReadyBytes();

    long countByStatus(String status);
}
