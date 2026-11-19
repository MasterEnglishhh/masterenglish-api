package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.dto.request.UpdateNotificationPreferencesRequest;
import com.masterenglish.api.modules.notifications.dto.response.NotificationPreferencesResponse;
import com.masterenglish.api.modules.notifications.dto.response.NotificationResponse;
import com.masterenglish.api.modules.notifications.dto.response.UnreadCountResponse;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.service.NotificationService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationServiceImpl(
            NotificationRepository notifications,
            NotificationPreferenceRepository preferences,
            NotificationProperties properties,
            Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getFeed(UUID userId, Pageable pageable) {
        return PageResponse.from(notifications.findFeed(userId, pageable), this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse countUnread(UUID userId) {
        return new UnreadCountResponse(notifications.countUnreadInFeed(userId));
    }

    @Override
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification notification =
                notifications
                        .findInFeed(userId, notificationId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Notification not found."));
        notification.markRead(clock.instant());
    }

    @Override
    @Transactional
    public void markAllRead(UUID userId) {
        notifications.markFeedRead(userId, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse getPreferences(UUID userId) {
        return preferences
                .findById(userId)
                .map(this::toResponse)
                .orElseGet(
                        () ->
                                new NotificationPreferencesResponse(
                                        true,
                                        properties.learningEmailDefault(),
                                        null,
                                        properties.defaultTimezone(),
                                        null,
                                        null,
                                        null));
    }

    @Override
    @Transactional
    public NotificationPreferencesResponse updatePreferences(
            UUID userId, UpdateNotificationPreferencesRequest request) {
        NotificationPreference preference =
                preferences.findById(userId).orElseGet(() -> new NotificationPreference(userId));
        preference.update(
                request.inAppEnabled(),
                request.emailEnabled(),
                request.dailyReminderTime(),
                request.timezone(),
                request.quietHoursStart(),
                request.quietHoursEnd(),
                clock.instant());
        return toResponse(preferences.save(preference));
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getTitle(),
                notification.getBody(),
                notification.getTargetUrl(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }

    private NotificationPreferencesResponse toResponse(NotificationPreference preference) {
        return new NotificationPreferencesResponse(
                preference.isInAppEnabled(),
                preference.isEmailEnabled(),
                preference.getDailyReminderTime(),
                preference.getTimezone(),
                preference.getQuietHoursStart(),
                preference.getQuietHoursEnd(),
                preference.getUpdatedAt());
    }
}
