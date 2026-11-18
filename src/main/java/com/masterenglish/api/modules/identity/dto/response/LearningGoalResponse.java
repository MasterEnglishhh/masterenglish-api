package com.masterenglish.api.modules.identity.dto.response;

import java.time.Instant;
import java.time.LocalDate;

public record LearningGoalResponse(
        short targetScore, int dailyMinutes, LocalDate targetDate, Instant updatedAt) {}
