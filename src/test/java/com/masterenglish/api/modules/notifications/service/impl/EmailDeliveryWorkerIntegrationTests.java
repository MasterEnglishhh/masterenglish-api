package com.masterenglish.api.modules.notifications.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.NotificationVisibility;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.service.EmailDeliveryWorker;
import com.masterenglish.api.modules.notifications.service.ReminderMailException;
import com.masterenglish.api.modules.notifications.service.ReminderMailer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Runs without a test transaction: claims must commit for concurrent workers to see them. */
@Import({TestcontainersConfiguration.class, EmailDeliveryWorkerIntegrationTests.Fakes.class})
@SpringBootTest
@TestPropertySource(
        properties = {
            "notification.mail.enabled=true",
            "notification.mail.from=reminders@example.test",
            "notification.mail.web-base-url=http://localhost:3000/"
        })
class EmailDeliveryWorkerIntegrationTests {

    private static final Instant T = Instant.parse("2026-11-19T13:00:00Z");

    @Autowired private EmailDeliveryWorker worker;
    @MockitoSpyBean private DeliveryClaimStore store;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FakeMailer mailer;
    @Autowired private MutableClock clock;

    private final List<UUID> users = new ArrayList<>();

    @BeforeEach
    void reset() {
        mailer.reset();
        clock.set(T);
    }

