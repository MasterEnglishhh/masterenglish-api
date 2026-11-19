package com.masterenglish.api.modules.notifications.repository;

import com.masterenglish.api.modules.notifications.entity.Notification;
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

    @Query(
            value = "SELECT n " + FEED + " ORDER BY n.createdAt DESC, n.id DESC",
            countQuery = "SELECT count(n) " + FEED)
    Page<Notification> findFeed(@Param("userId") UUID userId, Pageable pageable);

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
