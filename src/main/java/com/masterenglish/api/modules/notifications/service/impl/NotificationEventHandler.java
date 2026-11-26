package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.events.ContentEvents.DeckFirstPublished;
import com.masterenglish.api.common.events.NotificationEvents.AdminFindDeliveries;
import com.masterenglish.api.common.events.NotificationEvents.AdminFindReminderHistory;
import com.masterenglish.api.common.events.NotificationEvents.AdminGetReminderRule;
import com.masterenglish.api.common.events.NotificationEvents.AdminListReminderRules;
import com.masterenglish.api.common.events.NotificationEvents.AdminUpdateReminderRule;
import com.masterenglish.api.common.events.NotificationEvents.UnreadCountRequested;
import com.masterenglish.api.modules.notifications.service.ContentNotificationService;
import com.masterenglish.api.modules.notifications.service.NotificationAdminService;
import com.masterenglish.api.modules.notifications.service.NotificationService;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class NotificationEventHandler {

    private final NotificationService notificationService;
    private final NotificationAdminService adminService;
    private final ContentNotificationService contentNotifications;

    NotificationEventHandler(
            NotificationService notificationService,
            NotificationAdminService adminService,
            ContentNotificationService contentNotifications) {
        this.notificationService = notificationService;
        this.adminService = adminService;
        this.contentNotifications = contentNotifications;
    }

    @ApplicationModuleListener
    void on(DeckFirstPublished event) {
        contentNotifications.notifyDeckFirstPublished(
                event.deckId(), event.revisionId(), event.title(), event.level());
    }

    @EventListener
    void on(UnreadCountRequested event) {
        event.reply().complete(notificationService.countUnread(event.userId()).count());
    }

    @EventListener
    void on(AdminFindDeliveries event) {
        event.reply()
                .complete(
                        adminService.findDeliveries(
                                event.status(),
                                event.channel(),
                                event.type(),
                                event.from(),
                                event.to(),
                                event.pageable()));
    }

    @EventListener
    void on(AdminListReminderRules event) {
        event.reply().complete(adminService.listReminderRules());
    }

    @EventListener
    void on(AdminGetReminderRule event) {
        event.reply().complete(adminService.getReminderRule(event.ruleId()));
    }

    @EventListener
    void on(AdminUpdateReminderRule event) {
        event.reply()
                .complete(
                        adminService.updateReminderRule(
                                event.ruleId(), event.enabled(), event.minimumGapMinutes()));
    }

    @EventListener
    void on(AdminFindReminderHistory event) {
        event.reply()
                .complete(
                        adminService.findReminderHistory(
                                event.userId(),
                                event.type(),
                                event.status(),
                                event.ruleId(),
                                event.from(),
                                event.to(),
                                event.pageable()));
    }
}
