package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.AuthRateLimit;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthRateLimitRepository extends JpaRepository<AuthRateLimit, UUID> {

    Optional<AuthRateLimit> findByScopeAndSubjectHash(String scope, String subjectHash);

    @Modifying
    @Query(
            value =
                    "INSERT INTO auth_rate_limits (id, scope, subject_hash, updated_at) VALUES"
                            + " (:id, :scope, :subjectHash, :updatedAt) ON CONFLICT (scope,"
                            + " subject_hash) DO NOTHING",
            nativeQuery = true)
    void insertIfNotExists(
            @Param("id") UUID id,
            @Param("scope") String scope,
            @Param("subjectHash") String subjectHash,
            @Param("updatedAt") Instant updatedAt);
}
