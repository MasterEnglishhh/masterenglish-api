package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.UserSavedWord;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSavedWordRepository
        extends JpaRepository<UserSavedWord, UserSavedWord.UserSavedWordId> {

    String ELIGIBLE =
            """
            FROM user_saved_words sw JOIN word_senses s ON s.id=sw.word_sense_id AND NOT s.is_archived
            JOIN words w ON w.id=s.word_id AND w.published_revision_id=s.word_revision_id
            JOIN content_items i ON i.id=w.id AND i.published_revision_id=w.published_revision_id
            JOIN content_revisions r ON r.id=w.published_revision_id AND r.content_item_id=w.id AND r.status='PUBLISHED'
            WHERE sw.user_id=:userId AND NOT i.is_archived AND i.access_revoked_at IS NULL
              AND (cast(:cefrLevel AS text) IS NULL OR s.cefr_level=:cefrLevel)
            """;

    @Query(
            value = "SELECT sw.* " + ELIGIBLE + " ORDER BY sw.saved_at DESC,sw.word_sense_id",
            countQuery = "SELECT count(*) " + ELIGIBLE,
            nativeQuery = true)
    Page<UserSavedWord> findEligible(
            @Param("userId") UUID userId, @Param("cefrLevel") String cefrLevel, Pageable pageable);

    @Query(value = "SELECT sw.word_sense_id " + ELIGIBLE + " AND w.id=:wordId", nativeQuery = true)
    List<UUID> findSavedSenseIdsForWord(
            @Param("userId") UUID userId,
            @Param("wordId") UUID wordId,
            @Param("cefrLevel") String cefrLevel);

    @Modifying
    @Query(
            value =
                    "INSERT INTO user_saved_words(user_id,word_sense_id) VALUES (:userId,:senseId)"
                        + " ON CONFLICT DO NOTHING",
            nativeQuery = true)
    void insertIfAbsent(@Param("userId") UUID userId, @Param("senseId") UUID senseId);

    boolean existsByIdUserIdAndIdWordSenseId(UUID userId, UUID wordSenseId);

    Optional<UserSavedWord> findByIdUserIdAndIdWordSenseId(UUID userId, UUID wordSenseId);

    Page<UserSavedWord> findByIdUserId(UUID userId, Pageable pageable);

    @Query(
            "SELECT sw FROM UserSavedWord sw WHERE sw.id.userId = :userId AND sw.id.wordSenseId IN"
                + " :senseIds")
    List<UserSavedWord> findByUserIdAndSenseIdIn(
            @Param("userId") UUID userId, @Param("senseIds") Collection<UUID> senseIds);

    @Modifying
    @Query(
            "DELETE FROM UserSavedWord sw WHERE sw.id.userId = :userId AND sw.id.wordSenseId ="
                + " :wordSenseId")
    void deleteByUserIdAndWordSenseId(
            @Param("userId") UUID userId, @Param("wordSenseId") UUID wordSenseId);
}
