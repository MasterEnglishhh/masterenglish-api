package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Override
    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findById(UUID id);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findForAuthenticationByEmail(@Param("email") String email);

    @Query("select (count(u) > 0) from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
}
