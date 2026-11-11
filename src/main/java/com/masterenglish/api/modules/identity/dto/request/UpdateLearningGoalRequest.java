package com.masterenglish.api.modules.identity.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record UpdateLearningGoalRequest(
        @NotNull @Min(10) @Max(990) Short targetScore,
        @NotNull @Min(5) @Max(240) Integer dailyMinutes,
        LocalDate targetDate) {}
