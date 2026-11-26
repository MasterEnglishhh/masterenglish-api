package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.EmailDeliveryWorker;
import com.masterenglish.api.modules.notifications.service.ReminderPlanner;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic triggers; disabled unless notification.scheduler.enabled is true. */
@Slf4j
@Component
@ConditionalOnBooleanProperty("notification.scheduler.enabled")
class NotificationJobs {

    private final ReminderPlanner planner;
    private final EmailDeliveryWorker worker;
    private final Clock clock;

    NotificationJobs(
            ReminderPlanner planner,
            EmailDeliveryWorker worker,
            NotificationProperties properties,
            Clock clock) {
        this.planner = planner;
        this.worker = worker;
        this.clock = clock;
        if (properties.mail().enabled() && !properties.mail().deliverable()) {
            log.warn(
                    "Reminder email is enabled but MAIL_FROM or NOTIFICATION_WEB_BASE_URL is empty;"
                            + " reminders stay in-app only");
        } else if (properties.mail().deliverable() && !properties.mail().hasSettingsPath()) {
            log.info(
                    "Reminder email is enabled without notification.mail.settings-path;"
                            + " settings link is omitted until the frontend settings route is ready");
        }
    }

    @Scheduled(
            fixedDelayString = "${notification.scheduler.interval:PT15M}",
            initialDelayString = "${notification.scheduler.initial-delay:PT1M}")
    void planReminders() {
        java.time.Instant now = clock.instant();
        try {
            planner.planReviewDueReminders(now);
        } catch (RuntimeException exception) {
            log.error("Review reminder planning failed: {}", exception.getClass().getSimpleName());
        }
        try {
            planner.planDailyGoalReminders(now);
        } catch (RuntimeException exception) {
            log.error(
                    "Daily goal reminder planning failed: {}",
                    exception.getClass().getSimpleName());
        }
    }

    @Scheduled(
            fixedDelayString = "${notification.worker.interval:PT1M}",
            initialDelayString = "${notification.scheduler.initial-delay:PT1M}")
    void deliverEmails() {
        try {
            worker.deliverDueEmails();
        } catch (RuntimeException exception) {
            log.error("Email delivery run failed: {}", exception.getClass().getSimpleName());
        }
    }
}
