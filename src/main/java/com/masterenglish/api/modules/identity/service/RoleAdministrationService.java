package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.dto.response.UserProfileResponse;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.PasswordChangeStore;
import com.masterenglish.api.modules.identity.repository.RoleAuditStore;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleAdministrationService {

    private static final RoleCode SYSTEM_ADMIN = RoleCode.SYSTEM_ADMIN;
    private final RoleRepository roles;
    private final UserRepository users;
    private final AuthSessionRepository sessionRepository;
    private final AuthSessionService sessions;
    private final PasswordChangeStore passwordChanges;
    private final RoleAuditStore audit;
    private final Clock clock;

    public RoleAdministrationService(
            RoleRepository roles,
            UserRepository users,
            AuthSessionRepository sessionRepository,
            AuthSessionService sessions,
            PasswordChangeStore passwordChanges,
            RoleAuditStore audit,
            Clock clock) {
        this.roles = roles;
        this.users = users;
        this.sessionRepository = sessionRepository;
        this.sessions = sessions;
        this.passwordChanges = passwordChanges;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional
    public UserProfileResponse replaceRoles(
            UserPrincipal principal,
            UUID targetId,
            Set<RoleCode> requested,
            String requestId,
            String ip) {
        // Serialize role edits before checking the active administrator invariant.
        roles.findByCode(SYSTEM_ADMIN).orElseThrow();
        User actor = users.findByIdForUpdate(principal.getId()).orElse(null);
        if (actor == null
                || !actor.isActive()
                || actor.getAuthVersion() != principal.getAuthVersion()
                || !actor.getRoleNames().contains(SYSTEM_ADMIN.name())) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "Bạn không có quyền quản trị tài khoản.");
        }
        User target =
                targetId.equals(actor.getId())
                        ? actor
                        : users.findByIdForUpdate(targetId)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.RESOURCE_NOT_FOUND,
                                                        "Không tìm thấy tài khoản."));
        if (!target.isActive()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Tài khoản cần hoạt động và đã xác minh email.");
        }
        // Recheck after waiting for account locks. Hold the session lock through the role write.
        requireActiveSession(principal);

        Set<String> before = target.getRoleNames();
        Set<String> after = requested.stream().map(RoleCode::name).collect(Collectors.toSet());
        if (before.equals(after)) {
            return UserProfileResponse.from(target);
        }
        if (before.contains(SYSTEM_ADMIN.name())
                && !requested.contains(SYSTEM_ADMIN)
                && users.countVerifiedUsersByRoleAndStatus(SYSTEM_ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Hệ thống phải giữ ít nhất một quản trị viên đang hoạt động.");
        }

        var replacement = roles.findByCodeIn(requested);
        if (replacement.size() != requested.size()) {
            throw new IllegalStateException("Role catalogue does not contain all supported roles.");
        }
        target.replaceRoles(replacement, actor.getId());
        Instant now = clock.instant();
        passwordChanges.invalidateOpen(targetId, now);
        sessions.revokeAllSessions(targetId, RevokeReason.ROLE_CHANGE);
        audit.recordChange(actor.getId(), targetId, before, after, requestId, ip, now);
        return UserProfileResponse.from(target);
    }

    private void requireActiveSession(UserPrincipal principal) {
        boolean active =
                sessionRepository
                        .findOwnedSessionForUpdate(principal.getSessionId(), principal.getId())
                        .filter(
                                session ->
                                        session.getRevokedAt() == null
                                                && session.getExpiresAt().isAfter(clock.instant()))
                        .isPresent();
        if (!active) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED, "Phiên làm việc không hợp lệ hoặc đã hết hạn.");
        }
    }
}
