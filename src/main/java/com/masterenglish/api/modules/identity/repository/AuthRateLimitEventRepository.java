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
            "SELECT count(e) FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :rateLimitId AND e.occurredAt >= :windowStart")
    long countByRateLimitIdAndOccurredAtAfter(
            @Param("rateLimitId") UUID rateLimitId, @Param("windowStart") Instant windowStart);

    @Modifying
    @Query("DELETE FROM AuthRateLimitEvent e WHERE e.rateLimit.id = :rateLimitId")
    void deleteByRateLimitId(@Param("rateLimitId") UUID rateLimitId);
}
