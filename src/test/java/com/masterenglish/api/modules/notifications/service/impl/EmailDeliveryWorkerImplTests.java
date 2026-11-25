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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(OutputCaptureExtension.class)
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
                                true, "reminders@example.test", "https://app.example.test", ""));
        worker =
                new EmailDeliveryWorkerImpl(
                        store, mailer, identity, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName(
            "Khi SMTP gửi thành công nhưng recordSent lỗi DB liên tiếp thì không ghi đè MAIL_ERROR để retry")
    void recordSentFailureAfterSmtpSuccessDoesNotRecordMailErrorFailure(CapturedOutput output) {
        ClaimedDelivery claimed = claimed();
        when(store.claimNext(eq(NOW), any()))
                .thenReturn(Optional.of(claimed))
                .thenReturn(Optional.empty());
        when(identity.findActiveEmails(Set.of(claimed.userId())))
                .thenReturn(Map.of(claimed.userId(), claimed.recipient()));
        when(mailer.send(anyString(), anyString(), anyString())).thenReturn("smtp-msg-1");
        doThrow(new QueryTimeoutException("db timeout " + claimed.recipient()))
                .when(store)
                .recordSent(claimed.deliveryId(), claimed.claimToken(), "smtp-msg-1", NOW);

        assertThat(worker.deliverDueEmails()).isEqualTo(1);

        verify(store, org.mockito.Mockito.times(2))
                .recordSent(claimed.deliveryId(), claimed.claimToken(), "smtp-msg-1", NOW);
        verify(store, never()).recordFailure(any(), any(), any(), any());
        verify(store, never()).recordPermanentFailure(any(), any(), any(), any());
        assertThat(output.getAll())
                .contains(claimed.deliveryId().toString())
                .contains("QueryTimeoutException")
                .doesNotContain(claimed.recipient())
                .doesNotContain(claimed.claimToken().toString());
    }

    @Test
    @DisplayName(
            "recordSent phục hồi thành công khi thử lại 1 lần sau TransientDataAccessException")
    void retriesRecordSentOnceAfterTransientDbError() {
        ClaimedDelivery claimed = claimed();
        when(store.claimNext(eq(NOW), any()))
                .thenReturn(Optional.of(claimed))
                .thenReturn(Optional.empty());
        when(identity.findActiveEmails(Set.of(claimed.userId())))
                .thenReturn(Map.of(claimed.userId(), claimed.recipient()));
        when(mailer.send(anyString(), anyString(), anyString())).thenReturn(null);
        doThrow(new QueryTimeoutException("transient"))
                .doNothing()
                .when(store)
                .recordSent(claimed.deliveryId(), claimed.claimToken(), null, NOW);

        assertThat(worker.deliverDueEmails()).isEqualTo(1);

        verify(store, org.mockito.Mockito.times(2))
                .recordSent(claimed.deliveryId(), claimed.claimToken(), null, NOW);
        verify(store, never()).recordFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Cắt gọn providerMessageId về 180 ký tự và chuẩn hóa chuỗi trắng thành null")
    void sanitizesProviderMessageIdLengthAndBlank() {
        ClaimedDelivery first = claimed();
        ClaimedDelivery second = claimed();
        when(store.claimNext(eq(NOW), any()))
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
            "Lỗi mail permanent gọi recordPermanentFailure; RuntimeException lạ gọi MAIL_ERROR và log không lộ PII")
    void handlesPermanentMailAndUnexpectedRuntimeErrors(CapturedOutput output) {
        ClaimedDelivery perm = claimed();
        ClaimedDelivery unexpected = claimed();
        when(store.claimNext(eq(NOW), any()))
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
                .thenThrow(
                        new IllegalStateException("secret@example.test body=" + unexpected.body()));

        assertThat(worker.deliverDueEmails()).isEqualTo(2);

        verify(store)
                .recordPermanentFailure(
                        perm.deliveryId(), perm.claimToken(), "MAIL_NOT_CONFIGURED", NOW);
        verify(store, never()).recordFailure(eq(perm.deliveryId()), any(), any(), any());
        verify(store)
                .recordFailure(unexpected.deliveryId(), unexpected.claimToken(), "MAIL_ERROR", NOW);
        assertThat(perm.toString()).isEqualTo("ClaimedDelivery[" + perm.deliveryId() + "]");
        assertThat(output.getAll())
                .contains(unexpected.deliveryId().toString())
                .contains("IllegalStateException")
                .doesNotContain("secret@example.test")
                .doesNotContain(unexpected.recipient())
                .doesNotContain(unexpected.body())
                .doesNotContain(unexpected.claimToken().toString());
    }

    @Test
    @DisplayName(
            "Lỗi identity lookup hoặc lỗi ghi recordFailure ở delivery đầu không làm dừng các delivery tiếp theo trong batch")
    void deliveryPersistenceOrIdentityErrorsDoNotAbortRemainingBatch(CapturedOutput output) {
        ClaimedDelivery identityFails = claimed();
        ClaimedDelivery dbRecordFailureFails = claimed();
        ClaimedDelivery thirdSucceeds = claimed();
        when(store.claimNext(eq(NOW), any()))
                .thenReturn(Optional.of(identityFails))
                .thenReturn(Optional.of(dbRecordFailureFails))
                .thenReturn(Optional.of(thirdSucceeds))
                .thenReturn(Optional.empty());

        when(identity.findActiveEmails(Set.of(identityFails.userId())))
                .thenThrow(new QueryTimeoutException("identity " + identityFails.recipient()));
        when(identity.findActiveEmails(Set.of(dbRecordFailureFails.userId())))
                .thenReturn(
                        Map.of(dbRecordFailureFails.userId(), dbRecordFailureFails.recipient()));
        when(identity.findActiveEmails(Set.of(thirdSucceeds.userId())))
                .thenReturn(Map.of(thirdSucceeds.userId(), thirdSucceeds.recipient()));

        when(mailer.send(eq(dbRecordFailureFails.recipient()), anyString(), anyString()))
                .thenThrow(new ReminderMailException("MAIL_SEND_FAILED"));
        doThrow(new DataAccessResourceFailureException("db " + dbRecordFailureFails.recipient()))
                .when(store)
                .recordFailure(
                        dbRecordFailureFails.deliveryId(),
                        dbRecordFailureFails.claimToken(),
                        "MAIL_SEND_FAILED",
                        NOW);
        when(mailer.send(eq(thirdSucceeds.recipient()), anyString(), anyString())).thenReturn(null);

        assertThat(worker.deliverDueEmails()).isEqualTo(3);

        verify(store)
                .recordFailure(
                        identityFails.deliveryId(),
                        identityFails.claimToken(),
                        EmailDeliveryWorkerImpl.IDENTITY_LOOKUP_FAILED,
                        NOW);
        verify(store).recordSent(thirdSucceeds.deliveryId(), thirdSucceeds.claimToken(), null, NOW);
        assertThat(output.getAll())
                .doesNotContain(identityFails.recipient())
                .doesNotContain(dbRecordFailureFails.recipient())
                .doesNotContain(thirdSucceeds.recipient());
    }

    @Test
    @DisplayName(
            "Chỉ chèn link cài đặt vào nội dung email khi notification.mail.settings-path được cấu hình")
    void includesSettingsLinkOnlyWhenConfigured() {
        var propsWithSettings =
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
                                true,
                                "reminders@example.test",
                                "https://app.example.test",
                                "/learn/notifications/settings"));
        var workerWithSettings =
                new EmailDeliveryWorkerImpl(
                        store,
                        mailer,
                        identity,
                        propsWithSettings,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        ClaimedDelivery first = claimed();
        when(store.claimNext(eq(NOW), any()))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.empty());
        when(identity.findActiveEmails(Set.of(first.userId())))
                .thenReturn(Map.of(first.userId(), first.recipient()));

        assertThat(workerWithSettings.deliverDueEmails()).isEqualTo(1);
        org.mockito.ArgumentCaptor<String> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(first.recipient()), eq(first.title()), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("https://app.example.test/learn/review")
                .contains("https://app.example.test/learn/notifications/settings");
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
