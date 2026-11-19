package com.masterenglish.api.modules.notifications.service.impl;

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

    NotificationJobs(ReminderPlanner planner, EmailDeliveryWorker worker, Clock clock) {
        this.planner = planner;
        this.worker = worker;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${notification.scheduler.interval:PT15M}",
            initialDelayString = "${notification.scheduler.initial-delay:PT1M}")
    void planReminders() {
        try {
            planner.planReviewDueReminders(clock.instant());
        } catch (RuntimeException exception) {
            log.error("Reminder planning failed: {}", exception.getClass().getSimpleName());
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
