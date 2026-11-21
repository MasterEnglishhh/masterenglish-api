package com.masterenglish.api.modules.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attempts")
public class Attempt {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUBMITTED = "SUBMITTED";

    @Id private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(name = "source_type", nullable = false, length = 24)
    private String sourceType;

    @Column(name = "source_topic_id")
    private UUID sourceTopicId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "correct_count")
    private Integer correctCount;

    private Integer score;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Version private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Attempt(
            UUID id,
            UUID userId,
            String sourceType,
            UUID sourceTopicId,
            UUID requestId,
            String requestHash,
            int totalCount,
            Instant now) {
        this.id = id;
        this.userId = userId;
        this.category = "GRAMMAR";
        this.sourceType = sourceType;
        this.sourceTopicId = sourceTopicId;
        this.requestId = requestId;
        this.requestHash = requestHash;
        this.status = IN_PROGRESS;
        this.totalCount = totalCount;
        this.startedAt = now;
        this.updatedAt = now;
    }

    public boolean isSubmitted() {
        return SUBMITTED.equals(status);
    }

    /**
     * Moves an open attempt to submitted. Callers hold a row lock and check {@link #isSubmitted}.
     */
    public void submit(int correct, int score, Instant now) {
        this.status = SUBMITTED;
        this.correctCount = correct;
        this.score = score;
        this.submittedAt = now;
        this.updatedAt = now;
    }
}
