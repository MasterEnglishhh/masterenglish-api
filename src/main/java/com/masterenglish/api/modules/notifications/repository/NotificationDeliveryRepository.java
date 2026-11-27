package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    Optional<NotificationDelivery> findByNotificationIdAndChannel(
            UUID notificationId, DeliveryChannel channel);

    List<NotificationDelivery> findByNotificationIdInOrderByChannelAsc(
            Collection<UUID> notificationIds);

    /**
     * Admin monitoring query (M10.09). Returns delivery metadata and notification type/owner
     * without exposing recipientAddress, claimToken, or encrypted payloads.
     */
    @Query(
            value =
                    """
                    SELECT d.id AS id,
                           d.notificationId AS notificationId,
                           n.userId AS userId,
                           n.type AS type,
                           d.channel AS channel,
                           d.status AS status,
                           d.attemptCount AS attemptCount,
                           d.nextRetryAt AS nextRetryAt,
                           d.lastFailureCode AS lastFailureCode,
                           d.providerMessageId AS providerMessageId,
                           d.sentAt AS sentAt,
                           d.finishedAt AS finishedAt,
                           d.createdAt AS createdAt,
                           d.updatedAt AS updatedAt
                    FROM NotificationDelivery d
                    JOIN Notification n ON n.id = d.notificationId
                    WHERE (:status IS NULL OR d.status = :status)
                      AND (:channel IS NULL OR d.channel = :channel)
                      AND (:type IS NULL OR n.type = :type)
                      AND (:hasFrom = false OR d.createdAt >= :from)
                      AND (:hasTo = false OR d.createdAt <= :to)
                    ORDER BY d.createdAt DESC, d.id DESC
                    """,
            countQuery =
                    """
                    SELECT count(d)
                    FROM NotificationDelivery d
                    JOIN Notification n ON n.id = d.notificationId
                    WHERE (:status IS NULL OR d.status = :status)
                      AND (:channel IS NULL OR d.channel = :channel)
                      AND (:type IS NULL OR n.type = :type)
                      AND (:hasFrom = false OR d.createdAt >= :from)
                      AND (:hasTo = false OR d.createdAt <= :to)
                    """)
    Page<AdminDeliveryRow> findAdminDeliveries(
            @Param("status") DeliveryStatus status,
            @Param("channel") DeliveryChannel channel,
            @Param("type") NotificationType type,
            @Param("hasFrom") boolean hasFrom,
            @Param("from") Instant from,
            @Param("hasTo") boolean hasTo,
            @Param("to") Instant to,
            Pageable pageable);

    interface AdminDeliveryRow {
        UUID getId();

        UUID getNotificationId();

        UUID getUserId();

        NotificationType getType();

        DeliveryChannel getChannel();

        DeliveryStatus getStatus();

        short getAttemptCount();

        Instant getNextRetryAt();

        String getLastFailureCode();

        String getProviderMessageId();

        Instant getSentAt();

        Instant getFinishedAt();

        Instant getCreatedAt();

        Instant getUpdatedAt();
    }

    /**
     * Locks email deliveries that are due (PENDING past next_retry_at) or abandoned (PROCESSING
     * with an expired lease). Rows locked by another worker are skipped, never waited for.
     */
    @Query(
            value =
                    """
                    SELECT id FROM notification_deliveries
                    WHERE channel = 'EMAIL'
                      AND ((status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :now))
                        OR (status = 'PROCESSING' AND lease_until <= :now))
                    ORDER BY created_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<UUID> lockClaimable(@Param("now") Instant now, @Param("limit") int limit);

    @Query(
            value =
                    """
                    SELECT id FROM notification_deliveries
                    WHERE channel = 'EMAIL'
                      AND id NOT IN (:excludedIds)
                      AND ((status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :now))
                        OR (status = 'PROCESSING' AND lease_until <= :now))
                    ORDER BY created_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<UUID> lockClaimableExcluding(
            @Param("now") Instant now,
            @Param("excludedIds") Collection<UUID> excludedIds,
            @Param("limit") int limit);

    /** Finds the delivery only while the caller still holds its claim. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NotificationDelivery> findByIdAndClaimToken(UUID id, UUID claimToken);
}
