package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Atomically creates or replaces a learner's preferences so concurrent first-time PUT requests
     * cannot collide on the primary key.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO notification_preferences
                        (user_id, in_app_enabled, email_enabled, daily_reminder_time, timezone,
                         quiet_hours_start, quiet_hours_end, updated_at)
                    VALUES (:userId, :inAppEnabled, :emailEnabled, :dailyReminderTime, :timezone,
                            :quietHoursStart, :quietHoursEnd, :now)
                    ON CONFLICT (user_id) DO UPDATE SET
                        in_app_enabled = EXCLUDED.in_app_enabled,
                        email_enabled = EXCLUDED.email_enabled,
                        daily_reminder_time = EXCLUDED.daily_reminder_time,
                        timezone = EXCLUDED.timezone,
                        quiet_hours_start = EXCLUDED.quiet_hours_start,
                        quiet_hours_end = EXCLUDED.quiet_hours_end,
                        updated_at = EXCLUDED.updated_at
                    """,
            nativeQuery = true)
    void upsertPreference(
            @Param("userId") UUID userId,
            @Param("inAppEnabled") boolean inAppEnabled,
            @Param("emailEnabled") boolean emailEnabled,
            @Param("dailyReminderTime") LocalTime dailyReminderTime,
            @Param("timezone") String timezone,
            @Param("quietHoursStart") LocalTime quietHoursStart,
            @Param("quietHoursEnd") LocalTime quietHoursEnd,
            @Param("now") Instant now);
}
