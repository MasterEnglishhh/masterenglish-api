package com.masterenglish.api.modules.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "word_reviews")
public class WordReview {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "word_sense_id", nullable = false)
    private UUID wordSenseId;

    @Column(name = "source_word_id", nullable = false)
    private UUID sourceWordId;

    @Column(name = "source_revision_id", nullable = false)
    private UUID sourceRevisionId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 8)
    private String rating;

    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", nullable = false, columnDefinition = "jsonb")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", nullable = false, columnDefinition = "jsonb")
    private String afterState;

    @Column(name = "previous_due_at")
    private Instant previousDueAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private Instant reviewedAt;

    @Column(nullable = false, length = 60)
    private String timezone;

    @Column(name = "local_day", nullable = false)
    private LocalDate localDay;

    public WordReview(
            UUID id,
            UUID userId,
            UUID wordSenseId,
            UUID sourceWordId,
            UUID sourceRevisionId,
            UUID requestId,
            String requestHash,
            String rating,
            String policyVersion,
            String beforeState,
            String afterState,
            Instant previousDueAt,
            Instant nextDueAt,
            Instant reviewedAt,
            String timezone,
            LocalDate localDay) {
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.wordSenseId = wordSenseId;
        this.sourceWordId = sourceWordId;
        this.sourceRevisionId = sourceRevisionId;
        this.requestId = requestId;
        this.requestHash = requestHash;
        this.rating = rating;
        this.policyVersion = policyVersion;
        this.beforeState = beforeState != null ? beforeState : "{}";
        this.afterState = afterState != null ? afterState : "{}";
        this.previousDueAt = previousDueAt;
        this.nextDueAt = nextDueAt;
        this.reviewedAt = reviewedAt != null ? reviewedAt : Instant.now();
        this.timezone = timezone != null ? timezone : "UTC";
        this.localDay = localDay != null ? localDay : LocalDate.now();
    }

    @PrePersist
    void onPersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (reviewedAt == null) {
            reviewedAt = Instant.now();
        }
    }
}
