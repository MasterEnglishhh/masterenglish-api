package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.events.IdentityEvents.LockUser;
import com.masterenglish.api.common.events.IdentityEvents.ResolveContentAdmin;
import com.masterenglish.api.common.events.IdentityEvents.VerifyContentAdmin;
import com.masterenglish.api.common.events.IdentityEvents.VerifyContentAuthor;
import com.masterenglish.api.modules.identity.service.UserLockService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class IdentityEventHandler {
    private final UserLockService users;

    IdentityEventHandler(UserLockService users) {
        this.users = users;
    }

    @EventListener
    void on(ResolveContentAdmin event) {
        event.reply().complete(users.resolveContentAdmin(event.email()));
    }

    @EventListener
    void on(VerifyContentAdmin event) {
        users.verifyContentAdmin(event.userId());
        event.reply().complete(null);
    }

    @EventListener
    void on(LockUser event) {
        users.verifyAndLockUser(event.userId());
        event.reply().complete(null);
    }

    @EventListener
    void on(VerifyContentAuthor event) {
        users.verifyContentAuthor(event.userId());
        event.reply().complete(null);
    }
}
