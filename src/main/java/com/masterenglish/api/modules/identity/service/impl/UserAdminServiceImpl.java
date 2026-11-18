package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.IdentityEvents.UserView;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.service.UserAdminService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminServiceImpl implements UserAdminService {

    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final RoleRepository roleRepository;

    public UserAdminServiceImpl(
            UserRepository userRepository,
            AuthSessionRepository sessionRepository,
            RoleRepository roleRepository) {
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.sessionRepository =
                Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.roleRepository =
                Objects.requireNonNull(roleRepository, "roleRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserView> findUsers(
            String search, RoleCode role, String status, Pageable pageable) {
        String cleanSearch = search == null || search.isBlank() ? null : search.trim();
        UserStatus statusEnum = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                statusEnum = UserStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        String searchPattern = cleanSearch != null ? "%" + cleanSearch.toLowerCase() + "%" : null;
        Page<User> usersPage =
                userRepository.findAdminUsers(searchPattern, role, statusEnum, pageable);

        Instant now = Instant.now();
        List<UUID> userIds = usersPage.getContent().stream().map(User::getId).toList();
        Map<UUID, Integer> activeSessionCounts = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<Object[]> counts = sessionRepository.countActiveSessionsForUsers(userIds, now);
            for (Object[] row : counts) {
                UUID uId = (UUID) row[0];
                long c = (Long) row[1];
                activeSessionCounts.put(uId, (int) c);
            }
        }

        List<UserView> dtos =
                usersPage.getContent().stream()
                        .map(
                                u ->
                                        userView(
                                                u,
                                                activeSessionCounts.getOrDefault(u.getId(), 0),
                                                u.getStatus() == UserStatus.LOCKED
                                                        ? "Khóa quản trị"
                                                        : null,
                                                null))
                        .toList();

        return new PageResponse<>(
                dtos,
                usersPage.getNumber(),
                usersPage.getSize(),
                usersPage.getTotalElements(),
                usersPage.getTotalPages(),
                usersPage.hasNext());
    }

    @Override
    @Transactional(readOnly = true)
    public UserView getUserDetail(UUID id) {
        User user =
                userRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
        int activeSessions = (int) sessionRepository.countActiveSessions(id, Instant.now());
        return userView(user, activeSessions, null, null);
    }

    @Override
    @Transactional
    public UserView updateUserRoles(UUID id, List<RoleCode> roles, UUID actorId) {
        Objects.requireNonNull(roles, "roles must not be null");
        if (roles.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "A user must retain at least one role.");
        }

        User user =
                userRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

        List<Role> targetRoles =
                roles.stream()
                        .map(
                                code ->
                                        roleRepository
                                                .findByCode(code)
                                                .orElseThrow(
                                                        () ->
                                                                new BusinessException(
                                                                        ErrorCode.VALIDATION_ERROR,
                                                                        "Unknown role: " + code)))
                        .toList();

        int previousAuthVersion = user.getAuthVersion();
        // Add new roles
        for (Role r : targetRoles) {
            user.addRole(r);
        }

        // Remove unselected roles
        List<RoleCode> currentCodes =
                user.getUserRoles().stream().map(ur -> ur.getRole().getCode()).toList();
        for (RoleCode code : currentCodes) {
            if (!roles.contains(code)) {
                user.removeRole(code);
            }
        }
        if (user.getAuthVersion() == previousAuthVersion) {
            user.invalidateAuthentication();
        }
        sessionRepository.revokeAllActiveSessions(id, RevokeReason.ROLE_CHANGE, Instant.now());
        User saved = userRepository.saveAndFlush(user);
        return userView(saved, 0, null, null);
    }

    @Override
    @Transactional
    public UserView lockUser(UUID id, String reason, Instant lockedUntil, UUID actorId) {
        User user =
                userRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
        Instant until = lockedUntil != null ? lockedUntil : Instant.parse("2099-12-31T23:59:59Z");
        user.lock(until);
        sessionRepository.revokeAllActiveSessions(id, RevokeReason.ACCOUNT_LOCKED, Instant.now());
        User saved = userRepository.saveAndFlush(user);
        return userView(saved, 0, reason, null);
    }

    @Override
    @Transactional
    public UserView unlockUser(UUID id, UUID actorId) {
        User user =
                userRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
        user.unlock();
        User saved = userRepository.saveAndFlush(user);
        int activeSessions = (int) sessionRepository.countActiveSessions(id, Instant.now());
        return userView(saved, activeSessions, null, null);
    }

    @Override
    @Transactional
    public void revokeSessions(UUID id, UUID actorId) {
        User user =
                userRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "User not found"));
        user.invalidateAuthentication();
        sessionRepository.revokeAllActiveSessions(id, RevokeReason.SECURITY, Instant.now());
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public long countTotalUsers() {
        return userRepository.count();
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveUsers() {
        return userRepository.count(); // or filtered by ACTIVE
    }

    private static UserView userView(
            User user, int activeSessionsCount, String lockReason, Instant lastLoginAt) {
        Objects.requireNonNull(user, "user must not be null");
        List<RoleCode> roles =
                user.getUserRoles().stream().map(ur -> ur.getRole().getCode()).sorted().toList();
        return new UserView(
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
