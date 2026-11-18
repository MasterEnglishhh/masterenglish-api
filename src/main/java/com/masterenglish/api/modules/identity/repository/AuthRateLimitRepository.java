package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.AuthRateLimit;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
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

    default Bucket lock(String scope, String hash, Instant now) {
        insertIfNotExists(UUID.randomUUID(), scope, hash, now);
        var row = findBucketForUpdate(scope, hash).orElseThrow();
        return new Bucket(row.getId(), row.getScope(), row.getBlockedUntil(), row.getLastSend());
    }

    @Query(
            value =
                    """
                    SELECT id, scope, blocked_until AS "blockedUntil", reset_at AS "lastSend"
                    FROM auth_rate_limits WHERE scope = :scope AND subject_hash = :hash FOR UPDATE
                    """,
            nativeQuery = true)
    Optional<BucketRow> findBucketForUpdate(
            @Param("scope") String scope, @Param("hash") String hash);

    @Modifying
    @Query(
            value = "UPDATE auth_rate_limits SET reset_at = :now, updated_at = :now WHERE id = :id",
            nativeQuery = true)
    void markSent(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    "UPDATE auth_rate_limits SET blocked_until = :until, updated_at = :now WHERE id = :id",
            nativeQuery = true)
    void block(@Param("id") UUID id, @Param("until") Instant until, @Param("now") Instant now);

    interface BucketRow {
        UUID getId();

        String getScope();

        Instant getBlockedUntil();

        Instant getLastSend();
    }

    record Bucket(UUID id, String scope, Instant blockedUntil, Instant lastSend) {}
}
