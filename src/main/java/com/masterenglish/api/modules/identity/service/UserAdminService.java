package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.IdentityEvents.UserView;
import com.masterenglish.api.common.security.RoleCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface UserAdminService {

    PageResponse<UserView> findUsers(
            String search, RoleCode role, String status, Pageable pageable);

    UserView getUserDetail(UUID id);

    UserView updateUserRoles(UUID id, List<RoleCode> roles, UUID actorId);

    UserView lockUser(UUID id, String reason, Instant lockedUntil, UUID actorId);

    UserView unlockUser(UUID id, UUID actorId);

    void revokeSessions(UUID id, UUID actorId);

    long countTotalUsers();

    long countActiveUsers();
}
