package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    Optional<NotificationDelivery> findByNotificationIdAndChannel(
            UUID notificationId, DeliveryChannel channel);

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
                        OR (status = 'PROCESSING' AND lease_until < :now))
                    ORDER BY created_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<UUID> lockClaimable(@Param("now") Instant now, @Param("limit") int limit);

    /** Finds the delivery only while the caller still holds its claim. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NotificationDelivery> findByIdAndClaimToken(UUID id, UUID claimToken);
}
