package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query("SELECT r.session.id FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<UUID> findSessionIdByTokenHash(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findBySessionIdAndRevokedAtIsNull(UUID sessionId);

    @Modifying
    @Query(
            "UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.revokedAt IS NULL AND r.session.id IN (SELECT s.id FROM AuthSession s WHERE s.user.id = :userId)")
    void revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
