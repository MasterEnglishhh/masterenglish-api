package com.masterenglish.api.modules.identity.service.impl;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.identity.service.UserAdminService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class IdentityAdminEventHandler {
    private final UserAdminService admin;

    IdentityAdminEventHandler(UserAdminService admin) {
        this.admin = admin;
    }

    @EventListener
    void on(IdentityEvents.AdminFindUsers event) {
        event.reply()
                .complete(
                        admin.findUsers(
                                event.search(), event.role(), event.status(), event.pageable()));
    }

    @EventListener
    void on(IdentityEvents.AdminGetUserDetail event) {
        event.reply().complete(admin.getUserDetail(event.id()));
    }

    @EventListener
    void on(IdentityEvents.AdminUpdateUserRoles event) {
        event.reply().complete(admin.updateUserRoles(event.id(), event.roles(), event.actorId()));
    }

    @EventListener
    void on(IdentityEvents.AdminLockUser event) {
        event.reply()
                .complete(
                        admin.lockUser(
                                event.id(), event.reason(), event.lockedUntil(), event.actorId()));
    }

    @EventListener
    void on(IdentityEvents.AdminUnlockUser event) {
        event.reply().complete(admin.unlockUser(event.id(), event.actorId()));
    }

    @EventListener
    void on(IdentityEvents.AdminRevokeSessions event) {
        admin.revokeSessions(event.id(), event.actorId());
        event.reply().complete(null);
    }

    @EventListener
    void on(IdentityEvents.AdminCountTotalUsers event) {
        event.reply().complete(admin.countTotalUsers());
    }

    @EventListener
    void on(IdentityEvents.AdminCountActiveUsers event) {
        event.reply().complete(admin.countActiveUsers());
    }
}
