package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.LessonProgress;
import com.masterenglish.api.modules.learning.entity.LessonProgress.LessonProgressId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LessonProgressRepository extends JpaRepository<LessonProgress, LessonProgressId> {

    /**
     * Completes the lesson for the learner. An in-progress row is upgraded once; a completed row is
     * never rewritten, so the revision that was completed first stays on record. Clears the
     * persistence context so later reads in the same transaction see the new state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO lesson_progress
                        (user_id, lesson_id, state, last_completed_revision_id,
                         started_at, completed_at, updated_at)
                    VALUES (:userId, :lessonId, 'COMPLETED', :revisionId, :now, :now, :now)
                    ON CONFLICT (user_id, lesson_id) DO UPDATE SET
                        state = 'COMPLETED',
                        last_completed_revision_id = EXCLUDED.last_completed_revision_id,
                        completed_at = EXCLUDED.completed_at,
                        updated_at = EXCLUDED.updated_at,
                        version = lesson_progress.version + 1
                    WHERE lesson_progress.state <> 'COMPLETED'
                    """,
            nativeQuery = true)
    int upsertCompletion(
            @Param("userId") UUID userId,
            @Param("lessonId") UUID lessonId,
            @Param("revisionId") UUID revisionId,
            @Param("now") Instant now);

    /** Starts a lesson without overwriting an existing row, completed or not. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO lesson_progress
                        (user_id, lesson_id, state, started_at, updated_at)
                    VALUES (:userId, :lessonId, 'IN_PROGRESS', :now, :now)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertInProgressIfAbsent(
            @Param("userId") UUID userId,
            @Param("lessonId") UUID lessonId,
            @Param("now") Instant now);

    Optional<LessonProgress> findByIdUserIdAndIdLessonId(UUID userId, UUID lessonId);

    List<LessonProgress> findByIdUserIdOrderByUpdatedAtDesc(UUID userId);

    @Query(
            value =
                    """
                    SELECT DISTINCT to_char((started_at AT TIME ZONE :timezone)::date, 'YYYY-MM-DD')
                    FROM lesson_progress
                    WHERE user_id = :userId
                    UNION
                    SELECT DISTINCT to_char((completed_at AT TIME ZONE :timezone)::date, 'YYYY-MM-DD')
                    FROM lesson_progress
                    WHERE user_id = :userId AND completed_at IS NOT NULL
                    """,
            nativeQuery = true)
    List<String> findDistinctActivityLocalDays(
            @Param("userId") UUID userId, @Param("timezone") String timezone);
}
