package com.masterenglish.api.modules.admin.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.IdentityEvents.UserView;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.admin.dto.request.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.request.UpdateRolesRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminMessageResponse;
import com.masterenglish.api.modules.admin.dto.response.AdminUserDto;
import com.masterenglish.api.modules.admin.service.AdminUserService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminUserServiceImpl implements AdminUserService {

    private final IdentityEvents identity;
    private final ContentEvents content;

    public AdminUserServiceImpl(IdentityEvents identity, ContentEvents content) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminUserDto> findUsers(
            String search, RoleCode role, String status, Pageable pageable) {
        return identity.findUsers(search, role, status, pageable)
                .map(AdminUserServiceImpl::toUserDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDto getUserDetail(UUID id) {
        return toUserDto(identity.getUserDetail(id));
    }

    @Override
    @Transactional
    public AdminUserDto updateUserRoles(UUID id, UpdateRolesRequest request, UUID actorId) {
        UserView user = identity.updateUserRoles(id, request.roles(), actorId);
        content.recordAuditLog(
                actorId,
                "USER_ROLES_UPDATED",
                "USER",
                id,
                String.format("{\"roles\":\"%s\"}", request.roles()),
                "Cập nhật vai trò người dùng");
        return toUserDto(user);
    }

    @Override
    @Transactional
    public AdminUserDto lockUser(UUID id, LockUserRequest request, UUID actorId) {
        UserView user = identity.lockUser(id, request.reason(), request.lockedUntil(), actorId);
        content.recordAuditLog(
                actorId,
                "USER_LOCKED",
                "USER",
                id,
                String.format("{\"lockedUntil\":\"%s\"}", request.lockedUntil()),
                request.reason());
        return toUserDto(user);
    }

    @Override
    @Transactional
    public AdminUserDto unlockUser(UUID id, UUID actorId) {
        UserView user = identity.unlockUser(id, actorId);
        content.recordAuditLog(actorId, "USER_UNLOCKED", "USER", id, "{}", "Mở khóa tài khoản");
        return toUserDto(user);
    }

    @Override
    @Transactional
    public AdminMessageResponse revokeSessions(UUID id, UUID actorId) {
        identity.revokeSessions(id, actorId);
        content.recordAuditLog(
                actorId, "SESSIONS_REVOKED", "USER", id, "{}", "Thu hồi toàn bộ phiên đăng nhập");
        return new AdminMessageResponse("Đã thu hồi toàn bộ phiên đăng nhập của người dùng.");
    }

    private static AdminUserDto toUserDto(UserView view) {
        return new AdminUserDto(
                view.id(),
                view.email(),
                view.displayName(),
                view.firstName(),
                view.lastName(),
                view.phone(),
                view.status(),
                view.roles(),
                view.authVersion(),
                view.activeSessionsCount(),
                view.createdAt(),
                view.lastLoginAt(),
                view.lockReason(),
                view.lockedUntil());
    }
}
