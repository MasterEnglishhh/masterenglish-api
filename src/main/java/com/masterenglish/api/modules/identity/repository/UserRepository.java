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

    @Query(
            value = """
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.userRoles ur
            LEFT JOIN FETCH ur.role r
            WHERE (:status IS NULL OR u.status = :status)
              AND (:roleCode IS NULL OR EXISTS (
                    SELECT 1 FROM UserRole ur2
                    WHERE ur2.user = u AND ur2.role.code = :roleCode
              ))
              AND (:search IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(CONCAT(COALESCE(u.lastName, ''), ' ', COALESCE(u.firstName, ''))) LIKE LOWER(CONCAT('%', :search, '%')))
            """,
            countQuery = """
            SELECT count(DISTINCT u) FROM User u
            WHERE (:status IS NULL OR u.status = :status)
              AND (:roleCode IS NULL OR EXISTS (
                    SELECT 1 FROM UserRole ur2
                    WHERE ur2.user = u AND ur2.role.code = :roleCode
              ))
              AND (:search IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(CONCAT(COALESCE(u.lastName, ''), ' ', COALESCE(u.firstName, ''))) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    org.springframework.data.domain.Page<User> findAdminUsers(
            @Param("search") String search,
            @Param("roleCode") com.masterenglish.api.common.security.RoleCode roleCode,
            @Param("status") com.masterenglish.api.modules.identity.entity.UserStatus status,
            org.springframework.data.domain.Pageable pageable);
}
