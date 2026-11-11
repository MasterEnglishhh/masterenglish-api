package com.masterenglish.api.modules.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_word_progress")
public class UserWordProgress {

    @EmbeddedId private UserWordProgressId id;

    @Column(nullable = false)
    private int repetitions;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(name = "ease_factor", nullable = false, precision = 6, scale = 3)
    private BigDecimal easeFactor;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserWordProgress(
            UUID userId,
            UUID wordSenseId,
            int repetitions,
            int intervalDays,
            BigDecimal easeFactor,
            Instant dueAt,
            Instant lastReviewedAt,
            String policyVersion) {
        this.id = new UserWordProgressId(userId, wordSenseId);
        this.repetitions = repetitions;
        this.intervalDays = intervalDays;
        this.easeFactor = easeFactor;
        this.dueAt = dueAt;
        this.lastReviewedAt = lastReviewedAt;
        this.policyVersion = policyVersion != null ? policyVersion : "SM2_3BTN_V1";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateProgress(
            int repetitions,
            int intervalDays,
            BigDecimal easeFactor,
            Instant dueAt,
            Instant lastReviewedAt) {
        this.repetitions = repetitions;
        this.intervalDays = intervalDays;
        this.easeFactor = easeFactor;
        this.dueAt = dueAt;
        this.lastReviewedAt = lastReviewedAt;
        this.updatedAt = Instant.now();
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserWordProgressId implements Serializable {
        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "word_sense_id", nullable = false)
        private UUID wordSenseId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UserWordProgressId that)) return false;
            return Objects.equals(userId, that.userId)
                    && Objects.equals(wordSenseId, that.wordSenseId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, wordSenseId);
        }
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
