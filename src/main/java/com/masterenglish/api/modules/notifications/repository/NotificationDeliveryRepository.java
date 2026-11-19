package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    Optional<NotificationDelivery> findByNotificationIdAndChannel(
            UUID notificationId, DeliveryChannel channel);
}
