package com.masterenglish.api.modules.admin.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateReminderRuleRequest(
        @NotNull Boolean enabled, @NotNull @Min(0) @Max(43200) Integer minimumGapMinutes) {}
