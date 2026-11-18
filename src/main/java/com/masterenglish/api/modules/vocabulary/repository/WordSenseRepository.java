package com.masterenglish.api.modules.vocabulary.repository;

import com.masterenglish.api.modules.vocabulary.entity.WordSense;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WordSenseRepository extends JpaRepository<WordSense, UUID> {
    List<WordSense> findByWordIdAndArchivedFalseOrderBySenseNoAsc(UUID wordId);

    Optional<WordSense> findByIdAndArchivedFalse(UUID id);

    @Query("SELECT s.wordId FROM WordSense s WHERE s.id = :senseId")
    Optional<UUID> findWordId(@Param("senseId") UUID senseId);

    boolean existsByIdAndWordIdAndArchivedFalse(UUID id, UUID wordId);
}
