package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, UUID> {}
