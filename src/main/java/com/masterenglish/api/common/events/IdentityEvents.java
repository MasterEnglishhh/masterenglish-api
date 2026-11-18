package com.masterenglish.api.common.events;

import java.util.UUID;
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
}
