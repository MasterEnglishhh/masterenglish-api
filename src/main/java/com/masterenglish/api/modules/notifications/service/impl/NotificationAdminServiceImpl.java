package com.masterenglish.api.modules.notifications.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.NotificationEvents.AdminReminderHistoryView;
import com.masterenglish.api.common.events.NotificationEvents.ChannelDeliverySummary;
import com.masterenglish.api.common.events.NotificationEvents.DeliveryView;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleUpdateResult;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleView;
import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.ReminderRule;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository.AdminDeliveryRow;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.repository.ReminderRuleRepository;
import com.masterenglish.api.modules.notifications.service.NotificationAdminService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationAdminServiceImpl implements NotificationAdminService {

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final ReminderRuleRepository rules;

    public NotificationAdminServiceImpl(
            NotificationDeliveryRepository deliveries,
            NotificationRepository notifications,
            ReminderRuleRepository rules) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.rules = rules;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeliveryView> findDeliveries(
            String status,
            String channel,
            String type,
            Instant from,
            Instant to,
            Pageable pageable) {
        DeliveryStatus parsedStatus = parseEnum(DeliveryStatus.class, status, "status");
        DeliveryChannel parsedChannel = parseEnum(DeliveryChannel.class, channel, "channel");
        NotificationType parsedType = parseEnum(NotificationType.class, type, "type");
        validateDateRange(from, to);

        Page<AdminDeliveryRow> page =
                deliveries.findAdminDeliveries(
                        parsedStatus,
                        parsedChannel,
                        parsedType,
                        from != null,
                        from == null ? Instant.EPOCH : from,
                        to != null,
                        to == null ? Instant.EPOCH : to,
                        pageable);
        return PageResponse.from(page, NotificationAdminServiceImpl::toDeliveryView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReminderRuleView> listReminderRules() {
        return rules.findAll(Sort.by(Sort.Direction.ASC, "code")).stream()
                .map(NotificationAdminServiceImpl::toRuleView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReminderRuleView getReminderRule(UUID ruleId) {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        return toRuleView(findRuleOrThrow(ruleId));
    }

    @Override
    @Transactional
    public ReminderRuleUpdateResult updateReminderRule(
            UUID ruleId, boolean enabled, int minimumGapMinutes) {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        if (minimumGapMinutes < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "minimumGapMinutes must be >= 0.");
        }
        ReminderRule rule = findRuleOrThrow(ruleId);
        ReminderRuleView before = toRuleView(rule);
        rule.update(enabled, minimumGapMinutes);
        ReminderRule saved = rules.saveAndFlush(rule);
        return new ReminderRuleUpdateResult(before, toRuleView(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminReminderHistoryView> findReminderHistory(
            UUID userId,
            String type,
            String status,
            UUID ruleId,
            Instant from,
            Instant to,
            Pageable pageable) {
        NotificationType parsedType = parseReminderType(type);
        DeliveryStatus parsedStatus = parseEnum(DeliveryStatus.class, status, "status");
        validateDateRange(from, to);

        Page<Notification> page =
                notifications.findAdminReminderHistory(
                        userId != null,
                        userId == null ? new UUID(0, 0) : userId,
                        parsedType,
                        parsedStatus,
                        ruleId != null,
                        ruleId == null ? new UUID(0, 0) : ruleId,
                        from != null,
                        from == null ? Instant.EPOCH : from,
                        to != null,
                        to == null ? Instant.EPOCH : to,
                        pageable);

        List<UUID> notificationIds = page.getContent().stream().map(Notification::getId).toList();
        Map<UUID, List<ChannelDeliverySummary>> deliveriesByNotification =
                notificationIds.isEmpty()
                        ? Map.of()
                        : deliveries
                                .findByNotificationIdInOrderByChannelAsc(notificationIds)
                                .stream()
                                .collect(
                                        Collectors.groupingBy(
                                                NotificationDelivery::getNotificationId,
                                                Collectors.mapping(
                                                        NotificationAdminServiceImpl
                                                                ::toDeliverySummary,
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
                        new AdminReminderHistoryView(
                                notification.getId(),
                                notification.getUserId(),
                                notification.getType().name(),
                                notification.getReminderRuleId(),
                                notification.getReminderRuleId() == null
                                        ? null
                                        : ruleCodes.get(notification.getReminderRuleId()),
                                notification.getTargetUrl(),
                                notification.getReadAt(),
                                notification.getCreatedAt(),
                                deliveriesByNotification.getOrDefault(
                                        notification.getId(), List.of())));
    }

    private ReminderRule findRuleOrThrow(UUID ruleId) {
        return rules.findById(ruleId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND, "Reminder rule not found."));
    }

    private static void validateDateRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "'from' must not be after 'to'.");
        }
    }

    private static NotificationType parseReminderType(String raw) {
        NotificationType parsed = parseEnum(NotificationType.class, raw, "type");
        if (parsed == NotificationType.SECURITY) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "SECURITY is not a reminder notification type.");
        }
        return parsed;
    }

    static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Invalid " + field + ": " + raw);
        }
    }

    private static DeliveryView toDeliveryView(AdminDeliveryRow row) {
        return new DeliveryView(
                row.getId(),
                row.getNotificationId(),
                row.getUserId(),
                row.getType().name(),
                row.getChannel().name(),
                row.getStatus().name(),
                row.getAttemptCount(),
                row.getNextRetryAt(),
                row.getLastFailureCode(),
                row.getProviderMessageId(),
                row.getSentAt(),
                row.getFinishedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private static ReminderRuleView toRuleView(ReminderRule rule) {
        return new ReminderRuleView(
                rule.getId(),
                rule.getCode(),
                rule.isEnabled(),
                rule.getMinimumGapMinutes(),
                rule.getCreatedAt());
    }

    private static ChannelDeliverySummary toDeliverySummary(NotificationDelivery delivery) {
        return new ChannelDeliverySummary(
                delivery.getChannel().name(),
                delivery.getStatus().name(),
                delivery.getAttemptCount(),
                delivery.getLastFailureCode(),
                delivery.getSentAt(),
                delivery.getFinishedAt());
    }
}
