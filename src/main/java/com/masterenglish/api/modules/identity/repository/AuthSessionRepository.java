package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.AuthSession;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AuthSession s WHERE s.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AuthSession s WHERE s.id = :id AND s.user.id = :userId")
    Optional<AuthSession> findOwnedSessionForUpdate(
            @Param("id") UUID id, @Param("userId") UUID userId);

    @Query(
            """
            SELECT s FROM AuthSession s
            WHERE s.user.id = :userId AND s.revokedAt IS NULL AND s.expiresAt > :now
            ORDER BY s.createdAt DESC, s.id
            """)
    List<AuthSession> findActiveSessionsNewestFirst(
            @Param("userId") UUID userId, @Param("now") Instant now);

    @Query(
            "SELECT count(s) FROM AuthSession s WHERE s.user.id = :userId AND s.revokedAt IS NULL"
                    + " AND s.expiresAt > :now")
    long countActiveSessions(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query(
            "SELECT s.id FROM AuthSession s WHERE s.user.id = :userId AND s.revokedAt IS NULL AND"
                    + " s.expiresAt > :now ORDER BY s.createdAt, s.id")
    List<UUID> findActiveSessionIdsOldestFirst(
            @Param("userId") UUID userId, @Param("now") Instant now);

    @Query(
            "SELECT s FROM AuthSession s JOIN FETCH s.user u LEFT JOIN FETCH u.userRoles ur LEFT"
                    + " JOIN FETCH ur.role WHERE s.id = :id AND u.id = :userId AND s.revokedAt IS NULL AND s.expiresAt >"
                    + " :now")
    Optional<AuthSession> findActiveSessionWithUser(
            @Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    List<AuthSession> findByUserId(UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(
            "UPDATE AuthSession s SET s.revokedAt = :now, s.revokeReason = :reason WHERE s.user.id = :userId AND s.revokedAt IS NULL")
    void revokeAllForUser(
            @Param("userId") UUID userId,
            @Param("reason") RevokeReason reason,
            @Param("now") Instant now);
}
