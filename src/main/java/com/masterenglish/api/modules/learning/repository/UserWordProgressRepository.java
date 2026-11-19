package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserWordProgressRepository
        extends JpaRepository<UserWordProgress, UserWordProgress.UserWordProgressId> {

    String ELIGIBLE =
            """
            FROM user_word_progress p
            JOIN catalog_visible_senses s ON s.word_sense_id=p.word_sense_id
            WHERE p.user_id=:userId AND p.due_at<=:now
            AND (cast(:deckId AS uuid) IS NULL OR EXISTS (
                SELECT 1 FROM catalog_visible_deck_senses ds
                WHERE ds.word_sense_id=p.word_sense_id AND ds.deck_id=:deckId))
            """;

    @Query(
            value = "SELECT p.* " + ELIGIBLE + " ORDER BY p.due_at,p.word_sense_id",
            nativeQuery = true)
    List<UserWordProgress> findEligibleDue(
            @Param("userId") UUID userId,
            @Param("deckId") UUID deckId,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Same eligibility as the due queue, grouped per learner; learners with no due card are absent.
     */
    @Query(
            value =
                    """
                    SELECT p.user_id AS userId, count(*) AS dueCount
                    FROM user_word_progress p
                    JOIN catalog_visible_senses s ON s.word_sense_id=p.word_sense_id
                    WHERE p.user_id IN (:userIds) AND p.due_at<=:now
                    GROUP BY p.user_id
                    """,
            nativeQuery = true)
    List<DueCount> countEligibleDueByUser(
            @Param("userIds") Collection<UUID> userIds, @Param("now") Instant now);

    interface DueCount {
        UUID getUserId();

        long getDueCount();
    }

    @Query(value = "SELECT count(*) " + ELIGIBLE, nativeQuery = true)
    long countEligibleDue(
            @Param("userId") UUID userId, @Param("deckId") UUID deckId, @Param("now") Instant now);

    Optional<UserWordProgress> findByIdUserIdAndIdWordSenseId(UUID userId, UUID wordSenseId);

    long countByIdUserId(UUID userId);

    long countByIdUserIdAndIntervalDaysGreaterThanEqual(UUID userId, int intervalDays);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT p FROM UserWordProgress p WHERE p.id.userId = :userId AND p.id.wordSenseId = :wordSenseId")
    Optional<UserWordProgress> findByIdForUpdate(
            @Param("userId") UUID userId, @Param("wordSenseId") UUID wordSenseId);

    @Query(
            "SELECT p FROM UserWordProgress p WHERE p.id.userId = :userId AND p.id.wordSenseId IN :senseIds")
    List<UserWordProgress> findByUserIdAndSenseIdIn(
            @Param("userId") UUID userId, @Param("senseIds") Collection<UUID> senseIds);

    @Query(
            "SELECT p FROM UserWordProgress p WHERE p.id.userId = :userId AND p.dueAt <= :now ORDER BY p.dueAt ASC")
    List<UserWordProgress> findDueProgress(
            @Param("userId") UUID userId, @Param("now") Instant now, Pageable pageable);

    @Query(
            "SELECT count(p) FROM UserWordProgress p WHERE p.id.userId = :userId AND p.dueAt <= :now")
    long countDueProgress(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query(
            "SELECT p FROM UserWordProgress p WHERE p.id.userId = :userId AND p.id.wordSenseId IN :senseIds AND p.dueAt <= :now ORDER BY p.dueAt ASC")
    List<UserWordProgress> findDueProgressBySenseIdIn(
            @Param("userId") UUID userId,
            @Param("senseIds") Collection<UUID> senseIds,
            @Param("now") Instant now,
            Pageable pageable);

    @Query(
            "SELECT count(p) FROM UserWordProgress p WHERE p.id.userId = :userId AND p.id.wordSenseId IN :senseIds AND p.dueAt <= :now")
    long countDueProgressBySenseIdIn(
            @Param("userId") UUID userId,
            @Param("senseIds") Collection<UUID> senseIds,
            @Param("now") Instant now);
}
