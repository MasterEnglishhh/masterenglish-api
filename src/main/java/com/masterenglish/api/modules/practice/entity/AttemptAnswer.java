package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The learner's answer slot for one snapshot. Created empty at seal time; {@code version} is the
 * compare-and-set token for autosave and is bumped on every accepted write, including clearing.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempt_answers")
public class AttemptAnswer {

    @Id private UUID id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "snapshot_id", nullable = false, updatable = false)
    private UUID snapshotId;

    @Column(name = "selected_option_id")
    private UUID selectedOptionId;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(nullable = false)
    private long version;

    @Column(name = "is_correct")
    private Boolean correct;

    private Short score;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AttemptAnswer(UUID id, UUID attemptId, UUID snapshotId, Instant now) {
        this.id = id;
        this.attemptId = attemptId;
        this.snapshotId = snapshotId;
        this.version = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Grading runs once per attempt, at submission or time-out; unanswered slots score 0. */
    public void grade(boolean isCorrect) {
        this.correct = isCorrect;
        this.score = (short) (isCorrect ? 1 : 0);
    }
}
