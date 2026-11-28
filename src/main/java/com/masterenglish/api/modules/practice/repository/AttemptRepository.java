package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.Attempt;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    /** Allocation row per topic; the window columns cover submissions at or after {@code since}. */
    interface TopicStats {
        UUID getTopicId();

        long getAttemptCount();

        long getQuestionCount();

        long getCorrectCount();

        long getWindowQuestionCount();

        long getWindowCorrectCount();
    }

    /** One row over every submitted grammar question of the learner, each snapshot counted once. */
    interface SummaryRow {
        long getQuestionCount();

        long getCorrectCount();

        long getWindowQuestionCount();

        long getWindowCorrectCount();
    }

    Optional<Attempt> findByUserIdAndRequestId(UUID userId, UUID requestId);

    Optional<Attempt> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Row lock for the rest of the transaction: submit, save and time-out serialize per attempt.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Attempt a WHERE a.id = :id AND a.userId = :userId")
    Optional<Attempt> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Open attempts of one learner, locked in id order (lock order: user, then attempts by id). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT a FROM Attempt a WHERE a.userId = :userId AND a.status = 'IN_PROGRESS'"
                    + " ORDER BY a.id")
    List<Attempt> lockOpenByUserId(@Param("userId") UUID userId);

    long countByUserIdAndStatus(UUID userId, String status);

    /**
     * Allocation view: each question counts once, under the topic it was attributed to at start.
     * Unanswered questions have score 0 and count as wrong. The window is by effective finish time.
     */
    @Query(
            value =
                    """
                    SELECT (s.question_snapshot ->> 'attributedTopicId')::uuid AS "topicId",
                           count(DISTINCT a.id) AS "attemptCount",
                           count(s.id) AS "questionCount",
                           count(ans.id) FILTER (WHERE ans.score = 1) AS "correctCount",
                           count(s.id) FILTER (WHERE a.effective_finished_at >= :since)
                               AS "windowQuestionCount",
                           count(ans.id) FILTER (WHERE ans.score = 1
                               AND a.effective_finished_at >= :since) AS "windowCorrectCount"
                    FROM attempts a
                    JOIN attempt_question_snapshots s ON s.attempt_id = a.id
                    LEFT JOIN attempt_answers ans ON ans.snapshot_id = s.id
                    WHERE a.user_id = :userId AND a.status = 'SUBMITTED' AND a.category = 'GRAMMAR'
                    GROUP BY 1
                    """,
            nativeQuery = true)
    List<TopicStats> topicStatistics(@Param("userId") UUID userId, @Param("since") Instant since);

    @Query(
            value =
                    """
                    SELECT count(s.id) AS "questionCount",
                           count(ans.id) FILTER (WHERE ans.score = 1) AS "correctCount",
                           count(s.id) FILTER (WHERE a.effective_finished_at >= :since)
                               AS "windowQuestionCount",
                           count(ans.id) FILTER (WHERE ans.score = 1
                               AND a.effective_finished_at >= :since) AS "windowCorrectCount"
                    FROM attempts a
                    JOIN attempt_question_snapshots s ON s.attempt_id = a.id
                    LEFT JOIN attempt_answers ans ON ans.snapshot_id = s.id
                    WHERE a.user_id = :userId AND a.status = 'SUBMITTED' AND a.category = 'GRAMMAR'
                    """,
            nativeQuery = true)
    SummaryRow statisticsSummary(@Param("userId") UUID userId, @Param("since") Instant since);

    /**
     * Dashboard accuracy per topic, counting only submissions finished at or after {@code since}.
     */
    interface TopicAccuracyRow {
        UUID getTopicId();

        long getAttemptCount();

        long getQuestionCount();

        long getCorrectCount();
    }

    @Query(
            value =
                    """
                    SELECT (s.question_snapshot ->> 'attributedTopicId')::uuid AS "topicId",
                           count(DISTINCT a.id) AS "attemptCount",
                           count(s.id) AS "questionCount",
                           count(ans.id) FILTER (WHERE ans.score = 1) AS "correctCount"
                    FROM attempts a
                    JOIN attempt_question_snapshots s ON s.attempt_id = a.id
                    LEFT JOIN attempt_answers ans ON ans.snapshot_id = s.id
                    WHERE a.user_id = :userId
                      AND a.status = 'SUBMITTED'
                      AND a.category = 'GRAMMAR'
                      AND a.effective_finished_at >= :since
                    GROUP BY 1
                    ORDER BY 1
                    """,
            nativeQuery = true)
    List<TopicAccuracyRow> topicAccuracySince(
            @Param("userId") UUID userId, @Param("since") Instant since);

    List<Attempt> findTop5ByUserIdAndStatusOrderBySubmittedAtDescIdDesc(UUID userId, String status);

    /** Mean of the per-attempt percentage; the stored score is the correct count (baseline). */
    @Query(
            "SELECT avg(100.0 * a.correctCount / a.totalCount) FROM Attempt a"
                    + " WHERE a.userId = :userId AND a.status = 'SUBMITTED'")
    Double averageScoreByUserId(@Param("userId") UUID userId);

    @Query(
            value =
                    """
                    SELECT DISTINCT to_char((submitted_at AT TIME ZONE :timezone)::date, 'YYYY-MM-DD')
                    FROM attempts
                    WHERE user_id = :userId
                      AND status = 'SUBMITTED'
                      AND submitted_at IS NOT NULL
                    """,
            nativeQuery = true)
    List<String> findSubmittedLocalDays(
            @Param("userId") UUID userId, @Param("timezone") String timezone);

    /** Learner's own practice attempt history filtered by optional status and source type. */
    @Query(
            value =
                    """
                    SELECT a FROM Attempt a
                    WHERE a.userId = :userId
                      AND (:hasStatus = false OR a.status = :status)
                      AND (:hasSourceType = false OR a.sourceType = :sourceType)
                    ORDER BY COALESCE(a.submittedAt, a.startedAt) DESC, a.id DESC
                    """,
            countQuery =
                    """
                    SELECT count(a) FROM Attempt a
                    WHERE a.userId = :userId
                      AND (:hasStatus = false OR a.status = :status)
                      AND (:hasSourceType = false OR a.sourceType = :sourceType)
                    """)
    Page<Attempt> findHistory(
            @Param("userId") UUID userId,
            @Param("hasStatus") boolean hasStatus,
            @Param("status") String status,
            @Param("hasSourceType") boolean hasSourceType,
            @Param("sourceType") String sourceType,
            Pageable pageable);
}
