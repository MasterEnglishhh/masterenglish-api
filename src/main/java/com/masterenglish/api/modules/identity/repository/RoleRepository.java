package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.entity.Role;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Short> {

    Optional<Role> findByCode(RoleCode code);
}
