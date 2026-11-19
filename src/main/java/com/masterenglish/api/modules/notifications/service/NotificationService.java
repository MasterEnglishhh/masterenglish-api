package com.masterenglish.api.modules.notifications.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.notifications.dto.request.UpdateNotificationPreferencesRequest;
import com.masterenglish.api.modules.notifications.dto.response.NotificationPreferencesResponse;
import com.masterenglish.api.modules.notifications.dto.response.NotificationResponse;
import com.masterenglish.api.modules.notifications.dto.response.UnreadCountResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/** Learner-facing notification feed and reminder settings (M10.01–M10.04). */
public interface NotificationService {

    PageResponse<NotificationResponse> getFeed(UUID userId, Pageable pageable);

    UnreadCountResponse countUnread(UUID userId);

    /** Idempotent; notifications outside the learner's feed are reported as not found. */
    void markRead(UUID userId, UUID notificationId);

    void markAllRead(UUID userId);

    /** Returns defaults without creating a row when the learner has not saved settings yet. */
    NotificationPreferencesResponse getPreferences(UUID userId);

    NotificationPreferencesResponse updatePreferences(
            UUID userId, UpdateNotificationPreferencesRequest request);
}
