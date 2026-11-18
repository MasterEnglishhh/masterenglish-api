package com.masterenglish.api.modules.learning.repository;

import com.masterenglish.api.modules.learning.entity.UserSavedWord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSavedWordRepository
        extends JpaRepository<UserSavedWord, UserSavedWord.UserSavedWordId> {

    String ELIGIBLE =
            """
            FROM user_saved_words sw
            JOIN catalog_visible_senses s ON s.word_sense_id=sw.word_sense_id
            WHERE sw.user_id=:userId
              AND (cast(:cefrLevel AS text) IS NULL OR s.cefr_level=:cefrLevel)
            """;

    @Query(
            value = "SELECT sw.* " + ELIGIBLE + " ORDER BY sw.saved_at DESC,sw.word_sense_id",
            countQuery = "SELECT count(*) " + ELIGIBLE,
            nativeQuery = true)
    Page<UserSavedWord> findEligible(
            @Param("userId") UUID userId, @Param("cefrLevel") String cefrLevel, Pageable pageable);

    @Query(
            value = "SELECT sw.word_sense_id " + ELIGIBLE + " AND s.word_id=:wordId",
            nativeQuery = true)
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
