package com.masterenglish.api.modules.notifications.dto.request;

import com.masterenglish.api.common.time.TimeZones;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/** Full replacement of the learner's settings; times are local wall-clock minutes (HH:mm). */
public record UpdateNotificationPreferencesRequest(
        @NotNull Boolean inAppEnabled,
        @NotNull Boolean emailEnabled,
        LocalTime dailyReminderTime,
        @NotBlank @Size(max = 60) String timezone,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd) {

    /*
     * Checked here rather than with common @IanaTimeZone: its final validator cannot be proxied
     * by the module observability layer, so Spring fails to create it.
     */
    @AssertTrue(message = "Must be a supported IANA time zone.")
    public boolean isTimezoneSupported() {
        return timezone == null || timezone.isBlank() || TimeZones.isSupportedInput(timezone);
    }

    @AssertTrue(message = "Quiet hours need both start and end, and they must differ.")
    public boolean isQuietHoursValid() {
        if (quietHoursStart == null || quietHoursEnd == null) {
            return quietHoursStart == quietHoursEnd;
        }
        return !quietHoursStart.equals(quietHoursEnd);
    }

    @AssertTrue(message = "Times must use the HH:mm format.")
    public boolean isWholeMinutes() {
        return wholeMinute(dailyReminderTime)
                && wholeMinute(quietHoursStart)
                && wholeMinute(quietHoursEnd);
    }

    private static boolean wholeMinute(LocalTime time) {
        return time == null || (time.getSecond() == 0 && time.getNano() == 0);
    }
}
