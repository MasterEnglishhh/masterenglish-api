package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT r FROM RefreshToken r JOIN FETCH r.session s JOIN FETCH s.user u LEFT JOIN FETCH u.userRoles ur LEFT JOIN FETCH ur.role WHERE r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashWithSessionAndUser(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findBySessionIdAndRevokedAtIsNull(UUID sessionId);
}
