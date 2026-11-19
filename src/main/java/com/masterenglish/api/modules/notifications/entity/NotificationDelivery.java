package com.masterenglish.api.modules.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One delivery per notification and channel. The OTP ciphertext columns of the approved baseline
 * stay unmapped until security mail moves to this outbox. recipientAddress is read only by the
 * delivery worker and must never be logged.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private DeliveryChannel channel;

    @Column(name = "recipient_address", length = 254, updatable = false)
    private String recipientAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "provider_message_id", length = 180)
    private String providerMessageId;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private NotificationDelivery(
            UUID notificationId, DeliveryChannel channel, String recipientAddress, Instant now) {
        this.id = UUID.randomUUID();
        this.notificationId = Objects.requireNonNull(notificationId);
        this.channel = channel;
        this.recipientAddress = recipientAddress;
        this.status = DeliveryStatus.PENDING;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    /** In-app delivery is complete as soon as the notification row exists. */
    public static NotificationDelivery inAppDelivered(UUID notificationId, Instant now) {
        var delivery = new NotificationDelivery(notificationId, DeliveryChannel.IN_APP, null, now);
        delivery.status = DeliveryStatus.SENT;
        delivery.sentAt = now;
        delivery.finishedAt = now;
        return delivery;
    }

    public static NotificationDelivery emailPending(
            UUID notificationId, String recipientAddress, Instant now) {
        return new NotificationDelivery(
                notificationId,
                DeliveryChannel.EMAIL,
                Objects.requireNonNull(recipientAddress),
                now);
    }

    /** Starts one attempt; the caller only claims rows whose attempt budget is not exhausted. */
    public void claim(UUID token, Instant leaseUntil, Instant now) {
        this.status = DeliveryStatus.PROCESSING;
        this.claimToken = Objects.requireNonNull(token);
        this.leaseUntil = Objects.requireNonNull(leaseUntil);
        this.nextRetryAt = null;
        this.attemptCount++;
        this.updatedAt = now;
    }

    public void markSent(String providerMessageId, Instant now) {
        release(DeliveryStatus.SENT, now);
        this.providerMessageId = providerMessageId;
        this.lastFailureCode = null;
        this.sentAt = now;
        this.finishedAt = now;
    }

    public void scheduleRetry(String failureCode, Instant nextRetryAt, Instant now) {
        release(DeliveryStatus.PENDING, now);
        this.lastFailureCode = failureCode;
        this.nextRetryAt = Objects.requireNonNull(nextRetryAt);
    }

    public void markFailed(String failureCode, Instant now) {
        release(DeliveryStatus.FAILED, now);
        this.lastFailureCode = failureCode;
        this.nextRetryAt = null;
        this.finishedAt = now;
    }

    private void release(DeliveryStatus next, Instant now) {
        this.status = next;
        this.claimToken = null;
        this.leaseUntil = null;
        this.updatedAt = Objects.requireNonNull(now);
    }
}
