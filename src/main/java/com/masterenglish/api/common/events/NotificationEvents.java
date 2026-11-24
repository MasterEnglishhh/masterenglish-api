package com.masterenglish.api.common.events;

import com.masterenglish.api.common.api.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** Synchronous queries and commands handled by the notifications module. */
@Component
public class NotificationEvents {

    private final ModuleEvents events;

    NotificationEvents(ModuleEvents events) {
        this.events = events;
    }

    public long countUnreadInFeed(UUID userId) {
        return events.request(reply -> new UnreadCountRequested(userId, reply));
    }

    public PageResponse<DeliveryView> findDeliveries(
            String status,
            String channel,
            String type,
            Instant from,
            Instant to,
            Pageable pageable) {
        return events.request(
                reply -> new AdminFindDeliveries(status, channel, type, from, to, pageable, reply));
    }

    public List<ReminderRuleView> listReminderRules() {
        return events.request(AdminListReminderRules::new);
    }

    public ReminderRuleView getReminderRule(UUID ruleId) {
        return events.request(reply -> new AdminGetReminderRule(ruleId, reply));
    }

    public ReminderRuleUpdateResult updateReminderRule(
            UUID ruleId, boolean enabled, int minimumGapMinutes) {
        return events.request(
                reply -> new AdminUpdateReminderRule(ruleId, enabled, minimumGapMinutes, reply));
    }

    public PageResponse<AdminReminderHistoryView> findReminderHistory(
            UUID userId,
            String type,
            String status,
            UUID ruleId,
            Instant from,
            Instant to,
            Pageable pageable) {
        return events.request(
                reply ->
                        new AdminFindReminderHistory(
                                userId, type, status, ruleId, from, to, pageable, reply));
    }

    public record UnreadCountRequested(UUID userId, EventReply<Long> reply) {}

    public record DeliveryView(
            UUID id,
            UUID notificationId,
            UUID userId,
            String type,
            String channel,
            String status,
            int attemptCount,
            Instant nextRetryAt,
            String lastFailureCode,
            String providerMessageId,
            Instant sentAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record ReminderRuleView(
            UUID id, String code, boolean enabled, int minimumGapMinutes, Instant createdAt) {}

    public record ReminderRuleUpdateResult(ReminderRuleView before, ReminderRuleView after) {}

    public record ChannelDeliverySummary(
            String channel,
            String status,
            int attemptCount,
            String lastFailureCode,
            Instant sentAt,
            Instant finishedAt) {}

    public record AdminReminderHistoryView(
            UUID notificationId,
            UUID userId,
            String type,
            UUID reminderRuleId,
            String reminderRuleCode,
            String targetUrl,
            Instant readAt,
            Instant createdAt,
            List<ChannelDeliverySummary> deliveries) {}

    public record AdminFindDeliveries(
            String status,
            String channel,
            String type,
            Instant from,
            Instant to,
            Pageable pageable,
            EventReply<PageResponse<DeliveryView>> reply) {}

    public record AdminListReminderRules(EventReply<List<ReminderRuleView>> reply) {}

    public record AdminGetReminderRule(UUID ruleId, EventReply<ReminderRuleView> reply) {}

    public record AdminUpdateReminderRule(
            UUID ruleId,
            boolean enabled,
            int minimumGapMinutes,
            EventReply<ReminderRuleUpdateResult> reply) {}

    public record AdminFindReminderHistory(
            UUID userId,
            String type,
            String status,
            UUID ruleId,
            Instant from,
            Instant to,
            Pageable pageable,
            EventReply<PageResponse<AdminReminderHistoryView>> reply) {}
}
