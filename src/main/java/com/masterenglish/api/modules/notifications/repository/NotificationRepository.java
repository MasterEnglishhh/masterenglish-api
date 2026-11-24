package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * The learner feed: own USER notifications that have an IN_APP delivery. Feed, unread count and
     * mark-read all share this condition, so INTERNAL rows never reach a learner.
     */
    String FEED =
            """
            FROM Notification n
            WHERE n.userId = :userId
              AND n.visibility = com.masterenglish.api.modules.notifications.entity.NotificationVisibility.USER
              AND EXISTS (SELECT 1 FROM NotificationDelivery d
                          WHERE d.notificationId = n.id
                            AND d.channel = com.masterenglish.api.modules.notifications.entity.DeliveryChannel.IN_APP)
            """;

    String REMINDER_HISTORY_BASE =
            """
            FROM Notification n
            WHERE n.visibility = com.masterenglish.api.modules.notifications.entity.NotificationVisibility.USER
              AND (n.reminderRuleId IS NOT NULL
                   OR n.type IN (
                       com.masterenglish.api.modules.notifications.entity.NotificationType.REVIEW_DUE,
                       com.masterenglish.api.modules.notifications.entity.NotificationType.DAILY_GOAL,
                       com.masterenglish.api.modules.notifications.entity.NotificationType.CONTENT_NEW))
            """;

    @Query(
            value = "SELECT n " + FEED + " ORDER BY n.createdAt DESC, n.id DESC",
            countQuery = "SELECT count(n) " + FEED)
    Page<Notification> findFeed(@Param("userId") UUID userId, Pageable pageable);

    @Query(
            value =
                    "SELECT n "
                            + FEED
                            + " AND (:type IS NULL OR n.type = :type)"
                            + " AND (:unreadOnly = false OR n.readAt IS NULL)"
                            + " ORDER BY n.createdAt DESC, n.id DESC",
            countQuery =
                    "SELECT count(n) "
                            + FEED
                            + " AND (:type IS NULL OR n.type = :type)"
                            + " AND (:unreadOnly = false OR n.readAt IS NULL)")
    Page<Notification> findFeedFiltered(
            @Param("userId") UUID userId,
            @Param("type") NotificationType type,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable);

    @Query(
            value =
                    "SELECT n "
                            + REMINDER_HISTORY_BASE
                            + " AND n.userId = :userId"
                            + " AND (:type IS NULL OR n.type = :type)"
                            + " AND (:status IS NULL OR EXISTS ("
                            + "     SELECT 1 FROM NotificationDelivery d"
                            + "     WHERE d.notificationId = n.id AND d.status = :status))"
                            + " AND (:ruleCode IS NULL OR EXISTS ("
                            + "     SELECT 1 FROM ReminderRule r"
                            + "     WHERE r.id = n.reminderRuleId AND r.code = :ruleCode))"
                            + " AND (:hasFrom = false OR n.createdAt >= :from)"
                            + " AND (:hasTo = false OR n.createdAt <= :to)"
                            + " ORDER BY n.createdAt DESC, n.id DESC",
            countQuery =
                    "SELECT count(n) "
                            + REMINDER_HISTORY_BASE
                            + " AND n.userId = :userId"
                            + " AND (:type IS NULL OR n.type = :type)"
                            + " AND (:status IS NULL OR EXISTS ("
                            + "     SELECT 1 FROM NotificationDelivery d"
                            + "     WHERE d.notificationId = n.id AND d.status = :status))"
                            + " AND (:ruleCode IS NULL OR EXISTS ("
                            + "     SELECT 1 FROM ReminderRule r"
                            + "     WHERE r.id = n.reminderRuleId AND r.code = :ruleCode))"
                            + " AND (:hasFrom = false OR n.createdAt >= :from)"
                            + " AND (:hasTo = false OR n.createdAt <= :to)")
    Page<Notification> findLearnerReminderHistory(
            @Param("userId") UUID userId,
            @Param("type") NotificationType type,
            @Param("status") DeliveryStatus status,
            @Param("ruleCode") String ruleCode,
            @Param("hasFrom") boolean hasFrom,
            @Param("from") Instant from,
            @Param("hasTo") boolean hasTo,
            @Param("to") Instant to,
            Pageable pageable);

    @Query(
            value =
                    "SELECT n "
                            + REMINDER_HISTORY_BASE
                            + " AND (:hasUserId = false OR n.userId = :userId)"
                            + " AND (:type IS NULL OR n.type = :type)"
                            + " AND (:status IS NULL OR EXISTS ("
                            + "     SELECT 1 FROM NotificationDelivery d"
                            + "     WHERE d.notificationId = n.id AND d.status = :status))"
                            + " AND (:hasRuleId = false OR n.reminderRuleId = :ruleId)"
                            + " AND (:hasFrom = false OR n.createdAt >= :from)"
                            + " AND (:hasTo = false OR n.createdAt <= :to)"
                            + " ORDER BY n.createdAt DESC, n.id DESC",
            countQuery =
                    "SELECT count(n) "
                            + REMINDER_HISTORY_BASE
                            + " AND (:hasUserId = false OR n.userId = :userId)"
                            + " AND (:type IS NULL OR n.type = :type)"
                            + " AND (:status IS NULL OR EXISTS ("
                            + "     SELECT 1 FROM NotificationDelivery d"
                            + "     WHERE d.notificationId = n.id AND d.status = :status))"
                            + " AND (:hasRuleId = false OR n.reminderRuleId = :ruleId)"
                            + " AND (:hasFrom = false OR n.createdAt >= :from)"
                            + " AND (:hasTo = false OR n.createdAt <= :to)")
    Page<Notification> findAdminReminderHistory(
            @Param("hasUserId") boolean hasUserId,
            @Param("userId") UUID userId,
            @Param("type") NotificationType type,
            @Param("status") DeliveryStatus status,
            @Param("hasRuleId") boolean hasRuleId,
            @Param("ruleId") UUID ruleId,
            @Param("hasFrom") boolean hasFrom,
            @Param("from") Instant from,
            @Param("hasTo") boolean hasTo,
            @Param("to") Instant to,
            Pageable pageable);

    @Query("SELECT count(n) " + FEED + " AND n.readAt IS NULL")
    long countUnreadInFeed(@Param("userId") UUID userId);

    @Query("SELECT n " + FEED + " AND n.id = :id")
    Optional<Notification> findInFeed(@Param("userId") UUID userId, @Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE Notification u SET u.readAt = :now WHERE u.id IN (SELECT n.id "
                    + FEED
                    + " AND n.readAt IS NULL)")
    int markFeedRead(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query("SELECT n.dedupeKey FROM Notification n WHERE n.dedupeKey IN :keys")
    List<String> findExistingDedupeKeys(@Param("keys") Collection<String> keys);

    @Query(
            """
            SELECT n.userId AS userId, max(n.createdAt) AS lastCreatedAt FROM Notification n
            WHERE n.reminderRuleId = :ruleId AND n.userId IN :userIds
            GROUP BY n.userId
            """)
    List<LastReminder> findLastReminders(
            @Param("ruleId") UUID ruleId, @Param("userIds") Collection<UUID> userIds);

    interface LastReminder {
        UUID getUserId();

        Instant getLastCreatedAt();
    }

    /** Inserts a USER notification unless (user_id, dedupe_key) already exists; returns 0 or 1. */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO notifications
                        (id, user_id, reminder_rule_id, type, visibility, title, body, target_url,
                         dedupe_key, created_at)
                    VALUES (:id, :userId, :ruleId, :type, 'USER', :title, :body, :targetUrl,
                            :dedupeKey, :createdAt)
                    ON CONFLICT (user_id, dedupe_key) DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("ruleId") UUID ruleId,
            @Param("type") String type,
            @Param("title") String title,
            @Param("body") String body,
            @Param("targetUrl") String targetUrl,
            @Param("dedupeKey") String dedupeKey,
            @Param("createdAt") Instant createdAt);
}
