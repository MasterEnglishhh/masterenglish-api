package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.MediaAsset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    @Query("SELECT coalesce(sum(m.fileSizeBytes), 0) FROM MediaAsset m WHERE m.status = 'READY'")
    long sumReadyBytes();

    long countByStatus(String status);
}
