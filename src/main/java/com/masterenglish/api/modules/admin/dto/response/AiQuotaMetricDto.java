package com.masterenglish.api.modules.admin.dto.response;

public record AiQuotaMetricDto(
        String provider,
        String model,
        long dailyTokenUsage,
        long dailyTokenLimit,
        double percentUsed,
        int resetHours) {}
