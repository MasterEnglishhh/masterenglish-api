package com.masterenglish.api.modules.notifications.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.notifications.dto.request.UpdateNotificationPreferencesRequest;
import com.masterenglish.api.modules.notifications.dto.response.NotificationPreferencesResponse;
import com.masterenglish.api.modules.notifications.dto.response.NotificationResponse;
import com.masterenglish.api.modules.notifications.dto.response.ReminderHistoryResponse;
import com.masterenglish.api.modules.notifications.dto.response.UnreadCountResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/** Learner-facing notification feed, reminder history, and settings (M10.01–M10.04, M10.11). */
public interface NotificationService {

    PageResponse<NotificationResponse> getFeed(UUID userId, Pageable pageable);

    PageResponse<NotificationResponse> getFeed(
            UUID userId, String type, Boolean unreadOnly, Pageable pageable);

    UnreadCountResponse countUnread(UUID userId);

    /** Idempotent; notifications outside the learner's feed are reported as not found. */
    void markRead(UUID userId, UUID notificationId);

    void markAllRead(UUID userId);

    /** Returns defaults without creating a row when the learner has not saved settings yet. */
    NotificationPreferencesResponse getPreferences(UUID userId);

    String findLearnerTimezone(UUID userId);

    NotificationPreferencesResponse updatePreferences(
            UUID userId, UpdateNotificationPreferencesRequest request);

    PageResponse<ReminderHistoryResponse> getReminderHistory(
            UUID userId,
            String type,
            String status,
            String ruleCode,
            Instant from,
            Instant to,
            Pageable pageable);
}
