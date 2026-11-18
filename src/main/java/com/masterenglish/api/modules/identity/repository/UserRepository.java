package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.fragment.PasswordResetOperations;
import com.masterenglish.api.modules.identity.repository.fragment.RegistrationOperations;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository
        extends JpaRepository<User, UUID>, RegistrationOperations, PasswordResetOperations {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Override
    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findById(UUID id);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findForAuthenticationByEmail(@Param("email") String email);

    @Query("select u.id from User u where lower(u.email) = lower(:email)")
    Optional<UUID> findIdByEmailIgnoreCase(@Param("email") String email);

    @Query("select (count(u) > 0) from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    record PendingUser(UUID id, String email, String status, Instant createdAt) {}

    record Registration(
            UUID id,
            UUID userId,
            UUID challengeId,
            String firstName,
            String lastName,
            String passwordHash,
            Instant requestExpiresAt,
            Instant otpExpiresAt,
            String otpHmac,
            boolean closed) {
        @Override
        public String toString() {
            return "Registration[redacted]";
        }
    }

    record Account(
            UUID id,
            String email,
            String status,
            int authVersion,
            String passwordHash,
            boolean verified) {
        public boolean eligible() {
            return status.equals("ACTIVE") && verified;
        }

        @Override
        public String toString() {
            return "ResetAccount[redacted]";
        }
    }

    record Challenge(UUID id, String hmac, Instant expiresAt, boolean closed) {
        @Override
        public String toString() {
            return "ResetChallenge[redacted]";
        }
    }

    record Grant(UUID id, int authVersion, Instant expiresAt, boolean closed) {}
}
