package com.masterenglish.api.modules.admin.dto.system;

public record AiQuotaMetricDto(
        String provider,
        String model,
        long dailyTokenUsage,
        long dailyTokenLimit,
        double percentUsed,
        int resetHours) {}
