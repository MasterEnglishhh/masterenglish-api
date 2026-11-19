package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, UUID> {

    /** Keyset page of learners with a reminder time and at least one enabled channel. */
    @Query(
            """
            SELECT p FROM NotificationPreference p
            WHERE p.dailyReminderTime IS NOT NULL
              AND (p.inAppEnabled = true OR p.emailEnabled = true)
              AND p.userId > :after
            ORDER BY p.userId
            """)
    List<NotificationPreference> findReminderCandidates(
            @Param("after") UUID after, Pageable pageable);
}
