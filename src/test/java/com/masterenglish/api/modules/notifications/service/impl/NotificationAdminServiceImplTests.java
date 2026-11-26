package com.masterenglish.api.modules.notifications.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.repository.ReminderRuleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class NotificationAdminServiceImplTests {

    private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

    private final NotificationDeliveryRepository deliveries =
            mock(NotificationDeliveryRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final ReminderRuleRepository rules = mock(ReminderRuleRepository.class);

    private NotificationAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationAdminServiceImpl(deliveries, notifications, rules);
    }

    @Test
    @DisplayName("findDeliveries từ chối status/channel/type sai hoặc khoảng thời gian ngược")
    void findDeliveriesValidatesFilters() {
        var pageable = PageRequest.of(0, 20);
        assertThatThrownBy(
                        () ->
                                service.findDeliveries(
                                        "BAD_STATUS", null, null, null, null, pageable))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.findDeliveries(null, "SMS", null, null, null, pageable))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(
                        () ->
                                service.findDeliveries(
                                        null, null, null, NOW, NOW.minusSeconds(10), pageable))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("findDeliveries ánh xạ đúng bộ lọc và trả DeliveryView không có email")
    void findDeliveriesMapsFilters() {
        UUID deliveryId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var row =
                new NotificationDeliveryRepository.AdminDeliveryRow() {
                    @Override
                    public UUID getId() {
                        return deliveryId;
                    }

                    @Override
                    public UUID getNotificationId() {
                        return notificationId;
                    }

                    @Override
                    public UUID getUserId() {
                        return userId;
                    }

                    @Override
                    public NotificationType getType() {
                        return NotificationType.REVIEW_DUE;
                    }

                    @Override
                    public DeliveryChannel getChannel() {
                        return DeliveryChannel.EMAIL;
                    }

                    @Override
                    public DeliveryStatus getStatus() {
                        return DeliveryStatus.FAILED;
                    }

                    @Override
                    public short getAttemptCount() {
                        return 4;
                    }

                    @Override
                    public Instant getNextRetryAt() {
                        return null;
                    }

                    @Override
                    public String getLastFailureCode() {
                        return "MAIL_SEND_FAILED";
                    }

                    @Override
                    public String getProviderMessageId() {
                        return null;
                    }

                    @Override
                    public Instant getSentAt() {
                        return null;
                    }

                    @Override
                    public Instant getFinishedAt() {
                        return NOW;
                    }

                    @Override
                    public Instant getCreatedAt() {
                        return NOW.minusSeconds(60);
                    }

                    @Override
                    public Instant getUpdatedAt() {
                        return NOW;
                    }
                };
        var pageable = PageRequest.of(0, 20);
        when(deliveries.findAdminDeliveries(
                        DeliveryStatus.FAILED,
                        DeliveryChannel.EMAIL,
                        NotificationType.REVIEW_DUE,
                        false,
                        Instant.EPOCH,
                        false,
                        Instant.EPOCH,
                        pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));

        var result = service.findDeliveries("failed", "email", "review_due", null, null, pageable);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().lastFailureCode()).isEqualTo("MAIL_SEND_FAILED");
        assertThat(result.items().getFirst().attemptCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("listReminderRules sắp xếp theo code và getReminderRule báo 404 khi thiếu")
    void ruleLookupAndNotFound() {
        when(rules.findAll(Sort.by(Sort.Direction.ASC, "code"))).thenReturn(List.of());
        assertThat(service.listReminderRules()).isEmpty();

        UUID missing = UUID.randomUUID();
        when(rules.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getReminderRule(missing))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("updateReminderRule kiểm tra minimumGapMinutes >= 0")
    void updateRuleRejectsNegativeGap() {
        UUID ruleId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateReminderRule(ruleId, false, -1))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("findReminderHistory từ chối type SECURITY")
    void reminderHistoryRejectsSecurityType() {
        var pageable = PageRequest.of(0, 20);
        assertThatThrownBy(
                        () ->
                                service.findReminderHistory(
                                        null, "SECURITY", null, null, null, null, pageable))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
