package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.service.EmailDeliveryWorker;
import com.masterenglish.api.modules.notifications.service.ReminderMailException;
import com.masterenglish.api.modules.notifications.service.ReminderMailer;
import com.masterenglish.api.modules.notifications.service.impl.DeliveryClaimStore.ClaimedDelivery;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/**
 * Claims one delivery at a time so each email gets a fresh lease, and sends it outside any database
 * transaction. Provides at-least-once delivery semantics (if SMTP succeeds but persisting SENT
 * fails or the lease expires mid-send, another worker may reclaim and re-send). A failed email
 * never touches the in-app notification.
 */
@Slf4j
@Service
public class EmailDeliveryWorkerImpl implements EmailDeliveryWorker {

    static final String USER_INACTIVE = "USER_INACTIVE";
    static final String IDENTITY_LOOKUP_FAILED = "IDENTITY_LOOKUP_FAILED";
    static final String MAIL_ERROR = "MAIL_ERROR";
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
        Set<UUID> attemptedInBatch = new HashSet<>();
        while (processed < properties.workerBatchSize()) {
            Optional<ClaimedDelivery> claimed = store.claimNext(clock.instant(), attemptedInBatch);
            if (claimed.isEmpty()) break;
            attemptedInBatch.add(claimed.get().deliveryId());
            try {
                deliver(claimed.get());
            } catch (RuntimeException exception) {
                log.warn(
                        "Delivery {} unexpected batch error: {}",
                        claimed.get().deliveryId(),
                        exception.getClass().getSimpleName());
            }
            processed++;
        }
        return processed;
    }

    private void deliver(ClaimedDelivery delivery) {
        Map<UUID, String> activeEmails;
        try {
            activeEmails = identity.findActiveEmails(Set.of(delivery.userId()));
        } catch (RuntimeException exception) {
            log.warn(
                    "Delivery {} identity lookup error: {}",
                    delivery.deliveryId(),
                    exception.getClass().getSimpleName());
            safeRecordFailure(delivery.deliveryId(), delivery.claimToken(), IDENTITY_LOOKUP_FAILED);
            return;
        }
        if (!activeEmails.containsKey(delivery.userId())) {
            safeRecordPermanentFailure(delivery.deliveryId(), delivery.claimToken(), USER_INACTIVE);
            return;
        }
        String providerId;
        try {
            providerId =
                    sanitizeProviderId(
                            mailer.send(delivery.recipient(), delivery.title(), text(delivery)));
        } catch (ReminderMailException exception) {
            if (exception.isPermanent()) {
                safeRecordPermanentFailure(
                        delivery.deliveryId(), delivery.claimToken(), exception.getFailureCode());
            } else {
                safeRecordFailure(
                        delivery.deliveryId(), delivery.claimToken(), exception.getFailureCode());
            }
            return;
        } catch (RuntimeException exception) {
            log.warn(
                    "Delivery {} send error: {}",
                    delivery.deliveryId(),
                    exception.getClass().getSimpleName());
            safeRecordFailure(delivery.deliveryId(), delivery.claimToken(), MAIL_ERROR);
            return;
        }
        persistSent(delivery, providerId);
    }

    private void persistSent(ClaimedDelivery delivery, String providerId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                store.recordSent(
                        delivery.deliveryId(), delivery.claimToken(), providerId, clock.instant());
                return;
            } catch (TransientDataAccessException exception) {
                if (attempt == 0) {
                    log.debug(
                            "Retrying SENT state persistence for delivery {} after {}",
                            delivery.deliveryId(),
                            exception.getClass().getSimpleName());
                    continue;
                }
                log.warn(
                        "Delivery {} sent via SMTP but failed to persist SENT state: {}",
                        delivery.deliveryId(),
                        exception.getClass().getSimpleName());
                return;
            } catch (RuntimeException exception) {
                // Do not convert a DB persistence error after SMTP success into MAIL_ERROR;
                // keeping PROCESSING lets lease expiry recover if the write did not commit.
                log.warn(
                        "Delivery {} sent via SMTP but failed to persist SENT state: {}",
                        delivery.deliveryId(),
                        exception.getClass().getSimpleName());
                return;
            }
        }
    }

    private void safeRecordFailure(UUID deliveryId, UUID claimToken, String failureCode) {
        try {
            store.recordFailure(deliveryId, claimToken, failureCode, clock.instant());
        } catch (RuntimeException exception) {
            log.warn(
                    "Delivery {} failed to persist retry/failure {}: {}",
                    deliveryId,
                    failureCode,
                    exception.getClass().getSimpleName());
        }
    }

    private void safeRecordPermanentFailure(UUID deliveryId, UUID claimToken, String failureCode) {
        try {
            store.recordPermanentFailure(deliveryId, claimToken, failureCode, clock.instant());
        } catch (RuntimeException exception) {
            log.warn(
                    "Delivery {} failed to persist permanent failure {}: {}",
                    deliveryId,
                    failureCode,
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
        String ctaLabel =
                delivery.type() == NotificationType.CONTENT_NEW
                        ? "Xem ngay: "
                        : (delivery.type() == NotificationType.DAILY_GOAL
                                ? "Học ngay: "
                                : "Ôn ngay: ");
        StringBuilder body = new StringBuilder();
        body.append(delivery.body()).append("\n\n").append(ctaLabel).append(base).append(target);
        if (properties.mail().hasSettingsPath()) {
            body.append("\n\nĐổi giờ nhắc hoặc tắt email nhắc học: ")
                    .append(base)
                    .append(properties.mail().settingsPath());
        }
        body.append("\n\nMasterEnglish");
        return body.toString();
    }
}
