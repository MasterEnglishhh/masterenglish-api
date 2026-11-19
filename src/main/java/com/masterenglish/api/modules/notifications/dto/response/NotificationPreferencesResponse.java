package com.masterenglish.api.modules.notifications.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalTime;

/** updatedAt is null until the learner saves settings for the first time. */
public record NotificationPreferencesResponse(
        boolean inAppEnabled,
        boolean emailEnabled,
        @JsonFormat(pattern = "HH:mm") LocalTime dailyReminderTime,
        String timezone,
        @JsonFormat(pattern = "HH:mm") LocalTime quietHoursStart,
        @JsonFormat(pattern = "HH:mm") LocalTime quietHoursEnd,
        Instant updatedAt) {}
