package com.masterenglish.api.modules.identity.admin;

import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AdminUserDto(
        UUID id,
        String email,
        String displayName,
        String firstName,
        String lastName,
        String phone,
        String status,
        List<RoleCode> roles,
        int authVersion,
        int activeSessionsCount,
        Instant createdAt,
        Instant lastLoginAt,
        String lockReason,
        Instant lockedUntil) {

    public static AdminUserDto from(User user, int activeSessionsCount, String lockReason, Instant lastLoginAt) {
        Objects.requireNonNull(user, "user must not be null");
        List<RoleCode> roles = user.getUserRoles().stream()
                .map(ur -> ur.getRole().getCode())
                .sorted()
                .toList();
        return new AdminUserDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getStatus().name(),
                roles,
                user.getAuthVersion(),
                activeSessionsCount,
                user.getCreatedAt(),
                lastLoginAt,
                lockReason,
                user.getLockedUntil());
    }
}
