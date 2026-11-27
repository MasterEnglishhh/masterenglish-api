package com.masterenglish.api.modules.notifications.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.NotificationEvents.AdminReminderHistoryView;
import com.masterenglish.api.common.events.NotificationEvents.DeliveryView;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleUpdateResult;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/** Internal admin/ops operations for M10.09–M10.11, invoked via NotificationEvents. */
public interface NotificationAdminService {

    PageResponse<DeliveryView> findDeliveries(
            String status,
            String channel,
            String type,
            Instant from,
            Instant to,
            Pageable pageable);

    List<ReminderRuleView> listReminderRules();

    ReminderRuleView getReminderRule(UUID ruleId);

    ReminderRuleUpdateResult updateReminderRule(
            UUID ruleId, boolean enabled, int minimumGapMinutes);

    PageResponse<AdminReminderHistoryView> findReminderHistory(
            UUID userId,
            String type,
            String status,
            UUID ruleId,
            Instant from,
            Instant to,
            Pageable pageable);
}
