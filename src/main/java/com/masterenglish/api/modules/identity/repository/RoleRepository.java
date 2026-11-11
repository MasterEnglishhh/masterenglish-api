package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.entity.Role;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RoleRepository extends JpaRepository<Role, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Role> findByCode(RoleCode code);

    Set<Role> findByCodeIn(Set<RoleCode> codes);
}
