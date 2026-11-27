package com.masterenglish.api.modules.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "study_sessions")
public class StudySession {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16, updatable = false)
    private StudySourceType sourceType;

    @Column(name = "word_sense_id", updatable = false)
    private UUID wordSenseId;

    @Column(name = "lesson_id", updatable = false)
    private UUID lessonId;

    @Column(name = "attempt_id", updatable = false)
    private UUID attemptId;

    @Column(nullable = false, length = 60, updatable = false)
    private String timezone;

    @Column(name = "local_day", nullable = false, updatable = false)
    private LocalDate localDay;

    @Column(name = "credit_token", nullable = false, updatable = false)
    private UUID creditToken;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_credited_at", nullable = false)
    private Instant lastCreditedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "active_seconds", nullable = false, precision = 12, scale = 3)
    private BigDecimal activeSeconds;

    @Version
    @Column(nullable = false)
    private long version;

    private StudySession(
            UUID id,
            UUID userId,
            StudySourceType sourceType,
            UUID wordSenseId,
            UUID lessonId,
            UUID attemptId,
            String timezone,
            LocalDate localDay,
            UUID creditToken,
            Instant startedAt) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.sourceType = Objects.requireNonNull(sourceType);
        this.wordSenseId = wordSenseId;
        this.lessonId = lessonId;
        this.attemptId = attemptId;
        this.timezone = Objects.requireNonNull(timezone);
        this.localDay = Objects.requireNonNull(localDay);
        this.creditToken = Objects.requireNonNull(creditToken);
        this.startedAt = Objects.requireNonNull(startedAt);
        this.lastCreditedAt = startedAt;
        this.activeSeconds = BigDecimal.ZERO.setScale(3);
    }

    public static StudySession open(
            UUID userId,
            StudySourceType sourceType,
            UUID wordSenseId,
            UUID lessonId,
            UUID attemptId,
            String timezone,
            LocalDate localDay,
            UUID creditToken,
            Instant startedAt) {
        return new StudySession(
                UUID.randomUUID(),
                userId,
                sourceType,
                wordSenseId,
                lessonId,
                attemptId,
                timezone,
                localDay,
                creditToken,
                startedAt);
    }

    public static StudySession openContinuation(
            StudySession previous, LocalDate newLocalDay, UUID newCreditToken, Instant startedAt) {
        return new StudySession(
                UUID.randomUUID(),
                previous.userId,
                previous.sourceType,
                previous.wordSenseId,
                previous.lessonId,
                previous.attemptId,
                previous.timezone,
                newLocalDay,
                newCreditToken,
                startedAt);
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public void recordHeartbeat(Instant newLastCreditedAt, BigDecimal creditedDeltaSeconds) {
        if (newLastCreditedAt.isBefore(this.lastCreditedAt)) {
            throw new IllegalArgumentException("lastCreditedAt cannot move backwards");
        }
        if (creditedDeltaSeconds.signum() < 0) {
            throw new IllegalArgumentException("creditedDeltaSeconds cannot be negative");
        }
        this.lastCreditedAt = newLastCreditedAt;
        this.activeSeconds = this.activeSeconds.add(creditedDeltaSeconds);
    }

    public void close(Instant closeTime) {
        if (closeTime.isBefore(this.lastCreditedAt)) {
            throw new IllegalArgumentException("closedAt cannot precede lastCreditedAt");
        }
        this.closedAt = closeTime;
    }
}
