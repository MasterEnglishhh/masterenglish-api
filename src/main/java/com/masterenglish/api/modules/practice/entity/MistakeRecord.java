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
 * One learner's mistake state for one question. It is a projection: it is rebuilt from the graded
 * history of submitted attempts whenever one of them is submitted (see MistakeRecorder).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mistake_records")
public class MistakeRecord {

    public static final String OPEN = "OPEN";
    public static final String IMPROVING = "IMPROVING";
    public static final String RESOLVED = "RESOLVED";

    @Id private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "source_attempt_id", nullable = false)
    private UUID sourceAttemptId;

    @Column(name = "source_snapshot_id", nullable = false)
    private UUID sourceSnapshotId;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "wrong_count", nullable = false)
    private int wrongCount;

    @Column(name = "consecutive_correct", nullable = false)
    private int consecutiveCorrect;

    @Column(name = "last_wrong_at", nullable = false)
    private Instant lastWrongAt;

    @Column(name = "last_evaluated_at", nullable = false)
    private Instant lastEvaluatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private MistakeRecord(UUID id, UUID userId, UUID questionId, Instant now) {
        this.id = id;
        this.userId = userId;
        this.questionId = questionId;
        this.updatedAt = now;
    }

    public static MistakeRecord create(UUID id, UUID userId, UUID questionId, Instant now) {
        return new MistakeRecord(id, userId, questionId, now);
    }

    /** Replaces the projected fields with a freshly folded result. */
    public void apply(
            String newState,
            int newWrongCount,
            int newConsecutiveCorrect,
            UUID newSourceAttemptId,
            UUID newSourceSnapshotId,
            Instant newLastWrongAt,
            Instant newLastEvaluatedAt,
            Instant newResolvedAt,
            Instant now) {
        this.state = newState;
        this.wrongCount = newWrongCount;
        this.consecutiveCorrect = newConsecutiveCorrect;
        this.sourceAttemptId = newSourceAttemptId;
        this.sourceSnapshotId = newSourceSnapshotId;
        this.lastWrongAt = newLastWrongAt;
        this.lastEvaluatedAt = newLastEvaluatedAt;
        this.resolvedAt = newResolvedAt;
        this.updatedAt = now;
    }
}
