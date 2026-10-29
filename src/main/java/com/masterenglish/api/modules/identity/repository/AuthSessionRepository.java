package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.AuthSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AuthSession s WHERE s.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);

    @Query(
            "SELECT count(s) FROM AuthSession s WHERE s.user.id = :userId AND s.revokedAt IS NULL AND s.expiresAt > :now")
    long countActiveSessions(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query(
            "SELECT s FROM AuthSession s WHERE s.user.id = :userId AND s.revokedAt IS NULL AND s.expiresAt > :now ORDER BY s.createdAt ASC")
    List<AuthSession> findActiveSessionsOldestFirst(
            @Param("userId") UUID userId, @Param("now") Instant now);

    @Query(
            "SELECT s FROM AuthSession s JOIN FETCH s.user u LEFT JOIN FETCH u.userRoles ur LEFT JOIN FETCH ur.role WHERE s.id = :id AND s.revokedAt IS NULL AND s.expiresAt > :now")
    Optional<AuthSession> findActiveSessionWithUser(
            @Param("id") UUID id, @Param("now") Instant now);

    List<AuthSession> findByUserId(UUID userId);
}
