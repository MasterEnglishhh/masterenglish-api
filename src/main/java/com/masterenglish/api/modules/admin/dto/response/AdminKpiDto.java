package com.masterenglish.api.modules.admin.dto.response;

public record AdminKpiDto(
        String id,
        String title,
        String value,
        String subValue,
        Double changePercent,
        String trendText,
        String trendDirection,
        String iconName) {}
