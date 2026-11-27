package com.masterenglish.api.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.NotificationEvents;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleUpdateResult;
import com.masterenglish.api.common.events.NotificationEvents.ReminderRuleView;
import com.masterenglish.api.modules.admin.dto.request.UpdateReminderRuleRequest;
import com.masterenglish.api.modules.admin.service.impl.AdminNotificationServiceImpl;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminNotificationServiceImplTests {

    private final NotificationEvents notificationEvents = mock(NotificationEvents.class);
    private final ContentEvents contentEvents = mock(ContentEvents.class);

    private AdminNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminNotificationServiceImpl(notificationEvents, contentEvents);
    }

    @Test
    @DisplayName("Cập nhật reminder rule ghi audit log trước và sau thay đổi")
    void updateRuleRecordsAuditLog() {
        UUID ruleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-11-19T00:00:00Z");
        var before = new ReminderRuleView(ruleId, "REVIEW_DUE", true, 720, createdAt);
        var after = new ReminderRuleView(ruleId, "REVIEW_DUE", false, 360, createdAt);
        when(notificationEvents.updateReminderRule(ruleId, false, 360))
                .thenReturn(new ReminderRuleUpdateResult(before, after));

        var result = service.updateRule(ruleId, new UpdateReminderRuleRequest(false, 360), actorId);

        assertThat(result.enabled()).isFalse();
        assertThat(result.minimumGapMinutes()).isEqualTo(360);
        verify(contentEvents)
                .recordAuditLog(
                        eq(actorId),
                        eq("REMINDER_RULE_UPDATED"),
                        eq("REMINDER_RULE"),
                        eq(ruleId),
                        contains("\"minimumGapMinutes\":360"),
                        contains("REVIEW_DUE"));
    }
}
