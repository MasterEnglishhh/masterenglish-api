package com.masterenglish.api.modules.notifications.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.EmailDeliveryWorker;
import com.masterenglish.api.modules.notifications.service.ReminderPlanner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationJobsTests {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final ReminderPlanner planner = mock(ReminderPlanner.class);
    private final EmailDeliveryWorker worker = mock(EmailDeliveryWorker.class);
    private final NotificationProperties properties =
            new NotificationProperties(
                    "Asia/Ho_Chi_Minh",
                    false,
                    3,
                    60,
                    List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                    200,
                    20,
                    new NotificationProperties.Mail(false, "", "", ""));
    private final NotificationJobs jobs =
            new NotificationJobs(planner, worker, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("Scheduler gọi planner và worker với mốc thời gian từ Clock")
    void scheduledJobsInvokePlannerAndWorker() {
        jobs.planReminders();
        jobs.deliverEmails();

        verify(planner).planReviewDueReminders(NOW);
        verify(worker).deliverDueEmails();
    }

    @Test
    @DisplayName("Lỗi runtime trong job bị bắt an toàn để không làm chết scheduler")
    void scheduledJobsSwallowRuntimeExceptions() {
        when(planner.planReviewDueReminders(NOW)).thenThrow(new IllegalStateException("db"));
        when(worker.deliverDueEmails()).thenThrow(new IllegalStateException("smtp"));

        jobs.planReminders();
        jobs.deliverEmails();

        verify(planner).planReviewDueReminders(NOW);
        verify(worker).deliverDueEmails();
    }
}
