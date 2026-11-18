package com.masterenglish.api.modules.admin.dto.response;

import com.masterenglish.api.common.security.RoleCode;
import java.time.Instant;
import java.util.List;
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
        Instant lockedUntil) {}
