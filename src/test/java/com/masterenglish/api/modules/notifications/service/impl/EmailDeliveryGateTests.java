package com.masterenglish.api.modules.notifications.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.ReminderMailer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailDeliveryGateTests {

    @Test
    @DisplayName("Thiếu NOTIFICATION_WEB_BASE_URL thì worker không claim và không gửi email nào")
    void workerSendsNothingWithoutWebAddress() {
        DeliveryClaimStore store = mock(DeliveryClaimStore.class);
        ReminderMailer mailer = mock(ReminderMailer.class);
        var worker =
                new EmailDeliveryWorkerImpl(
                        store,
                        mailer,
                        mock(IdentityEvents.class),
                        properties(new NotificationProperties.Mail(true, "a@example.test", "", "")),
                        Clock.fixed(Instant.parse("2026-11-19T13:00:00Z"), ZoneOffset.UTC));

        assertThat(worker.deliverDueEmails()).isZero();
        verify(store, never()).claimNext(any());
        verify(store, never()).claimNext(any(), any());
        verifyNoInteractions(mailer);
    }

    private static NotificationProperties properties(NotificationProperties.Mail mail) {
        return new NotificationProperties(
                "Asia/Ho_Chi_Minh",
                false,
                3,
                60,
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                200,
                20,
                mail);
    }
}
