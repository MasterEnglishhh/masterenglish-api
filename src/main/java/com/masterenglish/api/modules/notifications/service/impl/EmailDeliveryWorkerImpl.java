package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.service.EmailDeliveryWorker;
import com.masterenglish.api.modules.notifications.service.ReminderMailException;
import com.masterenglish.api.modules.notifications.service.ReminderMailer;
import com.masterenglish.api.modules.notifications.service.impl.DeliveryClaimStore.ClaimedDelivery;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Claims one delivery at a time so each email gets a fresh lease, and sends it outside any database
 * transaction. Provides at-least-once delivery semantics (if SMTP succeeds but persisting SENT
 * fails or the 60s lease expires mid-send, another worker may reclaim and re-send). A failed email
 * never touches the in-app notification.
 */
@Slf4j
@Service
public class EmailDeliveryWorkerImpl implements EmailDeliveryWorker {

    static final String USER_INACTIVE = "USER_INACTIVE";
    static final String MAIL_ERROR = "MAIL_ERROR";
    static final String SETTINGS_PATH = "/learn/notifications/settings";
    private static final int MAX_PROVIDER_MESSAGE_ID_LENGTH = 180;

    private final DeliveryClaimStore store;
    private final ReminderMailer mailer;
    private final IdentityEvents identity;
    private final NotificationProperties properties;
    private final Clock clock;

    public EmailDeliveryWorkerImpl(
            DeliveryClaimStore store,
            ReminderMailer mailer,
            IdentityEvents identity,
            NotificationProperties properties,
            Clock clock) {
        this.store = store;
        this.mailer = mailer;
        this.identity = identity;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int deliverDueEmails() {
        if (!properties.mail().deliverable()) return 0;
        int processed = 0;
        while (processed < properties.workerBatchSize()) {
            Optional<ClaimedDelivery> claimed = store.claimNext(clock.instant());
            if (claimed.isEmpty()) break;
            deliver(claimed.get());
            processed++;
        }
        return processed;
    }

    private void deliver(ClaimedDelivery delivery) {
        if (!identity.findActiveEmails(Set.of(delivery.userId())).containsKey(delivery.userId())) {
            store.recordPermanentFailure(
                    delivery.deliveryId(), delivery.claimToken(), USER_INACTIVE, clock.instant());
            return;
        }
        String providerId;
        try {
            providerId =
                    sanitizeProviderId(
                            mailer.send(delivery.recipient(), delivery.title(), text(delivery)));
        } catch (ReminderMailException exception) {
            if (exception.isPermanent()) {
                store.recordPermanentFailure(
                        delivery.deliveryId(),
                        delivery.claimToken(),
                        exception.getFailureCode(),
                        clock.instant());
            } else {
                store.recordFailure(
                        delivery.deliveryId(),
                        delivery.claimToken(),
                        exception.getFailureCode(),
                        clock.instant());
            }
            return;
        } catch (RuntimeException exception) {
            log.warn(
                    "Delivery {} send error: {}",
                    delivery.deliveryId(),
                    exception.getClass().getSimpleName());
            store.recordFailure(
                    delivery.deliveryId(), delivery.claimToken(), MAIL_ERROR, clock.instant());
            return;
        }
        try {
            store.recordSent(
                    delivery.deliveryId(), delivery.claimToken(), providerId, clock.instant());
        } catch (RuntimeException exception) {
            // Do not convert a DB persistence error after SMTP success into MAIL_ERROR;
            // keeping PROCESSING lets lease expiry recover if the write did not commit.
            log.warn(
                    "Delivery {} sent via SMTP but failed to persist SENT state: {}",
                    delivery.deliveryId(),
                    exception.getClass().getSimpleName());
        }
    }

    private static String sanitizeProviderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.length() <= MAX_PROVIDER_MESSAGE_ID_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_PROVIDER_MESSAGE_ID_LENGTH);
    }

    private String text(ClaimedDelivery delivery) {
        String base = properties.mail().webBaseUrl();
        String target = delivery.targetUrl() == null ? "/" : delivery.targetUrl();
        return delivery.body()
                + "\n\nÔn ngay: "
                + base
                + target
                + "\n\nĐổi giờ nhắc hoặc tắt email nhắc học: "
                + base
                + SETTINGS_PATH
                + "\n\nMasterEnglish";
    }
}
