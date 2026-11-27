package com.masterenglish.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for Learner Dashboard recommendations. Grammar deep links are gated off
 * by default until the frontend routes consume {@code ?topic=...&mode=practice} and {@code
 * ?lesson=...}.
 */
@ConfigurationProperties("dashboard")
public record DashboardProperties(@DefaultValue Recommendations recommendations) {

    public record Recommendations(@DefaultValue("false") boolean grammarDeepLinksEnabled) {}

    public static DashboardProperties defaults() {
        return new DashboardProperties(new Recommendations(false));
    }
}
