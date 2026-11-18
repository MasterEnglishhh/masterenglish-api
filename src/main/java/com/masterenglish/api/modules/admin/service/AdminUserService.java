package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.admin.dto.request.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRolesRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.response.AdminUserDto;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface AdminUserService {

    PageResponse<AdminUserDto> findUsers(
            String search, RoleCode role, String status, Pageable pageable);

    AdminUserDto getUserDetail(UUID id);

    AdminUserDto updateUserRoles(UUID id, UpdateRolesRequest request, UUID actorId);

    AdminUserDto lockUser(UUID id, LockUserRequest request, UUID actorId);

    AdminUserDto unlockUser(UUID id, UUID actorId);

    AdminMessageResponse revokeSessions(UUID id, UUID actorId);
}
