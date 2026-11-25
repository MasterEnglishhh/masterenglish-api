package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempts")
public class Attempt {

    public static final String GRAMMAR = "GRAMMAR";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String ABANDONED = "ABANDONED";
    public static final String MANUAL = "MANUAL";
    public static final String TIME_LIMIT = "TIME_LIMIT";

    @Id private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 16, updatable = false)
    private String category;

    @Column(name = "source_type", nullable = false, length = 24, updatable = false)
    private String sourceType;

    @Column(name = "source_topic_id", updatable = false)
    private UUID sourceTopicId;

    @Column(name = "source_revision_id", updatable = false)
    private UUID sourceRevisionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_config", nullable = false, updatable = false)
    private String sourceConfig;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "total_count", nullable = false, updatable = false)
    private short totalCount;

    @Column(name = "correct_count")
    private Short correctCount;

    @Column(name = "answered_count")
    private Short answeredCount;

    private Short score;

    @Column(name = "snapshot_sealed_at")
    private Instant snapshotSealedAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "effective_finished_at")
    private Instant effectiveFinishedAt;

    @Column(name = "submit_reason", length = 16)
    private String submitReason;

    @Column(name = "abandoned_at")
    private Instant abandonedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false, length = 60, updatable = false)
    private String timezone;

    @Column(name = "local_day")
    private LocalDate localDay;

    @Version private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Attempt(
            UUID id,
            UUID userId,
            String sourceType,
            UUID sourceTopicId,
            UUID sourceRevisionId,
            String sourceConfig,
            UUID requestId,
            String requestHash,
            short totalCount,
            String timezone,
            Instant now) {
        this.id = id;
        this.userId = userId;
        this.category = GRAMMAR;
        this.sourceType = sourceType;
        this.sourceTopicId = sourceTopicId;
        this.sourceRevisionId = sourceRevisionId;
        this.sourceConfig = sourceConfig;
        this.requestId = requestId;
        this.requestHash = requestHash;
        this.status = IN_PROGRESS;
        this.totalCount = totalCount;
        this.startedAt = now;
        this.lastActiveAt = now;
        this.timezone = timezone;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isInProgress() {
        return IN_PROGRESS.equals(status);
    }

    public boolean isSubmitted() {
        return SUBMITTED.equals(status);
    }

    public boolean isAbandoned() {
        return ABANDONED.equals(status);
    }

    /**
     * Records the instant the snapshot became immutable; set once, after every snapshot row exists.
     */
    public void seal(Instant now) {
        this.snapshotSealedAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.lastActiveAt = now;
        this.updatedAt = now;
    }

    /**
     * Closes the attempt with a score; {@code effectiveFinishedAt} is the deadline for TIME_LIMIT.
     */
    public void submit(
            String reason,
            Instant effectiveFinishedAt,
            Instant now,
            int correct,
            int answered,
            ZoneId zone) {
        this.status = SUBMITTED;
        this.submitReason = reason;
        this.submittedAt = now;
        this.effectiveFinishedAt = effectiveFinishedAt;
        this.correctCount = (short) correct;
        this.answeredCount = (short) answered;
        this.score = (short) correct;
        this.durationSeconds = (int) Duration.between(startedAt, effectiveFinishedAt).getSeconds();
        this.localDay = effectiveFinishedAt.atZone(zone).toLocalDate();
        this.updatedAt = now;
    }

    /** Untimed attempt left idle for the abandon window: no score is produced. */
    public void abandon(Instant now) {
        this.status = ABANDONED;
        this.abandonedAt = now;
        this.updatedAt = now;
    }
}
