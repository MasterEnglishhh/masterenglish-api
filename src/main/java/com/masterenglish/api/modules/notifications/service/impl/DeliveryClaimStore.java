package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactions around the email outbox. A claim commits before the email is sent, and a
 * result is written only while the claim token still matches.
 */
@Slf4j
@Component
class DeliveryClaimStore {

    static final String LEASE_EXPIRED = "LEASE_EXPIRED";
    static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final NotificationProperties properties;

    DeliveryClaimStore(
            NotificationDeliveryRepository deliveries,
            NotificationRepository notifications,
            NotificationProperties properties) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.properties = properties;
    }

    /**
     * Claims the next claimable email delivery. Rows whose attempt budget is already spent are
     * closed as FAILED on the way and never claimed again.
     */
    @Transactional
    public Optional<ClaimedDelivery> claimNext(Instant now) {
        return claimNext(now, Set.of());
    }

    @Transactional
    public Optional<ClaimedDelivery> claimNext(Instant now, Collection<UUID> excludedIds) {
        int skippedExhausted = 0;
        int maxSkips = Math.max(1, properties.workerBatchSize());
        while (skippedExhausted <= maxSkips) {
            List<UUID> locked =
                    excludedIds == null || excludedIds.isEmpty()
                            ? deliveries.lockClaimable(now, 1)
                            : deliveries.lockClaimableExcluding(now, excludedIds, 1);
            if (locked.isEmpty()) return Optional.empty();
            NotificationDelivery delivery = deliveries.findById(locked.getFirst()).orElseThrow();
            if (delivery.getAttemptCount() >= properties.maxAttempts()) {
                String code =
                        delivery.getStatus() == DeliveryStatus.PROCESSING
                                ? LEASE_EXPIRED
                                : RETRIES_EXHAUSTED;
                delivery.markFailed(code, now);
                deliveries.saveAndFlush(delivery);
                log.warn("Delivery {} failed: {}", delivery.getId(), code);
                skippedExhausted++;
                continue;
            }
            UUID token = UUID.randomUUID();
            delivery.claim(token, now.plus(properties.lease()), now);
            deliveries.saveAndFlush(delivery);
            Notification notification =
                    notifications.findById(delivery.getNotificationId()).orElseThrow();
            return Optional.of(
                    new ClaimedDelivery(
                            delivery.getId(),
                            token,
                            notification.getUserId(),
                            delivery.getRecipientAddress(),
                            notification.getTitle(),
                            notification.getBody(),
                            notification.getTargetUrl(),
                            notification.getType()));
        }
        return Optional.empty();
    }

    @Transactional
    public void recordSent(UUID deliveryId, UUID token, String providerMessageId, Instant now) {
        held(deliveryId, token).ifPresent(delivery -> delivery.markSent(providerMessageId, now));
    }

    /** Schedules the next retry, or closes the delivery once the attempt budget is spent. */
    @Transactional
    public void recordFailure(UUID deliveryId, UUID token, String failureCode, Instant now) {
        held(deliveryId, token)
                .ifPresent(
                        delivery -> {
                            int attempts = delivery.getAttemptCount();
                            if (attempts >= properties.maxAttempts()) {
                                delivery.markFailed(failureCode, now);
                                log.warn("Delivery {} failed: {}", deliveryId, failureCode);
                                return;
                            }
                            Duration backoff = properties.retryBackoff().get(attempts - 1);
                            delivery.scheduleRetry(failureCode, now.plus(backoff), now);
                        });
    }

    /** Closes the delivery without retry, for failures that a retry cannot fix. */
    @Transactional
    public void recordPermanentFailure(
            UUID deliveryId, UUID token, String failureCode, Instant now) {
        held(deliveryId, token).ifPresent(delivery -> delivery.markFailed(failureCode, now));
    }

    private Optional<NotificationDelivery> held(UUID deliveryId, UUID token) {
        Optional<NotificationDelivery> delivery =
                deliveries.findByIdAndClaimToken(deliveryId, token);
        if (delivery.isEmpty()) log.warn("Delivery {} claim was lost; result ignored", deliveryId);
        return delivery;
    }

    /** Everything the sender needs, captured inside the claim transaction. */
    record ClaimedDelivery(
            UUID deliveryId,
            UUID claimToken,
            UUID userId,
            String recipient,
            String title,
            String body,
            String targetUrl,
            NotificationType type) {

        ClaimedDelivery(
                UUID deliveryId,
                UUID claimToken,
                UUID userId,
                String recipient,
                String title,
                String body,
                String targetUrl) {
            this(deliveryId, claimToken, userId, recipient, title, body, targetUrl, null);
        }

        @Override
        public String toString() {
            return "ClaimedDelivery[" + deliveryId + "]";
        }
    }
}
