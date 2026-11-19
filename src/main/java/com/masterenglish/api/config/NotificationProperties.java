package com.masterenglish.api.config;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Keys mirror the notification.* allowlist of system_settings so they can move there later. The
 * scheduler switch and intervals are read directly by the job annotations.
 */
@ConfigurationProperties("notification")
public record NotificationProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") String defaultTimezone,
        @DefaultValue("false") boolean learningEmailDefault,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("60") int leaseSeconds,
        @DefaultValue({"1m", "5m", "30m"}) List<Duration> retryBackoff,
        @DefaultValue("200") int schedulerBatchSize,
        @DefaultValue("20") int workerBatchSize,
        @DefaultValue Mail mail) {

    /** Reminder email settings; disabled by default so missing SMTP never blocks startup. */
    public record Mail(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String from,
            @DefaultValue("") String webBaseUrl) {

        public Mail {
            webBaseUrl = webBaseUrl == null ? "" : webBaseUrl.strip().replaceAll("/+$", "");
            if (!webBaseUrl.isEmpty() && !webBaseUrl.matches("https?://[^\\s/?#]+(/[^\\s?#]*)?")) {
                throw new IllegalArgumentException(
                        "notification.mail.web-base-url must be an absolute http(s) URL.");
            }
        }

        /**
         * Reminder emails are created and sent only when mail is on and both the sender and the web
         * address are set, so an email never carries a broken link.
         */
        public boolean deliverable() {
            return enabled && from != null && !from.isBlank() && !webBaseUrl.isEmpty();
        }
    }

    public NotificationProperties {
        try {
            ZoneId.of(defaultTimezone);
        } catch (DateTimeException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "Set notification.default-timezone to an IANA time zone.", exception);
        }
        // notification_deliveries.attempt_count is limited to 0..4: one send plus three retries.
        if (maxRetries < 0 || maxRetries > 3) {
            throw new IllegalArgumentException("notification.max-retries must be between 0 and 3.");
        }
        if (leaseSeconds < 1) {
            throw new IllegalArgumentException("notification.lease-seconds must be positive.");
        }
        retryBackoff = List.copyOf(retryBackoff);
        if (retryBackoff.size() < maxRetries) {
            throw new IllegalArgumentException(
                    "notification.retry-backoff needs one delay per retry.");
        }
        if (schedulerBatchSize < 1 || workerBatchSize < 1) {
            throw new IllegalArgumentException("Notification batch sizes must be positive.");
        }
    }

    /** One first attempt plus the configured retries. */
    public int maxAttempts() {
        return maxRetries + 1;
    }

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }
}
