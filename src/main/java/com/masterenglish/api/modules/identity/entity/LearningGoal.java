package com.masterenglish.api.modules.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "learning_goals")
public class LearningGoal {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "target_score", nullable = false)
    private short targetScore;

    @Column(name = "daily_minutes", nullable = false)
    private int dailyMinutes;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public LearningGoal(UUID userId, short targetScore, int dailyMinutes, LocalDate targetDate) {
        this.userId = userId;
        this.targetScore = targetScore;
        this.dailyMinutes = dailyMinutes;
        this.targetDate = targetDate;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(short targetScore, int dailyMinutes, LocalDate targetDate) {
        this.targetScore = targetScore;
        this.dailyMinutes = dailyMinutes;
        this.targetDate = targetDate;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
