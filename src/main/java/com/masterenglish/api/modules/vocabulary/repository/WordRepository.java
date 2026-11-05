package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.Word;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WordRepository extends JpaRepository<Word, UUID> {

    @Query(
            "SELECT w FROM Word w "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = w.id "
                    + "WHERE ci.archived = false AND ci.accessRevokedAt IS NULL")
    Page<Word> findAllActive(Pageable pageable);

    @Query(
            "SELECT DISTINCT w FROM Word w "
                    + "JOIN WordSense s ON s.wordId = w.id "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = w.id "
                    + "WHERE ci.archived = false AND ci.accessRevokedAt IS NULL "
                    + "AND s.archived = false "
                    + "AND lower(w.lemma) LIKE lower(concat(:q, '%'))")
    Page<Word> searchByLemmaPrefix(@Param("q") String q, Pageable pageable);

    @Query(
            "SELECT DISTINCT w FROM Word w "
                    + "JOIN WordSense s ON s.wordId = w.id "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = w.id "
                    + "WHERE ci.archived = false AND ci.accessRevokedAt IS NULL "
                    + "AND s.archived = false "
                    + "AND (:q IS NULL OR lower(w.lemma) LIKE lower(concat(cast(:q as string), '%'))) "
                    + "AND (:cefrLevel IS NULL OR s.cefrLevel = :cefrLevel) "
                    + "AND (:partOfSpeech IS NULL OR s.partOfSpeech = :partOfSpeech)")
    Page<Word> searchWords(
            @Param("q") String q,
            @Param("cefrLevel") String cefrLevel,
            @Param("partOfSpeech") String partOfSpeech,
            Pageable pageable);

    @Query(
            "SELECT w FROM Word w "
                    + "JOIN com.masterenglish.api.modules.content.entity.ContentItem ci ON ci.id = w.id "
                    + "WHERE w.id = :id AND ci.archived = false AND ci.accessRevokedAt IS NULL")
    Optional<Word> findActiveById(@Param("id") UUID id);
}
