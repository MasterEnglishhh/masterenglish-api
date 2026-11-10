package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.admin.dto.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.user.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.user.UpdateRolesRequest;
import com.masterenglish.api.modules.content.admin.ContentAdminService;
import com.masterenglish.api.modules.identity.admin.AdminUserDto;
import com.masterenglish.api.modules.identity.admin.UserAdminService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminUserServiceImpl implements AdminUserService {

    private final UserAdminService userAdminService;
    private final ContentAdminService contentAdminService;

    public AdminUserServiceImpl(
            UserAdminService userAdminService,
            ContentAdminService contentAdminService) {
        this.userAdminService = Objects.requireNonNull(userAdminService, "userAdminService must not be null");
        this.contentAdminService = Objects.requireNonNull(contentAdminService, "contentAdminService must not be null");
    }

    @Override
    public PageResponse<AdminUserDto> findUsers(
            String search, RoleCode role, String status, Pageable pageable) {
        return userAdminService.findUsers(search, role, status, pageable);
    }

    @Override
    public AdminUserDto getUserDetail(UUID id) {
        return userAdminService.getUserDetail(id);
    }

    @Override
    @Transactional
    public AdminUserDto updateUserRoles(UUID id, UpdateRolesRequest request, UUID actorId) {
        AdminUserDto dto = userAdminService.updateUserRoles(id, request.roles(), actorId);
        contentAdminService.recordAuditLog(
                actorId,
                "USER_ROLES_UPDATED",
                "USER",
                id,
                String.format("{\"roles\":\"%s\"}", request.roles()),
                "Cập nhật vai trò người dùng");
        return dto;
    }

    @Override
    @Transactional
    public AdminUserDto lockUser(UUID id, LockUserRequest request, UUID actorId) {
        AdminUserDto dto = userAdminService.lockUser(id, request.reason(), request.lockedUntil(), actorId);
        contentAdminService.recordAuditLog(
                actorId,
                "USER_LOCKED",
                "USER",
                id,
                String.format("{\"lockedUntil\":\"%s\"}", request.lockedUntil()),
                request.reason());
        return dto;
    }

    @Override
    @Transactional
    public AdminUserDto unlockUser(UUID id, UUID actorId) {
        AdminUserDto dto = userAdminService.unlockUser(id, actorId);
        contentAdminService.recordAuditLog(
                actorId,
                "USER_UNLOCKED",
                "USER",
                id,
                "{}",
                "Mở khóa tài khoản");
        return dto;
    }

    @Override
    @Transactional
    public AdminMessageResponse revokeSessions(UUID id, UUID actorId) {
        userAdminService.revokeSessions(id, actorId);
        contentAdminService.recordAuditLog(
                actorId,
                "SESSIONS_REVOKED",
                "USER",
                id,
                "{}",
                "Thu hồi toàn bộ phiên đăng nhập");
        return new AdminMessageResponse("Đã thu hồi toàn bộ phiên đăng nhập của người dùng.");
    }
}