    @AfterEach
    void cleanUp() {
        for (UUID user : users) {
            jdbc.update(
                    "DELETE FROM notification_deliveries WHERE notification_id IN (SELECT id FROM notifications WHERE user_id=?)",
                    user);
            jdbc.update("DELETE FROM notifications WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
        users.clear();
    }

    @Test
    @DisplayName(
            "Gửi email đang chờ đúng một lần và đánh dấu SENT; email có link ôn và bỏ qua link cài đặt khi chưa cấu hình settings-path")
    void sendsPendingEmailOnce() {
        UUID notification = reminder(user("ACTIVE"));
        NotificationDelivery email = pendingEmail(notification, "once@example.test");

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        assertThat(worker.deliverDueEmails()).isZero();

        NotificationDelivery sent = reload(email.getId());
        assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo((short) 1);
        assertThat(sent.getSentAt()).isEqualTo(T);
        assertThat(sent.getClaimToken()).isNull();
        assertThat(mailer.sends("once@example.test")).isEqualTo(1);
        String text = mailer.lastText.get();
        assertThat(text).contains("http://localhost:3000/learn/review");
        assertThat(text).doesNotContain("/learn/notifications/settings");
        assertThat(mailer.lastSubject.get()).isEqualTo("Bạn có 4 thẻ cần ôn hôm nay");
    }

    @Test
    @DisplayName("Hai worker chạy song song không gửi trùng email nào")
    void parallelWorkersNeverDoubleSend() throws Exception {
        UUID user = user("ACTIVE");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(pendingEmail(reminder(user), "parallel-" + i + "@example.test").getId());
        }
        mailer.delayMillis = 40;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return worker.deliverDueEmails();
                                }));
            }
            start.countDown();
            int processed = 0;
            for (Future<Integer> run : runs) processed += run.get(60, TimeUnit.SECONDS);
            assertThat(processed).isEqualTo(12);
            assertThat(runs.get(0).get()).isPositive();
            assertThat(runs.get(1).get()).isPositive();
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < 12; i++) {
            assertThat(mailer.sends("parallel-" + i + "@example.test")).isEqualTo(1);
        }
        assertThat(ids)
                .allSatisfy(
                        id -> assertThat(reload(id).getStatus()).isEqualTo(DeliveryStatus.SENT));
    }

    @Test
    @DisplayName("Lỗi gửi mail lùi lịch 1 phút và không ảnh hưởng thông báo in-app")
    void failureSchedulesRetryAndKeepsInApp() {
        UUID user = user("ACTIVE");
        UUID notification = reminder(user);
        NotificationDelivery email = pendingEmail(notification, "retry@example.test");
        mailer.failuresRemaining.set(1);

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery retry = reload(email.getId());
        assertThat(retry.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(retry.getAttemptCount()).isEqualTo((short) 1);
        assertThat(retry.getNextRetryAt()).isEqualTo(T.plusSeconds(60));
        assertThat(retry.getLastFailureCode()).isEqualTo("MAIL_SEND_FAILED");
        assertThat(notifications.findFeed(user, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(1);
        assertThat(
                        deliveries
                                .findByNotificationIdAndChannel(
                                        notification, DeliveryChannel.IN_APP)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(DeliveryStatus.SENT);

        clock.set(T.plusSeconds(30));
        assertThat(worker.deliverDueEmails()).isZero();
        clock.set(T.plusSeconds(60));
        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        assertThat(reload(email.getId()).getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reload(email.getId()).getAttemptCount()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("Lỗi liên tục: thử lại sau 1/5/30 phút rồi FAILED ở lần thứ 4, không gửi thêm")
    void exhaustsRetriesThenFails() {
        NotificationDelivery email = pendingEmail(reminder(user("ACTIVE")), "fail@example.test");
        mailer.failuresRemaining.set(Integer.MAX_VALUE);

        worker.deliverDueEmails();
        assertThat(reload(email.getId()).getNextRetryAt()).isEqualTo(T.plusSeconds(60));
        clock.set(T.plusSeconds(60));
        worker.deliverDueEmails();
        assertThat(reload(email.getId()).getNextRetryAt()).isEqualTo(T.plusSeconds(60 + 300));
        clock.set(T.plusSeconds(360));
        worker.deliverDueEmails();
        assertThat(reload(email.getId()).getNextRetryAt()).isEqualTo(T.plusSeconds(360 + 1800));
        clock.set(T.plusSeconds(2160));
        worker.deliverDueEmails();

        NotificationDelivery failed = reload(email.getId());
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo((short) 4);
        assertThat(failed.getFinishedAt()).isEqualTo(T.plusSeconds(2160));
        assertThat(failed.getNextRetryAt()).isNull();

        clock.set(T.plusSeconds(10_000));
        assertThat(worker.deliverDueEmails()).isZero();
        assertThat(mailer.sends("fail@example.test")).isEqualTo(4);
    }

    @Test
    @DisplayName("Delivery PROCESSING hết lease được nhận lại và gửi")
    void expiredLeaseIsReclaimed() {
        NotificationDelivery email =
                processing(reminder(user("ACTIVE")), "lease@example.test", 1, T.minusSeconds(5));

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery sent = reload(email.getId());
        assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("Delivery PROCESSING còn lease không bị worker khác nhận")
    void liveLeaseIsSkipped() {
        NotificationDelivery email =
                processing(reminder(user("ACTIVE")), "live@example.test", 1, T.plusSeconds(30));

        assertThat(worker.deliverDueEmails()).isZero();
        assertThat(reload(email.getId()).getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(mailer.sends("live@example.test")).isZero();
    }

    @Test
    @DisplayName(
            "PROCESSING hết lease khi attempt_count = 4 chuyển FAILED với LEASE_EXPIRED, không nhận lại")
    void expiredLeaseOnLastAttemptFails() {
        NotificationDelivery email =
                processing(reminder(user("ACTIVE")), "last@example.test", 4, T.minusSeconds(5));

        assertThat(worker.deliverDueEmails()).isZero();
        NotificationDelivery failed = reload(email.getId());
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getLastFailureCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(failed.getAttemptCount()).isEqualTo((short) 4);
        assertThat(failed.getClaimToken()).isNull();
        assertThat(mailer.sends("last@example.test")).isZero();

        clock.set(T.plusSeconds(3600));
        assertThat(worker.deliverDueEmails()).isZero();
    }

    @Test
    @DisplayName(
            "Kết quả từ claim đã mất (recordSent, recordFailure, recordPermanentFailure hoặc sau khi đã SENT) không ghi đè")
    void staleClaimCannotOverwrite() {
        NotificationDelivery email = pendingEmail(reminder(user("ACTIVE")), "stale@example.test");
        var first = store.claimNext(T).orElseThrow();
        var second = store.claimNext(T.plusSeconds(61)).orElseThrow();
        assertThat(second.deliveryId()).isEqualTo(first.deliveryId());

        store.recordSent(first.deliveryId(), first.claimToken(), null, T.plusSeconds(62));
        store.recordFailure(
                first.deliveryId(), first.claimToken(), "MAIL_SEND_FAILED", T.plusSeconds(62));
        store.recordPermanentFailure(
                first.deliveryId(), first.claimToken(), "USER_INACTIVE", T.plusSeconds(62));

        NotificationDelivery current = reload(email.getId());
        assertThat(current.getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(current.getClaimToken()).isEqualTo(second.claimToken());
        assertThat(current.getAttemptCount()).isEqualTo((short) 2);

        // Once second claim finishes and releases claimToken = null, stale claims still cannot
        // overwrite.
        store.recordSent(second.deliveryId(), second.claimToken(), "ok-2", T.plusSeconds(63));
        store.recordFailure(
                first.deliveryId(), first.claimToken(), "MAIL_SEND_FAILED", T.plusSeconds(64));
        store.recordPermanentFailure(
                second.deliveryId(), second.claimToken(), "MAIL_ERROR", T.plusSeconds(64));

        NotificationDelivery sent = reload(email.getId());
        assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.getProviderMessageId()).isEqualTo("ok-2");
    }

    @Test
    @DisplayName(
            "SMTP thành công lần 1 nhưng recordSent lỗi DB: giữ PROCESSING rồi reclaim và gửi lại (at-least-once) khi hết lease")
    void recordSentPersistenceFailureOnAttempt1RecoversAfterLeaseExpiry() {
        NotificationDelivery email =
                pendingEmail(reminder(user("ACTIVE")), "recovers-sent@example.test");
        doThrow(new org.springframework.dao.QueryTimeoutException("db down"))
                .doThrow(new org.springframework.dao.QueryTimeoutException("db down"))
                .doCallRealMethod()
                .when(store)
                .recordSent(eq(email.getId()), any(), any(), any());

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery stuck = reload(email.getId());
        assertThat(stuck.getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(stuck.getAttemptCount()).isEqualTo((short) 1);
        assertThat(mailer.sends("recovers-sent@example.test")).isEqualTo(1);

        // Before lease expiry, worker does not reclaim.
        clock.set(T.plusSeconds(30));
        assertThat(worker.deliverDueEmails()).isZero();

        // After lease expiry, worker reclaims and completes SENT on attempt 2 (at-least-once).
        clock.set(T.plusSeconds(61));
        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery recovered = reload(email.getId());
        assertThat(recovered.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(recovered.getAttemptCount()).isEqualTo((short) 2);
        assertThat(mailer.sends("recovers-sent@example.test")).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "SMTP thành công lần 4 nhưng recordSent lỗi DB: chuyển FAILED LEASE_EXPIRED sau hết lease và không gửi lần 5")
    void recordSentPersistenceFailureOnAttempt4ClosesAsLeaseExpired() {
        NotificationDelivery email =
                processing(
                        reminder(user("ACTIVE")),
                        "last-db-fail@example.test",
                        3,
                        T.minusSeconds(1));
        doThrow(new org.springframework.dao.QueryTimeoutException("db down"))
                .when(store)
                .recordSent(eq(email.getId()), any(), any(), any());

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery stuckAt4 = reload(email.getId());
        assertThat(stuckAt4.getStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(stuckAt4.getAttemptCount()).isEqualTo((short) 4);
        assertThat(mailer.sends("last-db-fail@example.test")).isEqualTo(1);

        clock.set(T.plusSeconds(61));
        assertThat(worker.deliverDueEmails()).isZero();
        NotificationDelivery closed = reload(email.getId());
        assertThat(closed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(closed.getLastFailureCode()).isEqualTo(DeliveryClaimStore.LEASE_EXPIRED);
        assertThat(closed.getAttemptCount()).isEqualTo((short) 4);
        assertThat(mailer.sends("last-db-fail@example.test")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Một batch không bao giờ tự claim lại cùng delivery vừa xử lý dù next_retry_at <= now")
    void singleBatchNeverReclaimsSameDeliveryEvenIfRetryIsImmediatelyDue() {
        // Ensure deterministic FIFO ordering (ORDER BY created_at, id) so d1 is claimed first.
        NotificationDelivery d1 =
                deliveries.save(
                        NotificationDelivery.emailPending(
                                reminder(user("ACTIVE")),
                                "batch-1@example.test",
                                T.minusSeconds(10)));
        NotificationDelivery d2 = pendingEmail(reminder(user("ACTIVE")), "batch-2@example.test");
        mailer.failuresRemaining.set(1);
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            UUID id = inv.getArgument(0);
                            UUID token = inv.getArgument(1);
                            String code = inv.getArgument(2);
                            Instant now = inv.getArgument(3);
                            deliveries
                                    .findByIdAndClaimToken(id, token)
                                    .ifPresent(
                                            row -> {
                                                row.scheduleRetry(code, now, now);
                                                deliveries.saveAndFlush(row);
                                            });
                            return null;
                        })
                .when(store)
                .recordFailure(eq(d1.getId()), any(), any(), any());

        assertThat(worker.deliverDueEmails()).isEqualTo(2);
        assertThat(mailer.sends("batch-1@example.test")).isEqualTo(1);
        assertThat(mailer.sends("batch-2@example.test")).isEqualTo(1);
        assertThat(reload(d1.getId()).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(reload(d2.getId()).getStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    @DisplayName("Người nhận không còn ACTIVE thì FAILED với USER_INACTIVE, không gửi")
    void inactiveRecipientFails() {
        NotificationDelivery email = pendingEmail(reminder(user("DISABLED")), "off@example.test");

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery failed = reload(email.getId());
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getLastFailureCode()).isEqualTo("USER_INACTIVE");
        assertThat(mailer.sends("off@example.test")).isZero();
    }

    @Test
    @DisplayName(
            "Lưu providerMessageId khi SMTP trả mã phản hồi và tôn trọng lease hết hạn đúng mốc")
    void storesProviderMessageIdAndReclaimsAtExactLeaseExpiry() {
        NotificationDelivery email =
                processing(reminder(user("ACTIVE")), "provider@example.test", 1, T);
        mailer.nextProviderId = "smtp-queue-id-12345";

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery sent = reload(email.getId());
        assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo((short) 2);
        assertThat(sent.getProviderMessageId()).isEqualTo("smtp-queue-id-12345");
    }

    @Test
    @DisplayName("Lỗi vĩnh viễn từ mailer chuyển FAILED ngay ở lần đầu, không retry")
    void permanentMailerFailureDoesNotRetry() {
        NotificationDelivery email = pendingEmail(reminder(user("ACTIVE")), "perm@example.test");
        mailer.permanentFailureCode = "MAIL_NOT_CONFIGURED";

        assertThat(worker.deliverDueEmails()).isEqualTo(1);
        NotificationDelivery failed = reload(email.getId());
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo((short) 1);
        assertThat(failed.getNextRetryAt()).isNull();
        assertThat(failed.getFinishedAt()).isEqualTo(T);
        assertThat(failed.getLastFailureCode()).isEqualTo("MAIL_NOT_CONFIGURED");
        assertThat(mailer.sends("perm@example.test")).isEqualTo(1);

        clock.set(T.plusSeconds(3600));
        assertThat(worker.deliverDueEmails()).isZero();
    }

    private NotificationDelivery reload(UUID id) {
        return deliveries.findById(id).orElseThrow();
    }

    private NotificationDelivery pendingEmail(UUID notification, String recipient) {
        return deliveries.save(NotificationDelivery.emailPending(notification, recipient, T));
    }

    /** A PROCESSING row after {@code attempts} claims, the last one leased until leaseUntil. */
    private NotificationDelivery processing(
            UUID notification, String recipient, int attempts, Instant leaseUntil) {
        NotificationDelivery delivery =
                NotificationDelivery.emailPending(notification, recipient, T.minusSeconds(7200));
        for (int i = 1; i < attempts; i++) {
            delivery.claim(UUID.randomUUID(), T.minusSeconds(7000), T.minusSeconds(7100));
            delivery.scheduleRetry("MAIL_SEND_FAILED", T.minusSeconds(6000), T.minusSeconds(7000));
        }
        delivery.claim(UUID.randomUUID(), leaseUntil, leaseUntil.minusSeconds(60));
        return deliveries.save(delivery);
    }

    private UUID reminder(UUID user) {
        Notification notification =
                notifications.save(
                        new Notification(
                                user,
                                null,
                                NotificationType.REVIEW_DUE,
                                NotificationVisibility.USER,
                                "Bạn có 4 thẻ cần ôn hôm nay",
                                "4 thẻ Flashcard đã đến hạn ôn tập. Ôn ngay để giữ nhịp ghi nhớ.",
                                "/learn/review",
                                "TEST:" + UUID.randomUUID(),
                                T));
        deliveries.save(NotificationDelivery.inAppDelivered(notification.getId(), T));
        return notification.getId();
    }

    private UUID user(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, 'fixture-only', 'Mail', 'Fixture', ?, NOW())",
                id,
                id + "@example.test",
                status);
        users.add(id);
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        @Primary
        FakeMailer fakeReminderMailer() {
            return new FakeMailer();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static class FakeMailer implements ReminderMailer {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();
        final List<String> recipients = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<String> lastText =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> lastSubject =
                new java.util.concurrent.atomic.AtomicReference<>();
        volatile long delayMillis;
        volatile String nextProviderId;
        volatile String permanentFailureCode;

        @Override
        public String send(String recipient, String subject, String text) {
            calls.computeIfAbsent(recipient, key -> new AtomicInteger()).incrementAndGet();
            recipients.add(recipient);
            lastText.set(text);
            lastSubject.set(subject);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (permanentFailureCode != null) {
                throw ReminderMailException.permanent(permanentFailureCode);
            }
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new ReminderMailException("MAIL_SEND_FAILED");
            }
            return nextProviderId;
        }

        int sends(String recipient) {
            AtomicInteger count = calls.get(recipient);
            return count == null ? 0 : count.get();
        }

        void reset() {
            calls.clear();
            recipients.clear();
            failuresRemaining.set(0);
            delayMillis = 0;
            nextProviderId = null;
            permanentFailureCode = null;
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = T;

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
