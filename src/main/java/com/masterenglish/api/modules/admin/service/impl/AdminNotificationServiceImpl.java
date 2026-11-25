package com.masterenglish.api.modules.admin.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.NotificationEvents;
import com.masterenglish.api.common.events.NotificationEvents.AdminReminderHistoryView;
import com.masterenglish.api.common.events.NotificationEvents.DeliveryView;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleUpdateResult;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleView;
import com.masterenglish.api.modules.admin.dto.request.UpdateReminderRuleRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminNotificationDeliveryDto;
import com.masterenglish.api.modules.admin.dto.response.AdminReminderHistoryDto;
import com.masterenglish.api.modules.admin.dto.response.AdminReminderHistoryDto.DeliverySummaryDto;
import com.masterenglish.api.modules.admin.dto.response.ReminderRuleDto;
import com.masterenglish.api.modules.admin.service.AdminNotificationService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminNotificationServiceImpl implements AdminNotificationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationEvents notifications;
    private final ContentEvents content;

    public AdminNotificationServiceImpl(NotificationEvents notifications, ContentEvents content) {
        this.notifications = Objects.requireNonNull(notifications);
        this.content = Objects.requireNonNull(content);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminNotificationDeliveryDto> findDeliveries(
            String status,
            String channel,
            String type,
            Instant from,
            Instant to,
            Pageable pageable) {
        return notifications
                .findDeliveries(status, channel, type, from, to, pageable)
                .map(AdminNotificationServiceImpl::toDeliveryDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReminderRuleDto> listRules() {
        return notifications.listReminderRules().stream()
                .map(AdminNotificationServiceImpl::toRuleDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReminderRuleDto getRule(UUID ruleId) {
        return toRuleDto(notifications.getReminderRule(ruleId));
    }

    @Override
    @Transactional
    public ReminderRuleDto updateRule(
            UUID ruleId, UpdateReminderRuleRequest request, UUID actorId) {
        ReminderRuleUpdateResult result =
                notifications.updateReminderRule(
                        ruleId, request.enabled(), request.minimumGapMinutes());
        ReminderRuleView before = result.before();
        ReminderRuleView after = result.after();
        String metadata = serializeRuleChangeMetadata(before, after);
        content.recordAuditLog(
                actorId,
                "REMINDER_RULE_UPDATED",
                "REMINDER_RULE",
                ruleId,
                metadata,
                "Cập nhật quy tắc nhắc học " + after.code());
        return toRuleDto(after);
    }

    private static String serializeRuleChangeMetadata(
            ReminderRuleView before, ReminderRuleView after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", after.code());
        payload.put(
                "before",
                Map.of(
                        "enabled", before.enabled(),
                        "minimumGapMinutes", before.minimumGapMinutes()));
        payload.put(
                "after",
                Map.of(
                        "enabled", after.enabled(),
                        "minimumGapMinutes", after.minimumGapMinutes()));
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize audit metadata.", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminReminderHistoryDto> findReminderHistory(
            UUID userId,
            String type,
            String status,
            UUID ruleId,
            Instant from,
            Instant to,
            Pageable pageable) {
        return notifications
                .findReminderHistory(userId, type, status, ruleId, from, to, pageable)
                .map(AdminNotificationServiceImpl::toHistoryDto);
    }

    private static AdminNotificationDeliveryDto toDeliveryDto(DeliveryView view) {
        return new AdminNotificationDeliveryDto(
                view.id(),
                view.notificationId(),
                view.userId(),
                view.type(),
                view.channel(),
                view.status(),
                view.attemptCount(),
                view.nextRetryAt(),
                view.lastFailureCode(),
                view.providerMessageId(),
                view.sentAt(),
                view.finishedAt(),
                view.createdAt(),
                view.updatedAt());
    }

    private static ReminderRuleDto toRuleDto(ReminderRuleView view) {
        return new ReminderRuleDto(
                view.id(), view.code(), view.enabled(), view.minimumGapMinutes(), view.createdAt());
    }

    private static AdminReminderHistoryDto toHistoryDto(AdminReminderHistoryView view) {
        return new AdminReminderHistoryDto(
                view.notificationId(),
                view.userId(),
                view.type(),
                view.reminderRuleId(),
                view.reminderRuleCode(),
                view.targetUrl(),
                view.readAt(),
                view.createdAt(),
                view.deliveries().stream()
                        .map(
                                d ->
                                        new DeliverySummaryDto(
                                                d.channel(),
                                                d.status(),
                                                d.attemptCount(),
                                                d.lastFailureCode(),
                                                d.sentAt(),
                                                d.finishedAt()))
                        .toList());
    }
}
