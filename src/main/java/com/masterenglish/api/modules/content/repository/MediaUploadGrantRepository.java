package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MediaUploadGrantRepository extends JpaRepository<MediaUploadGrant, UUID> {

    Optional<MediaUploadGrant> findByMediaId(UUID mediaId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(
            value =
                    """
                    UPDATE media_verification_slots SET worker_token = :token
                    WHERE slot = (
                        SELECT slot FROM media_verification_slots WHERE worker_token IS NULL
                        ORDER BY slot LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    """,
            nativeQuery = true)
    int acquirePhysicalSlot(@Param("token") UUID token);

    @org.springframework.data.jpa.repository.Modifying
    @Query(
            value =
                    "UPDATE media_verification_slots SET worker_token = NULL WHERE worker_token = :token",
            nativeQuery = true)
    int releasePhysicalSlot(@Param("token") UUID token);

    @Query(
            "SELECT g FROM MediaUploadGrant g, StorageAllocation a "
                    + "WHERE a.uploadRequestId = g.id AND a.poolId = :poolId "
                    + "AND a.state = 'RESERVED' "
                    + "AND g.status IN ('COMPLETED', 'FAILED', 'EXPIRED') "
                    + "AND g.finishedAt <= :cutoff "
                    + "AND g.expiresAt <= :now AND g.tempBucket = :bucket "
                    + "ORDER BY g.finishedAt, g.id")
    List<MediaUploadGrant> findCompletedForCleanup(
            @Param("poolId") UUID poolId,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("bucket") String bucket,
            Pageable page);

    @Query(
            "SELECT g FROM MediaUploadGrant g "
                    + "WHERE g.status = 'PENDING' AND g.expiresAt <= :now "
                    + "ORDER BY g.expiresAt, g.id")
    List<MediaUploadGrant> findExpiredPendingGrants(@Param("now") Instant now, Pageable page);

    @Query(
            "SELECT g FROM MediaUploadGrant g "
                    + "WHERE g.status = 'VERIFYING' AND g.verificationDeadlineAt <= :now "
                    + "ORDER BY g.verificationDeadlineAt, g.id")
    List<MediaUploadGrant> findDeadlineExceededVerifyingGrants(
            @Param("now") Instant now, Pageable page);

    @Query(
            value = "SELECT count(*) FROM media_verification_slots WHERE worker_token IS NOT NULL",
            nativeQuery = true)
    long countOccupiedPhysicalSlots();

    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM MediaUploadGrant g WHERE g.id = :id")
    Optional<MediaUploadGrant> findByIdForUpdate(@Param("id") UUID id);

    @Query(
            "SELECT count(r) FROM MediaUploadGrant r, MediaAsset m "
                    + "WHERE r.mediaId = m.id "
                    + "AND m.uploadedBy = :actorId "
                    + "AND m.purpose = 'CONTENT' "
                    + "AND ("
                    + "  (r.status = 'PENDING' AND r.expiresAt > :now) "
                    + "  OR (r.status = 'VERIFYING' AND r.verificationDeadlineAt > :now)"
                    + ")")
    long countOpenRequestsForActor(@Param("actorId") UUID actorId, @Param("now") Instant now);

    @Query(
            "SELECT count(r) FROM MediaUploadGrant r "
                    + "WHERE r.status = 'VERIFYING' "
                    + "AND r.leaseUntil > :now "
                    + "AND (:excludingId IS NULL OR r.id <> :excludingId)")
    long countActiveVerifications(
            @Param("excludingId") UUID excludingId, @Param("now") Instant now);

    @Query(
            value = "SELECT CURRENT_TIMESTAMP AS \"txTime\", clock_timestamp() AS \"wallTime\"",
            nativeQuery = true)
    DbTimestamps getDbTimestamps();

    interface DbTimestamps {
        Instant getTxTime();

        Instant getWallTime();
    }

    @Query(
            "SELECT count(r) FROM MediaUploadGrant r, MediaAsset m "
                    + "WHERE r.mediaId = m.id "
                    + "AND m.uploadedBy = :actorId "
                    + "AND m.purpose = 'CONTENT' "
                    + "AND r.createdAt >= :since")
    long countRequestsSinceForActor(@Param("actorId") UUID actorId, @Param("since") Instant since);
}
