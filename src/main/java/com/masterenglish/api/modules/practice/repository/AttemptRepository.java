package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.Attempt;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    interface TopicStats {
        UUID getTopicId();

        long getAttemptCount();

        long getQuestionCount();

        long getCorrectCount();
    }

    Optional<Attempt> findByUserIdAndRequestId(UUID userId, UUID requestId);

    Optional<Attempt> findByIdAndUserId(UUID id, UUID userId);

    /** Row lock for the rest of the transaction, so submit and answer writes serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Attempt a WHERE a.id = :id AND a.userId = :userId")
    Optional<Attempt> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Returns 0 when another request with the same (user, requestId) already created the row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO attempts (id, user_id, category, source_type, source_topic_id,
                        request_id, request_hash, status, total_count, started_at, updated_at)
                    VALUES (:id, :userId, 'GRAMMAR', :sourceType, CAST(:sourceTopicId AS uuid),
                        :requestId, :requestHash, 'IN_PROGRESS', :totalCount, :now, :now)
                    ON CONFLICT (user_id, request_id) DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("sourceType") String sourceType,
            @Param("sourceTopicId") UUID sourceTopicId,
            @Param("requestId") UUID requestId,
            @Param("requestHash") String requestHash,
            @Param("totalCount") int totalCount,
            @Param("now") java.time.Instant now);

    /**
     * Per-topic totals over the learner's submitted attempts. Unanswered questions count as wrong.
     */
    @Query(
            value =
                    """
                    SELECT s.topic_id AS topicId,
                        count(DISTINCT a.id) AS attemptCount,
                        count(*) AS questionCount,
                        count(*) FILTER (WHERE o.is_correct) AS correctCount
                    FROM attempts a
                    JOIN attempt_question_snapshots s ON s.attempt_id = a.id
                    LEFT JOIN attempt_answers ans ON ans.attempt_id = s.attempt_id
                        AND ans.question_position = s.position
                    LEFT JOIN attempt_snapshot_options o ON o.attempt_id = ans.attempt_id
                        AND o.question_position = ans.question_position
                        AND o.id = ans.selected_option_id
                    WHERE a.user_id = :userId AND a.status = 'SUBMITTED'
                    GROUP BY s.topic_id
                    ORDER BY s.topic_id
                    """,
            nativeQuery = true)
    List<TopicStats> topicStatistics(@Param("userId") UUID userId);
}
