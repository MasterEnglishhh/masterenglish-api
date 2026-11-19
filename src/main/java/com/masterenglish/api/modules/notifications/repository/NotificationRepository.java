package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {}
