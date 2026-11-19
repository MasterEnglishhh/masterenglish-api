package com.masterenglish.api.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Keys mirror the notification.* allowlist of system_settings so they can move there later. */
@ConfigurationProperties("notification")
public record NotificationProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") String defaultTimezone,
        @DefaultValue("false") boolean learningEmailDefault) {

    public NotificationProperties {
        try {
            ZoneId.of(defaultTimezone);
        } catch (DateTimeException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "Set notification.default-timezone to an IANA time zone.", exception);
        }
    }
}
