package com.masterenglish.api.modules.practice.repository;

import com.masterenglish.api.modules.practice.entity.MistakeRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MistakeRecordRepository extends JpaRepository<MistakeRecord, UUID> {

    /** One graded answer of a submitted attempt, in the order that defines the projection. */
    interface HistoryRow {
        UUID getAttemptId();

        UUID getSnapshotId();

        Instant getEffectiveFinishedAt();

        Short getScore();
    }

    /**
     * Full graded history of one learner for one question, ordered by (effective_finished_at,
     * attempt_id) in SQL so both sides of the tie-break use the same collation.
     */
    @Query(
            value =
                    """
                    SELECT a.id AS "attemptId", s.id AS "snapshotId",
                           a.effective_finished_at AS "effectiveFinishedAt", ans.score AS "score"
                    FROM attempts a
                    JOIN attempt_question_snapshots s ON s.attempt_id = a.id
                    JOIN attempt_answers ans ON ans.snapshot_id = s.id
                    WHERE a.user_id = :userId AND a.status = 'SUBMITTED'
                      AND s.question_id = :questionId
                    ORDER BY a.effective_finished_at, a.id
                    """,
            nativeQuery = true)
    List<HistoryRow> findSubmittedHistory(
            @Param("userId") UUID userId, @Param("questionId") UUID questionId);

    Optional<MistakeRecord> findByUserIdAndQuestionId(UUID userId, UUID questionId);

    List<MistakeRecord> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    /**
     * Filters use boolean flags instead of null parameters, so the query binds the same types
     * whatever the caller asks for. When a flag is true the matching value is ignored.
     */
    @Query(
            value =
                    "SELECT m FROM MistakeRecord m WHERE m.userId = :userId"
                            + " AND (:anyState = TRUE OR m.state = :state)"
                            + " AND (:anyTopic = TRUE OR EXISTS (SELECT qt FROM QuestionTopic qt"
                            + " WHERE qt.id.questionId = m.questionId"
                            + " AND qt.id.topicId = :topicId))",
            countQuery =
                    "SELECT count(m) FROM MistakeRecord m WHERE m.userId = :userId"
                            + " AND (:anyState = TRUE OR m.state = :state)"
                            + " AND (:anyTopic = TRUE OR EXISTS (SELECT qt FROM QuestionTopic qt"
                            + " WHERE qt.id.questionId = m.questionId"
                            + " AND qt.id.topicId = :topicId))")
    Page<MistakeRecord> search(
            @Param("userId") UUID userId,
            @Param("anyState") boolean anyState,
            @Param("state") String state,
            @Param("anyTopic") boolean anyTopic,
            @Param("topicId") UUID topicId,
            Pageable pageable);
}
