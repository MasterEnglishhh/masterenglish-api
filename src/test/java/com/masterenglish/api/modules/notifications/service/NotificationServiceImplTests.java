package com.masterenglish.api.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.config.NotificationProperties;
import com.masterenglish.api.modules.notifications.dto.request.UpdateNotificationPreferencesRequest;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.NotificationVisibility;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.service.impl.NotificationServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationServiceImplTests {

    private static final Instant NOW = Instant.parse("2026-11-19T09:00:00Z");

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationPreferenceRepository preferences =
            mock(NotificationPreferenceRepository.class);
    private final UUID userId = UUID.randomUUID();
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new NotificationServiceImpl(
                        notifications,
                        preferences,
                        new NotificationProperties(
                                "Asia/Bangkok",
                                false,
                                3,
                                60,
                                List.of(
                                        Duration.ofMinutes(1),
                                        Duration.ofMinutes(5),
                                        Duration.ofMinutes(30)),
                                200,
                                20,
                                new NotificationProperties.Mail(
                                        false, "", "http://localhost:3000")),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Chưa lưu cài đặt thì trả mặc định từ properties và không ghi CSDL")
    void defaultsComeFromPropertiesWithoutSaving() {
        when(preferences.findById(userId)).thenReturn(Optional.empty());

        var result = service.getPreferences(userId);

        assertThat(result.inAppEnabled()).isTrue();
        assertThat(result.emailEnabled()).isFalse();
        assertThat(result.timezone()).isEqualTo("Asia/Bangkok");
        assertThat(result.dailyReminderTime()).isNull();
        assertThat(result.updatedAt()).isNull();
        verify(preferences, never()).save(any());
    }

    @Test
    @DisplayName("Lưu cài đặt lần đầu tạo bản ghi với mốc thời gian từ Clock")
    void firstSaveCreatesPreference() {
        when(preferences.findById(userId)).thenReturn(Optional.empty());
        when(preferences.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result =
                service.updatePreferences(
                        userId,
                        new UpdateNotificationPreferencesRequest(
                                true,
                                true,
                                LocalTime.of(19, 30),
                                "Asia/Tokyo",
                                LocalTime.of(23, 0),
                                LocalTime.of(6, 0)));

        assertThat(result.emailEnabled()).isTrue();
        assertThat(result.dailyReminderTime()).isEqualTo(LocalTime.of(19, 30));
        assertThat(result.timezone()).isEqualTo("Asia/Tokyo");
        assertThat(result.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Đánh dấu thông báo ngoài feed báo RESOURCE_NOT_FOUND")
    void markReadOutsideFeedIsNotFound() {
        UUID id = UUID.randomUUID();
        when(notifications.findInFeed(userId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(userId, id))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("Đánh dấu đã đọc dùng Clock và không ghi đè mốc đọc cũ")
    void markReadKeepsFirstReadTime() {
        var notification =
                new Notification(
                        userId,
                        null,
                        NotificationType.REVIEW_DUE,
                        NotificationVisibility.USER,
                        "t",
                        "b",
                        null,
                        "k",
                        NOW.minusSeconds(3600));
        notification.markRead(NOW.minusSeconds(60));
        when(notifications.findInFeed(userId, notification.getId()))
                .thenReturn(Optional.of(notification));

        service.markRead(userId, notification.getId());

        assertThat(notification.getReadAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("Đánh dấu tất cả chuyển đúng userId và thời điểm cho truy vấn feed")
    void markAllReadUsesFeedQuery() {
        service.markAllRead(userId);
        verify(notifications).markFeedRead(userId, NOW);
    }

    @Test
    @DisplayName("NotificationPreference từ CSDL được trả nguyên giá trị")
    void storedPreferenceIsReturned() {
        var stored = new NotificationPreference(userId);
        stored.update(false, true, null, "UTC", null, null, NOW);
        when(preferences.findById(userId)).thenReturn(Optional.of(stored));

        var result = service.getPreferences(userId);

        assertThat(result.inAppEnabled()).isFalse();
        assertThat(result.timezone()).isEqualTo("UTC");
        assertThat(result.updatedAt()).isEqualTo(NOW);
    }
}
