package com.masterenglish.api.modules.notifications.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.ReminderMailException;
import com.masterenglish.api.modules.notifications.service.ReminderMailer;
import com.masterenglish.api.modules.notifications.service.impl.DeliveryClaimStore.ClaimedDelivery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class EmailDeliveryWorkerImplTests {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final DeliveryClaimStore store = mock(DeliveryClaimStore.class);
    private final ReminderMailer mailer = mock(ReminderMailer.class);
    private final IdentityEvents identity = mock(IdentityEvents.class);
    private EmailDeliveryWorkerImpl worker;

    @BeforeEach
    void setUp() {
        var props =
                new NotificationProperties(
                        "Asia/Ho_Chi_Minh",
                        false,
                        3,
                        60,
                        List.of(
                                Duration.ofMinutes(1),
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(30)),
                        200,
                        20,
                        new NotificationProperties.Mail(
                                true, "reminders@example.test", "https://app.example.test"));
        worker =
                new EmailDeliveryWorkerImpl(
                        store, mailer, identity, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName(
            "Khi SMTP gửi thành công nhưng recordSent lỗi DB thì không ghi đè MAIL_ERROR để retry")
    void recordSentFailureAfterSmtpSuccessDoesNotRecordMailErrorFailure() {
        ClaimedDelivery claimed = claimed();
        when(store.claimNext(NOW)).thenReturn(Optional.of(claimed)).thenReturn(Optional.empty());
        when(identity.findActiveEmails(Set.of(claimed.userId())))
                .thenReturn(Map.of(claimed.userId(), claimed.recipient()));
        when(mailer.send(anyString(), anyString(), anyString())).thenReturn("smtp-msg-1");
        doThrow(new QueryTimeoutException("db timeout"))
                .when(store)
                .recordSent(claimed.deliveryId(), claimed.claimToken(), "smtp-msg-1", NOW);

        assertThat(worker.deliverDueEmails()).isEqualTo(1);

        verify(store, never()).recordFailure(any(), any(), any(), any());
        verify(store, never()).recordPermanentFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Cắt gọn providerMessageId về 180 ký tự và chuẩn hóa chuỗi trắng thành null")
    void sanitizesProviderMessageIdLengthAndBlank() {
        ClaimedDelivery first = claimed();
        ClaimedDelivery second = claimed();
        when(store.claimNext(NOW))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());
        when(identity.findActiveEmails(Set.of(first.userId())))
                .thenReturn(Map.of(first.userId(), first.recipient()));
        when(identity.findActiveEmails(Set.of(second.userId())))
                .thenReturn(Map.of(second.userId(), second.recipient()));
        when(mailer.send(eq(first.recipient()), anyString(), anyString()))
                .thenReturn("  " + "x".repeat(220) + "  ");
        when(mailer.send(eq(second.recipient()), anyString(), anyString())).thenReturn("   ");

        assertThat(worker.deliverDueEmails()).isEqualTo(2);

        verify(store).recordSent(first.deliveryId(), first.claimToken(), "x".repeat(180), NOW);
        verify(store).recordSent(second.deliveryId(), second.claimToken(), null, NOW);
    }

    @Test
    @DisplayName(
            "Lỗi mail permanent gọi recordPermanentFailure; RuntimeException lạ gọi MAIL_ERROR")
    void handlesPermanentMailAndUnexpectedRuntimeErrors() {
        ClaimedDelivery perm = claimed();
        ClaimedDelivery unexpected = claimed();
        when(store.claimNext(NOW))
                .thenReturn(Optional.of(perm))
                .thenReturn(Optional.of(unexpected))
                .thenReturn(Optional.empty());
        when(identity.findActiveEmails(Set.of(perm.userId())))
                .thenReturn(Map.of(perm.userId(), perm.recipient()));
        when(identity.findActiveEmails(Set.of(unexpected.userId())))
                .thenReturn(Map.of(unexpected.userId(), unexpected.recipient()));
        when(mailer.send(eq(perm.recipient()), anyString(), anyString()))
                .thenThrow(ReminderMailException.permanent("MAIL_NOT_CONFIGURED"));
        when(mailer.send(eq(unexpected.recipient()), anyString(), anyString()))
                .thenThrow(new IllegalStateException("secret@example.test leaked"));

        assertThat(worker.deliverDueEmails()).isEqualTo(2);

        verify(store)
                .recordPermanentFailure(
                        perm.deliveryId(), perm.claimToken(), "MAIL_NOT_CONFIGURED", NOW);
        verify(store, never()).recordFailure(eq(perm.deliveryId()), any(), any(), any());
        verify(store)
                .recordFailure(unexpected.deliveryId(), unexpected.claimToken(), "MAIL_ERROR", NOW);
        assertThat(perm.toString()).isEqualTo("ClaimedDelivery[" + perm.deliveryId() + "]");
    }

    private static ClaimedDelivery claimed() {
        UUID userId = UUID.randomUUID();
        return new ClaimedDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                userId + "@example.test",
                "Bạn có 2 thẻ cần ôn hôm nay",
                "2 thẻ Flashcard đã đến hạn ôn tập.",
                "/learn/review");
    }
}
