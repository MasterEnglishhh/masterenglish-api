package com.masterenglish.api.common.events;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.security.RoleCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class IdentityEvents {
    private final ModuleEvents events;

    IdentityEvents(ModuleEvents events) {
        this.events = events;
    }

    public UUID resolveContentAdmin(String email) {
        return events.request(reply -> new ResolveContentAdmin(email, reply));
    }

    public void verifyContentAdmin(UUID userId) {
        events.<Void>request(reply -> new VerifyContentAdmin(userId, reply));
    }

    public void verifyAndLockUser(UUID userId) {
        events.<Void>request(reply -> new LockUser(userId, reply));
    }

    public record ResolveContentAdmin(String email, EventReply<UUID> reply) {
        @Override
        public String toString() {
            return "ResolveContentAdmin";
        }
    }

    public record VerifyContentAdmin(UUID userId, EventReply<Void> reply) {}

    public record LockUser(UUID userId, EventReply<Void> reply) {}

    public void verifyContentAuthor(UUID userId) {
        events.<Void>request(reply -> new VerifyContentAuthor(userId, reply));
    }

    public record VerifyContentAuthor(UUID userId, EventReply<Void> reply) {}

    public PageResponse<UserView> findUsers(
            String search, RoleCode role, String status, Pageable pageable) {
        return events.request(reply -> new AdminFindUsers(search, role, status, pageable, reply));
    }

    public UserView getUserDetail(UUID id) {
        return events.request(reply -> new AdminGetUserDetail(id, reply));
    }

    public UserView updateUserRoles(UUID id, List<RoleCode> roles, UUID actorId) {
        return events.request(reply -> new AdminUpdateUserRoles(id, roles, actorId, reply));
    }

    public UserView lockUser(UUID id, String reason, Instant lockedUntil, UUID actorId) {
        return events.request(reply -> new AdminLockUser(id, reason, lockedUntil, actorId, reply));
    }

    public UserView unlockUser(UUID id, UUID actorId) {
        return events.request(reply -> new AdminUnlockUser(id, actorId, reply));
    }

    public void revokeSessions(UUID id, UUID actorId) {
        events.<Void>request(reply -> new AdminRevokeSessions(id, actorId, reply));
    }

    public long countTotalUsers() {
        return events.request(reply -> new AdminCountTotalUsers(reply));
    }

    public long countActiveUsers() {
        return events.request(reply -> new AdminCountActiveUsers(reply));
    }

    public record UserView(
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

    public record AdminFindUsers(
            String search,
            RoleCode role,
            String status,
            Pageable pageable,
            EventReply<PageResponse<UserView>> reply) {}

    public record AdminGetUserDetail(UUID id, EventReply<UserView> reply) {}

    public record AdminUpdateUserRoles(
            UUID id, List<RoleCode> roles, UUID actorId, EventReply<UserView> reply) {}

    public record AdminLockUser(
            UUID id,
            String reason,
            Instant lockedUntil,
            UUID actorId,
            EventReply<UserView> reply) {}

    public record AdminUnlockUser(UUID id, UUID actorId, EventReply<UserView> reply) {}

    public record AdminRevokeSessions(UUID id, UUID actorId, EventReply<Void> reply) {}

    public record AdminCountTotalUsers(EventReply<Long> reply) {}

    public record AdminCountActiveUsers(EventReply<Long> reply) {}

    /** Emails of the given users that are ACTIVE; other users are absent from the result. */
    public Map<UUID, String> findActiveEmails(Set<UUID> userIds) {
        return events.request(reply -> new ActiveEmailsRequested(Set.copyOf(userIds), reply));
    }

    public record ActiveEmailsRequested(Set<UUID> userIds, EventReply<Map<UUID, String>> reply) {}
}
