package com.masterenglish.api.modules.identity.admin;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.security.RoleCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface UserAdminService {

    PageResponse<AdminUserDto> findUsers(
            String search, RoleCode role, String status, Pageable pageable);

    AdminUserDto getUserDetail(UUID id);

    AdminUserDto updateUserRoles(UUID id, List<RoleCode> roles, UUID actorId);

    AdminUserDto lockUser(UUID id, String reason, Instant lockedUntil, UUID actorId);

    AdminUserDto unlockUser(UUID id, UUID actorId);

    void revokeSessions(UUID id, UUID actorId);

    long countTotalUsers();

    long countActiveUsers();
}
