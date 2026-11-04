package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.AuthRateLimitEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthRateLimitEventRepository extends JpaRepository<AuthRateLimitEvent, Long> {

    @Query(
            "SELECT count(e) FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :rateLimitId AND e.reservedUntil IS NULL AND e.occurredAt >= :windowStart")
    long countByRateLimitIdAndOccurredAtAfter(
            @Param("rateLimitId") UUID rateLimitId, @Param("windowStart") Instant windowStart);

    @Query(
            """
            SELECT count(e) FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :bucketId
              AND e.occurredAt >= :windowStart
              AND (e.reservedUntil IS NULL OR e.reservedUntil > :now)
            """)
    long countOccupiedSlots(
            @Param("bucketId") UUID bucketId,
            @Param("now") Instant now,
            @Param("windowStart") Instant windowStart);

    @Modifying
    @Query(
            "DELETE FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :bucketId AND e.reservedUntil IS NULL")
    void deleteFailures(@Param("bucketId") UUID bucketId);

    @Modifying
    @Query(
            """
            DELETE FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :bucketId
              AND e.occurredAt >= :windowStart AND e.reservedUntil <= :now
            """)
    void deleteExpiredReservations(
            @Param("bucketId") UUID bucketId,
            @Param("now") Instant now,
            @Param("windowStart") Instant windowStart);
}
