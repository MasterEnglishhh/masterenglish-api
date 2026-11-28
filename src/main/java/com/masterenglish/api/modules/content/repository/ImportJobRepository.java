package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ImportJob;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    Optional<ImportJob> findByCreatedByAndRequestId(UUID createdBy, UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ImportJob j WHERE j.id = :id")
    Optional<ImportJob> findByIdForUpdate(@Param("id") UUID id);

    @Query(
            "SELECT j FROM ImportJob j "
                    + "WHERE j.status = 'UPLOADING' AND j.uploadExpiresAt <= :now "
                    + "ORDER BY j.uploadExpiresAt, j.id")
    List<ImportJob> findExpiredUploadingJobs(@Param("now") Instant now, Pageable page);

    @Query(
            "SELECT j FROM ImportJob j, StorageAllocation a "
                    + "WHERE a.importJobId = j.id AND a.poolId = :poolId "
                    + "AND a.state = 'RESERVED' "
                    + "AND j.status IN ('COMPLETED', 'PARTIAL_SUCCESS', 'FAILED', 'EXPIRED') "
                    + "AND j.finishedAt <= :cutoff "
                    + "AND j.uploadExpiresAt <= :now AND j.inputBucket = :bucket "
                    + "ORDER BY j.finishedAt, j.id")
    List<ImportJob> findTerminalForCleanup(
            @Param("poolId") UUID poolId,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("bucket") String bucket,
            Pageable page);
}
