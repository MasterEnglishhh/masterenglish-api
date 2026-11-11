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

public interface UserWordProgressRepository
        extends JpaRepository<UserWordProgress, UserWordProgress.UserWordProgressId> {

    String ELIGIBLE =
            """
            FROM user_word_progress p
            JOIN word_senses s ON s.id=p.word_sense_id AND NOT s.is_archived
            JOIN words w ON w.id=s.word_id AND w.published_revision_id=s.word_revision_id
            JOIN content_items i ON i.id=w.id AND i.published_revision_id=w.published_revision_id
            JOIN content_revisions r ON r.id=w.published_revision_id AND r.content_item_id=w.id AND r.status='PUBLISHED'
            WHERE p.user_id=:userId AND p.due_at<=:now AND NOT i.is_archived AND i.access_revoked_at IS NULL
            AND (cast(:deckId AS uuid) IS NULL OR EXISTS (
                SELECT 1 FROM deck_words dw JOIN decks d ON d.id=dw.deck_id
                JOIN content_items di ON di.id=d.id AND di.published_revision_id=d.published_revision_id
                JOIN content_revisions dr ON dr.id=d.published_revision_id AND dr.status='PUBLISHED'
                WHERE dw.word_sense_id=s.id AND d.id=:deckId AND NOT di.is_archived AND di.access_revoked_at IS NULL))
            """;

    @Query(
            value = "SELECT p.* " + ELIGIBLE + " ORDER BY p.due_at,p.word_sense_id",
            nativeQuery = true)
    List<UserWordProgress> findEligibleDue(
            @Param("userId") UUID userId,
            @Param("deckId") UUID deckId,
            @Param("now") Instant now,
            Pageable pageable);

    @Query(value = "SELECT count(*) " + ELIGIBLE, nativeQuery = true)
    long countEligibleDue(
            @Param("userId") UUID userId, @Param("deckId") UUID deckId, @Param("now") Instant now);

    Optional<UserWordProgress> findByIdUserIdAndIdWordSenseId(UUID userId, UUID wordSenseId);

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
