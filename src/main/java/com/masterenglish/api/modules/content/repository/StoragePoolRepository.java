package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.StoragePool;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StoragePoolRepository extends JpaRepository<StoragePool, UUID> {

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM StoragePool p WHERE p.id = :id")
    Optional<StoragePool> findByIdForUpdate(@Param("id") UUID id);

    @Query(
            value =
                    """
                    SELECT id,
                           quota_bytes AS "quotaBytes",
                           reserved_bytes AS "reservedBytes",
                           used_bytes AS "usedBytes"
                    FROM storage_pools
                    WHERE id IN (:poolIds)
                    ORDER BY id FOR UPDATE
                    """,
            nativeQuery = true)
    List<PoolQuotaRow> lockAndFetchPoolsForUpdate(@Param("poolIds") List<UUID> poolIds);

    interface PoolQuotaRow {
        UUID getId();

        Long getQuotaBytes();

        Long getReservedBytes();

        Long getUsedBytes();
    }
}
