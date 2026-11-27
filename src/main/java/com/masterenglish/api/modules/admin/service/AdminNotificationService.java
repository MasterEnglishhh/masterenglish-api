package com.masterenglish.api.modules.admin.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.admin.dto.request.UpdateReminderRuleRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminNotificationDeliveryDto;
import com.masterenglish.api.modules.admin.dto.response.AdminReminderHistoryDto;
import com.masterenglish.api.modules.admin.dto.response.ReminderRuleDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface AdminNotificationService {

    PageResponse<AdminNotificationDeliveryDto> findDeliveries(
            String status,
            String channel,
            String type,
            Instant from,
            Instant to,
            Pageable pageable);

    List<ReminderRuleDto> listRules();

    ReminderRuleDto getRule(UUID ruleId);

    ReminderRuleDto updateRule(UUID ruleId, UpdateReminderRuleRequest request, UUID actorId);

    PageResponse<AdminReminderHistoryDto> findReminderHistory(
            UUID userId,
            String type,
            String status,
            UUID ruleId,
            Instant from,
            Instant to,
            Pageable pageable);
}
