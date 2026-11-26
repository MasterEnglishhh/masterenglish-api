package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.time.TimeZones;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.dto.request.UpdateNotificationPreferencesRequest;
import com.masterenglish.api.modules.notifications.dto.response.NotificationPreferencesResponse;
import com.masterenglish.api.modules.notifications.dto.response.NotificationResponse;
import com.masterenglish.api.modules.notifications.dto.response.ReminderHistoryResponse;
import com.masterenglish.api.modules.notifications.dto.response.ReminderHistoryResponse.DeliveryOutcome;
import com.masterenglish.api.modules.notifications.dto.response.UnreadCountResponse;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.ReminderRule;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.repository.ReminderRuleRepository;
import com.masterenglish.api.modules.notifications.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationPreferenceRepository preferences;
    private final ReminderRuleRepository rules;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationServiceImpl(
            NotificationRepository notifications,
            NotificationDeliveryRepository deliveries,
            NotificationPreferenceRepository preferences,
            ReminderRuleRepository rules,
            NotificationProperties properties,
            Clock clock) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.preferences = preferences;
        this.rules = rules;
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
    public PageResponse<NotificationResponse> getFeed(
            UUID userId, String type, Boolean unreadOnly, Pageable pageable) {
        if ((type == null || type.isBlank()) && unreadOnly == null) {
            return getFeed(userId, pageable);
        }
        NotificationType parsedType = parseUserNotificationType(type);
        return PageResponse.from(
                notifications.findFeedFiltered(
                        userId, parsedType, Boolean.TRUE.equals(unreadOnly), pageable),
                this::toResponse);
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
                                        false,
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
        Instant now = clock.instant();
        String canonicalTimezone = TimeZones.requireCanonical(request.timezone());
        preferences.upsertPreference(
                userId,
                request.inAppEnabled(),
                request.emailEnabled(),
                request.contentNotificationsEnabled(),
                request.dailyReminderTime(),
                canonicalTimezone,
                request.quietHoursStart(),
                request.quietHoursEnd(),
                now);
        return new NotificationPreferencesResponse(
                request.inAppEnabled(),
                request.emailEnabled(),
                request.contentNotificationsEnabled(),
                request.dailyReminderTime(),
                canonicalTimezone,
                request.quietHoursStart(),
                request.quietHoursEnd(),
                now);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReminderHistoryResponse> getReminderHistory(
            UUID userId,
            String type,
            String status,
            String ruleCode,
            Instant from,
            Instant to,
            Pageable pageable) {
        NotificationType parsedType = parseUserNotificationType(type);
        DeliveryStatus parsedStatus =
                NotificationAdminServiceImpl.parseEnum(DeliveryStatus.class, status, "status");
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "'from' must not be after 'to'.");
        }
        String normalizedRuleCode = ruleCode == null || ruleCode.isBlank() ? null : ruleCode.trim();

        Page<Notification> page =
                notifications.findLearnerReminderHistory(
                        userId,
                        parsedType,
                        parsedStatus,
                        normalizedRuleCode,
                        from != null,
                        from == null ? Instant.EPOCH : from,
                        to != null,
                        to == null ? Instant.EPOCH : to,
                        pageable);

        List<UUID> ids = page.getContent().stream().map(Notification::getId).toList();
        Map<UUID, List<DeliveryOutcome>> outcomesByNotification =
                ids.isEmpty()
                        ? Map.of()
                        : deliveries.findByNotificationIdInOrderByChannelAsc(ids).stream()
                                .collect(
                                        Collectors.groupingBy(
                                                NotificationDelivery::getNotificationId,
                                                Collectors.mapping(
                                                        NotificationServiceImpl::toDeliveryOutcome,
                                                        Collectors.toList())));

        Set<UUID> ruleIds =
                page.getContent().stream()
                        .map(Notification::getReminderRuleId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Map<UUID, String> ruleCodes =
                ruleIds.isEmpty()
                        ? Map.of()
                        : rules.findAllById(ruleIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                ReminderRule::getId, ReminderRule::getCode));

        return PageResponse.from(
                page,
                notification ->
                        new ReminderHistoryResponse(
                                notification.getId(),
                                notification.getType().name(),
                                notification.getReminderRuleId() == null
                                        ? null
                                        : ruleCodes.get(notification.getReminderRuleId()),
                                notification.getTitle(),
                                notification.getBody(),
                                notification.getTargetUrl(),
                                notification.getReadAt(),
                                notification.getCreatedAt(),
                                outcomesByNotification.getOrDefault(
                                        notification.getId(), List.of())));
    }

    private static NotificationType parseUserNotificationType(String raw) {
        NotificationType parsed =
                NotificationAdminServiceImpl.parseEnum(NotificationType.class, raw, "type");
        if (parsed == NotificationType.SECURITY) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid type: " + raw);
        }
        return parsed;
    }

    private static DeliveryOutcome toDeliveryOutcome(NotificationDelivery delivery) {
        return new DeliveryOutcome(
                delivery.getChannel().name(),
                delivery.getStatus().name(),
                delivery.getAttemptCount(),
                delivery.getLastFailureCode(),
                delivery.getSentAt(),
                delivery.getFinishedAt());
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
                preference.isContentNotificationsEnabled(),
                preference.getDailyReminderTime(),
                preference.getTimezone(),
                preference.getQuietHoursStart(),
                preference.getQuietHoursEnd(),
                preference.getUpdatedAt());
    }
}
