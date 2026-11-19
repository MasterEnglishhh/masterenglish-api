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
 * transaction. A failed email never touches the in-app notification.
 */
@Slf4j
@Service
public class EmailDeliveryWorkerImpl implements EmailDeliveryWorker {

    static final String USER_INACTIVE = "USER_INACTIVE";
    static final String MAIL_ERROR = "MAIL_ERROR";
    static final String SETTINGS_PATH = "/learn/notifications/settings";

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
        try {
            String providerId = mailer.send(delivery.recipient(), delivery.title(), text(delivery));
            store.recordSent(
                    delivery.deliveryId(), delivery.claimToken(), providerId, clock.instant());
        } catch (ReminderMailException exception) {
            store.recordFailure(
                    delivery.deliveryId(),
                    delivery.claimToken(),
                    exception.getFailureCode(),
                    clock.instant());
        } catch (RuntimeException exception) {
            log.warn(
                    "Delivery {} send error: {}",
                    delivery.deliveryId(),
                    exception.getClass().getSimpleName());
            store.recordFailure(
                    delivery.deliveryId(), delivery.claimToken(), MAIL_ERROR, clock.instant());
        }
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
