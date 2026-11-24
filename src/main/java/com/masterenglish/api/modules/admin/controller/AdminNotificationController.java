package com.masterenglish.api.modules.admin.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.admin.dto.request.UpdateReminderRuleRequest;
import com.masterenglish.api.modules.admin.dto.response.AdminNotificationDeliveryDto;
import com.masterenglish.api.modules.admin.dto.response.AdminReminderHistoryDto;
import com.masterenglish.api.modules.admin.dto.response.ReminderRuleDto;
import com.masterenglish.api.modules.admin.service.AdminNotificationService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin notification monitoring, reminder rule management, and history lookup (M10.09–M10.11). */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminNotificationController {

    private final AdminNotificationService notificationService;

    public AdminNotificationController(AdminNotificationService notificationService) {
        this.notificationService = Objects.requireNonNull(notificationService);
    }

    @GetMapping("/deliveries")
    public ResponseEntity<PageResponse<AdminNotificationDeliveryDto>> getDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(
                notificationService.findDeliveries(
                        status, channel, type, from, to, PageRequestUtils.of(page, size)));
    }

    @GetMapping("/rules")
    public ResponseEntity<List<ReminderRuleDto>> listRules() {
        return ResponseEntity.ok(notificationService.listRules());
    }

    @GetMapping("/rules/{ruleId}")
    public ResponseEntity<ReminderRuleDto> getRule(@PathVariable UUID ruleId) {
        return ResponseEntity.ok(notificationService.getRule(ruleId));
    }

    @PutMapping("/rules/{ruleId}")
    public ResponseEntity<ReminderRuleDto> updateRule(
            @PathVariable UUID ruleId,
            @Valid @RequestBody UpdateReminderRuleRequest request,
            @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(
                notificationService.updateRule(ruleId, request, currentUser.getId()));
    }

    @GetMapping("/history")
    public ResponseEntity<PageResponse<AdminReminderHistoryDto>> getReminderHistory(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID ruleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(
                notificationService.findReminderHistory(
                        userId, type, status, ruleId, from, to, PageRequestUtils.of(page, size)));
    }
}
