package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.AuthRateLimitEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthRateLimitEventRepository extends JpaRepository<AuthRateLimitEvent, Long> {

    @Query(
            "SELECT count(e) FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :rateLimitId AND e.occurredAt >= :windowStart")
    long countByRateLimitIdAndOccurredAtAfter(
            @Param("rateLimitId") UUID rateLimitId, @Param("windowStart") Instant windowStart);

    @Query(
            "SELECT min(e.occurredAt) FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :id AND e.occurredAt >= :since")
    Instant firstEvent(@Param("id") UUID id, @Param("since") Instant since);

    @Modifying
    @Query(
            value =
                    "INSERT INTO auth_rate_limit_events(rate_limit_id, occurred_at) VALUES (:id, :now)",
            nativeQuery = true)
    void record(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :rateLimitId")
    void deleteByRateLimitId(@Param("rateLimitId") UUID rateLimitId);
}
