package com.masterenglish.api.modules.identity.dto.response;

import com.masterenglish.api.modules.identity.entity.LearningGoal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record LearningGoalResponse(
        short targetScore,
        int dailyMinutes,
        LocalDate targetDate,
        Instant updatedAt) {

    public static LearningGoalResponse from(LearningGoal goal) {
        Objects.requireNonNull(goal, "goal must not be null");
        return new LearningGoalResponse(
                goal.getTargetScore(),
                goal.getDailyMinutes(),
                goal.getTargetDate(),
                goal.getUpdatedAt());
    }
}
