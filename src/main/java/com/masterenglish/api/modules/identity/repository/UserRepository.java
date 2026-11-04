package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Override
    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    Optional<User> findById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"userRoles", "userRoles.role"})
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findForAuthenticationByEmail(@Param("email") String email);

    @Query("select (count(u) > 0) from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query(
            """
            SELECT count(u) FROM User u JOIN u.userRoles ur
            WHERE ur.role.code = :role AND u.status = :status AND u.emailVerifiedAt IS NOT NULL
            """)
    long countVerifiedUsersByRoleAndStatus(
            @Param("role") RoleCode role, @Param("status") UserStatus status);
}
